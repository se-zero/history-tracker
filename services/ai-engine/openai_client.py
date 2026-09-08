"""공유 OpenAI 클라이언트 + rate-limited 호출 게이트웨이.

여러 모듈이 각자 import 시점에 OpenAI()를 만들던 것을 단일 lazy 싱글턴으로 합친다.
- lazy: 최초 호출 시점에 생성 → 모듈 import만으로는 OPENAI_API_KEY가 필요 없다 (오프라인 import/테스트 가능).
- 단일 설정점: base_url·timeout·재시도 등을 이 한 곳에서만 조정한다.

모든 chat/embedding 호출은 이 모듈의 게이트웨이(chat_completion·embed)를 통한다.
게이트웨이는 rate_limiter로 호출을 페이싱한 뒤 동기 SDK 호출을 to_thread로 감싼다.
호출/임베딩 모델명은 각 호출부의 인자로 남겨둔다 (클라이언트 설정과 별개 관심사).
"""

import asyncio
import os
from functools import lru_cache

from openai import OpenAI

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


@lru_cache(maxsize=1)
def get_openai_client() -> OpenAI:
    """프로세스 전역에서 재사용하는 OpenAI 클라이언트.

    max_retries로 429/5xx를 SDK가 Retry-After·지수 백오프로 재시도한다 (limiter가
    페이싱해도 드물게 발생하는 429를 흡수). env OPENAI_MAX_RETRIES로 조정.
    """
    max_retries = _env_int("OPENAI_MAX_RETRIES", 3)
    return OpenAI(api_key=os.environ["OPENAI_API_KEY"], timeout=_DEFAULT_TIMEOUT, max_retries=max_retries)


def _env_int(name: str, default: int) -> int:
    raw = os.environ.get(name)
    if not raw:
        return default
    try:
        return max(0, int(raw))
    except ValueError:
        return default


def _usage_total(resp, fallback: int) -> int:
    usage = getattr(resp, "usage", None)
    total = getattr(usage, "total_tokens", None) if usage is not None else None
    return total if isinstance(total, int) else fallback


async def chat_completion(*, priority: Priority, **kwargs):
    """모든 chat.completions 호출의 단일 게이트웨이. limiter 통과 후 SDK 동기 호출을 to_thread로.

    kwargs는 client.chat.completions.create()로 그대로 전달된다 (model·messages·tools·response_format 등).
    """
    est = estimate_chat_tokens(kwargs.get("messages"), kwargs.get("model", ""))
    limiter = get_chat_limiter()
    reserved = await limiter.acquire(priority, est)
    resp = None
    try:
        resp = await asyncio.to_thread(lambda: get_openai_client().chat.completions.create(**kwargs))
        alerts.record_openai_success()
        return resp
    except Exception as exc:  # CancelledError는 BaseException — 의도적으로 안 잡음
        alerts.record_openai_failure(exc, caller="chat", model=kwargs.get("model"))
        raise
    finally:
        limiter.reconcile(reserved, _usage_total(resp, reserved))


async def embed(*, model: str, input, priority: Priority, dimensions: int | None = None):
    """모든 embeddings 호출의 단일 게이트웨이.

    dimensions: 출력 벡터 차원. None이면 모델 기본 차원으로 호출한다 — 차원 절삭을
    지원하지 않는 모델에 인자를 넘기면 API가 거부하므로 지정됐을 때만 전달한다.
    """
    est = estimate_embed_tokens(input, model)
    limiter = get_embed_limiter()
    reserved = await limiter.acquire(priority, est)
    kwargs = {"model": model, "input": input}
    if dimensions is not None:
        kwargs["dimensions"] = dimensions
    resp = None
    try:
        resp = await asyncio.to_thread(lambda: get_openai_client().embeddings.create(**kwargs))
        alerts.record_openai_success()
        return resp
    except Exception as exc:  # CancelledError는 BaseException — 의도적으로 안 잡음
        alerts.record_openai_failure(exc, caller="embed", model=model)
        raise
    finally:
        limiter.reconcile(reserved, _usage_total(resp, reserved))
