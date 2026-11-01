"""ingest_backlog(디바운스 유휴 판정용 스냅샷) 단위 테스트."""

import pytest

from graph import ingest_backlog


@pytest.fixture(autouse=True)
def _reset():
    ingest_backlog.reset()
    yield
    ingest_backlog.reset()


def test_defaults_are_zero():
    assert ingest_backlog.queue_backlog("p1") == 0
    assert ingest_backlog.dispatcher_pending("p1") == 0
    assert ingest_backlog.is_drained("p1")


@pytest.mark.parametrize("queue,pending,drained", [(0, 0, True), (3, 0, False), (0, 2, False), (3, 2, False)])
def test_is_drained_combinations(queue, pending, drained):
    ingest_backlog.update_snapshot({"p1": queue})
    ingest_backlog.set_pending_provider(lambda pid: pending)
    assert ingest_backlog.is_drained("p1") is drained


def test_update_snapshot_replaces_wholesale():
    ingest_backlog.update_snapshot({"p1": 5, "p2": 1})
    ingest_backlog.update_snapshot({"p2": 0})
    assert ingest_backlog.queue_backlog("p1") == 0
    assert ingest_backlog.queue_backlog("p2") == 0


def test_provider_can_be_cleared():
    ingest_backlog.set_pending_provider(lambda pid: 9)
    assert ingest_backlog.dispatcher_pending("p1") == 9
    ingest_backlog.set_pending_provider(None)
    assert ingest_backlog.dispatcher_pending("p1") == 0
