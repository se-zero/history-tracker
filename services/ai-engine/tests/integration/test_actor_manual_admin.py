"""
Actor 수동 관리 통합 검증 — 병합/복원/분리/이름 변경의 실제 Neo4j 쓰기 경로.

실제 Neo4j(docker, localhost:7687)에 throwaway project_id로 검증한다:
  1. merge_actors: 활동 엣지가 많은 쪽을 canonical로 자동 선택해 alias·권한 엣지를 이동한다.
  2. unmerge_actors: 결정 노드(canonical_uuid/merged_uuid/aliases_b)만으로 스냅샷 없이 복원하고
     distinct 결정을 남긴다.
  3. split_alias: alias 일부를 새 Actor로 분리하고 해당 소스의 권한 엣지를 재귀속한다.
  4. rename_actor: 표시 이름만 갱신한다 — ActorAlias의 검색용 이름은 매칭에 남아 있어야 한다.

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


async def _seed_actor(project_id: str, actor_uuid: str, name: str, aliases: list[str]) -> None:
    """Actor + 각 alias의 ActorAlias(pd_name=name)를 심는다.

    개인정보는 ActorAlias에 있으므로, 표시 이름 재계산이
    실제로 이 pd_name을 읽어 동작하는지까지 검증할 수 있게 alias 노드도 함께 만든다.
    """
    async with get_driver().session() as session:
        await session.run(
            """
            CREATE (a:Actor {uuid: $uuid, project_id: $pid, name: $name, aliases: $aliases})
            WITH a
            UNWIND $aliases AS source_id
            CREATE (al:ActorAlias {
                project_id: $pid, source_id: source_id,
                source: split(source_id, ':')[0], pd_name: $name
            })
            CREATE (al)-[:ALIAS_OF]->(a)
            """,
            uuid=actor_uuid,
            pid=project_id,
            name=name,
            aliases=aliases,
        )


async def _seed_merge_graph(project_id: str, more_uuid: str, less_uuid: str) -> None:
    """활동 엣지가 더 많은 쪽(more)이 병합 방향 자동 결정으로 canonical이 되는 시나리오.

    more: AUTHORED 1개 + 담당(ASSIGNED_TO) 1개 = 활동 2. less: AUTHORED 1개 = 활동 1.
    merge_actors를 어느 인자 순서로 불러도 more가 살아남아야 한다.
    """
    await _seed_actor(project_id, more_uuid, "More Actor", ["GITHUB:more"])
    await _seed_actor(project_id, less_uuid, "Less Actor", ["SLACK:less"])
    async with get_driver().session() as session:
        await session.run(
            """
            MATCH (more:Actor {uuid: $more_uuid})
            MATCH (less:Actor {uuid: $less_uuid})
            CREATE (more_cs:ChangeSet {project_id: $pid, hash: $more_hash, source: 'GITHUB'})
            CREATE (less_cs:ChangeSet {project_id: $pid, hash: $less_hash, source: 'SLACK'})
            CREATE (issue:Issue {project_id: $pid, jira_key: $issue_key, source: 'JIRA'})
            CREATE (more)-[:AUTHORED]->(more_cs)
            CREATE (less)-[:AUTHORED]->(less_cs)
            CREATE (issue)-[:ASSIGNED_TO]->(more)
            """,
            pid=project_id,
            more_uuid=more_uuid,
            less_uuid=less_uuid,
            more_hash=f"{project_id}-more",
            less_hash=f"{project_id}-less",
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
        RETURN a.uuid AS uuid, a.name AS name, a.aliases AS aliases, a.manual_name AS manual_name
        """,
        pid=project_id,
        uuid=actor_uuid,
    )
    return dict(record) if record else None


async def case_merge_unmerge_roundtrip() -> CaseResult:
    r = CaseResult("merge_actors + unmerge_actors: 실제 Neo4j 왕복 (활동 많은 쪽이 canonical)")
    pid = f"test-actor-merge-{uuid.uuid4()}"
    more_uuid = f"{pid}-more"
    less_uuid = f"{pid}-less"
    try:
        await _seed_merge_graph(pid, more_uuid, less_uuid)

        # 인자 순서를 일부러 뒤집어도(less, more) 활동이 더 많은 more가 canonical로 남아야 한다.
        merged = await merge_actors(pid, less_uuid, more_uuid, note="integration")
        r.assert_(merged["canonical_uuid"] == more_uuid, f"활동 많은 쪽이 canonical이어야 함, 실제 {merged}")
        r.assert_(merged["merged_uuid"] == less_uuid, f"활동 적은 쪽이 삭제 대상이어야 함, 실제 {merged}")
        r.assert_(merged["moved_edges"] == 1, f"less의 AUTHORED 1개만 이동 기대, 실제 {merged}")
        r.assert_(await _actor(pid, less_uuid) is None, "병합 후 less Actor는 삭제되어야 함")

        canonical = await _actor(pid, more_uuid)
        r.assert_(canonical is not None, "병합 후 canonical(more) Actor가 남아야 함")
        r.assert_(
            canonical and canonical["name"] == "More Actor",
            f"표시 이름은 alias 기준 재계산 결과여야 함, 실제 {canonical}",
        )
        r.assert_(
            canonical and canonical["aliases"] == ["GITHUB:more", "SLACK:less"],
            f"alias 합집합 기대, 실제 {canonical}",
        )
        r.assert_(
            await _count(
                """
                MATCH (:Actor {uuid: $canonical})-[:AUTHORED {merged_from: $merged}]
                      ->(:ChangeSet {project_id: $pid, hash: $less_hash})
                RETURN count(*) AS c
                """,
                pid=pid,
                canonical=more_uuid,
                merged=less_uuid,
                less_hash=f"{pid}-less",
            ) == 1,
            "less의 AUTHORED 엣지가 canonical로 merged_from 표식과 함께 이동해야 함",
        )
        r.assert_(
            await _count(
                """
                MATCH (:ActorAlias {project_id: $pid, source_id: 'SLACK:less'})
                      -[:ALIAS_OF]->(:Actor {uuid: $canonical})
                RETURN count(*) AS c
                """,
                pid=pid,
                canonical=more_uuid,
            ) == 1,
            "less alias가 canonical Actor로 재연결되어야 함",
        )

        restored = await unmerge_actors(pid, merged["decision_id"])
        r.assert_(restored["moved_edges"] == 1, f"복원 이동 엣지 1개 기대, 실제 {restored}")

        less = await _actor(pid, less_uuid)
        canonical = await _actor(pid, more_uuid)
        r.assert_(less is not None, "unmerge 후 less Actor가 복원되어야 함")
        r.assert_(canonical is not None, "unmerge 후 canonical(more) Actor가 남아야 함")
        r.assert_(less and less["aliases"] == ["SLACK:less"], f"less alias 복원 기대, 실제 {less}")
        r.assert_(less and less["name"] == "Less Actor", f"복원된 표시 이름 기대, 실제 {less}")
        r.assert_(
            canonical and canonical["aliases"] == ["GITHUB:more"],
            f"canonical alias에서 less alias 제거 기대, 실제 {canonical}",
        )
        r.assert_(
            await _count(
                """
                MATCH (:Actor {uuid: $less})-[:AUTHORED]
                      ->(:ChangeSet {project_id: $pid, hash: $less_hash})
                RETURN count(*) AS c
                """,
                pid=pid,
                less=less_uuid,
                less_hash=f"{pid}-less",
            ) == 1,
            "less의 AUTHORED 엣지가 원 Actor로 돌아와야 함",
        )
        r.assert_(
            await _count(
                """
                MATCH (:Issue {project_id: $pid})-[:ASSIGNED_TO]->(:Actor {uuid: $canonical})
                RETURN count(*) AS c
                """,
                pid=pid,
                canonical=more_uuid,
            ) == 1,
            "ASSIGNED_TO 엣지는 병합 전부터 canonical(more) 소유라 그대로 남아 있어야 함",
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
        await _seed_actor(pid, actor_uuid, "Mixed Actor", ["GITHUB:mixed", "SLACK:mixed"])
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

        split = await split_alias(pid, actor_uuid, ["SLACK:mixed"])
        new_uuid = split["new_uuid"]
        r.assert_(split["moved_edges"] == 1, f"SLACK AUTHORED 1개 이동 기대, 실제 {split}")
        r.assert_(split["moved_sources"] == ["SLACK"], f"moved_sources=['SLACK'] 기대, 실제 {split}")

        original = await _actor(pid, actor_uuid)
        created = await _actor(pid, new_uuid)
        r.assert_(original and original["aliases"] == ["GITHUB:mixed"], f"원 Actor alias 잔류 기대, 실제 {original}")
        r.assert_(created and created["aliases"] == ["SLACK:mixed"], f"새 Actor alias 기대, 실제 {created}")
        # name 파라미터 없이 표시 이름은 alias(SLACK:mixed)의 pd_name에서 유도된다 — _seed_actor가
        # 원 Actor 생성 시 모든 alias에 같은 pd_name("Mixed Actor")을 심었으므로 그 값 그대로다.
        r.assert_(created and created["name"] == "Mixed Actor", f"새 Actor 이름(alias 유도) 기대, 실제 {created}")
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


async def case_rename_actor_updates_display_name() -> CaseResult:
    r = CaseResult("rename_actor: 표시 이름만 갱신 — ActorAlias는 손대지 않음")
    pid = f"test-actor-rename-{uuid.uuid4()}"
    actor_uuid = f"{pid}-actor"
    try:
        await _seed_actor(pid, actor_uuid, "Old Name", ["GITHUB:old"])
        renamed = await rename_actor(pid, actor_uuid, "  New Person (BE)  ")
        actor = await _actor(pid, actor_uuid)
        r.assert_(renamed["name"] == "New Person (BE)", f"응답 name trim 기대, 실제 {renamed}")
        r.assert_(actor and actor["name"] == "New Person (BE)", f"저장 name 갱신 기대, 실제 {actor}")
        r.assert_(actor and actor["manual_name"] is True, f"manual_name=true 기대, 실제 {actor}")

        alias = await _single(
            """
            MATCH (al:ActorAlias {project_id: $pid, source_id: 'GITHUB:old'})
            RETURN al.pd_name AS pd_name
            """,
            pid=pid,
        )
        r.assert_(
            alias and alias["pd_name"] == "Old Name",
            f"rename은 검색용 이름(pd_name)을 건드리면 안 됨, 실제 {dict(alias) if alias else None}",
        )
    finally:
        await delete_project_graph(pid)
    return r


async def case_unmerge_blocked_after_split_relocates_all_aliases() -> CaseResult:
    """실사용 버그 재현: 병합 후 그 alias를 분리로 다른 Actor에 옮기면, canonical에는
    되돌릴 alias가 하나도 안 남아 unmerge가 빈 Actor를 복원하게 된다 — 가드가 막아야 한다."""
    r = CaseResult("unmerge_actors: 병합→분리로 전부 재배치된 경우 복원 불가 가드")
    pid = f"test-actor-unmerge-guard-{uuid.uuid4()}"
    more_uuid = f"{pid}-more"
    less_uuid = f"{pid}-less"
    try:
        await _seed_merge_graph(pid, more_uuid, less_uuid)
        merged = await merge_actors(pid, less_uuid, more_uuid, note="integration")
        canonical_uuid = merged["canonical_uuid"]  # more_uuid (활동 많은 쪽)

        # 병합 직후, less의 alias(SLACK:less)를 canonical에서 다시 분리해 재배치한다 —
        # 이제 canonical에는 병합 취소로 되돌릴 SLACK:less가 남아 있지 않다.
        split = await split_alias(pid, canonical_uuid, ["SLACK:less"])
        r.assert_(split["new_uuid"] != canonical_uuid, f"분리로 새 Actor가 생겨야 함, 실제 {split}")

        try:
            await unmerge_actors(pid, merged["decision_id"])
            r.assert_(False, "재배치 후 unmerge는 ValueError를 던져야 하는데 성공했다")
        except ValueError as exc:
            r.assert_("재배치" in str(exc), f"가드 에러 메시지에 재배치 안내가 있어야 함, 실제: {exc}")

        # 가드가 트랜잭션을 되돌렸으므로 canonical/분리된 Actor 상태는 분리 직후 그대로여야 한다.
        canonical = await _actor(pid, canonical_uuid)
        r.assert_(
            canonical and canonical["aliases"] == ["GITHUB:more"],
            f"가드 실패 후에도 canonical alias는 분리 결과 그대로여야 함, 실제 {canonical}",
        )
        r.assert_(
            await _actor(pid, less_uuid) is None,
            "가드가 less Actor를 복원하면 안 됨(빈 Actor 생성 방지가 이 가드의 목적)",
        )
    finally:
        await delete_project_graph(pid)
    return r


CASES = [
    case_merge_unmerge_roundtrip,
    case_split_alias_moves_source_edges,
    case_rename_actor_updates_display_name,
    case_unmerge_blocked_after_split_relocates_all_aliases,
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
