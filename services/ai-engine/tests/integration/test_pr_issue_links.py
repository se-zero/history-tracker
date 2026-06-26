"""
link_changeset_to_pr_issues(#9) 통합 검증 — PR→커밋 TRIGGERED_BY 단건 전파.

커밋이 올 때마다 PR 전체 커밋에 재전파(O(N²))하던 것을, 커밋 경로에서는
'그 커밋 하나만' 연결하도록 바꿨다(O(N)). 이 테스트는 단건 연결이:
  1. 호출 대상 커밋에만 TRIGGERED_BY를 건다(다른 커밋 불변)
  2. 커밋별로 부르면 전체가 빠짐없이 연결된다(완전성 = 옛 전체 전파와 동일 결과)
  3. CONTAINS 없거나 PR.jira_keys 비면 noop
를 실제 Neo4j(docker, localhost:7687)에서 확인한다. OpenAI 불필요.

실행:
  cd services/ai-engine
  PYTHONPATH=. python tests/integration/test_pr_issue_links.py

종료 코드: 0 통과 / 1 실패
"""

import asyncio
import sys
import uuid

from graph.builder import (
    close_driver,
    delete_project_graph,
    get_driver,
    link_changeset_to_pr_issues,
)


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
            for f in self.failures:
                print(f"     • {f}")
            return False
        print(f"  ✅ PASS — {self.name}")
        return True


async def _seed_pr_with_commits(project_id: str, pr_number: int, hashes: list[str],
                                jira_keys: list[str] | None) -> None:
    """PR(jira_keys 포함) + ChangeSet들 + CONTAINS 엣지를 심는다."""
    async with get_driver().session() as session:
        await session.run(
            """
            CREATE (pr:PullRequest {project_id:$pid, pr_number:$prnum, jira_keys:$keys})
            WITH pr
            UNWIND $hashes AS h
            CREATE (c:ChangeSet {project_id:$pid, hash:h})
            CREATE (pr)-[:CONTAINS]->(c)
            """,
            pid=project_id, prnum=pr_number, keys=jira_keys, hashes=hashes,
        )


async def _triggered_count(project_id: str, hash_: str) -> int:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (c:ChangeSet {project_id:$pid, hash:$h})-[r:TRIGGERED_BY]->(:Issue)
            RETURN count(r) AS c
            """,
            pid=project_id, h=hash_,
        )
        record = await result.single()
        return (record["c"] if record else 0) or 0


async def case_links_only_given_commit() -> CaseResult:
    r = CaseResult("link_changeset_to_pr_issues: 호출 대상 커밋에만 연결(다른 커밋 불변)")
    pid = f"test-pr9-only-{uuid.uuid4()}"
    try:
        await _seed_pr_with_commits(pid, 1, ["h1", "h2"], ["PROJ-1", "PROJ-2"])

        n = await link_changeset_to_pr_issues(pid, 1, "h1")

        r.assert_(n == 2, f"h1에 jira_keys 2개 연결 기대, 실제 반환 {n}")
        r.assert_(await _triggered_count(pid, "h1") == 2, "h1은 TRIGGERED_BY 2개여야 함")
        r.assert_(await _triggered_count(pid, "h2") == 0, "h2는 아직 연결 안 됨(불변)")
    finally:
        await delete_project_graph(pid)
    return r


async def case_all_commits_linked_per_commit() -> CaseResult:
    r = CaseResult("커밋별 호출로 전체가 빠짐없이 연결(O(N) 완전성)")
    pid = f"test-pr9-all-{uuid.uuid4()}"
    hashes = [f"h{i}" for i in range(5)]
    try:
        await _seed_pr_with_commits(pid, 2, hashes, ["PROJ-9"])

        # 커밋이 도착하는 순서대로 단건 연결 (event_handler 경로 모사)
        for h in hashes:
            await link_changeset_to_pr_issues(pid, 2, h)

        linked = [await _triggered_count(pid, h) for h in hashes]
        r.assert_(all(c == 1 for c in linked), f"모든 커밋이 1개 이슈에 연결돼야 함, 실제 {linked}")
    finally:
        await delete_project_graph(pid)
    return r


async def case_noop_when_not_contained() -> CaseResult:
    r = CaseResult("CONTAINS 없는 커밋은 noop(0)")
    pid = f"test-pr9-nocontains-{uuid.uuid4()}"
    try:
        await _seed_pr_with_commits(pid, 3, ["h1"], ["PROJ-1"])
        # h-orphan은 CONTAINS가 없음
        n = await link_changeset_to_pr_issues(pid, 3, "h-orphan")
        r.assert_(n == 0, f"CONTAINS 없으면 0 기대, 실제 {n}")
    finally:
        await delete_project_graph(pid)
    return r


async def case_noop_when_no_jira_keys() -> CaseResult:
    r = CaseResult("PR.jira_keys 비면 noop(0)")
    pid = f"test-pr9-nokeys-{uuid.uuid4()}"
    try:
        await _seed_pr_with_commits(pid, 4, ["h1"], None)  # jira_keys 없음
        n = await link_changeset_to_pr_issues(pid, 4, "h1")
        r.assert_(n == 0, f"jira_keys 없으면 0 기대, 실제 {n}")
        r.assert_(await _triggered_count(pid, "h1") == 0, "TRIGGERED_BY 생성 안 됨")
    finally:
        await delete_project_graph(pid)
    return r


CASES = [
    case_links_only_given_commit,
    case_all_commits_linked_per_commit,
    case_noop_when_not_contained,
    case_noop_when_no_jira_keys,
]


async def main() -> None:
    print("=" * 70)
    print("link_changeset_to_pr_issues(#9) 통합 검증 — 단건 전파")
    print("=" * 70)

    results: list[CaseResult] = []
    for case in CASES:
        print(f"\n▶ {case.__name__}")
        try:
            results.append(await case())
        except Exception as e:
            res = CaseResult(case.__name__)
            res.assert_(False, f"예외 발생: {type(e).__name__}: {e}")
            results.append(res)

    print("\n" + "=" * 70)
    for res in results:
        res.report()
    passed = sum(1 for res in results if not res.failures)
    failed = len(results) - passed
    print("=" * 70)
    print(f"결과: {passed} PASS / {failed} FAIL / {len(results)} 총")

    await close_driver()
    sys.exit(0 if failed == 0 else 1)


if __name__ == "__main__":
    asyncio.run(main())
