"""
Actor 수동 관리 통합 검증 — 병합/복원/분리/이름 변경의 실제 Neo4j 쓰기 경로.

실제 Neo4j(docker, localhost:7687)에 throwaway project_id로 검증한다:
  1. merge_actors: source Actor의 alias와 권한 엣지가 target Actor로 이동한다.
  2. unmerge_actors: same 결정 스냅샷으로 병합을 되돌리고 distinct 결정을 남긴다.
  3. split_alias: alias 일부를 새 Actor로 분리하고 해당 소스의 권한 엣지를 재귀속한다.
  4. rename_actor: 표시 이름과 normalized_name을 함께 갱신한다.

OpenAI 불필요 — 전부 Neo4j 경로만 검증한다.

실행:
  cd services/ai-engine
  PYTHONPATH=. python tests/integration/test_actor_manual_admin.py
  (NEO4J_URI/NEO4J_PASSWORD 미설정 시 기본값 bolt://localhost:7687 / password1234)

종료 코드: 0 통과 / 1 실패
"""

import asyncio
import sys
import uuid

from graph.actor_admin import merge_actors, rename_actor, split_alias, unmerge_actors
from graph.builder import close_driver, delete_project_graph, ensure_constraints, get_driver


class CaseResult:
    def __init__(self, name: str):
        self.name = name
        self.failures: list[str] = []

    def assert_(self, condition: bool, msg: str):
        if not condition:
            self.failures.append(msg)

    def report(self) -> bool:
        if self.failures:
            print(f"  ❌ FAIL — {self.name}")
            for failure in self.failures:
                print(f"     • {failure}")
            return False
        print(f"  ✅ PASS — {self.name}")
        return True


async def _seed_actor(project_id: str, actor_uuid: str, name: str,
                      aliases: list[str], emails: list[str] | None = None) -> None:
    async with get_driver().session() as session:
        await session.run(
            """
            CREATE (a:Actor {
                uuid: $uuid, project_id: $pid, name: $name,
                normalized_name: $normalized_name, aliases: $aliases,
                emails: $emails, confidence: 1.0
            })
            WITH a
            UNWIND $aliases AS source_id
            CREATE (al:ActorAlias {project_id: $pid, source_id: source_id})
            CREATE (al)-[:ALIAS_OF]->(a)
            """,
            uuid=actor_uuid,
            pid=project_id,
            name=name,
            normalized_name=name.lower().replace(" ", ""),
            aliases=aliases,
            emails=emails or [],
        )


async def _seed_merge_graph(project_id: str, target_uuid: str, source_uuid: str) -> None:
    await _seed_actor(project_id, target_uuid, "Target Actor", ["GITHUB:target"], ["target@example.com"])
    await _seed_actor(project_id, source_uuid, "Source Actor", ["SLACK:source"], ["source@example.com"])
    async with get_driver().session() as session:
        await session.run(
            """
            MATCH (target:Actor {uuid: $target_uuid})
            MATCH (source:Actor {uuid: $source_uuid})
            CREATE (target_cs:ChangeSet {project_id: $pid, hash: $target_hash, source: 'GITHUB'})
            CREATE (source_cs:ChangeSet {project_id: $pid, hash: $source_hash, source: 'SLACK'})
            CREATE (issue:Issue {project_id: $pid, jira_key: $issue_key, source: 'JIRA'})
            CREATE (target)-[:AUTHORED]->(target_cs)
            CREATE (source)-[:AUTHORED]->(source_cs)
            CREATE (issue)-[:ASSIGNED_TO]->(source)
            """,
            pid=project_id,
            target_uuid=target_uuid,
            source_uuid=source_uuid,
            target_hash=f"{project_id}-target",
            source_hash=f"{project_id}-source",
            issue_key=f"TST-{project_id[-6:]}",
        )


async def _single(query: str, **params):
    async with get_driver().session() as session:
        result = await session.run(query, **params)
        return await result.single()


async def _count(query: str, **params) -> int:
    record = await _single(query, **params)
    return (record["c"] if record else 0) or 0


async def _actor(project_id: str, actor_uuid: str) -> dict | None:
    record = await _single(
        """
        MATCH (a:Actor {project_id: $pid, uuid: $uuid})
        RETURN a.uuid AS uuid, a.name AS name, a.normalized_name AS normalized_name,
               a.aliases AS aliases, a.emails AS emails, a.manual_name AS manual_name
        """,
        pid=project_id,
        uuid=actor_uuid,
    )
    return dict(record) if record else None


async def case_merge_unmerge_roundtrip() -> CaseResult:
    r = CaseResult("merge_actors + unmerge_actors: 실제 Neo4j 왕복")
    pid = f"test-actor-merge-{uuid.uuid4()}"
    target_uuid = f"{pid}-target"
    source_uuid = f"{pid}-source"
    try:
        await _seed_merge_graph(pid, target_uuid, source_uuid)

        merged = await merge_actors(
            pid, source_uuid, target_uuid, name="Unified Actor", note="integration"
        )
        r.assert_(merged["moved_edges"] == 2, f"AUTHORED+ASSIGNED_TO 2개 이동 기대, 실제 {merged}")
        r.assert_(await _actor(pid, source_uuid) is None, "병합 후 source Actor는 삭제되어야 함")

        target = await _actor(pid, target_uuid)
        r.assert_(target is not None, "병합 후 target Actor가 남아야 함")
        r.assert_(target and target["name"] == "Unified Actor", f"표시 이름이 갱신되어야 함: {target}")
        r.assert_(
            target and target["aliases"] == ["GITHUB:target", "SLACK:source"],
            f"alias 합집합 기대, 실제 {target}",
        )
        r.assert_(
            await _count(
                """
                MATCH (:Actor {uuid: $target})-[:AUTHORED {merged_from: $source}]
                      ->(:ChangeSet {project_id: $pid, hash: $source_hash})
                RETURN count(*) AS c
                """,
                pid=pid,
                target=target_uuid,
                source=source_uuid,
                source_hash=f"{pid}-source",
            ) == 1,
            "source AUTHORED 엣지가 target으로 moved_from 표식과 함께 이동해야 함",
        )
        r.assert_(
            await _count(
                """
                MATCH (:ActorAlias {project_id: $pid, source_id: 'SLACK:source'})
                      -[:ALIAS_OF]->(:Actor {uuid: $target})
                RETURN count(*) AS c
                """,
                pid=pid,
                target=target_uuid,
            ) == 1,
            "source alias가 target Actor로 재연결되어야 함",
        )

        restored = await unmerge_actors(pid, merged["decision_id"])
        r.assert_(restored["moved_edges"] == 2, f"복원 이동 엣지 2개 기대, 실제 {restored}")

        source = await _actor(pid, source_uuid)
        target = await _actor(pid, target_uuid)
        r.assert_(source is not None, "unmerge 후 source Actor가 복원되어야 함")
        r.assert_(target is not None, "unmerge 후 target Actor가 남아야 함")
        r.assert_(source and source["aliases"] == ["SLACK:source"], f"source alias 복원 기대, 실제 {source}")
        r.assert_(target and target["aliases"] == ["GITHUB:target"], f"target alias에서 source alias 제거 기대, 실제 {target}")
        r.assert_(
            await _count(
                """
                MATCH (:Actor {uuid: $source})-[:AUTHORED]
                      ->(:ChangeSet {project_id: $pid, hash: $source_hash})
                RETURN count(*) AS c
                """,
                pid=pid,
                source=source_uuid,
                source_hash=f"{pid}-source",
            ) == 1,
            "source AUTHORED 엣지가 원 Actor로 돌아와야 함",
        )
        r.assert_(
            await _count(
                """
                MATCH (:Issue {project_id: $pid})-[:ASSIGNED_TO]->(:Actor {uuid: $source})
                RETURN count(*) AS c
                """,
                pid=pid,
                source=source_uuid,
            ) == 1,
            "ASSIGNED_TO 엣지가 원 Actor로 돌아와야 함",
        )
        r.assert_(
            await _count(
                """
                MATCH (:ActorDecision {project_id: $pid, kind: 'same'})
                RETURN count(*) AS c
                """,
                pid=pid,
            ) == 0,
            "unmerge 후 same 결정은 삭제되어야 함",
        )
        r.assert_(
            await _count(
                """
                MATCH (:ActorDecision {project_id: $pid, kind: 'distinct'})
                RETURN count(*) AS c
                """,
                pid=pid,
            ) == 1,
            "unmerge 후 distinct 결정이 자동 생성되어야 함",
        )
    finally:
        await delete_project_graph(pid)
    return r


async def case_split_alias_moves_source_edges() -> CaseResult:
    r = CaseResult("split_alias: alias 분리 + 소스 권한 엣지 재귀속")
    pid = f"test-actor-split-{uuid.uuid4()}"
    actor_uuid = f"{pid}-actor"
    try:
        await _seed_actor(
            pid,
            actor_uuid,
            "Mixed Actor",
            ["GITHUB:mixed", "SLACK:mixed"],
            ["mixed@example.com"],
        )
        async with get_driver().session() as session:
            await session.run(
                """
                MATCH (a:Actor {uuid: $actor_uuid})
                CREATE (github_cs:ChangeSet {project_id: $pid, hash: $github_hash, source: 'GITHUB'})
                CREATE (slack_msg:Communication {project_id: $pid, url: $slack_url, source: 'SLACK'})
                CREATE (a)-[:AUTHORED]->(github_cs)
                CREATE (a)-[:AUTHORED]->(slack_msg)
                """,
                pid=pid,
                actor_uuid=actor_uuid,
                github_hash=f"{pid}-github",
                slack_url=f"https://slack.example/{pid}",
            )

        split = await split_alias(pid, actor_uuid, ["SLACK:mixed"], name="Slack Actor")
        new_uuid = split["new_uuid"]
        r.assert_(split["moved_edges"] == 1, f"SLACK AUTHORED 1개 이동 기대, 실제 {split}")
        r.assert_(split["moved_sources"] == ["SLACK"], f"moved_sources=['SLACK'] 기대, 실제 {split}")

        original = await _actor(pid, actor_uuid)
        created = await _actor(pid, new_uuid)
        r.assert_(original and original["aliases"] == ["GITHUB:mixed"], f"원 Actor alias 잔류 기대, 실제 {original}")
        r.assert_(created and created["aliases"] == ["SLACK:mixed"], f"새 Actor alias 기대, 실제 {created}")
        r.assert_(created and created["name"] == "Slack Actor", f"새 Actor 이름 기대, 실제 {created}")
        r.assert_(
            await _count(
                """
                MATCH (:ActorAlias {project_id: $pid, source_id: 'SLACK:mixed'})
                      -[:ALIAS_OF]->(:Actor {uuid: $new_uuid})
                RETURN count(*) AS c
                """,
                pid=pid,
                new_uuid=new_uuid,
            ) == 1,
            "분리 alias가 새 Actor로 재연결되어야 함",
        )
        r.assert_(
            await _count(
                """
                MATCH (:Actor {uuid: $new_uuid})-[:AUTHORED]
                      ->(:Communication {project_id: $pid, source: 'SLACK'})
                RETURN count(*) AS c
                """,
                pid=pid,
                new_uuid=new_uuid,
            ) == 1,
            "SLACK 활동 엣지는 새 Actor로 이동해야 함",
        )
        r.assert_(
            await _count(
                """
                MATCH (:Actor {uuid: $actor_uuid})-[:AUTHORED]
                      ->(:ChangeSet {project_id: $pid, source: 'GITHUB'})
                RETURN count(*) AS c
                """,
                pid=pid,
                actor_uuid=actor_uuid,
            ) == 1,
            "GITHUB 활동 엣지는 원 Actor에 남아야 함",
        )
        r.assert_(
            await _count(
                "MATCH (:ActorDecision {project_id: $pid, kind: 'distinct'}) RETURN count(*) AS c",
                pid=pid,
            ) == 1,
            "split 후 distinct 결정이 자동 생성되어야 함",
        )
    finally:
        await delete_project_graph(pid)
    return r


async def case_rename_actor_updates_normalized_name() -> CaseResult:
    r = CaseResult("rename_actor: 표시 이름 + normalized_name 갱신")
    pid = f"test-actor-rename-{uuid.uuid4()}"
    actor_uuid = f"{pid}-actor"
    try:
        await _seed_actor(pid, actor_uuid, "Old Name", ["GITHUB:old"])
        renamed = await rename_actor(pid, actor_uuid, "  New Person (BE)  ")
        actor = await _actor(pid, actor_uuid)
        r.assert_(renamed["name"] == "New Person (BE)", f"응답 name trim 기대, 실제 {renamed}")
        r.assert_(actor and actor["name"] == "New Person (BE)", f"저장 name 갱신 기대, 실제 {actor}")
        r.assert_(actor and actor["normalized_name"] == "newpersonbe", f"normalized_name 갱신 기대, 실제 {actor}")
        r.assert_(actor and actor["manual_name"] is True, f"manual_name=true 기대, 실제 {actor}")
    finally:
        await delete_project_graph(pid)
    return r


CASES = [
    case_merge_unmerge_roundtrip,
    case_split_alias_moves_source_edges,
    case_rename_actor_updates_normalized_name,
]


async def main() -> None:
    print("=" * 70)
    print("Actor 수동 관리 통합 검증 — 병합/복원/분리/이름 변경")
    print("=" * 70)

    await ensure_constraints()

    results: list[CaseResult] = []
    for case in CASES:
        print(f"\n▶ {case.__name__}")
        try:
            results.append(await case())
        except Exception as exc:
            result = CaseResult(case.__name__)
            result.assert_(False, f"예외 발생: {type(exc).__name__}: {exc}")
            results.append(result)

    print("\n" + "=" * 70)
    for result in results:
        result.report()
    passed = sum(1 for result in results if not result.failures)
    failed = len(results) - passed
    print("=" * 70)
    print(f"결과: {passed} PASS / {failed} FAIL / {len(results)} 총")

    await close_driver()
    sys.exit(0 if failed == 0 else 1)


if __name__ == "__main__":
    asyncio.run(main())
