"""queue_discovery(관리 API 폴링 기반 프로젝트 큐 발견) 단위 테스트 (오프라인)."""

import asyncio
import logging
from urllib.parse import parse_qs

import httpx
import pytest
from aio_pika.exceptions import ChannelNotFoundEntity

from graph import consumer, ingest_backlog, ingest_tombstones, queue_discovery

P = "history.project."


# --- management_target -------------------------------------------------------

def test_management_target_defaults():
    assert queue_discovery.management_target("amqp://guest:guest@localhost/") == (
        "http://localhost:15672", ("guest", "guest"), "/")


def test_management_target_decodes_credentials_and_vhost():
    base, auth, vhost = queue_discovery.management_target("amqp://u:p%40ss@rabbitmq:5672/prod")
    assert base == "http://rabbitmq:15672"
    assert auth == ("u", "p@ss")
    assert vhost == "prod"


def test_management_target_encoded_default_vhost_and_no_userinfo():
    assert queue_discovery.management_target("amqp://u:p@h/%2F")[2] == "/"
    assert queue_discovery.management_target("amqp://h")[1] == ("guest", "guest")


# --- fetch_project_queues ----------------------------------------------------

def _client(handler):
    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


def test_fetch_paginates_and_sends_expected_request():
    seen = []

    def handler(request):
        seen.append(request)
        page = int(request.url.params["page"])
        # 마지막이 아닌 페이지는 가득 차 있다(page_size=500) — 짧은 페이지는 그 자체로 종료 신호다.
        n = 500 if page == 1 else 1
        items = [{"name": f"{P}p{page}-{i}", "messages": page} for i in range(n)]
        return httpx.Response(200, json={"items": items, "page_count": 2, "page": page})

    async def run():
        async with _client(handler) as c:
            return await queue_discovery.fetch_project_queues(c, "http://h:15672", "/")

    result = asyncio.run(run())
    assert len(result) == 501 and result[f"{P}p2-0"] == 2
    assert len(seen) == 2
    assert seen[0].url.raw_path.startswith(b"/api/queues/%2F?")
    q = parse_qs(seen[0].url.query.decode())
    assert q["page_size"] == ["500"]
    assert q["use_regex"] == ["true"]
    assert q["columns"] == ["name,messages"]


def test_fetch_stops_on_short_page_without_page_count():
    calls = []

    def handler(request):
        calls.append(1)
        return httpx.Response(200, json={"items": [{"name": f"{P}p{i}", "messages": None} for i in range(499)]})

    async def run():
        async with _client(handler) as c:
            return await queue_discovery.fetch_project_queues(c, "http://h:15672", "/")

    result = asyncio.run(run())
    assert len(calls) == 1
    assert len(result) == 499 and set(result.values()) == {0}


def test_fetch_accepts_plain_list():
    def handler(request):
        return httpx.Response(200, json=[{"name": f"{P}p1", "messages": 4}])

    async def run():
        async with _client(handler) as c:
            return await queue_discovery.fetch_project_queues(c, "http://h:15672", "/")

    assert asyncio.run(run()) == {f"{P}p1": 4}


# --- diff --------------------------------------------------------------------

def test_diff():
    assert queue_discovery.diff({"a", "b"}, {"b", "c", "d"}) == (["c", "d"], ["a"])


# --- tick --------------------------------------------------------------------

class _Q:
    def __init__(self, name):
        self.name = name


class _Channel:
    def __init__(self, error=None):
        self.declared = []
        self.error = error

    async def declare_queue(self, name, passive=False, **kw):
        self.declared.append((name, passive))
        if self.error:
            raise self.error
        return _Q(name)


class _State:
    def __init__(self, consumers, alive=True, channel_error=None):
        self.consumers = dict(consumers)
        self.alive = alive
        self.channel = _Channel(channel_error)
        self.subscribed = []
        self.unsubscribed = []

    def is_alive(self):
        return self.alive

    async def subscribe(self, name, queue):
        self.subscribed.append(name)
        self.consumers[name] = "tag"

    async def unsubscribe(self, name):
        self.unsubscribed.append(name)
        self.consumers.pop(name, None)


@pytest.fixture(autouse=True)
def _clean(monkeypatch):
    ingest_backlog.reset()
    monkeypatch.setattr(ingest_tombstones, "_project_ids", set())
    yield
    ingest_backlog.reset()


def _run_tick(state, handler):
    async def run():
        async with _client(handler) as c:
            await queue_discovery.tick(state, c, "http://h:15672", "/")
    asyncio.run(run())


def _ok(queues):
    return lambda request: httpx.Response(200, json=[{"name": n, "messages": m} for n, m in queues.items()])


def test_tick_subscribes_new_queue_after_passive_declare_and_updates_snapshot():
    state = _State({})
    _run_tick(state, _ok({P + "p1": 7}))
    assert state.channel.declared == [(P + "p1", True)]
    assert state.subscribed == [P + "p1"]
    assert ingest_backlog.queue_backlog("p1") == 7


def test_tick_ignores_non_project_queues_even_if_api_returns_them():
    # 관리 API 필터는 page가 있을 때만 적용된다 — 필터가 빠져 DLQ·재시도 큐가 섞여 와도 구독하면 안 된다
    state = _State({})
    _run_tick(state, _ok({"history.dlq": 3, "history.retry": 1, P + "p1": 2}))
    assert state.subscribed == [P + "p1"]
    assert state.channel.declared == [(P + "p1", True)]
    assert ingest_backlog.queue_backlog("p1") == 2


def test_tick_unsubscribes_vanished_queue():
    state = _State({P + "p1": "t1"})
    _run_tick(state, _ok({}))
    assert state.unsubscribed == [P + "p1"]
    assert state.consumers == {}


def test_tick_management_failure_keeps_everything():
    state = _State({P + "p1": "t1"})
    ingest_backlog.update_snapshot({"p1": 3})
    _run_tick(state, lambda request: httpx.Response(500))
    assert state.unsubscribed == [] and state.subscribed == []
    assert ingest_backlog.queue_backlog("p1") == 3


def test_tick_deletes_orphan_queue_of_tombstoned_project(monkeypatch):
    ingest_tombstones._project_ids.add("gone")
    deleted = []

    async def fake_delete(pid):
        deleted.append(pid)
        return True

    monkeypatch.setattr(consumer, "delete_project_queue", fake_delete)
    state = _State({})
    _run_tick(state, _ok({P + "gone": 1}))
    assert deleted == ["gone"]
    assert state.subscribed == [] and state.channel.declared == []


def test_tick_orphan_delete_failure_is_swallowed(monkeypatch):
    ingest_tombstones._project_ids.add("gone")

    async def boom(pid):
        raise RuntimeError("x")

    monkeypatch.setattr(consumer, "delete_project_queue", boom)
    _run_tick(_State({}), _ok({P + "gone": 1}))


def test_tick_raises_when_connection_dead():
    with pytest.raises(ConnectionError):
        _run_tick(_State({}, alive=False), _ok({}))


def test_tick_returns_when_queue_vanishes_between_list_and_declare():
    state = _State({}, channel_error=ChannelNotFoundEntity("x"))
    _run_tick(state, _ok({P + "p1": 1}))
    assert state.subscribed == []


# --- httpx 관리 API 요청 로그 필터 -------------------------------------------

BASE = "http://rabbitmq:15672"


@pytest.fixture
def _httpx_filter_cleanup():
    yield
    httpx_logger = logging.getLogger("httpx")
    for f in list(httpx_logger.filters):
        if isinstance(f, queue_discovery._ManagementRequestLogFilter):
            httpx_logger.removeFilter(f)


def _record(msg):
    return logging.makeLogRecord({"msg": msg, "args": ()})


def test_log_filter_drops_management_lines_and_keeps_openai_lines():
    f = queue_discovery._ManagementRequestLogFilter(BASE)
    mgmt = _record('HTTP Request: GET http://rabbitmq:15672/api/queues/%2F?page=1 "HTTP/1.1 200 OK"')
    openai = _record('HTTP Request: POST https://api.openai.com/v1/chat/completions "HTTP/1.1 200 OK"')
    assert f.filter(mgmt) is False
    assert f.filter(openai) is True


def test_install_httpx_filter_is_idempotent(_httpx_filter_cleanup):
    queue_discovery.install_httpx_filter(BASE)
    queue_discovery.install_httpx_filter(BASE)
    installed = [f for f in logging.getLogger("httpx").filters
                 if isinstance(f, queue_discovery._ManagementRequestLogFilter)]
    assert len(installed) == 1


def test_polling_leaves_no_httpx_request_log_with_filter_installed(caplog, _httpx_filter_cleanup):
    async def run():
        async with _client(_ok({P + "p1": 1})) as c:
            await queue_discovery.fetch_project_queues(c, BASE, "/")

    with caplog.at_level(logging.INFO, logger="httpx"):
        queue_discovery.install_httpx_filter(BASE)
        asyncio.run(run())
    assert [r for r in caplog.records if "HTTP Request" in r.getMessage()] == []
