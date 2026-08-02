"""Actor 수동 병합·분리 — 운영 쓰기 경로 (설계: docs/actor-manual-merge.md).

자동 동일인 판단(actor_resolver)의 오류를 운영자가 교정한다. 수동 결정은
ActorDecision 노드로 영속화되어 재수집을 이긴다 — 병합은 ALIAS_OF 재연결로
Step 0에서 유지되고, 분리는 distinct 결정이 자동 병합을 거부(veto)한다.

모든 쓰기 작업은 execute_write 단일 트랜잭션 — 부분 실패로 엣지가 절반만
이동한 상태(귀속 오염)가 남지 않게 한다.

검증 실패는 ValueError(잘못된 입력), LookupError(대상 없음)로 던지고
HTTP 상태 변환은 라우터가 담당한다.
"""

import logging
import uuid as uuid_mod

from graph.actor_store import recompute_display_name
from graph.driver import get_driver

logger = logging.getLogger(__name__)

# Actor가 갖는 권한 엣지 — outgoing 3종 + incoming ASSIGNED_TO (graph.writes 참고)
_OUTGOING_RELS = ("AUTHORED", "CREATED", "WROTE")


async def _load_actor(tx, project_id: str, actor_uuid: str) -> dict | None:
    result = await tx.run(
        """
        MATCH (a:Actor {uuid: $uuid, project_id: $project_id})
        RETURN a.uuid AS uuid, a.name AS name, a.aliases AS aliases
        """,
        uuid=actor_uuid,
        project_id=project_id,
    )
    record = await result.single()
    return dict(record) if record else None


async def _count_activity_edges(tx, actor_uuid: str) -> int:
    """병합 방향 결정용 활동 엣지 수 — outgoing AUTHORED/CREATED/WROTE + incoming ASSIGNED_TO."""
    result = await tx.run(
        """
        MATCH (a:Actor {uuid: $actor_uuid})
        OPTIONAL MATCH (a)-[r:AUTHORED|CREATED|WROTE]->()
        WITH a, count(r) AS authored
        OPTIONAL MATCH ()-[ra:ASSIGNED_TO]->(a)
        // 집계(count)와 비집계 변수(authored)를 한 식에 섞으면 Neo4j 5.x가 42I18로 거부한다
        WITH authored, count(ra) AS assigned
        RETURN authored + assigned AS activity_count
        """,
        actor_uuid=actor_uuid,
    )
    record = await result.single()
    return record["activity_count"] if record else 0


def _pick_canonical(count_a: int, count_b: int, uuid_a: str, uuid_b: str) -> tuple[str, str]:
    """활동 엣지가 많은 쪽을 canonical(생존)로 고른다 — (canonical_uuid, merged_uuid) 반환.

    삭제되는 쪽의 엣지만 옮기면 되므로 활동이 많은 쪽을 살리는 것이 항상 최소 작업량이다.
    동수면 uuid 사전순 작은 쪽을 canonical로 — 인자 순서를 바꿔도 항상 같은 결과를 내야
    호출자가 uuid_a/uuid_b 어느 순서로 넘기든 병합 결과가 결정적이다.
    """
    if count_a > count_b:
        return uuid_a, uuid_b
    if count_b > count_a:
        return uuid_b, uuid_a
    return (uuid_a, uuid_b) if uuid_a < uuid_b else (uuid_b, uuid_a)


async def _move_authorship_edges(tx, from_uuid: str, to_uuid: str, mark_merged_from: bool) -> int:
    """권한 엣지(AUTHORED/CREATED/WROTE/ASSIGNED_TO)를 from → to Actor로 이동한다.

    mark_merged_from=True면 새로 생긴 엣지에만 merged_from 표식을 남긴다
    (to가 원래 갖고 있던 엣지는 표식 없음 → unmerge가 이동분만 정확히 되돌린다).
    """
    mark = "ON CREATE SET r2.merged_from = $from_uuid" if mark_merged_from else ""
    moved = 0
    for rel in _OUTGOING_RELS:
        result = await tx.run(
            f"""
            MATCH (b:Actor {{uuid: $from_uuid}})-[r:{rel}]->(n)
            MATCH (a:Actor {{uuid: $to_uuid}})
            MERGE (a)-[r2:{rel}]->(n)
            {mark}
            DELETE r
            RETURN count(*) AS n
            """,
            from_uuid=from_uuid,
            to_uuid=to_uuid,
        )
        moved += (await result.single())["n"]
    result = await tx.run(
        f"""
        MATCH (n)-[r:ASSIGNED_TO]->(b:Actor {{uuid: $from_uuid}})
        MATCH (a:Actor {{uuid: $to_uuid}})
        MERGE (n)-[r2:ASSIGNED_TO]->(a)
        {mark}
        DELETE r
        RETURN count(*) AS n
        """,
        from_uuid=from_uuid,
        to_uuid=to_uuid,
    )
    moved += (await result.single())["n"]
    return moved


def _dedup(items: list) -> list:
    # 중복 제거 + 입력 순서 유지
    return list(dict.fromkeys(items))


async def merge_actors(project_id: str, uuid_a: str, uuid_b: str, note: str = "") -> dict:
    """두 Actor를 같은 사람으로 합친다 — 활동 엣지가 많은 쪽을 canonical(생존)로 살린다.

    병합은 '같은 사람' 선언이므로 승자·패자가 없다. 표시 이름은 입력받지 않고 유도 규칙이
    다시 계산한다 — 운영자가 직접 정하고 싶으면 rename_actor를 쓴다. canonical은 두 Actor의
    활동 엣지(outgoing AUTHORED/CREATED/WROTE + incoming ASSIGNED_TO) 수를 세어 많은 쪽으로
    정한다(_pick_canonical) — 삭제되는 쪽의 엣지만 옮기면 되므로 항상 최소 작업량이고, 동수면
    uuid 사전순 작은 쪽으로 정해 인자 순서를 바꿔도 항상 같은 결과를 낸다.
    두 Actor의 소스 계정(aliases)은 canonical로 통합되고 나머지 쪽은 삭제된다. ActorDecision에
    aliases_a(canonical의 병합 전 aliases)·aliases_b(삭제된 쪽 aliases)·canonical_uuid·merged_uuid를
    남겨 unmerge로 되돌릴 수 있다. ALIAS_OF 재연결로 이후 재수집 이벤트는 Step 0에서 canonical로 귀속된다.
    """
    if not project_id:
        raise ValueError("project_id는 필수다")
    if uuid_a == uuid_b:
        raise ValueError("합칠 두 액터가 같은 노드다")

    async def _tx(tx):
        actor_a = await _load_actor(tx, project_id, uuid_a)
        if actor_a is None:
            raise LookupError(f"Actor 없음: {uuid_a}")
        actor_b = await _load_actor(tx, project_id, uuid_b)
        if actor_b is None:
            raise LookupError(f"Actor 없음: {uuid_b}")

        count_a = await _count_activity_edges(tx, uuid_a)
        count_b = await _count_activity_edges(tx, uuid_b)
        canonical_uuid, merged_uuid = _pick_canonical(count_a, count_b, uuid_a, uuid_b)
        actors_by_uuid = {uuid_a: actor_a, uuid_b: actor_b}
        canonical = actors_by_uuid[canonical_uuid]
        merged = actors_by_uuid[merged_uuid]

        moved = await _move_authorship_edges(tx, merged_uuid, canonical_uuid, mark_merged_from=True)

        await tx.run(
            """
            MATCH (al:ActorAlias)-[r:ALIAS_OF]->(b:Actor {uuid: $merged_uuid})
            MATCH (a:Actor {uuid: $canonical_uuid})
            MERGE (al)-[:ALIAS_OF]->(a)
            DELETE r
            """,
            merged_uuid=merged_uuid,
            canonical_uuid=canonical_uuid,
        )

        merged_aliases = _dedup((canonical["aliases"] or []) + (merged["aliases"] or []))
        await tx.run(
            "MATCH (a:Actor {uuid: $canonical_uuid}) SET a.aliases = $aliases",
            canonical_uuid=canonical_uuid,
            aliases=merged_aliases,
        )

        decision_id = str(uuid_mod.uuid4())
        await tx.run(
            """
            CREATE (d:ActorDecision {
                decision_id: $decision_id, project_id: $project_id, kind: 'same',
                aliases_a: $aliases_a, aliases_b: $aliases_b,
                canonical_uuid: $canonical_uuid, merged_uuid: $merged_uuid,
                note: $note, decided_at: datetime()
            })
            """,
            decision_id=decision_id,
            project_id=project_id,
            aliases_a=canonical["aliases"] or [],
            aliases_b=merged["aliases"] or [],
            canonical_uuid=canonical_uuid,
            merged_uuid=merged_uuid,
            note=note,
        )

        await tx.run("MATCH (b:Actor {uuid: $merged_uuid}) DETACH DELETE b", merged_uuid=merged_uuid)
        await recompute_display_name(tx, canonical_uuid)
        return {
            "decision_id": decision_id,
            "canonical_uuid": canonical_uuid,
            "merged_uuid": merged_uuid,
            "moved_edges": moved,
            "aliases": merged_aliases,
        }

    async with get_driver().session() as session:
        summary = await session.execute_write(_tx)
    logger.info(
        "Actor 수동 병합: project=%s %s → %s (edges=%d)",
        project_id, summary["merged_uuid"], summary["canonical_uuid"], summary["moved_edges"],
    )
    return summary


async def rename_actor(project_id: str, actor_uuid: str, name: str) -> dict:
    """Actor 표시 이름을 수동 변경한다.

    검색·Step 2 후보 조회는 ActorAlias.pd_normalized_name을 타므로, 여기서 정하는 이름은
    표시 전용이며 매칭에는 쓰이지 않는다 — 운영자 라벨을 매칭 키로 함께 갱신하면 오매칭·검색
    축소를 만든다. ActorAlias는 어느 필드도 건드리지 않는다.
    """
    new_name = name.strip()
    if not project_id:
        raise ValueError("project_id는 필수다")
    if not actor_uuid:
        raise ValueError("actor_uuid는 필수다")
    if not new_name:
        raise ValueError("name은 필수다")

    async def _tx(tx):
        result = await tx.run(
            """
            MATCH (a:Actor {uuid: $actor_uuid, project_id: $project_id})
            SET a.name = $name, a.manual_name = true, a.name_updated_at = datetime()
            RETURN a.uuid AS uuid, a.name AS name
            """,
            actor_uuid=actor_uuid,
            project_id=project_id,
            name=new_name,
        )
        record = await result.single()
        if record is None:
            raise LookupError(f"Actor 없음: {actor_uuid}")
        return dict(record)

    async with get_driver().session() as session:
        renamed = await session.execute_write(_tx)
    logger.info("Actor 이름 변경: project=%s actor=%s name=%s", project_id, actor_uuid, new_name)
    return renamed


async def unmerge_actors(project_id: str, decision_id: str) -> dict:
    """수동 병합(same 결정)을 되돌린다 — 스냅샷 없이 결정 노드와 alias로 복원한다.

    개인정보는 alias에 있고 병합으로 삭제된 적 없이 화살표만 옮겨갔으므로, 되돌린 뒤
    alias에서 다시 읽으면 스냅샷 없이도 정확히 복원된다(docs/actor-identity-model.md §7).
    구버전(스냅샷 방식) 결정은 merged_uuid가 없어 이 함수로 복원할 수 없다 — 백필 마이그레이션
    대상이다. 복원 후 distinct 결정을 자동 생성해 다음 수집에서 자동 파이프라인이 같은 병합을
    반복하지 않게 한다. 병합 이후 수집된 이벤트는 표식이 없어 canonical에 남는다
    (docs/actor-manual-merge.md 한계 참고).

    병합 후 그 alias들이 분리(split_alias)로 다른 Actor에 재배치된 경우, canonical에
    더 이상 ALIAS_OF로 붙어 있지 않은 alias는 되돌릴 대상이 없다 — 그런 alias만 있으면
    (movable 없음) ValueError를 던지고, 일부만 있으면(movable 일부) 그 alias들만으로
    부분 복원한다(복원 Actor의 aliases·ALIAS_OF 이동·canonical aliases 차감·자동 생성
    distinct 결정의 aliases_b 모두 movable 기준).
    """

    async def _tx(tx):
        result = await tx.run(
            """
            MATCH (d:ActorDecision {decision_id: $decision_id, project_id: $project_id, kind: 'same'})
            RETURN d.canonical_uuid AS canonical_uuid, d.merged_uuid AS merged_uuid,
                   d.aliases_b AS aliases_b
            """,
            decision_id=decision_id,
            project_id=project_id,
        )
        record = await result.single()
        if record is None:
            raise LookupError(f"same 병합 결정 없음: {decision_id}")
        if record["merged_uuid"] is None:
            raise LookupError(
                f"구버전 병합 결정이라 복원할 수 없습니다 — 백필 마이그레이션 후 다시 시도: {decision_id}"
            )
        canonical_uuid = record["canonical_uuid"]
        merged_uuid = record["merged_uuid"]
        aliases_b = record["aliases_b"] or []

        canonical = await _load_actor(tx, project_id, canonical_uuid)
        if canonical is None:
            raise LookupError(f"canonical Actor가 이후 병합/삭제로 사라져 복원 불가: {canonical_uuid}")

        # 병합 후 분리(split_alias)로 다른 Actor에 재배치된 alias는 되돌릴 수 없다 —
        # canonical에 아직 ALIAS_OF로 붙어 있는 것(movable)만 복원 대상으로 좁힌다.
        movable_result = await tx.run(
            """
            MATCH (al:ActorAlias {project_id: $project_id})-[:ALIAS_OF]->(a:Actor {uuid: $canonical_uuid})
            WHERE al.source_id IN $aliases_b
            RETURN collect(al.source_id) AS movable
            """,
            project_id=project_id,
            canonical_uuid=canonical_uuid,
            aliases_b=aliases_b,
        )
        movable = (await movable_result.single())["movable"]
        if not movable:
            raise ValueError(
                "병합으로 넘어온 계정이 분리로 이미 재배치되어 되돌릴 수 없습니다 — 분리 결과를 확인하세요"
            )

        # name은 아래 recompute_display_name이 alias 기준으로 채운다.
        await tx.run(
            "CREATE (b:Actor {uuid: $uuid, project_id: $project_id, aliases: $aliases})",
            uuid=merged_uuid,
            project_id=project_id,
            aliases=movable,
        )

        await tx.run(
            """
            MATCH (al:ActorAlias {project_id: $project_id})-[r:ALIAS_OF]->(a:Actor {uuid: $canonical_uuid})
            WHERE al.source_id IN $movable
            MATCH (b:Actor {uuid: $merged_uuid})
            MERGE (al)-[:ALIAS_OF]->(b)
            DELETE r
            """,
            project_id=project_id,
            canonical_uuid=canonical_uuid,
            merged_uuid=merged_uuid,
            movable=movable,
        )

        # merged_from 표식이 붙은 엣지만 원 Actor로 반환
        moved = 0
        for rel in _OUTGOING_RELS:
            result = await tx.run(
                f"""
                MATCH (a:Actor {{uuid: $canonical_uuid}})-[r:{rel} {{merged_from: $merged_uuid}}]->(n)
                MATCH (b:Actor {{uuid: $merged_uuid}})
                MERGE (b)-[:{rel}]->(n)
                DELETE r
                RETURN count(*) AS n
                """,
                canonical_uuid=canonical_uuid,
                merged_uuid=merged_uuid,
            )
            moved += (await result.single())["n"]
        result = await tx.run(
            """
            MATCH (n)-[r:ASSIGNED_TO {merged_from: $merged_uuid}]->(a:Actor {uuid: $canonical_uuid})
            MATCH (b:Actor {uuid: $merged_uuid})
            MERGE (n)-[:ASSIGNED_TO]->(b)
            DELETE r
            RETURN count(*) AS n
            """,
            canonical_uuid=canonical_uuid,
            merged_uuid=merged_uuid,
        )
        moved += (await result.single())["n"]

        # canonical에서 합쳐졌던 alias만 제거 (movable 기준)
        restored_set = set(movable)
        remaining_aliases = [x for x in (canonical["aliases"] or []) if x not in restored_set]
        await tx.run(
            "MATCH (a:Actor {uuid: $canonical_uuid}) SET a.aliases = $aliases",
            canonical_uuid=canonical_uuid,
            aliases=remaining_aliases,
        )

        # same 결정 삭제 + distinct 결정 생성(자동 재병합 방지)
        await tx.run(
            "MATCH (d:ActorDecision {decision_id: $decision_id}) DELETE d",
            decision_id=decision_id,
        )
        distinct_id = str(uuid_mod.uuid4())
        await tx.run(
            """
            CREATE (d:ActorDecision {
                decision_id: $decision_id, project_id: $project_id, kind: 'distinct',
                aliases_a: $aliases_a, aliases_b: $aliases_b,
                note: $note, decided_at: datetime()
            })
            """,
            decision_id=distinct_id,
            project_id=project_id,
            aliases_a=remaining_aliases,
            aliases_b=movable,
            note="unmerge 자동 생성",
        )

        await recompute_display_name(tx, canonical_uuid)
        await recompute_display_name(tx, merged_uuid)

        return {
            "restored_uuid": merged_uuid,
            "canonical_uuid": canonical_uuid,
            "moved_edges": moved,
            "distinct_decision_id": distinct_id,
        }

    async with get_driver().session() as session:
        summary = await session.execute_write(_tx)
    logger.info(
        "Actor 병합 취소: project=%s decision=%s → 복원 %s (edges=%d)",
        project_id, decision_id, summary["restored_uuid"], summary["moved_edges"],
    )
    return summary


async def split_alias(project_id: str, actor_uuid: str, source_ids: list[str]) -> dict:
    """자동 병합이 잘못 합친 Actor에서 alias 일부를 새 Actor로 분리한다.

    소스 단위 휴리스틱 재귀속: 떼어낸 alias의 소스가 원 Actor에 더 이상 없으면
    그 소스의 이벤트 권한 엣지를 새 Actor로 옮긴다. 같은 소스 alias가 남아 있으면
    어느 신원의 활동인지 판별 불가 → 옮기지 않는다(보수적).
    표시 이름은 입력받지 않고 alias 기준으로 재계산된다 — 운영자가 직접 정하고 싶으면
    rename_actor를 쓴다(병합과 대칭). distinct 결정을 자동 생성해 자동 파이프라인의 재병합을 막는다.
    """
    if not source_ids:
        raise ValueError("분리할 source_ids가 비어 있다")

    async def _tx(tx):
        actor = await _load_actor(tx, project_id, actor_uuid)
        if actor is None:
            raise LookupError(f"Actor 없음: {actor_uuid}")
        aliases = actor["aliases"] or []
        unknown = [s for s in source_ids if s not in aliases]
        if unknown:
            raise ValueError(f"Actor가 갖지 않은 alias: {unknown}")
        remaining = [a for a in aliases if a not in set(source_ids)]
        if not remaining:
            raise ValueError("모든 alias를 분리할 수 없다 — 최소 1개는 남아야 한다")

        new_uuid = str(uuid_mod.uuid4())
        await tx.run(
            "CREATE (b:Actor {uuid: $uuid, project_id: $project_id, aliases: $aliases})",
            uuid=new_uuid,
            project_id=project_id,
            aliases=source_ids,
        )
        await tx.run(
            """
            MATCH (al:ActorAlias {project_id: $project_id})-[r:ALIAS_OF]->(a:Actor {uuid: $actor_uuid})
            WHERE al.source_id IN $source_ids
            MATCH (b:Actor {uuid: $new_uuid})
            MERGE (al)-[:ALIAS_OF]->(b)
            DELETE r
            """,
            project_id=project_id,
            actor_uuid=actor_uuid,
            new_uuid=new_uuid,
            source_ids=source_ids,
        )

        # 원 Actor에 해당 소스 alias가 하나도 안 남는 소스만 이벤트 재귀속 대상
        split_sources = {s.split(":", 1)[0] for s in source_ids}
        kept_sources = {a.split(":", 1)[0] for a in remaining}
        moved_sources = sorted(split_sources - kept_sources)
        moved = 0
        if moved_sources:
            for rel in _OUTGOING_RELS:
                result = await tx.run(
                    f"""
                    MATCH (a:Actor {{uuid: $actor_uuid}})-[r:{rel}]->(n)
                    WHERE n.source IN $sources
                    MATCH (b:Actor {{uuid: $new_uuid}})
                    MERGE (b)-[:{rel}]->(n)
                    DELETE r
                    RETURN count(*) AS n
                    """,
                    actor_uuid=actor_uuid,
                    new_uuid=new_uuid,
                    sources=moved_sources,
                )
                moved += (await result.single())["n"]
            result = await tx.run(
                """
                MATCH (n)-[r:ASSIGNED_TO]->(a:Actor {uuid: $actor_uuid})
                WHERE n.source IN $sources
                MATCH (b:Actor {uuid: $new_uuid})
                MERGE (n)-[:ASSIGNED_TO]->(b)
                DELETE r
                RETURN count(*) AS n
                """,
                actor_uuid=actor_uuid,
                new_uuid=new_uuid,
                sources=moved_sources,
            )
            moved += (await result.single())["n"]

        await tx.run(
            "MATCH (a:Actor {uuid: $actor_uuid}) SET a.aliases = $aliases",
            actor_uuid=actor_uuid,
            aliases=remaining,
        )

        distinct_id = str(uuid_mod.uuid4())
        await tx.run(
            """
            CREATE (d:ActorDecision {
                decision_id: $decision_id, project_id: $project_id, kind: 'distinct',
                aliases_a: $aliases_a, aliases_b: $aliases_b,
                note: $note, decided_at: datetime()
            })
            """,
            decision_id=distinct_id,
            project_id=project_id,
            aliases_a=remaining,
            aliases_b=source_ids,
            note="split 자동 생성",
        )

        await recompute_display_name(tx, actor_uuid)
        await recompute_display_name(tx, new_uuid)
        result = await tx.run(
            "MATCH (b:Actor {uuid: $new_uuid}) RETURN b.name AS name", new_uuid=new_uuid
        )
        new_name = (await result.single())["name"]

        return {
            "new_uuid": new_uuid,
            "new_name": new_name,
            "moved_edges": moved,
            "moved_sources": moved_sources,
            "distinct_decision_id": distinct_id,
        }

    async with get_driver().session() as session:
        summary = await session.execute_write(_tx)
    logger.info(
        "Actor alias 분리: project=%s actor=%s aliases=%s → 신규 %s (edges=%d, sources=%s)",
        project_id, actor_uuid, source_ids, summary["new_uuid"],
        summary["moved_edges"], summary["moved_sources"],
    )
    return summary


async def list_actors(project_id: str) -> list[dict]:
    """프로젝트의 Actor 목록 + 활동 수 (관리 UI용). 활동 많은 순 정렬.

    소스별 이름(source_names)만 판단 재료로 내려준다 — 이메일·계정ID(source_id)는
    "같은 사람인가" 판단에 쓸모없는 개인정보라 목록에 깔지 않는다. 필요하면
    get_actor_detail(병합·분리 폼)로 별도 조회한다.
    """
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (a:Actor {project_id: $project_id})
            OPTIONAL MATCH (a)-[r:AUTHORED|CREATED|WROTE]->()
            WITH a, count(r) AS authored
            OPTIONAL MATCH ()-[ra:ASSIGNED_TO]->(a)
            // 집계(count)와 비집계 변수(authored)를 한 식에 섞으면 Neo4j 5.x가 42I18로 거부한다
            // — 집계를 먼저 확정한 뒤 다음 WITH에서 합산한다.
            WITH a, authored, count(ra) AS assigned
            WITH a, authored + assigned AS activity_count
            OPTIONAL MATCH (al:ActorAlias)-[:ALIAS_OF]->(a)
            WITH a, activity_count, al
            ORDER BY al.source_id
            WITH a, activity_count,
                 [x IN collect(CASE WHEN al IS NULL THEN null ELSE
                     {source: al.source, name: al.pd_name, erased: al.pd_erased}
                 END) WHERE x IS NOT NULL] AS source_names
            RETURN a.uuid AS uuid, a.name AS name, activity_count, source_names
            ORDER BY activity_count DESC, name
            """,
            project_id=project_id,
        )
        rows = await result.data()
    return [dict(r) for r in rows]


async def get_actor_detail(project_id: str, actor_uuid: str) -> dict:
    """Actor 상세 조회 (병합·분리 폼용) — 이메일·계정ID(source_id)를 함께 내려준다.

    list_actors(목록)와 달리 여기서만 개인정보를 노출한다. 획득·보고 시각
    (pd_updated_at·pd_reported_at)은 개인정보 관리 내부값이라 여기서도 반환하지 않는다.
    """
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (a:Actor {project_id: $project_id, uuid: $actor_uuid})
            OPTIONAL MATCH (al:ActorAlias)-[:ALIAS_OF]->(a)
            WITH a, al
            ORDER BY al.source_id
            RETURN a.uuid AS uuid, a.name AS name,
                   collect(CASE WHEN al IS NULL THEN null ELSE
                       {source_id: al.source_id, source: al.source, name: al.pd_name,
                        email: al.pd_email, erased: al.pd_erased}
                   END) AS aliases
            """,
            project_id=project_id,
            actor_uuid=actor_uuid,
        )
        record = await result.single()
    if record is None:
        raise LookupError(f"Actor 없음: {actor_uuid}")
    return {
        "uuid": record["uuid"],
        "name": record["name"],
        "aliases": [a for a in (record["aliases"] or []) if a is not None],
    }


async def list_decisions(project_id: str) -> list[dict]:
    """수동 결정 이력 (감사·unmerge 대상 조회용). 스냅샷 본문은 제외해 경량화.

    ActorDecision에는 aliases_a/aliases_b가 source_id(계정ID)로 저장돼 있다 — 사람이 못
    읽고 목록에 계정ID를 깔지 않는 원칙에도 어긋나므로, 조회 시점에 현재 ActorAlias에서
    이름을 읽어 {source, name, erased}로 바꿔 반환한다. 결정 노드 자체에는 이름을 저장하지
    않는다 — 개인정보 사본을 여러 곳에 두지 않기 위해서다. 매칭되는 alias가 없으면(이론상
    없지만) {source: null, name: null, erased: null}로 채운다.
    """
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (d:ActorDecision {project_id: $project_id})
            RETURN d.decision_id AS decision_id, d.kind AS kind,
                   d.aliases_a AS aliases_a, d.aliases_b AS aliases_b,
                   d.canonical_uuid AS canonical_uuid, d.merged_uuid AS merged_uuid,
                   d.note AS note, toString(d.decided_at) AS decided_at
            ORDER BY d.decided_at DESC
            """,
            project_id=project_id,
        )
        rows = await result.data()

        source_ids = list({sid for r in rows for sid in (r["aliases_a"] or []) + (r["aliases_b"] or [])})
        alias_result = await session.run(
            """
            MATCH (al:ActorAlias {project_id: $project_id})
            WHERE al.source_id IN $source_ids
            RETURN al.source_id AS source_id, al.source AS source,
                   al.pd_name AS name, al.pd_erased AS erased
            """,
            project_id=project_id,
            source_ids=source_ids,
        )
        alias_rows = await alias_result.data()

    alias_by_id = {
        r["source_id"]: {"source": r["source"], "name": r["name"], "erased": r["erased"]}
        for r in alias_rows
    }

    def _resolve(ids: list[str]) -> list[dict]:
        return [alias_by_id.get(sid, {"source": None, "name": None, "erased": None}) for sid in ids]

    return [
        {
            "decision_id": r["decision_id"],
            "kind": r["kind"],
            "aliases_a": _resolve(r["aliases_a"] or []),
            "aliases_b": _resolve(r["aliases_b"] or []),
            "canonical_uuid": r["canonical_uuid"],
            "merged_uuid": r["merged_uuid"],
            "note": r["note"],
            "decided_at": r["decided_at"],
        }
        for r in rows
    ]


async def delete_decision(project_id: str, decision_id: str) -> int:
    """distinct 결정을 철회한다 — 자동 파이프라인의 재병합을 다시 허용.

    same 결정은 삭제 불가(unmerge 복원 데이터를 담고 있다) — unmerge로만 해소한다.
    """
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (d:ActorDecision {decision_id: $decision_id, project_id: $project_id, kind: 'distinct'})
            DELETE d
            RETURN count(*) AS n
            """,
            decision_id=decision_id,
            project_id=project_id,
        )
        record = await result.single()
    deleted = record["n"] if record else 0
    if deleted:
        logger.info("Actor distinct 결정 철회: project=%s decision=%s", project_id, decision_id)
    return deleted
