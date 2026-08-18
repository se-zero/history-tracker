"""consumer.py 재시도/DLQ/parking 라우팅 단위 테스트 (오프라인).

aio_pika 브로커 없이 stub 채널/메시지로 순수 라우팅 로직만 검증한다.
async 함수는 pytest-asyncio 없이 asyncio.run()으로 구동한다(pytest.ini에 플러그인 미설정).
"""

import asyncio

import pytest

from graph import consumer
from graph.consumer import (
    DLQ_QUEUE,
    PARKING_QUEUE,
    RETRY_MAX,
    RETRY_QUEUE,
    _handle_failure,
    _process_event,
    _retry_count,
    _route_message,
)


class _FakeExchange:
    def __init__(self):
        self.published = []  # (message, routing_key) 기록

    async def publish(self, message, routing_key):
        self.published.append((message, routing_key))


class _BoomExchange:
    async def publish(self, message, routing_key):
        raise RuntimeError("broker down")


class _FakeChannel:
    def __init__(self, exchange=None):
        self.default_exchange = exchange or _FakeExchange()


class _FakeMessage:
    def __init__(self, body=b"{}", headers=None, routing_key="event.github"):
        self.body = body
        self.headers = headers
        self.routing_key = routing_key
        self.acked = False

    async def ack(self):
        self.acked = True


class _FakeDispatcher:
    def __init__(self):
        self.submitted = []  # (partition_key, item) 기록

    def submit(self, key, item):
        self.submitted.append((key, item))


# --- _retry_count -----------------------------------------------------------

def test_retry_count_defaults_zero_when_no_header():
    assert _retry_count(_FakeMessage(headers=None)) == 0


def test_retry_count_reads_header():
    assert _retry_count(_FakeMessage(headers={"x-retry-count": 2})) == 2


# --- _handle_failure --------------------------------------------------------

def test_handle_failure_below_max_republishes_to_retry_and_acks():
    ch = _FakeChannel()
    msg = _FakeMessage(body=b'{"projectId":"p1"}', headers={"x-retry-count": 0})

    asyncio.run(_handle_failure(msg, {"projectId": "p1"}, ch))

    assert len(ch.default_exchange.published) == 1
    published_msg, routing_key = ch.default_exchange.published[0]
    assert routing_key == RETRY_QUEUE
    assert published_msg.headers["x-retry-count"] == 1
    assert published_msg.body == msg.body
    assert msg.acked is True


def test_handle_failure_at_max_parks_to_dlq_and_acks():
    ch = _FakeChannel()
    msg = _FakeMessage(headers={"x-retry-count": RETRY_MAX})

    asyncio.run(_handle_failure(msg, {}, ch))

    published_msg, routing_key = ch.default_exchange.published[0]
    assert routing_key == DLQ_QUEUE
    assert msg.acked is True


def test_handle_failure_publish_error_does_not_ack():
    ch = _FakeChannel(exchange=_BoomExchange())
    msg = _FakeMessage(headers={"x-retry-count": 0})

    with pytest.raises(RuntimeError):
        asyncio.run(_handle_failure(msg, {}, ch))
    assert msg.acked is False  # 재발행 실패 → ack 안 함 → 브로커 재배달


# --- _route_message ---------------------------------------------------------

def test_route_message_malformed_goes_to_parking_and_acks():
    ch = _FakeChannel()
    dispatcher = _FakeDispatcher()
    msg = _FakeMessage(body=b"{not json", headers=None)

    asyncio.run(_route_message(msg, dispatcher, ch))

    assert dispatcher.submitted == []  # 워커로 안 감
    published_msg, routing_key = ch.default_exchange.published[0]
    assert routing_key == PARKING_QUEUE
    assert msg.acked is True


def test_route_message_valid_submits_to_partition():
    ch = _FakeChannel()
    dispatcher = _FakeDispatcher()
    msg = _FakeMessage(body=b'{"projectId":"p9"}')

    asyncio.run(_route_message(msg, dispatcher, ch))

    assert ch.default_exchange.published == []  # parking 안 감
    assert len(dispatcher.submitted) == 1
    key, (submitted_msg, event) = dispatcher.submitted[0]
    assert key == "p9"
    assert event == {"projectId": "p9"}
    assert msg.acked is False  # ack은 워커(_process_event)가 처리 후


# --- _process_event ---------------------------------------------------------

def test_process_event_success_acks(monkeypatch):
    ch = _FakeChannel()
    msg = _FakeMessage(body=b'{"projectId":"p1"}')
    calls = {}

    async def fake_handle(event, prepared=None):
        calls["handled"] = event

    monkeypatch.setattr(consumer, "handle", fake_handle)
    monkeypatch.setattr(consumer, "mark_dirty", lambda pid: calls.__setitem__("dirty", pid))

    asyncio.run(_process_event((msg, {"projectId": "p1"}), None, ch))

    assert calls["handled"] == {"projectId": "p1"}
    assert calls["dirty"] == "p1"
    assert msg.acked is True
    assert ch.default_exchange.published == []  # 성공 시 재발행 없음


def test_process_event_failure_routes_to_retry(monkeypatch):
    ch = _FakeChannel()
    msg = _FakeMessage(headers={"x-retry-count": 0})

    async def boom_handle(event, prepared=None):
        raise RuntimeError("neo4j down")

    monkeypatch.setattr(consumer, "handle", boom_handle)

    asyncio.run(_process_event((msg, {"projectId": "p1"}), None, ch))

    published_msg, routing_key = ch.default_exchange.published[0]
    assert routing_key == RETRY_QUEUE
    assert msg.acked is True
