"""
ActorAlias(A) + 배치 upsert(#6) 통합 검증 — Phase 1 변경의 핵심 위험 확인.

실제 Neo4j(docker, localhost:7687)에 throwaway project_id로 검증한다:
  1. backfill_actor_aliases: 구버전 Actor.aliases 배열 → ActorAlias 노드 백필
  2. lookup_by_alias: ActorAlias 인덱스로 기존 actor를 찾는다(스캔 대체)
  3. 동시 create_actor: 같은 alias로 N개 동시 생성 → Actor 1개만 (#1 동시성 race 멱등)
  4. upsert_files_with_modified_edges: 다중 파일을 UNWIND 1회로 저장 (#6)

OpenAI 불필요 — 전부 Neo4j 경로만 검증한다(create_actor는 Step 4라 LLM 없음,
임베딩은 더미 벡터를 직접 넣는다).

실행 방법:
  cd services/ai-engine
  PYTHONPATH=. python tests/integration/test_actor_alias.py
  (NEO4J_URI/NEO4J_PASSWORD 미설정 시 기본값 bolt://localhost:7687 / password1234)

종료 코드:
  0  모든 케이스 통과
  1  하나 이상 실패
"""

import asyncio
import sys
import uuid

from graph.builder import (
    backfill_actor_aliases,
    close_driver,
    delete_project_graph,
    ensure_constraints,
    get_driver,
    make_neo4j_actor_store,
    upsert_files_with_modified_edges,
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


async def _seed_legacy_actor(project_id: str, actor_uuid: str, aliases: list[str]) -> None:
    """ActorAlias 노드 없이 aliases 배열만 가진 구버전 Actor를 심는다(백필 대상)."""
    async with get_driver().session() as session:
        await session.run(
            """
            CREATE (a:Actor {
                uuid: $uuid, project_id: $pid, name: 'Legacy',
                normalized_name: 'legacy', aliases: $aliases, emails: [], confidence: 1.0
            })
            """,
            uuid=actor_uuid, pid=project_id, aliases=aliases,
        )


async def _seed_changeset(project_id: str, hash_: str) -> None:
    async with get_driver().session() as session:
        await session.run(
            """
            CREATE (a:Actor {uuid: $auid, project_id: $pid, name: 't'})
            CREATE (c:ChangeSet {project_id: $pid, hash: $hash, message: 't-cs'})
            CREATE (a)-[:AUTHORED]->(c)
            """,
            auid=f"{project_id}-author", pid=project_id, hash=hash_,
        )


async def _count(query: str, **params) -> int:
    async with get_driver().session() as session:
        result = await session.run(query, **params)
        record = await result.single()
        return (record["c"] if record else 0) or 0


async def case_backfill_creates_alias_nodes() -> CaseResult:
    r = CaseResult("backfill_actor_aliases: 구버전 aliases 배열 → ActorAlias 노드 백필")
    pid = f"test-alias-bf-{uuid.uuid4()}"
    auid = f"{pid}-legacy"
    try:
        await _seed_legacy_actor(pid, auid, ["GITHUB:legacy", "JIRA:legacy-j"])
        before = await _count(
            "MATCH (al:ActorAlias {project_id:$pid}) RETURN count(al) AS c", pid=pid
        )
        r.assert_(before == 0, f"사전 조건: ActorAlias 0개여야 함, 실제 {before}")

        await backfill_actor_aliases()

        linked = await _count(
            "MATCH (al:ActorAlias {project_id:$pid})-[:ALIAS_OF]->(a:Actor {uuid:$auid}) "
            "RETURN count(al) AS c",
            pid=pid, auid=auid,
        )
        r.assert_(linked == 2, f"alias 2개가 노드로 연결돼야 함, 실제 {linked}")

        # 멱등 — 재실행해도 중복 안 생김
        await backfill_actor_aliases()
        again = await _count(
            "MATCH (al:ActorAlias {project_id:$pid}) RETURN count(al) AS c", pid=pid
        )
        r.assert_(again == 2, f"재실행 후에도 2개여야 함(멱등), 실제 {again}")
    finally:
        await delete_project_graph(pid)
    return r


async def case_lookup_by_alias_returns_actor() -> CaseResult:
    r = CaseResult("lookup_by_alias: ActorAlias 인덱스로 기존 actor 조회")
    pid = f"test-alias-lk-{uuid.uuid4()}"
    auid = f"{pid}-legacy"
    try:
        await _seed_legacy_actor(pid, auid, ["GITHUB:lk"])
        await backfill_actor_aliases()

        store = make_neo4j_actor_store(pid)
        found = await store.lookup_by_alias("GITHUB:lk")
        r.assert_(found is not None, "alias로 actor를 찾아야 함")
        r.assert_(found and found["uuid"] == auid, f"찾은 actor uuid가 일치해야 함: {found}")

        miss = await store.lookup_by_alias("GITHUB:does-not-exist")
        r.assert_(miss is None, "없는 alias는 None이어야 함")
    finally:
        await delete_project_graph(pid)
    return r


async def case_concurrent_create_no_duplicate() -> CaseResult:
    r = CaseResult("create_actor: 같은 alias로 동시 N개 생성 → Actor 1개 (#1 race 멱등)")
    pid = f"test-alias-race-{uuid.uuid4()}"
    try:
        store = make_neo4j_actor_store(pid)
        # 같은 alias로 10개 동시 생성 — 프로덕션에서 두 이벤트가 동시에 Step 4에 도달한 상황.
        results = await asyncio.gather(
            *[store.create_actor("Race Guy", ["GITHUB:raceguy"], [], 1.0) for _ in range(10)]
        )
        uuids = {res["uuid"] for res in results}
        r.assert_(len(uuids) == 1, f"모든 호출이 같은 actor를 받아야 함, 실제 uuid {len(uuids)}종")

        actors = await _count(
            "MATCH (al:ActorAlias {project_id:$pid, source_id:'GITHUB:raceguy'})-[:ALIAS_OF]->(a:Actor) "
            "RETURN count(DISTINCT a) AS c",
            pid=pid,
        )
        r.assert_(actors == 1, f"alias당 Actor 1개여야 함(중복 0), 실제 {actors}")
    finally:
        await delete_project_graph(pid)
    return r


async def case_batch_upsert_modified_edges() -> CaseResult:
    r = CaseResult("upsert_files_with_modified_edges: 다중 파일 UNWIND 1회 저장 (#6)")
    pid = f"test-batch-up-{uuid.uuid4()}"
    h = "cs1"
    try:
        await _seed_changeset(pid, h)
        await upsert_files_with_modified_edges(
            project_id=pid, changeset_hash=h,
            files=[
                {"file_path": "a.py", "diff_summary": "sum a", "embedding": [0.1, 0.2]},
                {"file_path": "b.py", "diff_summary": "sum b", "embedding": [0.3, 0.4]},
                {"file_path": "c.py", "diff_summary": "sum c", "embedding": []},  # 빈 임베딩 허용
            ],
        )
        edges = await _count(
            "MATCH (c:ChangeSet {project_id:$pid, hash:$h})-[r:MODIFIED]->(:File) "
            "RETURN count(r) AS c",
            pid=pid, h=h,
        )
        r.assert_(edges == 3, f"MODIFIED 엣지 3개여야 함, 실제 {edges}")

        summarized = await _count(
            "MATCH (c:ChangeSet {project_id:$pid, hash:$h})-[r:MODIFIED]->(:File) "
            "WHERE r.diffSummary IS NOT NULL RETURN count(r) AS c",
            pid=pid, h=h,
        )
        r.assert_(summarized == 3, f"모든 엣지에 diffSummary가 있어야 함, 실제 {summarized}")

        # 빈 목록은 no-op (예외 없이 통과)
        await upsert_files_with_modified_edges(project_id=pid, changeset_hash=h, files=[])
    finally:
        await delete_project_graph(pid)
    return r


CASES = [
    case_backfill_creates_alias_nodes,
    case_lookup_by_alias_returns_actor,
    case_concurrent_create_no_duplicate,
    case_batch_upsert_modified_edges,
]


async def main() -> None:
    print("=" * 70)
    print("ActorAlias(A) + 배치 upsert(#6) 통합 검증")
    print("=" * 70)

    # ActorAlias 유니크 제약이 있어야 동시 생성 멱등이 보장된다 — 먼저 보장.
    await ensure_constraints()

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
