"""Actor 동일인 판단용 Neo4j ActorStore 어댑터.

graph.actor_resolver.ActorStore에 주입할 프로젝트 스코프 조회/병합/생성 콜백을 제공한다.
"""

import uuid
from typing import Optional

from graph.driver import get_driver


# ── ActorStore Neo4j 구현체 ───────────────────────────────────────────────


async def _lookup_actor_by_alias(project_id: str, source_id: str) -> Optional[dict]:
    """ActorAlias 인덱스로 O(1) 조회 — 배열 멤버십 스캔(WHERE x IN a.aliases) 대신.
    매 이벤트의 Step 0에서 호출되므로 인덱스 조회가 기여자 수와 무관하게 일정 비용이다."""
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (al:ActorAlias {project_id: $project_id, source_id: $source_id})-[:ALIAS_OF]->(a:Actor)
            RETURN a.uuid AS uuid, a.name AS name,
                   a.aliases AS aliases, a.emails AS emails,
                   a.confidence AS confidence
            """,
            project_id=project_id,
            source_id=source_id,
        )
        record = await result.single()
    return dict(record) if record else None


async def _lookup_actor_by_email(project_id: str, email: str) -> Optional[dict]:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (a:Actor {project_id: $project_id})
            WHERE $email IN a.emails
            RETURN a.uuid AS uuid, a.name AS name,
                   a.aliases AS aliases, a.emails AS emails,
                   a.confidence AS confidence
            """,
            project_id=project_id,
            email=email,
        )
        record = await result.single()
    return dict(record) if record else None


async def _lookup_actor_by_name(project_id: str, normalized_name: str) -> list[dict]:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (a:Actor {project_id: $project_id})
            WHERE a.normalized_name = $normalized_name
            RETURN a.uuid AS uuid, a.name AS name,
                   a.aliases AS aliases, a.emails AS emails,
                   a.confidence AS confidence
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
    actor: dict, new_alias: str, new_email: Optional[str], confidence: float
) -> None:
    async with get_driver().session() as session:
        await session.run(
            """
            MATCH (a:Actor {uuid: $actor_uuid})
            SET a.aliases = CASE WHEN $new_alias IN a.aliases
                                 THEN a.aliases
                                 ELSE a.aliases + $new_alias END,
                a.emails  = CASE WHEN $new_email IS NULL OR $new_email IN a.emails
                                 THEN a.emails
                                 ELSE a.emails + $new_email END,
                a.confidence = $confidence
            // 새 alias도 ActorAlias 인덱스 노드로 연결 — Step 0 조회가 이 actor를 찾도록.
            MERGE (al:ActorAlias {project_id: a.project_id, source_id: $new_alias})
            MERGE (al)-[:ALIAS_OF]->(a)
            """,
            actor_uuid=actor.get("uuid"),
            new_alias=new_alias,
            new_email=new_email,
            confidence=confidence,
        )


async def _create_actor(
    project_id: str, name: str, aliases: list, emails: list, confidence: float
) -> dict:
    """신규 Actor를 생성하되 ActorAlias로 멱등화한다.

    resolve_actor는 alias 1개(source_id)와 함께 호출한다. 그 alias로 ActorAlias를
    MERGE하고, alias가 아직 어떤 Actor에도 안 붙어 있을 때만 Actor를 새로 만든다.
    (project_id, source_id) 유니크 제약이 동시 MERGE를 직렬화하므로, 같은 alias로
    동시에 들어온 두 이벤트 중 하나만 Actor를 만들고 둘 다 같은 Actor를 돌려받는다
    — #1 동시 수집의 중복 Actor 생성 race를 막는다.
    """
    from graph.actor_resolver import normalize_name
    actor_uuid     = str(uuid.uuid4())
    normalized     = normalize_name(name)
    primary_alias  = aliases[0] if aliases else None
    async with get_driver().session() as session:
        result = await session.run(
            """
            MERGE (al:ActorAlias {project_id: $project_id, source_id: $primary_alias})
            FOREACH (_ IN CASE WHEN NOT EXISTS { (al)-[:ALIAS_OF]->(:Actor) } THEN [1] ELSE [] END |
                CREATE (a:Actor {
                    uuid: $uuid,
                    project_id: $project_id,
                    name: $name,
                    normalized_name: $normalized_name,
                    aliases: $aliases,
                    emails: $emails,
                    confidence: $confidence
                })
                MERGE (al)-[:ALIAS_OF]->(a)
            )
            WITH al
            MATCH (al)-[:ALIAS_OF]->(a:Actor)
            RETURN a.uuid AS uuid, a.name AS name,
                   a.aliases AS aliases, a.emails AS emails,
                   a.confidence AS confidence
            """,
            uuid=actor_uuid,
            project_id=project_id,
            name=name,
            normalized_name=normalized,
            aliases=aliases,
            emails=emails,
            confidence=confidence,
            primary_alias=primary_alias,
        )
        record = await result.single()
    return dict(record)

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
        create_actor=lambda name, aliases, emails, confidence: _create_actor(
            project_id, name, aliases, emails, confidence
        ),
        lookup_vetoes=lambda source_id: _lookup_veto_uuids(project_id, source_id),
    )
