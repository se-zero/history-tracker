"""공유 OpenAI 클라이언트 + rate-limited 호출 게이트웨이.

여러 모듈이 각자 import 시점에 OpenAI()를 만들던 것을 단일 lazy 싱글턴으로 합친다.
- lazy: 최초 호출 시점에 생성 → 모듈 import만으로는 OPENAI_API_KEY가 필요 없다 (오프라인 import/테스트 가능).
- 단일 설정점: base_url·timeout·재시도 등을 이 한 곳에서만 조정한다.

모든 chat/embedding 호출은 이 모듈의 게이트웨이(chat_completion·embed)를 통한다.
클라이언트는 비동기(AsyncOpenAI)라 스레드풀을 거치지 않는다 — 동시 호출 수가 스레드풀 크기 상한에
묶이지 않고, 질의가 수집 호출 뒤에 굶지 않는다.
게이트웨이는 BACKGROUND 호출에만 동시 요청 상한 게이트를 걸고, 모델별 limiter로 페이싱한 뒤,
응답 헤더(x-ratelimit-*)를 limiter에 넘겨 모델별 실제 한도를 학습시킨다.
호출/임베딩 모델명은 각 호출부의 인자로 남겨둔다 (클라이언트 설정과 별개 관심사).
"""

import asyncio
import contextlib
import os
from functools import lru_cache

from openai import AsyncOpenAI

import alerts
from rate_limiter import (
    Priority,
    estimate_chat_tokens,
    estimate_embed_tokens,
    get_chat_limiter,
    get_embed_limiter,
)

# 호출 상한. 가장 긴 경로(에이전트 tool-calling)에 맞춰 통일.
_DEFAULT_TIMEOUT = 60.0

# BACKGROUND 동시 요청 게이트. 세마포어는 대기가 생기는 순간 현재 이벤트 루프에 묶이므로
# 루프가 바뀌면(테스트의 루프별 격리 등) 다시 만든다 — lru_cache로 고정하면 다른 루프에서 RuntimeError.
_gate: asyncio.Semaphore | None = None
_gate_loop = None


@lru_cache(maxsize=1)
def get_openai_client() -> AsyncOpenAI:
    """프로세스 전역에서 재사용하는 비동기 OpenAI 클라이언트.

    max_retries로 429/5xx를 SDK가 Retry-After·지수 백오프로 재시도한다 (limiter가
    페이싱해도 드물게 발생하는 429를 흡수). env OPENAI_MAX_RETRIES로 조정.
    동기 클라이언트와 재시도 정책·예외 클래스가 같다. httpx가 요청마다 INFO로 상태 코드를 남긴다.
    """
    max_retries = _env_int("OPENAI_MAX_RETRIES", 3)
    return AsyncOpenAI(api_key=os.environ["OPENAI_API_KEY"], timeout=_DEFAULT_TIMEOUT, max_retries=max_retries)


def _env_int(name: str, default: int) -> int:
    raw = os.environ.get(name)
    if not raw:
        return default
    try:
        return max(0, int(raw))
    except ValueError:
        return default


def _background_gate() -> asyncio.Semaphore:
    global _gate, _gate_loop
    loop = asyncio.get_running_loop()
    if _gate is None or _gate_loop is not loop:
        _gate = asyncio.Semaphore(max(1, _env_int("OPENAI_BACKGROUND_CONCURRENCY", 32)))
        _gate_loop = loop
    return _gate


def _gate_for(priority: Priority):
    # INTERACTIVE(라이브 질의)는 게이트를 거치지 않는다 — 수집 호출이 게이트를 채워도 질의가 막히지 않게.
    if priority != Priority.INTERACTIVE:
        return _background_gate()
    return contextlib.nullcontext()


def _error_headers(exc):
    # 429 등 상태 오류는 response.headers에 잔량이 실려 온다. 연결·타임아웃 오류에는 response가 없다.
    return getattr(getattr(exc, "response", None), "headers", None)


def _usage_total(resp, fallback: int) -> int:
    usage = getattr(resp, "usage", None)
    total = getattr(usage, "total_tokens", None) if usage is not None else None
    return total if isinstance(total, int) else fallback


async def chat_completion(*, priority: Priority, **kwargs):
    """모든 chat.completions 호출의 단일 게이트웨이. 게이트 → limiter → HTTP 순으로 통과한다.

    kwargs는 client.chat.completions.create()로 그대로 전달된다 (model·messages·tools·response_format 등).
    """
    est = estimate_chat_tokens(kwargs.get("messages"), kwargs.get("model", ""))
    # 게이트를 먼저 통과해야 한다 — 토큰을 먼저 예약하고 게이트에서 놀면 버킷이 그 밑에서 충전돼 나중에 몰린다.
    # 대신 limiter 예산을 기다리는 동안에도 게이트 슬롯을 쥔다. 한 모델 버킷이 마르면 예산이 남은 다른 모델의
    # BACKGROUND 호출(embed 포함)도 슬롯이 없어 같이 기다린다 — 한도 압박 시 수집 전체가 함께 느려지는 의도된 trade-off.
    async with _gate_for(priority):
        limiter = get_chat_limiter(kwargs.get("model") or "")
        reserved = await limiter.acquire(priority, est)
        raw = resp = headers = None
        try:
            raw = await get_openai_client().chat.completions.with_raw_response.create(**kwargs)
            headers = raw.headers  # parse() 실패 시에도 헤더는 남긴다
            resp = raw.parse()  # 동기 함수 — await 금지 (SDK 다음 메이저에서 코루틴이 될 예정)
            alerts.record_openai_success()
            return resp
        except Exception as exc:  # CancelledError는 BaseException — 의도적으로 안 잡음
            alerts.record_openai_failure(exc, caller="chat", model=kwargs.get("model"))
            if headers is None:
                headers = _error_headers(exc)
            raise
        finally:
            limiter.reconcile(reserved, _usage_total(resp, reserved))
            if headers is not None:
                limiter.observe_headers(headers)


async def embed(*, model: str, input, priority: Priority, dimensions: int | None = None):
    """모든 embeddings 호출의 단일 게이트웨이.

    dimensions: 출력 벡터 차원. None이면 모델 기본 차원으로 호출한다 — 차원 절삭을
    지원하지 않는 모델에 인자를 넘기면 API가 거부하므로 지정됐을 때만 전달한다.
    """
    est = estimate_embed_tokens(input, model)
    kwargs = {"model": model, "input": input}
    if dimensions is not None:
        kwargs["dimensions"] = dimensions
    async with _gate_for(priority):
        limiter = get_embed_limiter(model)
        reserved = await limiter.acquire(priority, est)
        raw = resp = headers = None
        try:
            raw = await get_openai_client().embeddings.with_raw_response.create(**kwargs)
            headers = raw.headers
            resp = raw.parse()  # 동기 — await 금지. base64→float 변환까지 여기서 한다
            alerts.record_openai_success()
            return resp
        except Exception as exc:  # CancelledError는 BaseException — 의도적으로 안 잡음
            alerts.record_openai_failure(exc, caller="embed", model=model)
            if headers is None:
                headers = _error_headers(exc)
            raise
        finally:
            limiter.reconcile(reserved, _usage_total(resp, reserved))
            if headers is not None:
                limiter.observe_headers(headers)
