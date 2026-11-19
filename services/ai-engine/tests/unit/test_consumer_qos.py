"""consumer.py QoS 설정과 prefetch 상수 파생 단위 테스트 (오프라인).

Amazon MQ for RabbitMQ 4.3은 basic.qos를 global=true로 부르면 채널 오류를 낸다. 그래서 consumer는
큐(소비자)별 상한(global=false) 하나만 걸고 채널 전체 상한(옛 INGEST_PREFETCH)은 두지 않는다.
로컬·홈서버의 RabbitMQ 3.x에서도 같은 코드가 그대로 돈다.

상수는 import 시점에 env에서 읽으므로 importlib.reload로 다시 평가한다.
reload는 같은 모듈 객체를 갱신하므로 다른 테스트의 참조는 깨지지 않지만, env를 되돌린 뒤
한 번 더 reload해 상수를 원래대로 돌려놓는다.
"""

import asyncio
import importlib

import pytest

from graph import consumer


class _FakeQueue:
    async def bind(self, exchange, routing_key=None, **kwargs):
        pass


class _FakeChannel:
    def __init__(self):
        self.qos_calls = []
        self.is_closed = False

    async def set_qos(self, prefetch_count=0, prefetch_size=0, global_=False, timeout=None, all_channels=None):
        # all_channels는 aio-pika에 남은 global_의 옛 이름이다 — 그 이름으로 걸어도 채널 전체 qos로 기록한다
        self.qos_calls.append((prefetch_count, global_ if all_channels is None else all_channels))

    async def declare_exchange(self, name, type=None, **kwargs):
        return object()

    async def declare_queue(self, name, **kwargs):
        return _FakeQueue()


class _FakeConnection:
    def __init__(self):
        self.channels = []
        self.is_closed = False

    async def channel(self, *args, **kwargs):
        ch = _FakeChannel()
        self.channels.append(ch)
        return ch

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc_info):
        return False


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


# --- _apply_qos --------------------------------------------------------------

def test_apply_qos_sets_only_per_queue_limit_once():
    # 큐별(global=false) 한 번만 건다 — global=true 호출이 하나라도 나가면 Amazon MQ 4.3이 채널을 닫는다
    ch = _FakeChannel()

    asyncio.run(consumer._apply_qos(ch))

    assert ch.qos_calls == [(consumer.INGEST_PREFETCH_PER_QUEUE, False)]
    assert not any(global_ for _, global_ in ch.qos_calls)


def test_apply_qos_ignores_legacy_channel_prefetch_env(reload_consumer):
    # 서버 .env에 옛 INGEST_PREFETCH가 남아 있어도 채널 전체 qos를 걸지 않는다.
    # 큐별 값은 리터럴 9로 본다 — 옛 값(50)이 큐별 상한으로 새어 들어가도 잡히게.
    mod = reload_consumer(INGEST_PREFETCH="50")
    ch = _FakeChannel()

    asyncio.run(mod._apply_qos(ch))

    assert ch.qos_calls == [(9, False)]


def test_channel_wide_prefetch_constant_is_removed(reload_consumer):
    # 채널 전체 상한 상수는 없어졌다 — 옛 env를 줘도 되살아나지 않는다
    mod = reload_consumer(INGEST_PREFETCH="50")

    assert not hasattr(mod, "INGEST_PREFETCH")


# --- 기동 경로 ---------------------------------------------------------------

class _ReachedDiscovery(Exception):
    """_run_consumer가 연결·QoS·토폴로지 준비를 마치고 큐 발견까지 왔음을 알리는 테스트 전용 예외."""


def test_consumer_startup_applies_only_per_queue_qos_on_consume_channel(monkeypatch):
    # 기동 경로 전체에서 global qos가 한 번도 나가지 않아야 Amazon MQ 4.3에서 재시작 루프에 빠지지 않는다.
    # 기동 로그 등이 지운 상수를 계속 참조하면 _ReachedDiscovery 대신 NameError가 난다 —
    # import는 되므로 다른 단위 테스트는 통과하지만 운영 consumer는 5초마다 재시작만 반복한다.
    conn = _FakeConnection()
    reached = []

    async def fake_connect(*args, **kwargs):
        return conn

    async def fake_run_forever(state):
        reached.append(state)
        raise _ReachedDiscovery

    monkeypatch.setattr(consumer.aio_pika, "connect", fake_connect)
    monkeypatch.setattr(consumer.queue_discovery, "run_forever", fake_run_forever)

    with pytest.raises(_ReachedDiscovery):
        asyncio.run(consumer._run_consumer())

    state = reached[0]
    # 큐별 상한은 소비 채널에 걸려야 백프레셔가 된다(발행 채널에 걸면 소비 쪽은 무제한으로 받는다)
    assert state.channel.qos_calls == [(consumer.INGEST_PREFETCH_PER_QUEUE, False)]
    # 발행 채널을 포함한 다른 채널에는 qos가 더 나가지 않는다
    assert [call for ch in conn.channels for call in ch.qos_calls] == [(consumer.INGEST_PREFETCH_PER_QUEUE, False)]


# --- 큐별 prefetch 파생 ------------------------------------------------------

def test_per_queue_prefetch_defaults_to_write_plus_lookahead(reload_consumer):
    mod = reload_consumer()

    assert mod.INGEST_PREFETCH_PER_QUEUE == 9  # 쓰기 1 + look-ahead 8


def test_per_queue_prefetch_empty_string_is_treated_as_unset(reload_consumer):
    # compose가 빈 값을 넘겨도 파생 규칙이 살아 있어야 한다
    mod = reload_consumer(INGEST_PREFETCH_PER_QUEUE="")

    assert mod.INGEST_PREFETCH_PER_QUEUE == 9


def test_per_queue_prefetch_explicit_value_wins(reload_consumer):
    mod = reload_consumer(INGEST_PREFETCH_PER_QUEUE="5")

    assert mod.INGEST_PREFETCH_PER_QUEUE == 5


@pytest.mark.parametrize("lookahead, expected", [
    ("3", 4),  # 쓰기 1 + look-ahead 3
    ("0", 1),  # look-ahead 킬스위치 — 쓰기 1건만 받아 둔다
])
def test_per_queue_prefetch_follows_lookahead(reload_consumer, lookahead, expected):
    mod = reload_consumer(INGEST_CHANGESET_LOOKAHEAD=lookahead)

    assert mod.INGEST_PREFETCH_PER_QUEUE == expected
