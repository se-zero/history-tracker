"""수집 차단 tombstone — 끊긴 연동·지운 프로젝트의 이벤트가 그래프를 다시 채우지 못하게 한다.

연동 해제나 프로젝트 삭제는 그래프를 지우지만, 그 뒤에도 이벤트는 계속 들어온다. 메인 큐에 이미
쌓인 것, 소비자가 미ack로 쥔 프리페치분, 재시도 큐(3분 x 20회)와 DLQ replay에 남은 것, 수집 서버에서
아직 돌던 수집이 낸 것이 전부 소비되면서 지운 그래프를 다시 채운다. 큐에서 그 이벤트를 골라 지울
수는 없으므로, 소비하는 쪽에서 "이 연결/프로젝트의 이벤트는 버린다"를 기억해 두고 거른다.

- 연동 해제 -> integration id tombstone. 재연결하면 새 integration id가 생기므로 새 연결의 이벤트는
  막히지 않는다. 이벤트 엔벨로프의 `integrationId`가 없는 이벤트(구 형식·미전달)는 이 검사로
  막을 수 없어 기존처럼 처리된다.
- 프로젝트 삭제 -> project id tombstone. 프로젝트 id는 재사용되지 않으므로 영구 차단해도 안전하다.

판정은 메모리 집합만 본다(이벤트마다 DB 왕복 금지). 재기동하면 메모리가 비므로 Neo4j에도 남겨 두고
기동 시 consumer를 띄우기 전에 다시 올린다. tombstone이 필요한 기간은 재시도 큐(약 1시간)와 수동
DLQ replay를 덮는 TTL까지만이라, 기동 때 그보다 오래된 것은 지운다.

노드 속성 이름에 `project_id`·`source`를 쓰지 않는다 — graph.maintenance의 삭제 쿼리가 라벨 없이
`MATCH (n {project_id: ...})`로 지우고, 그래프 조회·개요 쿼리도 `project_id`로 노드를 고르기 때문에,
그 이름을 쓰면 tombstone이 다음 삭제 때 같이 지워지거나 사용자 그래프에 섞여 보인다.
"""

import logging

from graph.driver import get_driver

logger = logging.getLogger(__name__)

# 재시도 큐가 약 1시간이고 DLQ replay는 수동이라(운영자가 며칠 뒤 돌릴 수 있다) 넉넉히 30일.
TOMBSTONE_TTL_DAYS = 30

_integration_ids: set[str] = set()
_project_ids: set[str] = set()


def is_blocked(event: dict) -> bool:
    """이벤트가 끊긴 연결이나 지운 프로젝트의 것이면 True. 메모리만 본다."""
    integration_id = event.get("integrationId")
    if integration_id and integration_id in _integration_ids:
        return True
    project_id = event.get("projectId")
    return bool(project_id) and project_id in _project_ids


# MERGE라 같은 해제가 재시도돼도 노드가 늘지 않는다. created_at은 매번 갱신해 TTL을 마지막 해제
# 시도 기준으로 잡는다. 속성 이름이 다르므로 쿼리를 둘로 나눈다(Cypher는 속성 키를 파라미터로 못 받는다).
_MERGE_INTEGRATION = """
MERGE (t:IngestTombstone {integration_id: $key})
SET t.kind = 'integration', t.created_at = datetime()
"""
_MERGE_PROJECT = """
MERGE (t:IngestTombstone {target_project_id: $key})
SET t.kind = 'project', t.created_at = datetime()
"""


async def record_integration_tombstone(integration_id: str) -> None:
    """끊긴 연결을 기록한다. Neo4j에 먼저 쓰고, 성공한 뒤에 메모리에 올린다.

    Neo4j 쓰기가 실패하면 예외를 그대로 올린다 — 호출부(그래프 삭제)가 중단돼 backend가 실패를
    보고 재시도하고, 재시도는 MERGE라 안전하다. 기록 없이 지우면 재기동 후 옛 이벤트가 샌다.
    메모리를 나중에 올리는 이유: 쓰기가 멈췄다 실패하면 삭제가 중단돼 연동은 연결된 채로 남는데, 그동안
    메모리로 막고 있었다면 아직 살아 있는 연결의 이벤트를 버리고 ack해 되살릴 길이 없다. 쓰는 동안 막지
    못한 이벤트는 그래프에 들어가지만, 호출부가 바로 다음에 그 소스(프로젝트)의 노드를 지우므로 남지 않는다.
    """
    if not integration_id:
        return
    await _record(_integration_ids, integration_id, _MERGE_INTEGRATION)
    logger.info("수집 차단 tombstone 기록: integration=%s", integration_id)


async def record_project_tombstone(project_id: str) -> None:
    """지운 프로젝트를 기록한다. 실패 처리는 record_integration_tombstone과 같다."""
    if not project_id:
        return
    await _record(_project_ids, project_id, _MERGE_PROJECT)
    logger.info("수집 차단 tombstone 기록: project=%s", project_id)


async def _record(memory: set[str], key: str, query: str) -> None:
    """Neo4j에 남긴 뒤 메모리에 올린다. 쓰기가 실패하면 메모리는 건드리지 않고 예외를 올린다."""
    async with get_driver().session() as session:
        result = await session.run(query, key=key)
        await result.consume()   # 커밋 완료를 확인한 뒤에야 메모리에 올리고 호출부가 노드를 지운다
    memory.add(key)


_PURGE_EXPIRED = """
MATCH (t:IngestTombstone)
WHERE t.created_at < datetime() - duration({days: $ttl_days})
DELETE t
"""
_LOAD_ALL = """
MATCH (t:IngestTombstone)
RETURN t.kind AS kind, t.integration_id AS integration_id, t.target_project_id AS target_project_id
"""


async def load_tombstones() -> None:
    """만료된 tombstone을 지우고 남은 것을 전부 메모리로 올린다.

    consumer를 시작하기 전에 불러야 한다 — 올리기 전에 소비가 시작되면 재기동 직후 옛 이벤트가 샌다.
    Neo4j 오류는 그대로 올려 호출부(기동 시퀀스)의 재시도·실패 처리에 맡긴다.
    """
    async with get_driver().session() as session:
        result = await session.run(_PURGE_EXPIRED, ttl_days=TOMBSTONE_TTL_DAYS)
        purged = (await result.consume()).counters.nodes_deleted
        result = await session.run(_LOAD_ALL)
        rows = await result.data()

    for row in rows:
        if row["kind"] == "integration" and row["integration_id"]:
            _integration_ids.add(row["integration_id"])
        elif row["kind"] == "project" and row["target_project_id"]:
            _project_ids.add(row["target_project_id"])
    logger.info(
        "수집 차단 tombstone 로드 완료: integrations=%d projects=%d (만료 삭제 %d)",
        len(_integration_ids), len(_project_ids), purged,
    )
