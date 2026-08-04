"""Actor 동일인 판단용 Neo4j ActorStore 어댑터.

graph.actor_resolver.ActorStore에 주입할 프로젝트 스코프 조회/병합/생성 콜백을 제공한다.
개인정보(이름·이메일)는 Actor가 아니라 ActorAlias(소스 계정 단위)에 저장한다 — Atlassian
개인정보 보고·삭제가 "어느 소스에서 받았는지" 단위로 동작해야 하기 때문이다.
"""

import uuid
from typing import Optional

from graph.driver import get_driver


# ── 표시 이름 헬퍼 ────────────────────────────────────────────────────────
# Actor.name은 alias들로부터 파생되는 값이라, 파생 규칙을 이 파일에 하나만 둔다.
# alias가 바뀌는 모든 경로(Step 1/3 병합, Step 4 생성, Step 0 이름 갱신, 이후 수동
# 병합·취소·분리·개인정보 삭제)가 이 규칙을 거쳐 Actor.name을 재계산한다.

_DELETED_USER_LABEL = "(삭제된 사용자)"


def derive_display_name(
    aliases: list[dict],
    manual_name: bool,
    current_name: Optional[str],
    activity_by_source: Optional[dict[str, int]] = None,
) -> str:
    """alias 목록에서 표시 이름을 폴백 체인으로 유도한다.

    체인: manual_name=true면 현재 이름 유지 > GitHub 프로필 이름 > (이름 있는 alias 중)
    소스 활동량 최다 > GitHub login(source_id의 'GITHUB:' 뒤) > "(삭제된 사용자)".
    소스마다 고정 서열(과거엔 Jira > Slack)을 두지 않는 이유는, 데이터 소스가 늘어날 때마다
    서열을 다시 정의해야 하는 유지 비용 때문이다 — 대신 "그 사람이 가장 활발히 쓰는 툴의
    이름"으로 일반화한다. activity_by_source가 없으면(None) 모든 소스를 활동량 0으로 보고
    소스명 사전순으로만 정해지므로, 활동량 정보 없이도 항상 같은 결과를 내는 결정적 동작이다.
    빈 pd_name은 후보에서 제외한다. 활동량·소스명까지 같으면 source_id 사전순
    최솟값을 쓴다 — 어느 alias를 고를지 결정적으로 정해야 재계산이 매번 같은 값을 낸다.

    GitHub은 pd_name == login(source_id의 'GITHUB:' 뒤)이면 "프로필 이름 없음, login으로
    대체 수집"으로 본다 — pipeline-worker가 프로필 이름이 비어 있을 때 login을 name에
    그대로 채워 보내기 때문이다. 이 경우 활동량 비교에도 끼지 않고 건너뛰며, 그 값은
    마지막 "GitHub login" 폴백 단계에서 어차피 다시 쓰인다(실제 프로필 이름을 우연히 login과
    똑같이 지은 경우도 login 취급으로 무해하다).

    Args:
        aliases: 이 Actor에 붙은 alias 목록. 각 항목 {"source_id", "source", "pd_name"}
        manual_name: 운영자가 이름을 수동 확정했는지 (true면 자동 유도를 건너뛴다)
        current_name: 현재 Actor.name (manual_name=true일 때 유지할 값)
        activity_by_source: {"JIRA": 96, "SLACK": 12}처럼 소스별 활동량. None이면 빈 dict 취급.
    """
    if manual_name and current_name:
        return current_name

    activity_by_source = activity_by_source or {}

    def _is_github_login_placeholder(a: dict) -> bool:
        return a.get("source") == "GITHUB" and a.get("pd_name") == a["source_id"].split(":", 1)[-1]

    github_named = sorted(
        (
            a for a in aliases
            if a.get("source") == "GITHUB" and a.get("pd_name") and not _is_github_login_placeholder(a)
        ),
        key=lambda a: a["source_id"],
    )
    if github_named:
        return github_named[0]["pd_name"]

    named = sorted(
        (a for a in aliases if a.get("pd_name") and not _is_github_login_placeholder(a)),
        key=lambda a: (-activity_by_source.get(a.get("source"), 0), a.get("source") or "", a["source_id"]),
    )
    if named:
        return named[0]["pd_name"]

    github_aliases = sorted(
        (a for a in aliases if a.get("source") == "GITHUB"),
        key=lambda a: a["source_id"],
    )
    if github_aliases:
        return github_aliases[0]["source_id"].split(":", 1)[-1]

    return _DELETED_USER_LABEL


async def _compute_display_name(tx, actor_uuid: str) -> Optional[tuple[str, str]]:
    """Actor의 현재 name과 alias 기준 기대값을 읽기 전용으로 계산한다.

    recompute_display_name과 graph.maintenance.verify_actor_name_consistency가 공유하는
    계산 로직 — 검증이 실제 재계산과 다른 쿼리를 쓰면 alias는 멀쩡한데 검증만 오탐하는
    상황이 생기므로 여기 하나로만 둔다.

    소스별 활동량은 outgoing(AUTHORED/CREATED/WROTE)과 incoming(ASSIGNED_TO)을 별도 쿼리로
    구해 파이썬에서 합산한다 — 한 쿼리에서 두 OPTIONAL MATCH의 집계를 그대로 더하면 집계와
    비집계 변수가 섞여 Neo4j 5.x가 42I18로 거부한다.

    Returns:
        (현재 a.name, alias 기준 기대값) 튜플. Actor가 없으면 None.
    """
    result = await tx.run(
        """
        MATCH (a:Actor {uuid: $actor_uuid})
        OPTIONAL MATCH (al:ActorAlias)-[:ALIAS_OF]->(a)
        RETURN a.manual_name AS manual_name, a.name AS name,
               collect(CASE WHEN al IS NULL THEN null ELSE
                   {source_id: al.source_id, source: al.source, pd_name: al.pd_name}
               END) AS aliases
        """,
        actor_uuid=actor_uuid,
    )
    record = await result.single()
    if record is None:
        return None
    aliases = [a for a in (record["aliases"] or []) if a is not None]

    activity_by_source: dict[str, int] = {}
    outgoing = await tx.run(
        """
        MATCH (a:Actor {uuid: $actor_uuid})-[:AUTHORED|CREATED|WROTE]->(n)
        RETURN n.source AS source, count(n) AS cnt
        """,
        actor_uuid=actor_uuid,
    )
    for row in await outgoing.data():
        activity_by_source[row["source"]] = activity_by_source.get(row["source"], 0) + row["cnt"]
    incoming = await tx.run(
        """
        MATCH (n)-[:ASSIGNED_TO]->(a:Actor {uuid: $actor_uuid})
        RETURN n.source AS source, count(n) AS cnt
        """,
        actor_uuid=actor_uuid,
    )
    for row in await incoming.data():
        activity_by_source[row["source"]] = activity_by_source.get(row["source"], 0) + row["cnt"]

    expected = derive_display_name(
        aliases, bool(record["manual_name"]), record["name"], activity_by_source
    )
    return record["name"], expected


async def recompute_display_name(tx, actor_uuid: str) -> None:
    """Actor의 표시 이름을 alias 기준으로 재계산해 SET한다.

    tx(트랜잭션 또는 세션)를 받아 동작한다 — actor_admin의 수동 병합·취소·분리·개인정보
    삭제가 각자의 단일 트랜잭션 안에서 이 함수를 그대로 재사용할 수 있어야
    "alias는 비웠는데 표시 이름은 안 바뀌는" 정합성 결함을 구조로 막을 수 있다.
    """
    computed = await _compute_display_name(tx, actor_uuid)
    if computed is None:
        return
    _current_name, display_name = computed
    await tx.run(
        "MATCH (a:Actor {uuid: $actor_uuid}) SET a.name = $name",
        actor_uuid=actor_uuid,
        name=display_name,
    )


# ── ActorStore Neo4j 구현체 ───────────────────────────────────────────────


async def _lookup_actor_by_alias(project_id: str, source_id: str) -> Optional[dict]:
    """ActorAlias 인덱스로 O(1) 조회 — 배열 멤버십 스캔(WHERE x IN a.aliases) 대신.
    매 이벤트의 Step 0에서 호출되므로 인덱스 조회가 기여자 수와 무관하게 일정 비용이다.
    alias_pd_name·alias_pd_erased는 resolve_actor의 Step 0 이름 갱신 판단 재료다.
    """
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (al:ActorAlias {project_id: $project_id, source_id: $source_id})-[:ALIAS_OF]->(a:Actor)
            RETURN a.uuid AS uuid, a.name AS name, a.aliases AS aliases,
                   al.pd_name AS alias_pd_name, al.pd_erased AS alias_pd_erased
            """,
            project_id=project_id,
            source_id=source_id,
        )
        record = await result.single()
    return dict(record) if record else None


async def _lookup_actor_by_email(project_id: str, email: str) -> Optional[dict]:
    """alias의 pd_email로 Actor를 찾는다. 여러 Actor가 걸리면 첫 1명만 본다
    (기존 단일 Actor.emails 배열 스캔 시절의 single() 의미를 그대로 유지)."""
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (al:ActorAlias {project_id: $project_id, pd_email: $email})-[:ALIAS_OF]->(a:Actor)
            WITH a LIMIT 1
            MATCH (other:ActorAlias)-[:ALIAS_OF]->(a)
            RETURN a.uuid AS uuid, a.name AS name, a.aliases AS aliases,
                   [x IN collect(DISTINCT other.pd_email) WHERE x IS NOT NULL] AS emails
            """,
            project_id=project_id,
            email=email,
        )
        record = await result.single()
    return dict(record) if record else None


async def _lookup_actor_by_name(project_id: str, normalized_name: str) -> list[dict]:
    """alias의 pd_normalized_name으로 Actor 후보를 찾는다. 후보당 추가 왕복 없이
    같은 쿼리에서 각 후보의 전체 alias pd_email을 모아 Step 2 스코어링 재료로 반환한다."""
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (al:ActorAlias {project_id: $project_id, pd_normalized_name: $normalized_name})-[:ALIAS_OF]->(a:Actor)
            MATCH (other:ActorAlias)-[:ALIAS_OF]->(a)
            WITH a, [x IN collect(DISTINCT other.pd_email) WHERE x IS NOT NULL] AS emails
            RETURN a.uuid AS uuid, a.name AS name, a.aliases AS aliases, emails
            """,
            project_id=project_id,
            normalized_name=normalized_name,
        )
        rows = await result.data()
    return [dict(r) for r in rows]


async def _lookup_actor_activities(actor: dict) -> list[dict]:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (a:Actor {uuid: $actor_uuid})-[:AUTHORED|WROTE|CREATED]->(n)
            WHERE n.occurredAt IS NOT NULL
            RETURN labels(n)[0] AS nodeType,
                   n.source AS source,
                   n.message AS message,
                   n.title AS title,
                   n.body AS body,
                   n.channel AS channel,
                   n.occurredAt AS occurred_at
            ORDER BY n.occurredAt DESC
            LIMIT 10
            """,
            actor_uuid=actor.get("uuid"),
        )
        rows = await result.data()
    return [
        {
            **{k: r[k] for k in ("nodeType", "source", "message", "title", "body", "channel")},
            "occurred_at": r["occurred_at"].to_native() if r["occurred_at"] else None,
        }
        for r in rows
    ]


async def _lookup_veto_uuids(project_id: str, source_id: str) -> list[str]:
    """수동 distinct 결정에서 이 source_id의 반대편 alias를 가진 Actor uuid 목록.

    resolve_actor가 Step 1 매칭·Step 2 후보에서 이 Actor들을 제외한다
    (수동 결정이 자동 병합을 이긴다 — docs/actor-manual-merge.md).
    """
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (d:ActorDecision {project_id: $project_id, kind: 'distinct'})
            WHERE $source_id IN d.aliases_a OR $source_id IN d.aliases_b
            WITH CASE WHEN $source_id IN d.aliases_a
                 THEN d.aliases_b ELSE d.aliases_a END AS opposing
            UNWIND opposing AS opp
            MATCH (al:ActorAlias {project_id: $project_id, source_id: opp})-[:ALIAS_OF]->(x:Actor)
            RETURN collect(DISTINCT x.uuid) AS uuids
            """,
            project_id=project_id,
            source_id=source_id,
        )
        record = await result.single()
    return list(record["uuids"]) if record else []


async def _merge_actor(
    actor: dict, new_alias: str, new_email: Optional[str], new_name: str
) -> None:
    """기존 Actor에 새 alias를 붙이고, 그 alias 노드에 이 계정의 개인정보를 심는다."""
    from graph.actor_resolver import normalize_name
    source = new_alias.split(":", 1)[0]
    normalized = normalize_name(new_name)
    async with get_driver().session() as session:
        await session.run(
            """
            MATCH (a:Actor {uuid: $actor_uuid})
            SET a.aliases = CASE WHEN $new_alias IN a.aliases
                                 THEN a.aliases
                                 ELSE a.aliases + $new_alias END
            // 새 alias도 ActorAlias 인덱스 노드로 연결 — Step 0 조회가 이 actor를 찾도록.
            MERGE (al:ActorAlias {project_id: a.project_id, source_id: $new_alias})
            // closed(계정 폐쇄로 영구 삭제)는 resolver Step 0 가드를 못 미치는 경로(예: ALIAS_OF가
            // 없어 Step 0 lookup이 미스나는 alias)로도 이 MERGE가 잡을 수 있어, 되돌릴 수 없는
            // 삭제 상태를 store 계층에서도 부활시키지 않는다.
            SET al += CASE WHEN al.pd_erased = 'closed' THEN {} ELSE {
                pd_name: $new_name,
                pd_normalized_name: $normalized,
                pd_email: $new_email,
                pd_updated_at: datetime(),
                pd_erased: null
            } END
            SET al.source = $source
            MERGE (al)-[:ALIAS_OF]->(a)
            """,
            actor_uuid=actor.get("uuid"),
            new_alias=new_alias,
            new_name=new_name,
            normalized=normalized,
            new_email=new_email,
            source=source,
        )
        await recompute_display_name(session, actor.get("uuid"))


async def _create_actor(project_id: str, source_id: str, name: str, email: Optional[str]) -> dict:
    """신규 Actor를 생성하되 ActorAlias로 멱등화한다.

    resolve_actor는 alias 1개(source_id)와 함께 호출한다. 그 alias로 ActorAlias를
    MERGE하고, alias가 아직 어떤 Actor에도 안 붙어 있을 때만 Actor를 새로 만든다.
    (project_id, source_id) 유니크 제약이 동시 MERGE를 직렬화하므로, 같은 alias로
    동시에 들어온 두 이벤트 중 하나만 Actor를 만들고 둘 다 같은 Actor를 돌려받는다
    — #1 동시 수집의 중복 Actor 생성 race를 막는다.
    """
    from graph.actor_resolver import normalize_name
    actor_uuid = str(uuid.uuid4())
    source = source_id.split(":", 1)[0]
    normalized = normalize_name(name)
    # 첫 alias 하나로 표시 이름을 유도한다 — derive_display_name과 규칙을 하나로 유지한다.
    display_name = derive_display_name(
        [{"source_id": source_id, "source": source, "pd_name": name}],
        manual_name=False,
        current_name=None,
    )
    async with get_driver().session() as session:
        result = await session.run(
            """
            MERGE (al:ActorAlias {project_id: $project_id, source_id: $source_id})
            FOREACH (_ IN CASE WHEN NOT EXISTS { (al)-[:ALIAS_OF]->(:Actor) } THEN [1] ELSE [] END |
                CREATE (a:Actor {
                    uuid: $uuid,
                    project_id: $project_id,
                    name: $display_name,
                    aliases: [$source_id]
                })
                MERGE (al)-[:ALIAS_OF]->(a)
            )
            // closed(계정 폐쇄로 영구 삭제)는 resolver Step 0 가드를 못 미치는 경로(예: ALIAS_OF가
            // 없어 Step 0 lookup이 미스나는 alias)로도 이 MERGE가 잡을 수 있어, 되돌릴 수 없는
            // 삭제 상태를 store 계층에서도 부활시키지 않는다.
            SET al += CASE WHEN al.pd_erased = 'closed' THEN {} ELSE {
                pd_name: $name,
                pd_normalized_name: $normalized,
                pd_email: $email,
                pd_updated_at: datetime(),
                pd_erased: null
            } END
            SET al.source = $source
            WITH al
            MATCH (al)-[:ALIAS_OF]->(a:Actor)
            RETURN a.uuid AS uuid, a.name AS name, a.aliases AS aliases
            """,
            uuid=actor_uuid,
            project_id=project_id,
            source_id=source_id,
            display_name=display_name,
            name=name,
            normalized=normalized,
            email=email,
            source=source,
        )
        record = await result.single()
    return dict(record)


async def _update_alias_name(project_id: str, source_id: str, name: str, email: Optional[str]) -> None:
    """이미 아는 alias의 이름을 갱신한다 (resolve_actor Step 0).

    pd_erased=null로 되돌리는 것은 access_lost(재조회 불가로 비워둔 휴면 상태)가
    재수집으로 복구되는 경로다 — closed는 resolve_actor가 호출 전에 걸러내므로 이 함수는
    보통 호출되지 않지만, WHERE절로 store 계층에서도 한 번 더 막는다(심층 방어).

    al.source는 _merge_actor/_create_actor와 동일하게 source_id 접두어로 유도해 SET한다 —
    backfill_actor_aliases(graph/maintenance.py)가 만든 bare alias는 source가 없어 이 경로를
    타기 전까지 graph/privacy.py의 al.source='JIRA' 필터에서 누락되기 때문이다.

    pd_email은 coalesce로 SET한다 — 이벤트에 email이 없는 소스(예: Jira 담당자)가 access_lost
    alias를 되살릴 때 email까지 null로 덮어 영구 소실시키지 않기 위함이다.
    """
    from graph.actor_resolver import normalize_name
    source = source_id.split(":", 1)[0]
    normalized = normalize_name(name)
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (al:ActorAlias {project_id: $project_id, source_id: $source_id})-[:ALIAS_OF]->(a:Actor)
            WHERE al.pd_erased IS NULL OR al.pd_erased <> 'closed'
            SET al.pd_name = $name,
                al.pd_normalized_name = $normalized,
                al.pd_email = coalesce($email, al.pd_email),
                al.pd_updated_at = datetime(),
                al.pd_erased = null,
                al.source = $source
            RETURN a.uuid AS uuid
            """,
            project_id=project_id,
            source_id=source_id,
            name=name,
            normalized=normalized,
            email=email,
            source=source,
        )
        record = await result.single()
        if record:
            await recompute_display_name(session, record["uuid"])


def make_neo4j_actor_store(project_id: str):
    """프로젝트 스코프 Neo4j ActorStore 인스턴스를 반환한다.

    Actor 동일인 판단(이름/이메일 매칭)이 프로젝트 경계를 넘지 않도록
    조회·생성 함수에 project_id를 바인딩한다 — 같은 사람이 두 프로젝트에
    등장하면 프로젝트마다 별도 Actor 노드가 생긴다.
    """
    from graph.actor_resolver import ActorStore
    return ActorStore(
        lookup_by_alias=lambda source_id: _lookup_actor_by_alias(project_id, source_id),
        lookup_by_email=lambda email: _lookup_actor_by_email(project_id, email),
        lookup_by_name=lambda name: _lookup_actor_by_name(project_id, name),
        lookup_activities=_lookup_actor_activities,
        merge_actor=_merge_actor,
        create_actor=lambda name, source_id, email: _create_actor(
            project_id, source_id, name, email
        ),
        lookup_vetoes=lambda source_id: _lookup_veto_uuids(project_id, source_id),
        update_alias_name=lambda source_id, name, email: _update_alias_name(
            project_id, source_id, name, email
        ),
    )
