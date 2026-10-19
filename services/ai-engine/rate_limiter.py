"""OpenAI 호출 token-bucket rate limiter.

OpenAI 한도(RPM·TPM)를 넘지 않도록 모든 호출을 페이싱한다. 한도는 모델별·조직 단위라
버킷도 (호출 종류, 모델)별로 둔다 — chat.completions·embeddings 각각 모델마다 버킷이 따로 생긴다.
호출 전 입력 토큰을 추정해 예약하고, 응답 usage로 실제 소비량을 보정(reconcile)한다.
응답 헤더(x-ratelimit-*)는 observe_headers로 반영한다 — limit 헤더로 그 모델의 실제 한도를 학습하고,
remaining 헤더로 잔량을 낮춰 맞춘다(올리지는 않는다).

우선순위 2단계: INTERACTIVE(라이브 질의) > BACKGROUND(수집·빌드).
예산이 부족할 때 INTERACTIVE 대기자가 있으면 BACKGROUND 호출은 양보한다.
양보는 같은 버킷 안에서만 작동한다 — 모델이 갈리는 chat(INTERACTIVE는 질의 모델,
BACKGROUND는 요약·필터 모델)에서는 서로 영향이 없고, 공유 임베딩 버킷에서만 살아 있다.

한도 env (종류 단위, 해당 종류의 모든 모델 버킷에 적용):
  OPENAI_RPM_CHAT  OPENAI_TPM_CHAT  OPENAI_RPM_EMBED  OPENAI_TPM_EMBED
  - 비어 있으면: 코드 기본값(chat 500/200000, embed 3000/1000000)이 부팅 초기값일 뿐이고,
    첫 응답 헤더부터 실제 한도를 학습해 교체한다.
  - 값이 있으면: 부팅값이자 상한(cap)이다. 헤더가 더 큰 한도를 알려도 cap을 넘기지 않는다.

한계: 버킷이 in-process라 ai-engine을 다중 인스턴스로 띄우면 인스턴스별 버킷의 합이
org 한도를 넘을 수 있다. remaining 헤더 동기화가 겹침을 어느 정도 완화하지만,
수평 확장 시 공유 저장소 기반 limiter로 교체해야 한다.
(per-project 빌드 상태·디바운스·빌드 락도 같은 전환 대상.)
"""

import asyncio
import enum
import logging
import os
import time
from functools import lru_cache

logger = logging.getLogger(__name__)


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

    def __init__(
        self,
        rpm: int,
        tpm: int,
        *,
        clock=time.monotonic,
        rpm_cap: int | None = None,
        tpm_cap: int | None = None,
        name: str = "",
    ):
        self._rpm = max(1, int(rpm))
        self._tpm = max(1, int(tpm))
        self._req = float(self._rpm)   # 남은 요청 슬롯
        self._tok = float(self._tpm)   # 남은 토큰 예산
        self._clock = clock
        self._updated = clock()
        self._interactive_waiters = 0
        self._rpm_cap = rpm_cap
        self._tpm_cap = tpm_cap
        self._name = name
        self._learned = False

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

    def observe_headers(self, headers) -> None:
        """OpenAI 응답 헤더로 버킷 용량(limit)과 잔량(remaining)을 맞춘다."""
        # 동기화 전에 지금 시점까지 충전을 반영해야, 이후 refill이 과거 구간을 다시 충전하지 않는다.
        self._refill()

        limit_rpm = _header_int(headers, "x-ratelimit-limit-requests", minimum=1)
        limit_tpm = _header_int(headers, "x-ratelimit-limit-tokens", minimum=1)
        if limit_rpm is not None and self._rpm_cap is not None:
            limit_rpm = min(limit_rpm, self._rpm_cap)
        if limit_tpm is not None and self._tpm_cap is not None:
            limit_tpm = min(limit_tpm, self._tpm_cap)
        new_rpm = self._rpm if limit_rpm is None else limit_rpm
        new_tpm = self._tpm if limit_tpm is None else limit_tpm
        if (new_rpm, new_tpm) != (self._rpm, self._tpm) or (
            not self._learned and (limit_rpm is not None or limit_tpm is not None)
        ):
            self._rpm = new_rpm
            self._tpm = new_tpm
            self._req = min(self._req, float(self._rpm))
            self._tok = min(self._tok, float(self._tpm))
            self._learned = True
            logger.info(
                "OpenAI 한도 학습: %s rpm=%d tpm=%d (cap rpm=%s tpm=%s)",
                self._name, self._rpm, self._tpm, self._rpm_cap, self._tpm_cap,
            )

        # remaining은 조직 전체 잔량이라 다른 프로세스 소비까지 반영한다. 0은 429 직전 신호라 유효.
        # 낮추기만 한다 — 올리면 아직 응답 안 온 in-flight 예약분을 되돌려 과다 발사하게 된다.
        rem_req = _header_int(headers, "x-ratelimit-remaining-requests", minimum=0)
        rem_tok = _header_int(headers, "x-ratelimit-remaining-tokens", minimum=0)
        if rem_req is not None:
            self._req = min(self._req, float(rem_req))
        if rem_tok is not None:
            self._tok = min(self._tok, float(rem_tok))


def _header_int(headers, name: str, *, minimum: int) -> int | None:
    raw = headers.get(name)
    if raw is None:
        return None
    try:
        value = int(raw)
    except (TypeError, ValueError):
        return None
    return value if value >= minimum else None


def _env_cap(name: str) -> int | None:
    raw = os.environ.get(name)
    if not raw:
        return None
    try:
        value = int(raw)
    except ValueError:
        value = None
    if value is None or value < 1:
        # 폴백이 "상한 없음"이라 낮추려던 의도와 반대가 된다 — 조용히 넘기지 않고 경고한다.
        logger.warning("%s=%r 는 1 이상의 정수가 아니라 무시한다 (상한 없음, 헤더 학습값 그대로)", name, raw)
        return None
    return value


_DEFAULTS = {"chat": (500, 200_000), "embed": (3000, 1_000_000)}
_ENV = {
    "chat": ("OPENAI_RPM_CHAT", "OPENAI_TPM_CHAT"),
    "embed": ("OPENAI_RPM_EMBED", "OPENAI_TPM_EMBED"),
}
_limiters: dict[tuple[str, str], _ModelLimiter] = {}


def _get_limiter(kind: str, model: str) -> _ModelLimiter:
    key = (kind, model)
    limiter = _limiters.get(key)
    if limiter is None:
        # env는 생성 시점에 읽는다 — 값이 있으면 부팅값이자 상한, 없으면 기본값(상한 없음, 헤더로 학습).
        rpm_cap = _env_cap(_ENV[kind][0])
        tpm_cap = _env_cap(_ENV[kind][1])
        default_rpm, default_tpm = _DEFAULTS[kind]
        limiter = _ModelLimiter(
            rpm_cap or default_rpm,
            tpm_cap or default_tpm,
            rpm_cap=rpm_cap,
            tpm_cap=tpm_cap,
            name=f"{kind}/{model}",
        )
        _limiters[key] = limiter
    return limiter


def get_chat_limiter(model: str | None = None) -> _ModelLimiter:
    """chat.completions 모델별 버킷. model 생략 시 이름 없는 공용 버킷."""
    return _get_limiter("chat", model or "")


def get_embed_limiter(model: str | None = None) -> _ModelLimiter:
    """embeddings 모델별 버킷. model 생략 시 이름 없는 공용 버킷."""
    return _get_limiter("embed", model or "")


def reset() -> None:
    """버킷 레지스트리를 비운다(테스트용)."""
    _limiters.clear()


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


def truncate_to_tokens(text: str, model: str, max_tokens: int) -> str:
    """text를 max_tokens 토큰 이내로 앞에서부터 자른다. 이미 이내면 원문 그대로 반환."""
    if not text:
        return text
    enc = _encoder(model)
    if enc is not None:
        try:
            # disallowed_special=(): 본문에 "<|endoftext|>" 같은 특수 토큰 문자열이 있어도 보통 글자로 센다 —
            # OpenAI 서버가 입력을 그렇게 세므로 같은 기준이 된다(기본값이면 encode가 예외를 낸다).
            tokens = enc.encode(text, disallowed_special=())
            if len(tokens) <= max_tokens:
                return text
            # 토큰 경계가 멀티바이트 글자 한가운데에 걸리면 decode가 깨진 글자(U+FFFD)를 붙인다 —
            # 바이트로 풀어 끝의 불완전한 글자만 버린다.
            return enc.decode_bytes(tokens[:max_tokens]).decode("utf-8", errors="ignore")
        except Exception:
            pass  # 예기치 않은 encode 실패 — 임베딩 경로의 never-raise 계약을 지키려 글자 컷으로 폴백
    # tiktoken 미가용(또는 encode 실패): 글자 수로 보수적으로 자른다. 띄어쓰기 없는 한글은 실측 1.43토큰/자라
    # 1자≈1토큰 가정으로는 상한을 넘기므로 글자 max_tokens//2개만 남긴다.
    return text[: max_tokens // 2]


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
