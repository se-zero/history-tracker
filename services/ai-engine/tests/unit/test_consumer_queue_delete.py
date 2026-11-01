"""delete_project_queue / _wait_partition_drained 단위 테스트 (오프라인)."""

import asyncio

import aio_pika
import pytest

from graph import consumer


class _Dispatcher:
    def __init__(self, counts):
        self._counts = list(counts)

    def pending(self, key):
        return self._counts.pop(0) if len(self._counts) > 1 else self._counts[0]


class _State:
    def __init__(self, events, dispatcher):
        self.events = events
        self.dispatcher = dispatcher

    async def unsubscribe(self, name):
        self.events.append(("unsubscribe", name))


def _install(monkeypatch, events, delete_error=None, pending_counts=(0,), state=True):
    class _Channel:
        async def queue_delete(self, name):
            events.append(("queue_delete", name))
            if delete_error:
                raise delete_error

    class _Conn:
        async def channel(self):
            return _Channel()

        async def __aenter__(self):
            return self

        async def __aexit__(self, *a):
            return False

    async def fake_connect(url):
        events.append(("connect", url))
        return _Conn()

    monkeypatch.setattr(aio_pika, "connect_robust", fake_connect)
    monkeypatch.setattr(consumer, "_state", _State(events, _Dispatcher(pending_counts)) if state else None)


def test_deletes_after_unsubscribe_and_drain(monkeypatch):
    events = []
    _install(monkeypatch, events, pending_counts=(2, 1, 0))
    assert asyncio.run(consumer.delete_project_queue("p1")) is True
    names = [e[0] for e in events]
    assert names == ["unsubscribe", "connect", "queue_delete"]
    assert events[-1] == ("queue_delete", "history.project.p1")


def test_missing_queue_returns_false(monkeypatch):
    events = []
    _install(monkeypatch, events, delete_error=aio_pika.exceptions.ChannelNotFoundEntity("x"))
    assert asyncio.run(consumer.delete_project_queue("p1")) is False


def test_deletes_even_without_consumer_state(monkeypatch):
    events = []
    _install(monkeypatch, events, state=False)
    assert asyncio.run(consumer.delete_project_queue("p1")) is True
    assert ("queue_delete", "history.project.p1") in events
    assert ("unsubscribe", "history.project.p1") not in events


def test_wait_partition_drained_times_out():
    assert asyncio.run(consumer._wait_partition_drained(_Dispatcher([1]), "p1", timeout=0.15)) is False
