"""
하위 2 (스레드 경계 보존) 회귀 테스트.

1차 자연어 질문 테스트에서 드러난 Slack 응답 실패 패턴(HT-36 스레드 머지, 화자 swap 등)을
도구 호출 단에서 결정적으로 검증한다.

대상:
  - get_issue_context.discussions, get_pr_context.discussions,
    get_changeset_context.communications, get_conflict_context.comm_contexts
    가 모두 conversation_id별로 그룹핑된 [{conversation_id, source, channel, messages:[...]}]
    구조로 반환되는지
  - 그룹 내 메시지가 occurredAt 오름차순 정렬되는지
  - search_by_keyword가 같은 thread의 다중 hit을 dedupe 하는지

실행 전 선행 작업: 하위 1 회귀 테스트와 동일 — 그래프가 새 정책 적용된 상태여야 함.

실행 방법:
  cd services/ai-engine
  OPENAI_API_KEY=sk-... NEO4J_PASSWORD=... python test_phase2_regression.py

종료 코드:
  0  모든 케이스 통과
  1  하나 이상 실패
"""

import asyncio
import os
import sys

from graph.builder import close_driver
from graph.embedder import embed_text
from tools import queries


# ─── 기대값 (1차 분석 기반) ─────────────────────────────────────────────────────

# HT-36: 1차 분석에서 "스레드 2개를 비슷한 내용으로 착각" — 최소 2개 distinct 스레드 기대
HT36_MIN_DISTINCT_THREADS = 2

# search_by_keyword 입력 — HT-36 분석에서 사용된 키워드 ("그래프 생성방안" 등 자주 등장)
SEARCH_QUERY = "그래프 생성방안 확정"


# ─── 헬퍼 ─────────────────────────────────────────────────────────────────────


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


def _assert_thread_group_shape(r: CaseResult, groups: list[dict], context: str) -> None:
    """groups가 [{conversation_id, messages:[...]}, ...] 형태이고
    각 그룹의 모든 메시지가 group.conversation_id에 일관되게 속하는지 검사."""
    for i, g in enumerate(groups):
        r.assert_(
            "conversation_id" in g and "messages" in g,
            f"{context}[{i}]: conversation_id 또는 messages 키 없음 → {list(g.keys())}"
        )
        msgs = g.get("messages", [])
        # 모든 메시지가 같은 conversation_id에 속해야 하지만, _group_communications_by_thread는
        # 메시지 dict에서 conversation_id 키를 GROUP_KEYS로 옮겨놨으므로 이제 그룹 메타에만 있음.
        # author/body 짝은 dict 안에 그대로 → swap 방지.
        for j, m in enumerate(msgs):
            r.assert_(
                "body" in m or "text" in m,
                f"{context}[{i}].messages[{j}]: body/text 키 없음"
            )

        # occurredAt 오름차순 정렬 검증
        timestamps = [m.get("occurredAt") for m in msgs if m.get("occurredAt")]
        r.assert_(
            timestamps == sorted(timestamps),
            f"{context}[{i}]: messages가 occurredAt 오름차순으로 정렬되지 않음"
        )


# ─── 테스트 케이스 ─────────────────────────────────────────────────────────────


async def case_ht36_discussions_grouped() -> CaseResult:
    r = CaseResult("HT-36: discussions가 conversation_id별 그룹핑 구조")
    result = await queries.get_issue_context("HT-36")

    if "message" in result:
        r.assert_(False, f"이슈를 못 찾음: {result['message']}")
        return r

    discussions = result.get("discussions", [])
    print(f"     discussions 그룹 수: {len(discussions)}")
    for i, g in enumerate(discussions):
        print(f"       [{i}] conv={g.get('conversation_id')[:20] if g.get('conversation_id') else None}... "
              f"msgs={len(g.get('messages', []))}")

    _assert_thread_group_shape(r, discussions, "HT-36.discussions")
    return r


async def case_ht36_multiple_threads() -> CaseResult:
    r = CaseResult("HT-36: 최소 2개 이상의 distinct 스레드 존재 (분리 검증)")
    result = await queries.get_issue_context("HT-36")

    if "message" in result:
        r.assert_(False, f"이슈를 못 찾음: {result['message']}")
        return r

    discussions = result.get("discussions", [])
    conv_ids = {g.get("conversation_id") for g in discussions if g.get("conversation_id")}
    print(f"     distinct conversation_id 수: {len(conv_ids)}")

    r.assert_(
        len(conv_ids) >= HT36_MIN_DISTINCT_THREADS,
        f"HT-36은 여러 스레드에서 논의됐어야 함 (실제 {len(conv_ids)}개, 기대 ≥ {HT36_MIN_DISTINCT_THREADS})"
    )
    return r


async def case_messages_sorted_within_thread() -> CaseResult:
    r = CaseResult("HT-36: 같은 그룹 내 메시지가 occurredAt 오름차순 정렬")
    result = await queries.get_issue_context("HT-36")

    if "message" in result:
        r.assert_(False, f"이슈를 못 찾음: {result['message']}")
        return r

    for g in result.get("discussions", []):
        msgs = g.get("messages", [])
        timestamps = [m.get("occurredAt") for m in msgs if m.get("occurredAt")]
        r.assert_(
            timestamps == sorted(timestamps),
            f"conv={g.get('conversation_id')} 의 messages가 시간순 정렬 어긋남: {timestamps}"
        )
    return r


async def case_pr_discussions_grouped() -> CaseResult:
    """PR #18의 discussions도 동일한 그룹핑 구조로 반환되는지."""
    r = CaseResult("PR #18: discussions가 conversation_id별 그룹핑 구조")
    result = await queries.get_pr_context(18)

    if "message" in result:
        r.assert_(False, f"PR을 못 찾음: {result['message']}")
        return r

    discussions = result.get("discussions", [])
    print(f"     discussions 그룹 수: {len(discussions)}")
    _assert_thread_group_shape(r, discussions, "PR18.discussions")
    return r


async def case_search_dedupes_same_thread() -> CaseResult:
    """search_by_keyword 결과 중 Communication 타입은 같은 conversation_id가 중복으로 나오면 안 됨."""
    r = CaseResult(f"search_by_keyword('{SEARCH_QUERY}'): 같은 스레드 다중 hit dedupe")
    embedding = await embed_text(SEARCH_QUERY)
    rows = await queries.search_by_keyword(embedding, top_k=10, threshold=0.20)

    comm_rows = [x for x in rows if x.get("type") == "Communication"]
    conv_ids = [x.get("conversation_id") for x in comm_rows]
    non_null = [c for c in conv_ids if c]
    print(f"     Communication 결과: {len(comm_rows)}개, distinct conversation_id: {len(set(non_null))}")

    r.assert_(
        len(non_null) == len(set(non_null)),
        f"같은 conversation_id 메시지가 중복 노출됨: {conv_ids}"
    )
    return r


async def case_search_exposes_conversation_id() -> CaseResult:
    """search_by_keyword Communication 결과에 conversation_id 필드가 노출되는지."""
    r = CaseResult("search_by_keyword: Communication 결과에 conversation_id 필드 노출")
    embedding = await embed_text(SEARCH_QUERY)
    rows = await queries.search_by_keyword(embedding, top_k=5, threshold=0.20)

    comm_rows = [x for x in rows if x.get("type") == "Communication"]
    if not comm_rows:
        print("     (Communication 결과 없음 — 본 케이스 SKIP)")
        return r

    for i, row in enumerate(comm_rows):
        r.assert_(
            "conversation_id" in row,
            f"Communication[{i}]: conversation_id 키 없음 → keys={list(row.keys())}"
        )
    return r


async def case_changeset_communications_grouped() -> CaseResult:
    """get_changeset_context의 communications도 그룹핑 구조여야 함.

    REFERENCE 엣지가 적은 환경에서는 빈 리스트일 수 있어 hard fail은 아니지만,
    값이 있다면 구조가 일관되어야 함.
    """
    r = CaseResult("get_changeset_context.communications: 값이 있으면 그룹핑 구조")
    # 회귀 테스트에서 HT-34에 연결된 첫 커밋으로 검사
    issue_result = await queries.get_issue_context("HT-34")
    if "message" in issue_result or not issue_result.get("changesets"):
        print("     (HT-34 changesets 없음 — 본 케이스 SKIP)")
        return r

    sample_hash = next(
        (cs["hash"] for cs in issue_result["changesets"] if cs.get("hash")),
        None,
    )
    if not sample_hash:
        print("     (sample hash 없음 — 본 케이스 SKIP)")
        return r

    result = await queries.get_changeset_context(sample_hash)
    if "message" in result:
        print(f"     (커밋 조회 실패: {result['message']} — SKIP)")
        return r

    communications = result.get("communications", [])
    print(f"     sample hash: {sample_hash[:7]}, communications 그룹 수: {len(communications)}")
    if communications:
        _assert_thread_group_shape(r, communications, "changeset.communications")
    return r


# ─── main ────────────────────────────────────────────────────────────────────


CASES = [
    case_ht36_discussions_grouped,
    case_ht36_multiple_threads,
    case_messages_sorted_within_thread,
    case_pr_discussions_grouped,
    case_search_dedupes_same_thread,
    case_search_exposes_conversation_id,
    case_changeset_communications_grouped,
]


async def main() -> None:
    if not os.environ.get("OPENAI_API_KEY"):
        print("실행 방법: OPENAI_API_KEY=sk-... NEO4J_PASSWORD=... python test_phase2_regression.py")
        sys.exit(1)

    print("=" * 70)
    print("하위 2 회귀 테스트 — Slack 스레드 경계 보존")
    print("=" * 70)
    print("선행 작업이 완료된 그래프 상태에서 실행하세요 (하위 1 회귀와 동일):")
    print("  1) POST /migrations/triggered-by-source")
    print("  2) POST /migrations/clear-semantic-triggered-by")
    print("  3) POST /migrations/pr-issue-keys")
    print("  4) POST /issue-links/build")
    print("=" * 70)

    results: list[CaseResult] = []
    for case in CASES:
        print(f"\n▶ {case.__name__}")
        try:
            results.append(await case())
        except Exception as e:
            r = CaseResult(case.__name__)
            r.assert_(False, f"예외 발생: {type(e).__name__}: {e}")
            results.append(r)

    print("\n" + "=" * 70)
    for r in results:
        r.report()
    passed = sum(1 for r in results if not r.failures)
    failed = len(results) - passed
    print("=" * 70)
    print(f"결과: {passed} PASS / {failed} FAIL / {len(results)} 총")

    await close_driver()
    sys.exit(0 if failed == 0 else 1)


if __name__ == "__main__":
    asyncio.run(main())
