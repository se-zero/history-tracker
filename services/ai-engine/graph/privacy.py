"""Jira 개인정보 보고 대상 조회·결과 반영 — 이 레포에서 유일하게 project_id 스코프를 두지 않는 그래프 쿼리.

(a) 앱 전역 스코프: 이 레포의 모든 그래프 쿼리는 project_id로 스코프하는 것이 원칙이지만
    (services/ai-engine/CLAUDE.md), Atlassian 개인정보 보고 규칙상 accountId는 한 사람이
    여러 프로젝트에 걸쳐 활동해도 전역에서 한 번만 중복 제거해 보고해야 한다. 이 모듈은
    그 규칙 때문에 의도적으로 project_id 인자를 받지 않는다.
(b) 인증 없음: ai-engine은 backend가 호출하는 내부 서비스이고 인가는 backend가 담당한다는
    신뢰 모델을 따른다 (routers/graph.py, routers/admin.py의 관행).

backend의 보고 스케줄러가 "보고할 때가 된 Jira accountId 목록"을 조회(list_due_accounts)하고,
Atlassian에 보고한 결과를 반영(apply_report)하는 데 쓴다.
"""

import logging
from datetime import datetime

from graph.actor_resolver import normalize_name
from graph.actor_store import recompute_display_name
from graph.driver import get_driver

logger = logging.getLogger(__name__)

_ERASE_REASONS = {"closed", "access_lost"}


def _serialize_updated_at(value) -> str | None:
    """pd_updated_at은 neo4j.time.DateTime으로 온다 — iso_format()으로 ISO-8601 문자열화."""
    if value is None:
        return None
    if hasattr(value, "iso_format"):
        return value.iso_format()
    return str(value)


async def list_due_accounts(due_before: str, limit: int = 90) -> list[dict]:
    """보고 기한(due_before)이 지난 Jira accountId를 오래 미보고된 순으로 조회한다.

    accountId별 alias가 여러 프로젝트에 흩어져 있을 수 있어 substring(al.source_id, 5)로
    "JIRA:" 접두어만 벗겨 accountId 단위로 min() 집계한다. **split(':')는 쓰지 않는다** —
    Atlassian accountId 자체에 콜론이 들어가서(`712020:<uuid>`) 앞부분만 잘라내면 accountId가
    손상된다. source_id = "JIRA:<accountId>"라는 고정 접두어 길이만큼만 벗기면 안전하다.

    (source, pd_reported_at) range 인덱스(graph/schema.py)는 pd_reported_at이 아예 없는
    노드는 색인하지 못하지만, alias 규모(수천 단위)에서는 풀스캔으로도 충분해 허용한다.
    """
    try:
        datetime.fromisoformat(due_before)
    except ValueError as e:
        raise ValueError(f"due_before가 RFC3339 형식이 아니다: {due_before}") from e
    if not 1 <= limit <= 90:
        raise ValueError(f"limit은 1~90 범위여야 한다: {limit}")

    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (al:ActorAlias)
            WHERE al.source = 'JIRA' AND al.pd_erased IS NULL
              AND (al.pd_name IS NOT NULL OR al.pd_email IS NOT NULL)
              // pd_name/pd_email이 있으면 pd_updated_at도 항상 있는 인바리언트이지만,
              // null이 흘러나가면 backend가 ISO_INSTANT.format(null)에서 NPE로 죽는다 — 자기완결시킨다.
              AND al.pd_updated_at IS NOT NULL
            WITH substring(al.source_id, 5) AS account_id,
                 min(al.pd_updated_at) AS updated_at,
                 min(coalesce(al.pd_reported_at, datetime('1970-01-01T00:00:00Z'))) AS oldest_reported
            WHERE oldest_reported < datetime($due_before)
            RETURN account_id, updated_at
            ORDER BY oldest_reported, account_id
            LIMIT $limit
            """,
            due_before=due_before,
            limit=limit,
        )
        rows = await result.data()

    return [
        {"account_id": r["account_id"], "updated_at": _serialize_updated_at(r["updated_at"])}
        for r in rows
    ]


async def _erase_item(tx, item: dict, actor_uuids: set[str]) -> int:
    """alias 하나의 개인정보를 비우고 pd_erased에 사유를 남긴다.

    project_id 없는 전역 MATCH — 같은 accountId가 여러 프로젝트에 alias로 흩어져 있으면
    한 번에 전부 지운다. closed는 access_lost(휴면) alias를 영구 삭제로 격상할 수 있다
    — 반대로 access_lost로는 closed를 덮어쓰지 않는다(closed가 더 강한 상태).
    """
    result = await tx.run(
        """
        MATCH (al:ActorAlias)
        WHERE al.source_id = 'JIRA:' + $account_id
          AND (al.pd_erased IS NULL OR ($reason = 'closed' AND al.pd_erased = 'access_lost'))
        SET al.pd_name = null, al.pd_normalized_name = null, al.pd_email = null,
            al.pd_updated_at = null, al.pd_reported_at = null, al.pd_erased = $reason
        WITH al
        OPTIONAL MATCH (al)-[:ALIAS_OF]->(a:Actor)
        RETURN al.source_id AS source_id, a.uuid AS actor_uuid
        """,
        account_id=item["account_id"],
        reason=item["reason"],
    )
    rows = await result.data()
    actor_uuids.update(r["actor_uuid"] for r in rows if r["actor_uuid"])
    return len(rows)


async def _refresh_item(tx, item: dict, reported_at: str, actor_uuids: set[str]) -> int:
    """alias 하나를 재조회한 값으로 갱신한다.

    email이 None이면 null로 저장한다 — Atlassian 가시성 제한으로 email을 못 받는 경우가
    있고(access_lost 복구가 pd_email을 되살리지 않는 것과 같은 계열의 제약), 여기서 임의로
    지어내지 않는다.
    """
    normalized = normalize_name(item["name"])
    result = await tx.run(
        """
        MATCH (al:ActorAlias)
        WHERE al.source_id = 'JIRA:' + $account_id
          // 'closed'는 이 조건에 절대 매치되지 않는다 — actor_store.py의 _merge_actor/
          // _create_actor CASE 가드·_update_alias_name WHERE절과 함께 이 WHERE절도
          // pd_erased='closed' alias가 되살아나지 않도록 막는 방어선 중 하나다.
          AND (al.pd_erased IS NULL OR al.pd_erased = 'access_lost')
        SET al.pd_name = $name, al.pd_normalized_name = $normalized, al.pd_email = $email,
            al.pd_updated_at = datetime($reported_at), al.pd_reported_at = datetime($reported_at),
            al.pd_erased = null
        WITH al
        OPTIONAL MATCH (al)-[:ALIAS_OF]->(a:Actor)
        RETURN al.source_id AS source_id, a.uuid AS actor_uuid
        """,
        account_id=item["account_id"],
        name=item["name"],
        normalized=normalized,
        email=item.get("email"),
        reported_at=reported_at,
    )
    rows = await result.data()
    actor_uuids.update(r["actor_uuid"] for r in rows if r["actor_uuid"])
    return len(rows)


async def _stamp_ok(tx, ok: list[str], reported_at: str) -> int:
    """변경 없이 확인만 된 accountId는 pd_reported_at만 찍는다 (삭제된 alias는 제외)."""
    if not ok:
        return 0
    result = await tx.run(
        """
        MATCH (al:ActorAlias)
        WHERE al.source_id IN $source_ids AND al.pd_erased IS NULL
        SET al.pd_reported_at = datetime($reported_at)
        RETURN count(al) AS n
        """,
        source_ids=[f"JIRA:{account_id}" for account_id in ok],
        reported_at=reported_at,
    )
    record = await result.single()
    return record["n"] if record else 0


async def apply_report(reported_at: str, ok: list[str], erase: list[dict], refresh: list[dict]) -> dict:
    """backend의 Jira 개인정보 보고 결과를 그래프에 반영한다.

    erase → alias의 pd_* 비우기 + Actor.name 재계산을 같은 트랜잭션으로 묶는다 —
    나뉘면 지운 이름이 Actor에 남는데 에러도 안 나는 정합성 결함이 생긴다. refresh도 같은
    이유로 재계산을 트랜잭션 안에서 한다. 대상이 0건이어도 에러가 아니다(멱등) — 카운트 0을
    그대로 반환한다.
    """
    try:
        datetime.fromisoformat(reported_at)
    except ValueError as e:
        raise ValueError(f"reported_at이 RFC3339 형식이 아니다: {reported_at}") from e

    for item in erase:
        if item.get("reason") not in _ERASE_REASONS:
            raise ValueError(f"erase reason이 올바르지 않다: {item.get('reason')}")
        if not item.get("account_id"):
            raise ValueError("erase 항목의 account_id는 필수다")

    for item in refresh:
        if not item.get("account_id"):
            raise ValueError("refresh 항목의 account_id는 필수다")
        if not item.get("name"):
            raise ValueError("refresh 항목의 name은 필수다")

    async def _tx(tx):
        actor_uuids: set[str] = set()

        erased = 0
        for item in erase:
            erased += await _erase_item(tx, item, actor_uuids)

        refreshed = 0
        for item in refresh:
            refreshed += await _refresh_item(tx, item, reported_at, actor_uuids)

        for actor_uuid in actor_uuids:
            await recompute_display_name(tx, actor_uuid)

        stamped = await _stamp_ok(tx, ok, reported_at)
        return {"erased": erased, "refreshed": refreshed, "stamped": stamped}

    async with get_driver().session() as session:
        summary = await session.execute_write(_tx)
    logger.info(
        "Jira 개인정보 보고 결과 반영: erased=%d refreshed=%d stamped=%d",
        summary["erased"], summary["refreshed"], summary["stamped"],
    )
    return summary
