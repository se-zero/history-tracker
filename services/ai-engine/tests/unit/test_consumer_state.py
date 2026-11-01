"""_ConsumerState(한 연결 수명 동안의 구독 장부) 단위 테스트 (오프라인).

큐 발견(queue_discovery)과 프로젝트 삭제 시 큐 삭제가 subscribe/unsubscribe/is_alive 위에서
돌아가므로 세 동작의 계약을 여기서 고정한다.
"""

import asyncio
import functools

from graph import consumer
from graph.consumer import _ConsumerState


class _FakeQueue:
    def __init__(self, name, cancel_error=None):
        self.name = name
        self.consumed = []    # consume에 넘어온 콜백
        self.cancelled = []   # cancel에 넘어온 consumer tag
        self._cancel_error = cancel_error

    async def consume(self, callback, **kwargs):
        self.consumed.append(callback)
        return f"ctag-{self.name}"

    async def cancel(self, tag, **kwargs):
        if self._cancel_error is not None:
            raise self._cancel_error
        self.cancelled.append(tag)


class _Closable:
    def __init__(self, is_closed=False):
        self.is_closed = is_closed


def _state(connection_closed=False, channel_closed=False, publish_channel_closed=False):
    return _ConsumerState(
        connection=_Closable(connection_closed),
        channel=_Closable(channel_closed),
        publish_channel=_Closable(publish_channel_closed),
        retry_exchange=object(),
        dispatcher=object(),
    )


# --- subscribe ---------------------------------------------------------------

def test_subscribe_consumes_and_records_queue_and_tag():
    state = _state()
    queue = _FakeQueue("history.project.p1")

    asyncio.run(state.subscribe("history.project.p1", queue))

    assert state.queues == {"history.project.p1": queue}
    assert state.consumers == {"history.project.p1": "ctag-history.project.p1"}
    # 콜백은 _on_message에 이 state와 원래 큐 이름을 묶은 partial — 재시도 때 origin이 이 이름이 된다
    callback = queue.consumed[0]
    assert isinstance(callback, functools.partial)
    assert callback.func is consumer._on_message
    assert callback.keywords == {"state": state, "origin_queue": "history.project.p1"}


# --- unsubscribe -------------------------------------------------------------

def test_unsubscribe_cancels_with_tag_and_forgets_queue():
    state = _state()
    queue = _FakeQueue("history.project.p1")
    asyncio.run(state.subscribe("history.project.p1", queue))

    asyncio.run(state.unsubscribe("history.project.p1"))

    assert queue.cancelled == ["ctag-history.project.p1"]
    assert state.queues == {}
    assert state.consumers == {}


def test_unsubscribe_unknown_queue_is_noop():
    state = _state()
    queue = _FakeQueue("history.project.p1")
    asyncio.run(state.subscribe("history.project.p1", queue))

    asyncio.run(state.unsubscribe("history.project.unknown"))  # 예외 없음

    assert queue.cancelled == []
    assert set(state.queues) == {"history.project.p1"}


def test_unsubscribe_swallows_cancel_error_and_still_forgets_queue():
    # 서버가 지운 큐는 소비자가 이미 취소돼 있어 cancel이 실패한다 — 장부에서는 지워져야 한다
    state = _state()
    queue = _FakeQueue("history.project.p1", cancel_error=RuntimeError("NOT_FOUND"))
    asyncio.run(state.subscribe("history.project.p1", queue))

    asyncio.run(state.unsubscribe("history.project.p1"))

    assert state.queues == {}
    assert state.consumers == {}


# --- is_alive ----------------------------------------------------------------

def test_is_alive_true_when_connection_and_channel_open():
    assert _state().is_alive() is True


def test_is_alive_false_when_connection_closed():
    assert _state(connection_closed=True).is_alive() is False


def test_is_alive_false_when_consume_channel_closed():
    assert _state(channel_closed=True).is_alive() is False


def test_is_alive_false_when_publish_channel_closed():
    # 비-robust 연결에서는 닫힌 발행 채널이 되살아나지 않는다 — 재시도·DLQ 발행이 전부 막히므로 통째 재구성 대상
    assert _state(publish_channel_closed=True).is_alive() is False
