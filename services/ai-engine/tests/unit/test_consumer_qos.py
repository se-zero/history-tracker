"""consumer.py QoS 설정과 prefetch 상수 파생 단위 테스트 (오프라인).

상수는 import 시점에 env에서 읽으므로 importlib.reload로 다시 평가한다.
reload는 같은 모듈 객체를 갱신하므로 다른 테스트의 참조는 깨지지 않지만, env를 되돌린 뒤
한 번 더 reload해 상수를 원래대로 돌려놓는다.
"""

import asyncio
import importlib

import pytest

from graph import consumer


class _FakeChannel:
    def __init__(self):
        self.qos_calls = []

    async def set_qos(self, prefetch_count=0, prefetch_size=0, global_=False, timeout=None):
        self.qos_calls.append((prefetch_count, global_))


def test_apply_qos_sets_per_consumer_limit_then_channel_limit():
    # 순서 고정 — 소비자별(global=false) qos를 나중에 걸면 브로커가 채널 상한을 지운다(3.13.7 실측)
    ch = _FakeChannel()

    asyncio.run(consumer._apply_qos(ch))

    assert ch.qos_calls == [
        (consumer.INGEST_PREFETCH_PER_QUEUE, False),
        (consumer.INGEST_PREFETCH, True),
    ]


@pytest.fixture
def reload_consumer(monkeypatch):
    def _reload(**env):
        for key in ("INGEST_PREFETCH", "INGEST_PREFETCH_PER_QUEUE", "INGEST_MAX_CONCURRENCY",
                    "INGEST_CHANGESET_LOOKAHEAD"):
            monkeypatch.delenv(key, raising=False)
        for key, value in env.items():
            monkeypatch.setenv(key, value)
        return importlib.reload(consumer)

    yield _reload
    monkeypatch.undo()
    importlib.reload(consumer)


def test_prefetch_defaults_derive_from_lookahead_and_concurrency(reload_consumer):
    mod = reload_consumer()

    assert mod.INGEST_PREFETCH_PER_QUEUE == 9  # 쓰기 1 + look-ahead 8
    assert mod.INGEST_PREFETCH == 36           # 동시성 4 × 큐당 9


def test_prefetch_empty_strings_are_treated_as_unset(reload_consumer):
    mod = reload_consumer(INGEST_PREFETCH="", INGEST_PREFETCH_PER_QUEUE="")

    assert mod.INGEST_PREFETCH_PER_QUEUE == 9
    assert mod.INGEST_PREFETCH == 36


def test_prefetch_explicit_values_win(reload_consumer):
    mod = reload_consumer(INGEST_PREFETCH="50", INGEST_PREFETCH_PER_QUEUE="5")

    assert mod.INGEST_PREFETCH_PER_QUEUE == 5
    assert mod.INGEST_PREFETCH == 50


def test_channel_prefetch_derives_from_explicit_per_queue(reload_consumer):
    mod = reload_consumer(INGEST_PREFETCH_PER_QUEUE="3")

    assert mod.INGEST_PREFETCH == 12  # 동시성 4 × 3
