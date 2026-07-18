"""Actor 수동 병합·분리 — 운영 쓰기 경로 (설계: docs/actor-manual-merge.md).

자동 동일인 판단(actor_resolver)의 오류를 운영자가 교정한다. 수동 결정은
ActorDecision 노드로 영속화되어 재수집을 이긴다 — 병합은 ALIAS_OF 재연결로
Step 0에서 유지되고, 분리는 distinct 결정이 자동 병합을 거부(veto)한다.

모든 쓰기 작업은 execute_write 단일 트랜잭션 — 부분 실패로 엣지가 절반만
이동한 상태(귀속 오염)가 남지 않게 한다.

검증 실패는 ValueError(잘못된 입력), LookupError(대상 없음)로 던지고
HTTP 상태 변환은 라우터가 담당한다.
"""

import json
import logging
import uuid as uuid_mod

from graph.actor_resolver import normalize_name
from graph.driver import get_driver

logger = logging.getLogger(__name__)

# Actor가 갖는 권한 엣지 — outgoing 3종 + incoming ASSIGNED_TO (graph.writes 참고)
_OUTGOING_RELS = ("AUTHORED", "CREATED", "WROTE")


async def _load_actor(tx, project_id: str, actor_uuid: str) -> dict | None:
    result = await tx.run(
        """
        MATCH (a:Actor {uuid: $uuid, project_id: $project_id})
        RETURN a.uuid AS uuid, a.name AS name, a.normalized_name AS normalized_name,
               a.aliases AS aliases, a.emails AS emails, a.confidence AS confidence
        """,
        uuid=actor_uuid,
        project_id=project_id,
    )
    record = await result.single()
    return dict(record) if record else None


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


async def merge_actors(
    project_id: str, source_uuid: str, target_uuid: str, name: str = "", note: str = ""
) -> dict:
    """두 Actor를 같은 사람으로 합친다 — 두 노드의 소스 계정을 하나로 통합한다.

    병합은 '같은 사람' 선언이므로 승자·패자가 없다. 사용자는 합친 뒤의 표시 이름(name)만 입력하며,
    두 노드의 소스 계정(aliases)·이메일은 target 노드로 통합되고 source 노드는 삭제된다.
    name이 비면 target의 기존 이름을 유지한다. source 스냅샷과 이동 엣지의 merged_from 표식을
    남겨 unmerge로 되돌릴 수 있다. ALIAS_OF 재연결로 이후 재수집 이벤트는 Step 0에서 통합 노드로 귀속된다.
    """
    new_name = (name or "").strip()
    if not project_id:
        raise ValueError("project_id는 필수다")
    if source_uuid == target_uuid:
        raise ValueError("합칠 두 액터가 같은 노드다")

    async def _tx(tx):
        target = await _load_actor(tx, project_id, target_uuid)
        if target is None:
            raise LookupError(f"target Actor 없음: {target_uuid}")
        source = await _load_actor(tx, project_id, source_uuid)
        if source is None:
            raise LookupError(f"source Actor 없음: {source_uuid}")

        moved = await _move_authorship_edges(tx, source_uuid, target_uuid, mark_merged_from=True)

        await tx.run(
            """
            MATCH (al:ActorAlias)-[r:ALIAS_OF]->(b:Actor {uuid: $source_uuid})
            MATCH (a:Actor {uuid: $target_uuid})
            MERGE (al)-[:ALIAS_OF]->(a)
            DELETE r
            """,
            source_uuid=source_uuid,
            target_uuid=target_uuid,
        )

        merged_aliases = _dedup((target["aliases"] or []) + (source["aliases"] or []))
        merged_emails = _dedup((target["emails"] or []) + (source["emails"] or []))
        # 수동 확정이므로 confidence는 최고 신뢰도로 올린다. 이름을 입력했으면 표시 이름도 갱신
        # (normalized_name까지 함께 갱신해 이름 검색을 유지, manual_name으로 자동 갱신 방지).
        set_name = (
            ", a.name = $name, a.normalized_name = $normalized_name, a.manual_name = true"
            if new_name else ""
        )
        params = {"target_uuid": target_uuid, "aliases": merged_aliases, "emails": merged_emails}
        if new_name:
            params["name"] = new_name
            params["normalized_name"] = normalize_name(new_name)
        await tx.run(
            f"""
            MATCH (a:Actor {{uuid: $target_uuid}})
            SET a.aliases = $aliases, a.emails = $emails, a.confidence = 1.0{set_name}
            """,
            **params,
        )

        decision_id = str(uuid_mod.uuid4())
        await tx.run(
            """
            CREATE (d:ActorDecision {
                decision_id: $decision_id, project_id: $project_id, kind: 'same',
                aliases_a: $aliases_a, aliases_b: $aliases_b, emails_a: $emails_a,
                canonical_uuid: $target_uuid, merged_snapshot: $snapshot,
                note: $note, decided_at: datetime()
            })
            """,
            decision_id=decision_id,
            project_id=project_id,
            aliases_a=target["aliases"] or [],
            aliases_b=source["aliases"] or [],
            emails_a=target["emails"] or [],
            target_uuid=target_uuid,
            snapshot=json.dumps(source, ensure_ascii=False),
            note=note,
        )

        await tx.run("MATCH (b:Actor {uuid: $source_uuid}) DETACH DELETE b", source_uuid=source_uuid)
        return {
            "decision_id": decision_id,
            "canonical_uuid": target_uuid,
            "merged_uuid": source_uuid,
            "moved_edges": moved,
            "aliases": merged_aliases,
        }

    async with get_driver().session() as session:
        summary = await session.execute_write(_tx)
    logger.info(
        "Actor 수동 병합: project=%s %s → %s (edges=%d)",
        project_id, source_uuid, target_uuid, summary["moved_edges"],
    )
    return summary


async def rename_actor(project_id: str, actor_uuid: str, name: str) -> dict:
    """Actor 표시 이름을 수동 변경한다. normalized_name도 함께 갱신해 이름 검색을 유지한다."""
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
            SET a.name = $name, a.normalized_name = $normalized_name,
                a.manual_name = true, a.name_updated_at = datetime()
            RETURN a.uuid AS uuid, a.name AS name,
                   a.normalized_name AS normalized_name
            """,
            actor_uuid=actor_uuid,
            project_id=project_id,
            name=new_name,
            normalized_name=normalize_name(new_name),
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
    """수동 병합(same 결정)을 스냅샷 기준으로 정확히 되돌린다.

    복원 후 distinct 결정을 자동 생성해 다음 수집에서 자동 파이프라인이 같은
    병합을 반복하지 않게 한다. 병합 이후 수집된 이벤트는 표식이 없어 canonical에
    남는다(스냅샷 시점 복원 — docs/actor-manual-merge.md 한계 참고).
    """

    async def _tx(tx):
        result = await tx.run(
            # absorbed_snapshot은 이 속성이 merged_snapshot으로 rename되기 전 버전에서 쓰던 키다.
            # 그 시절에 만들어진 결정도 복원할 수 있도록 coalesce로 둘 다 읽는다.
            """
            MATCH (d:ActorDecision {decision_id: $decision_id, project_id: $project_id, kind: 'same'})
            RETURN d.canonical_uuid AS canonical_uuid,
                   coalesce(d.merged_snapshot, d.absorbed_snapshot) AS snapshot,
                   d.emails_a AS emails_a
            """,
            decision_id=decision_id,
            project_id=project_id,
        )
        record = await result.single()
        if record is None:
            raise LookupError(f"same 병합 결정 없음: {decision_id}")
        if record["snapshot"] is None:
            # 스냅샷이 없는 결정은 복원 데이터가 없어 되돌릴 수 없다 — 500 대신 명확한 4xx로 알린다.
            raise LookupError(f"병합 스냅샷이 없어 복원할 수 없습니다: {decision_id}")
        canonical_uuid = record["canonical_uuid"]
        snapshot = json.loads(record["snapshot"])
        emails_a = set(record["emails_a"] or [])

        canonical = await _load_actor(tx, project_id, canonical_uuid)
        if canonical is None:
            raise LookupError(f"canonical Actor가 이후 병합/삭제로 사라져 복원 불가: {canonical_uuid}")

        merged_uuid = snapshot["uuid"]
        await tx.run(
            """
            CREATE (b:Actor {
                uuid: $uuid, project_id: $project_id, name: $name,
                normalized_name: $normalized_name, aliases: $aliases,
                emails: $emails, confidence: $confidence
            })
            """,
            uuid=merged_uuid,
            project_id=project_id,
            name=snapshot["name"],
            normalized_name=snapshot.get("normalized_name") or normalize_name(snapshot["name"]),
            aliases=snapshot.get("aliases") or [],
            emails=snapshot.get("emails") or [],
            confidence=snapshot.get("confidence") or 1.0,
        )

        restored_aliases = snapshot.get("aliases") or []
        await tx.run(
            """
            MATCH (al:ActorAlias {project_id: $project_id})-[r:ALIAS_OF]->(a:Actor {uuid: $canonical_uuid})
            WHERE al.source_id IN $aliases
            MATCH (b:Actor {uuid: $merged_uuid})
            MERGE (al)-[:ALIAS_OF]->(b)
            DELETE r
            """,
            project_id=project_id,
            canonical_uuid=canonical_uuid,
            merged_uuid=merged_uuid,
            aliases=restored_aliases,
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

        # canonical에서 합쳐졌던 분 제거 — emails는 병합 전부터 canonical이 갖고 있던 값(emails_a)이면 보존
        restored_set = set(restored_aliases)
        merged_emails = set(snapshot.get("emails") or [])
        remaining_aliases = [x for x in (canonical["aliases"] or []) if x not in restored_set]
        remaining_emails = [
            e for e in (canonical["emails"] or [])
            if not (e in merged_emails and e not in emails_a)
        ]
        await tx.run(
            """
            MATCH (a:Actor {uuid: $canonical_uuid})
            SET a.aliases = $aliases, a.emails = $emails
            """,
            canonical_uuid=canonical_uuid,
            aliases=remaining_aliases,
            emails=remaining_emails,
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
            aliases_b=restored_aliases,
            note="unmerge 자동 생성",
        )
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


async def split_alias(
    project_id: str, actor_uuid: str, source_ids: list[str], name: str = ""
) -> dict:
    """자동 병합이 잘못 합친 Actor에서 alias 일부를 새 Actor로 분리한다.

    소스 단위 휴리스틱 재귀속: 떼어낸 alias의 소스가 원 Actor에 더 이상 없으면
    그 소스의 이벤트 권한 엣지를 새 Actor로 옮긴다. 같은 소스 alias가 남아 있으면
    어느 신원의 활동인지 판별 불가 → 옮기지 않는다(보수적).
    emails는 신원 귀속을 판별할 수 없어 원 Actor에 남긴다.
    distinct 결정을 자동 생성해 자동 파이프라인의 재병합을 막는다.
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
        # 이름 미지정 시 alias의 소스 접두어 뒤 원본 ID로 유도 (예: "GITHUB:se-zero" → "se-zero")
        new_name = name or source_ids[0].split(":", 1)[-1]
        await tx.run(
            """
            CREATE (b:Actor {
                uuid: $uuid, project_id: $project_id, name: $name,
                normalized_name: $normalized_name, aliases: $aliases,
                emails: [], confidence: 1.0
            })
            """,
            uuid=new_uuid,
            project_id=project_id,
            name=new_name,
            normalized_name=normalize_name(new_name),
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
    """프로젝트의 Actor 목록 + 활동 수 (관리 UI용). 활동 많은 순 정렬."""
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (a:Actor {project_id: $project_id})
            OPTIONAL MATCH (a)-[r:AUTHORED|CREATED|WROTE]->()
            WITH a, count(r) AS authored
            OPTIONAL MATCH ()-[ra:ASSIGNED_TO]->(a)
            WITH a, authored, count(ra) AS assigned
            RETURN a.uuid AS uuid, a.name AS name,
                   a.aliases AS aliases, a.emails AS emails,
                   a.confidence AS confidence,
                   authored + assigned AS activity_count
            ORDER BY activity_count DESC, name
            """,
            project_id=project_id,
        )
        rows = await result.data()
    return [dict(r) for r in rows]


async def list_decisions(project_id: str) -> list[dict]:
    """수동 결정 이력 (감사·unmerge 대상 조회용). 스냅샷 본문은 제외해 경량화."""
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (d:ActorDecision {project_id: $project_id})
            RETURN d.decision_id AS decision_id, d.kind AS kind,
                   d.aliases_a AS aliases_a, d.aliases_b AS aliases_b,
                   d.canonical_uuid AS canonical_uuid, d.note AS note,
                   toString(d.decided_at) AS decided_at
            ORDER BY d.decided_at DESC
            """,
            project_id=project_id,
        )
        rows = await result.data()
    return [dict(r) for r in rows]


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
