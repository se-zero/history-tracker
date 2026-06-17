"""
delete_project_graph 회귀 테스트 — 프로젝트 삭제 시 Neo4j cascade.

실제 Neo4j(docker, localhost:7687)에 throwaway project_id 노드를 심고,
delete_project_graph가 해당 project_id의 노드/관계만 지우는지(다른 프로젝트 보존),
멱등인지(없는 project_id는 deleted=0) 검증한다.

실행 방법:
  cd services/ai-engine
  python test_delete_project_graph.py
  (NEO4J_URI/NEO4J_PASSWORD 미설정 시 기본값 bolt://localhost:7687 / password1234)

종료 코드:
  0  모든 케이스 통과
  1  하나 이상 실패
"""

import asyncio
import sys
import uuid

from graph.builder import close_driver, delete_project_graph, get_driver


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


async def _seed_project(project_id: str) -> None:
    """Actor·PullRequest·ChangeSet + 관계를 심는다 (Actor도 project_id 스코프 확인용)."""
    async with get_driver().session() as session:
        await session.run(
            """
            CREATE (a:Actor {uuid: $actor_uuid, project_id: $pid, name: 'tester'})
            CREATE (pr:PullRequest {project_id: $pid, pr_number: 9991, title: 't-pr'})
            CREATE (cs:ChangeSet {project_id: $pid, hash: $hash, message: 't-cs'})
            CREATE (a)-[:AUTHORED]->(pr)
            CREATE (pr)-[:CONTAINS]->(cs)
            """,
            actor_uuid=f"{project_id}-actor",
            pid=project_id,
            hash=f"{project_id}-hash",
        )


async def _count_nodes(project_id: str) -> int:
    async with get_driver().session() as session:
        result = await session.run(
            "MATCH (n {project_id: $pid}) RETURN count(n) AS c", pid=project_id
        )
        record = await result.single()
        return record["c"] if record else 0


async def case_deletes_only_target_project() -> CaseResult:
    r = CaseResult("delete_project_graph: 대상 project_id만 삭제, 타 프로젝트 보존")
    target = f"test-del-{uuid.uuid4()}"
    other = f"test-keep-{uuid.uuid4()}"
    try:
        await _seed_project(target)
        await _seed_project(other)

        r.assert_(await _count_nodes(target) == 3, "사전 조건: target 노드 3개여야 함")
        r.assert_(await _count_nodes(other) == 3, "사전 조건: other 노드 3개여야 함")

        deleted = await delete_project_graph(target)

        r.assert_(deleted == 3, f"삭제된 노드 수 3 기대, 실제 {deleted} (Actor 포함 cascade)")
        r.assert_(await _count_nodes(target) == 0, "target 노드가 모두 삭제되어야 함")
        r.assert_(await _count_nodes(other) == 3, "other 프로젝트 노드는 보존되어야 함")
    finally:
        await delete_project_graph(target)
        await delete_project_graph(other)
    return r


async def case_batches_across_multiple_transactions() -> CaseResult:
    r = CaseResult("delete_project_graph: 작은 batch_size로 다중 트랜잭션 분할 시 전량 삭제·카운터 집계")
    target = f"test-batch-{uuid.uuid4()}"
    try:
        await _seed_project(target)  # 노드 3개
        # batch_size=1 → 노드당 별도 inner tx. 카운터가 배치 간 합산되는지 확인.
        deleted = await delete_project_graph(target, batch_size=1)
        r.assert_(deleted == 3, f"다중 배치 합산 3 기대, 실제 {deleted}")
        r.assert_(await _count_nodes(target) == 0, "다중 배치 후 노드가 모두 삭제되어야 함")
    finally:
        await delete_project_graph(target)
    return r


async def case_idempotent_on_missing_project() -> CaseResult:
    r = CaseResult("delete_project_graph: 존재하지 않는 project_id는 deleted=0 (멱등)")
    deleted = await delete_project_graph(f"test-absent-{uuid.uuid4()}")
    r.assert_(deleted == 0, f"없는 프로젝트는 0 기대, 실제 {deleted}")
    return r


async def case_empty_project_id_is_noop() -> CaseResult:
    r = CaseResult("delete_project_graph: 빈 project_id는 0 반환 (안전 가드)")
    deleted = await delete_project_graph("")
    r.assert_(deleted == 0, f"빈 project_id는 0 기대, 실제 {deleted}")
    return r


CASES = [
    case_deletes_only_target_project,
    case_batches_across_multiple_transactions,
    case_idempotent_on_missing_project,
    case_empty_project_id_is_noop,
]


async def main() -> None:
    print("=" * 70)
    print("delete_project_graph 회귀 테스트 — 프로젝트 cascade 삭제")
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
