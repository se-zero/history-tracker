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


# Amazon MQ for RabbitMQ — AMQPS 5671, 관리 API는 HTTPS 443·15671만 연다(평문 15672 없음)
AMQPS_URL = "amqps://u:p@b-x-1.mq.ap-northeast-2.amazonaws.com:5671/"
AMQPS_BASE = "https://b-x-1.mq.ap-northeast-2.amazonaws.com:15671"


@pytest.mark.parametrize("amqp_url", [
    AMQPS_URL,
    "amqps://u:p@b-x-1.mq.ap-northeast-2.amazonaws.com/",  # 포트 생략 — 관리 포트는 AMQP 포트에서 계산하지 않는다
])
def test_management_target_amqps_derives_https_management_port(amqp_url):
    assert queue_discovery.management_target(amqp_url) == (AMQPS_BASE, ("u", "p"), "/")


@pytest.mark.parametrize("override, expected_base", [
    ("https://Host:443/", "https://host"),      # 기본 포트 제거 + 호스트 소문자 + 끝 / 제거
    ("https://h:15671/", "https://h:15671"),    # 기본이 아닌 포트는 남긴다
    ("https://h/prefix/", "https://h/prefix"),  # 경로는 유지한다
    ("http://H:80/", "http://h"),               # http의 기본 포트는 80
    ("http://h:443/", "http://h:443"),          # 기본 포트는 스킴별이다 — http의 443은 남긴다
])
def test_management_target_override_is_normalized_like_httpx_log(override, expected_base):
    # httpx는 요청 줄에 기본 포트를 지우고 호스트를 소문자로 찍는다 — base가 그 모양이어야 로그 필터가 맞는다.
    # 인증·vhost는 덮어쓰기와 상관없이 AMQP URL에서 온다(덮어쓰기의 경로를 vhost로 읽지 않는다).
    assert queue_discovery.management_target("amqp://u:p%40ss@rabbitmq:5672/prod", override) == (
        expected_base, ("u", "p@ss"), "prod")


@pytest.mark.parametrize("override", [
    "b-x.mq.amazonaws.com",                                # 스킴 없음
    "ftp://h",                                             # http(s)가 아닌 스킴
    "amqps://b-x-1.mq.ap-northeast-2.amazonaws.com:5671",  # AMQP 주소를 잘못 넣음
    "https://",                                            # 호스트 없음
    "https://h:notaport",                                  # 숫자가 아닌 포트 — 그대로 쓰면 폴링 때 httpx.InvalidURL
    "https://h?x=1",                                       # 쿼리 — base 뒤에 경로를 붙이면 주소가 깨져 매 틱 실패한다
    "https://h#frag",                                      # 프래그먼트 — 위와 같다
])
def test_management_target_invalid_override_falls_back_to_derived_with_error(caplog, override):
    # httpx.InvalidURL은 tick이 잡지 않아 consumer가 5초마다 재시작한다 — 파생 주소로 계속 돌고 ERROR로 알린다
    with caplog.at_level(logging.DEBUG):
        result = queue_discovery.management_target(AMQPS_URL, override)

    assert result == (AMQPS_BASE, ("u", "p"), "/")
    assert [r for r in caplog.records if r.levelno >= logging.ERROR]


@pytest.mark.parametrize("override", ["https://u:s3cret@h", "https://:s3cret@h"])
def test_management_target_override_with_password_falls_back_without_logging_it(caplog, override):
    # 인증은 항상 AMQP URL에서 온다. httpx는 요청 줄에 사용자 정보를 비밀번호까지 그대로 찍으므로
    # 덮어쓰기에 든 사용자 정보는 받지 않는다. 알리는 로그(트레이스백 포함)에도 비밀번호를 남기지 않는다.
    with caplog.at_level(logging.DEBUG):
        result = queue_discovery.management_target(AMQPS_URL, override)

    assert result == (AMQPS_BASE, ("u", "p"), "/")
    assert [r for r in caplog.records if r.levelno >= logging.ERROR]
    assert "s3cret" not in caplog.text


def test_management_target_override_with_username_only_falls_back(caplog):
    # 비밀번호가 없어도 사용자 정보는 받지 않는다 — 인증의 출처는 AMQP URL 하나다
    with caplog.at_level(logging.DEBUG):
        result = queue_discovery.management_target(AMQPS_URL, "https://admin@h")

    assert result == (AMQPS_BASE, ("u", "p"), "/")
    assert [r for r in caplog.records if r.levelno >= logging.ERROR]


@pytest.mark.parametrize("override", [None, "", "   ", "\t\n"])
def test_management_target_blank_override_uses_derived_without_log(caplog, override):
    # 빈 값(예: compose의 ${VAR:-})은 미설정과 같다 — 기동마다 경고·ERROR가 찍히면 안 된다
    with caplog.at_level(logging.DEBUG):
        result = queue_discovery.management_target(AMQPS_URL, override)

    assert result == (AMQPS_BASE, ("u", "p"), "/")
    assert [r for r in caplog.records if r.levelno >= logging.WARNING] == []


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


def _management_and_openai(seen):
    """관리 API 요청은 주소를 기록하고 큐 목록을, OpenAI 요청은 빈 성공 응답을 돌려준다.

    httpx는 응답을 받은 뒤에만 요청 줄을 찍으므로 두 경우 모두 응답을 만든다.
    """
    def handler(request):
        if request.url.host == "api.openai.com":
            return httpx.Response(200, json={})
        seen.append(str(request.url))
        return httpx.Response(200, json=[{"name": P + "p1", "messages": 1}])
    return handler


@pytest.mark.parametrize("override, expected_base", [
    (None, AMQPS_BASE),                    # amqps에서 파생한 https 관리 주소(기본이 아닌 포트 15671)
    ("https://Host:443", "https://host"),  # 덮어쓰기 — httpx는 :443을 지우고 호스트를 소문자로 찍는다
], ids=["derived-amqps", "override-default-port"])
def test_https_management_polling_lines_are_dropped_and_other_hosts_kept(
        caplog, _httpx_filter_cleanup, override, expected_base):
    # 운영과 같은 배선 — management_target의 base로 필터를 붙이고 같은 base로 폴링한다
    base, _, vhost = queue_discovery.management_target(AMQPS_URL, override)
    seen = []

    async def run():
        async with _client(_management_and_openai(seen)) as c:
            await queue_discovery.fetch_project_queues(c, base, vhost)
            await c.post("https://api.openai.com/v1/chat/completions")

    with caplog.at_level(logging.INFO, logger="httpx"):
        queue_discovery.install_httpx_filter(base)
        asyncio.run(run())

    # 폴링이 실제로 그 https 관리 주소로 갔다 — 필터와 요청이 같은 엉뚱한 주소를 써서 우연히 맞는 경우를 배제한다
    assert seen and all(url.startswith(expected_base + "/api/queues/") for url in seen)
    lines = [r.getMessage() for r in caplog.records if r.name == "httpx" and "HTTP Request" in r.getMessage()]
    # 관리 API 줄은 떨어지고 OpenAI 줄은 남는다 — 필터가 httpx 줄을 전부 삼켜도 이 단언이 깨진다
    assert len(lines) == 1 and "https://api.openai.com/" in lines[0]


# --- run_forever -------------------------------------------------------------

class _StopDiscovery(Exception):
    """run_forever의 무한 루프를 첫 틱에서 끊기 위한 테스트 전용 예외."""


def _run_forever_once(monkeypatch, amqp_url):
    """run_forever를 첫 틱까지만 돌린다. (install_httpx_filter에 넘긴 base 목록, tick에 넘긴 (base, vhost) 목록)을 돌려준다."""
    monkeypatch.setattr(consumer, "RABBITMQ_URL", amqp_url)
    installed, ticked = [], []

    def fake_install(management_base):
        installed.append(management_base)

    async def fake_tick(state, client, base, vhost):
        ticked.append((base, vhost))
        raise _StopDiscovery

    monkeypatch.setattr(queue_discovery, "install_httpx_filter", fake_install)
    monkeypatch.setattr(queue_discovery, "tick", fake_tick)
    with pytest.raises(_StopDiscovery):
        asyncio.run(queue_discovery.run_forever(_State({})))
    return installed, ticked


@pytest.mark.parametrize("env_value, expected_base", [
    ("https://Mgmt.Example.com:443/", "https://mgmt.example.com"),  # 덮어쓰기 — 정규화한 값
    (None, AMQPS_BASE),                                              # 미설정 — amqps에서 파생
    ("", AMQPS_BASE),                                                # 빈 값 — 미설정과 같다
    ("b-x.mq.amazonaws.com", AMQPS_BASE),                            # 무효 — 파생값으로 계속 돈다
], ids=["override", "unset", "empty", "invalid"])
def test_run_forever_reads_management_override_at_call_time(monkeypatch, env_value, expected_base):
    # env는 import 시점이 아니라 run_forever 호출 시점에 읽는다 — 여기서 바꾼 env가 반영돼야 한다
    if env_value is None:
        monkeypatch.delenv("RABBITMQ_MANAGEMENT_URL", raising=False)
    else:
        monkeypatch.setenv("RABBITMQ_MANAGEMENT_URL", env_value)

    installed, ticked = _run_forever_once(monkeypatch, "amqps://u:p@b-x-1.mq.ap-northeast-2.amazonaws.com:5671/prod")

    # 필터와 폴링이 같은 base를 써야 관리 API 줄이 떨어진다. vhost는 덮어쓰기가 아니라 AMQP URL에서 온다.
    assert ticked == [(expected_base, "prod")]
    assert installed == [expected_base]


def test_run_forever_without_override_keeps_plain_amqp_derivation(monkeypatch):
    # 로컬·홈서버(amqp://, 관리 http://:15672)는 아무 설정 없이 지금과 같다
    monkeypatch.delenv("RABBITMQ_MANAGEMENT_URL", raising=False)

    installed, ticked = _run_forever_once(monkeypatch, "amqp://guest:guest@rabbitmq:5672/")

    assert ticked == [("http://rabbitmq:15672", "/")]
    assert installed == ["http://rabbitmq:15672"]
