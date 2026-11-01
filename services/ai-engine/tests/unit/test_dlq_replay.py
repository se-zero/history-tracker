"""POST /dlq/replay 단위 테스트 (오프라인) — 프로젝트 큐로 재발행, projectId 없는 메시지는 루프 뒤 nack."""

import asyncio
import json

import aio_pika
import pytest

from routers.admin import DlqReplayOptions, dlq_replay


class _Process:
    def __init__(self, message, events):
        self._message = message
        self._events = events

    async def __aenter__(self):
        return self._message

    async def __aexit__(self, exc_type, exc, tb):
        if exc_type is None:
            self._message.acked = True
            self._events.append(("ack", self._message.body))
        else:
            self._message.nacked = True
            self._events.append(("nack", self._message.body))
        return False


class _Message:
    def __init__(self, body, events):
        self.body = body
        self.acked = False
        self.nacked = False
        self._events = events

    def process(self, requeue=True):
        return _Process(self, self._events)

    async def nack(self, requeue=True):
        self.nacked = True
        self._events.append(("nack", self.body))


def _body(project_id="p1", **extra):
    data = {"nodeType": "COMMIT", **extra}
    if project_id is not None:
        data["projectId"] = project_id
    return json.dumps(data).encode()


def _install(monkeypatch, bodies, publish_error=None):
    events = []
    messages = [_Message(b, events) for b in bodies]
    gets = []

    class _Queue:
        async def get(self, fail=True):
            gets.append(fail)
            return messages.pop(0) if messages else None

    class _DefaultExchange:
        async def publish(self, message, routing_key):
            if publish_error:
                raise publish_error
            events.append(("publish", routing_key, message.body))

    class _Channel:
        default_exchange = _DefaultExchange()

        async def declare_queue(self, name, **kwargs):
            return _Queue()

    class _Conn:
        async def channel(self):
            return _Channel()

        async def __aenter__(self):
            return self

        async def __aexit__(self, *a):
            return False

    async def fake_connect(url):
        return _Conn()

    monkeypatch.setattr(aio_pika, "connect_robust", fake_connect)
    return events, gets


def test_replays_to_project_queue_via_default_exchange(monkeypatch):
    a, b = _body("pa"), _body("pb")
    events, _ = _install(monkeypatch, [a, b])

    result = asyncio.run(dlq_replay(DlqReplayOptions(max_messages=10)))

    assert result == {"replayed": 2, "skipped": 0}
    assert events == [
        ("publish", "history.project.pa", a), ("ack", a),
        ("publish", "history.project.pb", b), ("ack", b),
    ]


def test_skipped_messages_are_nacked_only_after_loop(monkeypatch):
    ok1, no_pid, bad_json, ok2 = _body("p1"), _body(None), b"not json", _body("p2")
    events, _ = _install(monkeypatch, [ok1, no_pid, bad_json, ok2])

    result = asyncio.run(dlq_replay(DlqReplayOptions(max_messages=10)))

    assert result == {"replayed": 2, "skipped": 2}
    assert [e[0] for e in events if e[0] != "ack"] == ["publish", "publish", "nack", "nack"]
    assert [e[1] for e in events if e[0] == "nack"] == [no_pid, bad_json]


def test_skipped_count_toward_max_messages(monkeypatch):
    events, gets = _install(monkeypatch, [_body(None), _body(None), _body("p1")])

    result = asyncio.run(dlq_replay(DlqReplayOptions(max_messages=2)))

    assert result == {"replayed": 0, "skipped": 2}
    assert len(gets) == 2
    assert [e[0] for e in events] == ["nack", "nack"]


def test_publish_failure_requeues_message_and_propagates(monkeypatch):
    body = _body("p1")
    events, _ = _install(monkeypatch, [body], publish_error=RuntimeError("publish failed"))

    with pytest.raises(RuntimeError):
        asyncio.run(dlq_replay(DlqReplayOptions(max_messages=10)))

    assert events == [("nack", body)]
