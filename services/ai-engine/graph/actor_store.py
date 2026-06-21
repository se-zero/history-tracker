"""Actor 동일인 판단용 Neo4j ActorStore 어댑터.

graph.actor_resolver.ActorStore에 주입할 프로젝트 스코프 조회/병합/생성 콜백을 제공한다.
"""

import uuid
from typing import Optional

from graph.driver import get_driver


# ── ActorStore Neo4j 구현체 ───────────────────────────────────────────────


async def _lookup_actor_by_alias(project_id: str, source_id: str) -> Optional[dict]:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (a:Actor {project_id: $project_id})
            WHERE $source_id IN a.aliases
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
            """,
            actor_uuid=actor.get("uuid"),
            new_alias=new_alias,
            new_email=new_email,
            confidence=confidence,
        )


async def _create_actor(
    project_id: str, name: str, aliases: list, emails: list, confidence: float
) -> dict:
    from graph.actor_resolver import normalize_name
    actor_uuid     = str(uuid.uuid4())
    normalized     = normalize_name(name)
    async with get_driver().session() as session:
        result = await session.run(
            """
            CREATE (a:Actor {
                uuid: $uuid,
                project_id: $project_id,
                name: $name,
                normalized_name: $normalized_name,
                aliases: $aliases,
                emails: $emails,
                confidence: $confidence
            })
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
    )
