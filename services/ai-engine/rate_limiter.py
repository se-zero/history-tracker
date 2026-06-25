"""OpenAI 호출 token-bucket rate limiter.

전역 OpenAI org 한도(RPM·TPM)를 넘지 않도록 모든 호출을 페이싱한다.
호출 종류별로 버킷을 둔다 — chat.completions는 CHAT 버킷, embeddings는 EMBED 버킷.
호출 전 입력 토큰을 추정해 예약하고, 응답 usage로 실제 소비량을 보정(reconcile)한다.

우선순위 2단계: INTERACTIVE(라이브 질의) > BACKGROUND(수집·빌드).
예산이 부족할 때 INTERACTIVE 대기자가 있으면 BACKGROUND 호출은 양보한다.

한도는 env로 외부화한다 (Tier 변경 = env 값만 수정, 코드 불변):
  OPENAI_RPM_CHAT  (기본 500)    OPENAI_TPM_CHAT  (기본 200000)
  OPENAI_RPM_EMBED (기본 3000)   OPENAI_TPM_EMBED (기본 1000000)

한계: 버킷이 in-process라 ai-engine을 다중 인스턴스로 띄우면 인스턴스별 버킷의 합이
org 한도를 넘을 수 있다. 수평 확장 시 공유 저장소 기반 limiter로 교체해야 한다.
(per-project 빌드 상태·디바운스·빌드 락도 같은 전환 대상.)
"""

import asyncio
import enum
import os
import time
from functools import lru_cache


class Priority(enum.IntEnum):
    """호출 우선순위. 값이 작을수록 우선(예산 경합 시 INTERACTIVE가 먼저)."""

    INTERACTIVE = 0  # 라이브 질의(orchestrator) — 사용자가 응답을 기다림
    BACKGROUND = 1   # 수집·그래프 빌드(요약·actor·verify·slack 필터·임베딩)


# chat 응답(출력) 토큰을 미리 알 수 없어 예약에 더하는 보수적 상수.
# 실제값은 응답 usage로 reconcile되므로 페이싱 추정치 역할만 한다.
_RESPONSE_RESERVE_TOKENS = 700


class _ModelLimiter:
    """단일 버킷의 RPM·TPM token-bucket.

    clock은 테스트에서 가짜 시계를 주입하기 위한 seam (기본 time.monotonic).
    asyncio 단일 이벤트 루프 전제 — _try_acquire/reconcile 내부에 await가 없어
    코루틴 간 read-modify-write가 원자적이므로 별도 락이 필요 없다.
    """

    def __init__(self, rpm: int, tpm: int, *, clock=time.monotonic):
        self._rpm = max(1, int(rpm))
        self._tpm = max(1, int(tpm))
        self._req = float(self._rpm)   # 남은 요청 슬롯
        self._tok = float(self._tpm)   # 남은 토큰 예산
        self._clock = clock
        self._updated = clock()
        self._interactive_waiters = 0

    def _refill(self) -> None:
        now = self._clock()
        elapsed = now - self._updated
        if elapsed <= 0:
            return
        self._updated = now
        self._req = min(self._rpm, self._req + elapsed * self._rpm / 60.0)
        self._tok = min(self._tpm, self._tok + elapsed * self._tpm / 60.0)

    def _try_acquire(self, priority: Priority, cost: int) -> bool:
        """충전 후 예산이 있으면 즉시 차감하고 True. 없으면(또는 양보) False."""
        self._refill()
        if priority != Priority.INTERACTIVE and self._interactive_waiters > 0:
            return False  # INTERACTIVE 대기자에게 양보
        if self._req >= 1.0 and self._tok >= cost:
            self._req -= 1.0
            self._tok -= cost
            return True
        return False

    def _seconds_until(self, cost: int) -> float:
        """요청 슬롯과 토큰 예산이 모두 충족될 때까지의 예상 대기 시간(초)."""
        req_wait = 0.0 if self._req >= 1.0 else (1.0 - self._req) * 60.0 / self._rpm
        tok_wait = 0.0 if self._tok >= cost else (cost - self._tok) * 60.0 / self._tpm
        return max(req_wait, tok_wait)

    def _poll_delay(self, cost: int) -> float:
        # 다음 충전까지 필요한 만큼 자되 폴링 상한(0.5s)을 둬 우선순위 변화에 반응.
        return min(max(self._seconds_until(cost), 0.01), 0.5)

    async def acquire(self, priority: Priority, est_tokens: int) -> int:
        """예산이 확보될 때까지 대기 후 차감. 예약한 토큰 수(cost)를 반환 → reconcile에 사용."""
        cost = min(max(0, int(est_tokens)), self._tpm)
        is_interactive = priority == Priority.INTERACTIVE
        if is_interactive:
            self._interactive_waiters += 1
        try:
            while not self._try_acquire(priority, cost):
                await asyncio.sleep(self._poll_delay(cost))
            return cost
        finally:
            if is_interactive:
                self._interactive_waiters -= 1

    def reconcile(self, reserved: int, actual_tokens: int) -> None:
        """추정과 실제 소비량의 차이를 버킷에 반영한다.

        acquire가 reserved만큼 차감했으므로, 실제(actual)와의 차이만 추가 조정한다.
        순효과: 버킷에서 actual만큼 소비된 셈이 된다.
        """
        actual = min(max(0, int(actual_tokens)), self._tpm)
        self._tok = max(float(-self._tpm), min(float(self._tpm), self._tok - (actual - reserved)))


def _env_int(name: str, default: int) -> int:
    raw = os.environ.get(name)
    if not raw:
        return default
    try:
        return max(1, int(raw))
    except ValueError:
        return default


@lru_cache(maxsize=1)
def get_chat_limiter() -> _ModelLimiter:
    """chat.completions 공용 버킷 (gpt-4o-mini 등 chat 모델 공유)."""
    return _ModelLimiter(_env_int("OPENAI_RPM_CHAT", 500), _env_int("OPENAI_TPM_CHAT", 200_000))


@lru_cache(maxsize=1)
def get_embed_limiter() -> _ModelLimiter:
    """embeddings 공용 버킷."""
    return _ModelLimiter(_env_int("OPENAI_RPM_EMBED", 3000), _env_int("OPENAI_TPM_EMBED", 1_000_000))


@lru_cache(maxsize=8)
def _encoder(model: str):
    """tiktoken 인코더 (모델별 캐시). 미설치/오프라인이면 None → 글자수 기반 추정으로 폴백."""
    try:
        import tiktoken
    except Exception:
        return None
    try:
        return tiktoken.encoding_for_model(model)
    except Exception:
        try:
            return tiktoken.get_encoding("o200k_base")
        except Exception:
            return None


def _count_tokens(text: str, model: str) -> int:
    if not text:
        return 0
    enc = _encoder(model)
    if enc is None:
        # tiktoken 미가용: 보수적 추정. 한국어는 토큰당 글자수가 적어(≈1~2자/토큰)
        # char//4는 과소추정 위험 → //2로 여유를 둔다.
        return max(1, len(text) // 2)
    try:
        return len(enc.encode(text))
    except Exception:
        return max(1, len(text) // 2)


def estimate_chat_tokens(messages, model: str) -> int:
    """chat 요청의 입력 토큰 추정 + 응답 예약. 비정형 메시지(SDK 객체 등)는 건너뛴다."""
    parts = []
    for m in messages or []:
        content = m.get("content") if isinstance(m, dict) else None
        if isinstance(content, str):
            parts.append(content)
    return _count_tokens("\n".join(parts), model) + _RESPONSE_RESERVE_TOKENS


def estimate_embed_tokens(input, model: str) -> int:
    """embeddings 요청의 입력 토큰 추정."""
    texts = [input] if isinstance(input, str) else list(input or [])
    return sum(_count_tokens(t, model) for t in texts if isinstance(t, str))
