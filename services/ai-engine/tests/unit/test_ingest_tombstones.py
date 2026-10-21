"""수집 차단 tombstone(끊긴 연동·지운 프로젝트) 단위 테스트 (오프라인 — Neo4j driver fake 주입).

연동 해제·프로젝트 삭제 뒤에도 큐에 남은 이벤트가 소비되며 지운 그래프를 다시 채우던 결함을 고정한다.
- tombstone 모듈: 기록 -> 판정, 기동 시 로드(만료 정리)
- 삭제 함수: 노드를 지우기 전에 tombstone을 먼저 남기는 순서 (소스 삭제는 test_delete_source_graph.py)
- 관문: handle()·프리페치 판정·consumer가 tombstone 이벤트를 LLM·쓰기 전에 끊는지
- 라우터: integration_id 쿼리 인자 전달, 기동 시 consumer보다 먼저 로드

모듈 전역 집합은 테스트마다 monkeypatch로 비운 새 집합으로 바꾼다 — 다른 테스트로 새면 안 된다.
async 함수는 pytest-asyncio 없이 asyncio.run()으로 구동한다.
"""

import asyncio
import logging
import re
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

import main
from graph import consumer, ingest_tombstones
from graph.consumer import DLQ_QUEUE, RETRY_QUEUE, _changeset_prefetch, _process_event
from graph.event_handler import handle, is_prefetchable_changeset
from graph.ingest_tombstones import (
    TOMBSTONE_TTL_DAYS,
    is_blocked,
    load_tombstones,
    record_integration_tombstone,
    record_project_tombstone,
)
from graph.maintenance import delete_project_graph
from routers.graph import router as graph_router


@pytest.fixture(autouse=True)
def _fresh_tombstones(monkeypatch):
    monkeypatch.setattr(ingest_tombstones, "_integration_ids", set())
    monkeypatch.setattr(ingest_tombstones, "_project_ids", set())


def _event(node_type="Issue", project_id="p1", integration_id=None, source="GITHUB"):
    event = {
        "nodeType": node_type, "source": source, "projectId": project_id,
        "occurredAt": "2026-07-01T00:00:00Z",
        "actor": {"id": "human1", "name": "Human", "email": ""},
        "properties": {"external_id": "1", "issue_key": "#1", "title": "t", "hash": "abc", "message": "m"},
        "refs": {},
    }
    if integration_id is not None:
        event["integrationId"] = integration_id
    return event


# --- Neo4j fake -------------------------------------------------------------

class _FakeResult:
    def __init__(self, nodes_deleted=0, rows=()):
        self._nodes_deleted = nodes_deleted
        self._rows = list(rows)

    async def consume(self):
        return SimpleNamespace(counters=SimpleNamespace(nodes_deleted=self._nodes_deleted))

    async def data(self):
        return self._rows


class _FakeTombstoneSession:
    """IngestTombstone 노드 저장소 흉내. 쿼리 본문으로 MERGE·만료 삭제·전체 조회를 구분한다.

    store: {(kind, id): 경과 일수}. 만료 삭제는 쿼리가 받은 ttl_days를 그대로 써서 판정하므로,
    구현이 TTL을 다르게 넘기거나 정리 없이 조회만 하면 결과가 달라진다.
    """

    def __init__(self, store=None, on_run=None):
        self.store = dict(store or {})
        self.queries = []
        self._on_run = on_run

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return None

    async def run(self, query, **params):
        self.queries.append((query, params))
        if self._on_run is not None:
            self._on_run(query, params)
        if "MERGE (t:IngestTombstone {integration_id" in query:
            self.store[("integration", params["key"])] = 0
            return _FakeResult()
        if "MERGE (t:IngestTombstone {target_project_id" in query:
            self.store[("project", params["key"])] = 0
            return _FakeResult()
        if "DELETE t" in query:
            expired = [k for k, age in self.store.items() if age > params["ttl_days"]]
            for key in expired:
                del self.store[key]
            return _FakeResult(nodes_deleted=len(expired))
        if "RETURN t.kind" in query:
            return _FakeResult(rows=[
                {
                    "kind": kind,
                    "integration_id": key if kind == "integration" else None,
                    "target_project_id": key if kind == "project" else None,
                }
                for (kind, key) in self.store
            ])
        raise AssertionError(f"예상 밖 쿼리: {query}")


class _FakeDriver:
    def __init__(self, session):
        self._session = session

    def session(self):
        return self._session


def _with_session(session):
    return patch("graph.ingest_tombstones.get_driver", return_value=_FakeDriver(session))


# --- 판정 -------------------------------------------------------------------

def test_recorded_integration_blocks_only_its_own_events():
    with _with_session(_FakeTombstoneSession()):
        asyncio.run(record_integration_tombstone("int-1"))

    assert is_blocked(_event(integration_id="int-1")) is True
    assert is_blocked(_event(integration_id="int-2")) is False


def test_event_without_integration_id_is_not_blocked_by_integration_tombstone():
    with _with_session(_FakeTombstoneSession()):
        asyncio.run(record_integration_tombstone("int-1"))

    assert is_blocked(_event()) is False
    assert is_blocked(_event(integration_id="")) is False
    assert is_blocked(_event(integration_id=None)) is False


def test_integration_tombstone_does_not_block_other_project_events_without_matching_id():
    # 연결이 끊겨도 같은 프로젝트의 다른 연결(다른 integration id)·id 없는 이벤트는 그대로 흐른다
    with _with_session(_FakeTombstoneSession()):
        asyncio.run(record_integration_tombstone("int-1"))

    assert is_blocked(_event(project_id="p1", integration_id="int-reconnected")) is False
    assert is_blocked(_event(project_id="p1")) is False


def test_deleted_project_blocks_all_its_events_even_without_integration_id():
    with _with_session(_FakeTombstoneSession()):
        asyncio.run(record_project_tombstone("p-gone"))

    assert is_blocked(_event(project_id="p-gone")) is True
    assert is_blocked(_event(project_id="p-gone", integration_id="int-9")) is True
    assert is_blocked(_event(project_id="p-alive")) is False


def test_event_without_project_id_is_not_blocked():
    with _with_session(_FakeTombstoneSession()):
        asyncio.run(record_project_tombstone("p-gone"))

    assert is_blocked(_event(project_id="")) is False


def test_nothing_is_blocked_before_any_tombstone():
    assert is_blocked(_event(integration_id="int-1")) is False
    assert is_blocked(_event(project_id="p1")) is False


# --- 기록 -------------------------------------------------------------------

def test_record_merges_node_with_kind_and_key():
    session = _FakeTombstoneSession()
    with _with_session(session):
        asyncio.run(record_integration_tombstone("int-1"))
        asyncio.run(record_project_tombstone("p-gone"))

    assert session.store == {("integration", "int-1"): 0, ("project", "p-gone"): 0}
    kinds = [query for query, _ in session.queries]
    assert "kind = 'integration'" in kinds[0] and "created_at = datetime()" in kinds[0]
    assert "kind = 'project'" in kinds[1] and "created_at = datetime()" in kinds[1]


def test_record_is_idempotent():
    session = _FakeTombstoneSession()
    with _with_session(session):
        asyncio.run(record_integration_tombstone("int-1"))
        asyncio.run(record_integration_tombstone("int-1"))

    assert session.store == {("integration", "int-1"): 0}
    assert all("MERGE" in query for query, _ in session.queries)


def test_blank_ids_are_not_recorded():
    session = _FakeTombstoneSession()
    with _with_session(session):
        asyncio.run(record_integration_tombstone(""))
        asyncio.run(record_project_tombstone(""))

    assert session.queries == []
    assert ingest_tombstones._integration_ids == set()
    assert ingest_tombstones._project_ids == set()


def test_event_is_blocked_while_neo4j_write_is_still_in_flight():
    # 삭제 도중 도착한 이벤트도 걸러야 하므로 메모리가 DB 쓰기보다 먼저 올라가 있어야 한다
    seen = {}
    session = _FakeTombstoneSession(
        on_run=lambda query, params: seen.setdefault("blocked", is_blocked(_event(integration_id="int-1")))
    )
    with _with_session(session):
        asyncio.run(record_integration_tombstone("int-1"))

    assert seen["blocked"] is True


def _boom(query, params):
    raise RuntimeError("neo4j down")


def test_record_failure_propagates_and_releases_memory():
    # 기록이 안 된 채 삭제가 진행되면 재기동 후 옛 이벤트가 샌다 -> 예외를 올려 삭제를 중단시킨다.
    # 삭제가 중단되면 연동·프로젝트는 그대로 남으므로, 메모리에 남기면 아직 살아 있는 쪽의 이벤트를
    # 재기동 때까지 버리게 된다 -> 메모리에서도 되돌린다.
    with _with_session(_FakeTombstoneSession(on_run=_boom)):
        with pytest.raises(RuntimeError):
            asyncio.run(record_integration_tombstone("int-1"))
        with pytest.raises(RuntimeError):
            asyncio.run(record_project_tombstone("p-1"))

    assert is_blocked(_event(integration_id="int-1")) is False
    assert is_blocked(_event(project_id="p-1")) is False


def test_record_failure_keeps_tombstone_that_was_already_in_memory():
    # 이미 기록된(또는 기동 때 읽어 온) 표식을 다시 기록하다 실패해도 그 표식은 풀면 안 된다
    with _with_session(_FakeTombstoneSession()):
        asyncio.run(record_integration_tombstone("int-1"))
        asyncio.run(record_project_tombstone("p-1"))

    with _with_session(_FakeTombstoneSession(on_run=_boom)):
        with pytest.raises(RuntimeError):
            asyncio.run(record_integration_tombstone("int-1"))
        with pytest.raises(RuntimeError):
            asyncio.run(record_project_tombstone("p-1"))

    assert is_blocked(_event(integration_id="int-1")) is True
    assert is_blocked(_event(project_id="p-1")) is True


def test_tombstone_node_never_uses_project_id_or_source_property_names():
    # graph.maintenance의 삭제 쿼리가 라벨 없이 MATCH (n {project_id: ...}) / {project_id, source}로
    # 노드를 지우고, 그래프 조회·개요 쿼리도 project_id로 노드를 고른다. 이 이름을 쓰면 tombstone이
    # 다음 삭제 때 같이 지워지거나 사용자 그래프에 섞인다 (target_project_id는 다른 이름이라 안전).
    queries = [
        ingest_tombstones._MERGE_INTEGRATION, ingest_tombstones._MERGE_PROJECT,
        ingest_tombstones._PURGE_EXPIRED, ingest_tombstones._LOAD_ALL,
    ]
    for query in queries:
        assert not re.search(r"\bproject_id\b", query), query
        assert not re.search(r"\bsource\b", query), query


# --- 기동 시 로드 -----------------------------------------------------------

def test_load_purges_expired_then_loads_the_rest_into_memory():
    session = _FakeTombstoneSession(store={
        ("integration", "int-old"): TOMBSTONE_TTL_DAYS + 1,
        ("integration", "int-fresh"): TOMBSTONE_TTL_DAYS - 1,
        ("project", "p-old"): TOMBSTONE_TTL_DAYS + 10,
        ("project", "p-fresh"): 1,
    })
    with _with_session(session):
        asyncio.run(load_tombstones())

    assert is_blocked(_event(integration_id="int-fresh")) is True
    assert is_blocked(_event(project_id="p-fresh")) is True
    assert is_blocked(_event(integration_id="int-old")) is False
    assert is_blocked(_event(project_id="p-old")) is False
    # 만료분은 DB에서도 지워졌다
    assert set(session.store) == {("integration", "int-fresh"), ("project", "p-fresh")}


def test_load_uses_thirty_day_ttl():
    assert TOMBSTONE_TTL_DAYS == 30
    session = _FakeTombstoneSession()
    with _with_session(session):
        asyncio.run(load_tombstones())

    purge_params = next(params for query, params in session.queries if "DELETE t" in query)
    assert purge_params["ttl_days"] == 30


def test_load_failure_propagates_so_startup_can_retry():
    def boom(query, params):
        raise RuntimeError("neo4j not ready")

    with _with_session(_FakeTombstoneSession(on_run=boom)):
        with pytest.raises(RuntimeError):
            asyncio.run(load_tombstones())


# --- 프로젝트 삭제: 지우기 전에 tombstone ---------------------------------------

class _FakeDeleteSession:
    def __init__(self, events):
        self._events = events
        self.calls = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return None

    async def run(self, query, **params):
        self._events.append("delete-query")
        self.calls.append((query, params))
        return _FakeResult(nodes_deleted=7)


def _delete_project(project_id):
    events = []
    session = _FakeDeleteSession(events)
    record = AsyncMock(side_effect=lambda pid: events.append(f"tombstone:{pid}"))
    with patch("graph.maintenance.get_driver", return_value=_FakeDriver(session)), \
         patch("graph.maintenance.record_project_tombstone", record):
        deleted = asyncio.run(delete_project_graph(project_id))
    return deleted, events, session


def test_project_delete_records_tombstone_before_deleting_nodes():
    deleted, events, _session = _delete_project("p1")

    assert events == ["tombstone:p1", "delete-query"]
    assert deleted == 7


def test_project_delete_with_blank_id_records_nothing_and_deletes_nothing():
    deleted, events, session = _delete_project("")

    assert deleted == 0
    assert events == []
    assert session.calls == []


# --- 관문: handle() ---------------------------------------------------------

_NODE_TYPE_HANDLERS = [
    ("ChangeSet", "_handle_changeset"),
    ("PullRequest", "_handle_pull_request"),
    ("Issue", "_handle_issue"),
    ("Communication", "_handle_communication"),
    ("Document", "_handle_document"),
]


@pytest.mark.parametrize("node_type,handler_name", _NODE_TYPE_HANDLERS)
def test_handle_drops_event_of_disconnected_integration(node_type, handler_name):
    ingest_tombstones._integration_ids.add("int-1")
    handler = AsyncMock()
    with patch(f"graph.event_handler.{handler_name}", handler):
        asyncio.run(handle(_event(node_type=node_type, integration_id="int-1")))

    handler.assert_not_awaited()


@pytest.mark.parametrize("node_type,handler_name", _NODE_TYPE_HANDLERS)
def test_handle_drops_event_of_deleted_project(node_type, handler_name):
    ingest_tombstones._project_ids.add("p-gone")
    handler = AsyncMock()
    with patch(f"graph.event_handler.{handler_name}", handler):
        asyncio.run(handle(_event(node_type=node_type, project_id="p-gone")))

    handler.assert_not_awaited()


@pytest.mark.parametrize("node_type,handler_name", _NODE_TYPE_HANDLERS)
def test_handle_still_processes_events_that_are_not_tombstoned(node_type, handler_name):
    ingest_tombstones._integration_ids.add("int-1")
    ingest_tombstones._project_ids.add("p-gone")
    handler = AsyncMock()
    with patch(f"graph.event_handler.{handler_name}", handler):
        # 다른 연결 / id 없는 이벤트 / 다른 프로젝트 — 전부 지금처럼 처리된다
        asyncio.run(handle(_event(node_type=node_type, integration_id="int-reconnected")))
        asyncio.run(handle(_event(node_type=node_type)))

    assert handler.await_count == 2


def _changeset_event_with_files(**kwargs):
    event = _event(node_type="ChangeSet", **kwargs)
    event["properties"] = {
        "hash": "abc123", "message": "fix: 버그 수정",
        "files": [{"path": "src/a.py", "diff": "@@ -1 +1 @@", "additions": 1, "deletions": 1}],
    }
    return event


def _run_changeset(event):
    """ChangeSet을 실제 경로로 처리하되 LLM·Neo4j 경계만 mock — 어디까지 불렸는지 돌려준다."""
    mocks = {
        "summarize_diff": AsyncMock(return_value="요약"),
        "embed_text_batched": AsyncMock(return_value=[0.1]),
        "embed_batch": AsyncMock(return_value=[[0.2]]),
        "resolve_actor": AsyncMock(return_value={"uuid": "author-uuid"}),
    }
    upsert_changeset = AsyncMock()
    upsert_files = AsyncMock()
    with patch.multiple("graph.event_handler", make_neo4j_actor_store=lambda pid: "STORE", **mocks), \
         patch("graph.event_handler.builder.upsert_changeset", upsert_changeset), \
         patch("graph.event_handler.builder.upsert_files_with_modified_edges", upsert_files):
        asyncio.run(handle(event))
    return mocks, upsert_changeset, upsert_files


def test_tombstoned_changeset_never_reaches_llm_actor_or_write():
    ingest_tombstones._integration_ids.add("int-1")

    mocks, upsert_changeset, upsert_files = _run_changeset(_changeset_event_with_files(integration_id="int-1"))

    for name, mock in mocks.items():
        assert mock.await_count == 0, name
    upsert_changeset.assert_not_awaited()
    upsert_files.assert_not_awaited()


def test_regular_changeset_reaches_llm_actor_and_write():
    # 위 테스트의 "안 불렸다"가 빈 통과가 아님을 보이는 대조군 — 같은 mock 구성에서 정상 이벤트는 끝까지 간다
    ingest_tombstones._integration_ids.add("int-1")

    mocks, upsert_changeset, upsert_files = _run_changeset(_changeset_event_with_files(integration_id="int-2"))

    for name, mock in mocks.items():
        assert mock.await_count >= 1, name
    upsert_changeset.assert_awaited_once()
    upsert_files.assert_awaited_once()


def test_dropped_event_is_logged_with_identifiers_only(caplog):
    ingest_tombstones._integration_ids.add("int-1")
    event = _event(node_type="Communication", source="SLACK", integration_id="int-1")
    event["properties"]["body"] = "SECRET-BODY-TEXT"

    with caplog.at_level(logging.INFO, logger="graph.event_handler"), \
         patch("graph.event_handler._handle_communication", AsyncMock()):
        asyncio.run(handle(event))

    dropped = [r.getMessage() for r in caplog.records if "tombstone" in r.getMessage()]
    assert len(dropped) == 1
    message = dropped[0]
    assert "project=p1" in message
    assert "source=SLACK" in message
    assert "nodeType=Communication" in message
    assert "integrationId=int-1" in message
    assert "SECRET-BODY-TEXT" not in message


# --- 관문: 프리페치 판정 --------------------------------------------------------

def test_tombstoned_changeset_is_not_prefetchable():
    ingest_tombstones._integration_ids.add("int-1")
    ingest_tombstones._project_ids.add("p-gone")

    assert is_prefetchable_changeset(_event(node_type="ChangeSet", integration_id="int-1")) is False
    assert is_prefetchable_changeset(_event(node_type="ChangeSet", project_id="p-gone")) is False


def test_non_tombstoned_changeset_is_still_prefetchable():
    ingest_tombstones._integration_ids.add("int-1")
    ingest_tombstones._project_ids.add("p-gone")

    assert is_prefetchable_changeset(_event(node_type="ChangeSet", integration_id="int-2")) is True
    assert is_prefetchable_changeset(_event(node_type="ChangeSet")) is True


def test_consumer_prefetch_hook_skips_tombstoned_changeset(monkeypatch):
    # 디스패처가 submit 시점에 부르는 훅 — 코루틴을 만들지 않아야 LLM 준비 단계가 시작되지 않는다
    monkeypatch.setattr(consumer, "prepare_changeset", lambda event: "PREPARE-CORO")
    ingest_tombstones._integration_ids.add("int-1")

    assert _changeset_prefetch((object(), _event(node_type="ChangeSet", integration_id="int-1"))) is None
    assert _changeset_prefetch((object(), _event(node_type="ChangeSet", integration_id="int-2"))) == "PREPARE-CORO"


# --- 관문: consumer ---------------------------------------------------------

class _FakeExchange:
    def __init__(self):
        self.published = []

    async def publish(self, message, routing_key):
        self.published.append((message, routing_key))


class _FakeChannel:
    def __init__(self):
        self.default_exchange = _FakeExchange()


class _FakeMessage:
    def __init__(self):
        self.headers = None
        self.body = b"{}"
        self.acked = False

    async def ack(self):
        self.acked = True


def test_process_event_acks_tombstoned_event_without_handling_retrying_or_marking_dirty(monkeypatch):
    handled, dirty = [], []

    async def fake_handle(event, prepared=None):
        handled.append(event)

    monkeypatch.setattr(consumer, "handle", fake_handle)
    monkeypatch.setattr(consumer, "mark_dirty", dirty.append)
    ingest_tombstones._integration_ids.add("int-1")
    channel, message = _FakeChannel(), _FakeMessage()

    asyncio.run(_process_event((message, _event(integration_id="int-1")), None, channel))

    assert handled == []                 # 핸들러 미호출
    assert dirty == []                   # 지운 프로젝트에 후처리 빌드·최초 수집중 상태가 되살아나지 않는다
    assert message.acked is True         # 정상 처리처럼 ack
    # 재시도 큐·DLQ로 보내지 않는다 (소용없이 맴돌 뿐)
    assert channel.default_exchange.published == []


def test_process_event_still_handles_events_that_are_not_tombstoned(monkeypatch):
    handled, dirty = [], []

    async def fake_handle(event, prepared=None):
        handled.append(event)

    monkeypatch.setattr(consumer, "handle", fake_handle)
    monkeypatch.setattr(consumer, "mark_dirty", dirty.append)
    ingest_tombstones._integration_ids.add("int-1")
    channel, message = _FakeChannel(), _FakeMessage()

    asyncio.run(_process_event((message, _event(integration_id="int-2")), None, channel))

    assert len(handled) == 1
    assert dirty == ["p1"]
    assert message.acked is True
    assert channel.default_exchange.published == []


def test_tombstoned_event_with_retry_header_is_dropped_not_requeued(monkeypatch):
    # 재시도(x-retry-count)·DLQ replay로 되돌아온 메시지도 같은 소비 경로를 타므로 똑같이 버려진다
    async def fake_handle(event, prepared=None):
        raise AssertionError("tombstone 이벤트는 handle까지 가면 안 된다")

    monkeypatch.setattr(consumer, "handle", fake_handle)
    ingest_tombstones._project_ids.add("p-gone")
    channel, message = _FakeChannel(), _FakeMessage()
    message.headers = {"x-retry-count": 5}

    asyncio.run(_process_event((message, _event(project_id="p-gone")), None, channel))

    assert message.acked is True
    assert [rk for _, rk in channel.default_exchange.published if rk in (RETRY_QUEUE, DLQ_QUEUE)] == []


# --- 라우터: integration_id 쿼리 인자 -------------------------------------------

def _graph_client():
    # 인증 의존성·lifespan 없이 라우터만 얹는다 — 쿼리 인자 바인딩만 본다
    app = FastAPI()
    app.include_router(graph_router)
    return TestClient(app)


def test_source_delete_endpoint_passes_integration_id_query_param():
    delete = AsyncMock(return_value={"nodes": 0})
    with patch("routers.graph.delete_project_source_graph", delete):
        response = _graph_client().delete("/graph/projects/p1/sources/google-chat?integration_id=int-1")

    assert response.status_code == 200
    delete.assert_awaited_once_with("p1", "google-chat", integration_id="int-1")


def test_source_delete_endpoint_without_integration_id_passes_none():
    delete = AsyncMock(return_value={"nodes": 0})
    with patch("routers.graph.delete_project_source_graph", delete):
        response = _graph_client().delete("/graph/projects/p1/sources/slack")

    assert response.status_code == 200
    delete.assert_awaited_once_with("p1", "slack", integration_id=None)


# --- 기동: consumer보다 먼저 로드 -------------------------------------------------

def test_lifespan_loads_tombstones_before_starting_consumer(monkeypatch):
    order = []

    async def noop(*_args, **_kwargs):
        return None

    async def fake_load():
        # 이미 만들어진 태스크(consumer)가 있다면 여기서 양보하는 사이 먼저 돌아 순서가 뒤집힌다
        await asyncio.sleep(0)
        order.append("load_tombstones")

    async def fake_consumer():
        order.append("consumer")

    async def fake_debounce():
        await asyncio.sleep(3600)

    monkeypatch.setattr(main, "ensure_token_configured", lambda: None)
    monkeypatch.setattr(main, "get_driver", lambda: None)
    for name in (
        "drop_legacy_issue_constraint", "ensure_constraints", "ensure_vector_indexes",
        "drop_node_search_index", "backfill_actor_aliases", "close_driver",
    ):
        monkeypatch.setattr(main, name, noop)
    monkeypatch.setattr(main, "load_tombstones", fake_load)
    monkeypatch.setattr(main, "start_consumer", fake_consumer)
    monkeypatch.setattr(main, "start_debounce_loop", fake_debounce)

    async def scenario():
        async with main.lifespan(main.app):
            await asyncio.sleep(0)   # 기동 직후 만들어진 태스크가 한 번 돌게 한다

    asyncio.run(scenario())

    assert order == ["load_tombstones", "consumer"]
