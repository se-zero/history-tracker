"""
하위 3 (LLM 환각 방지) 회귀 테스트.

Structured Output 도입으로 LLM 답변이 grounded_answer 스키마를 따르고, evidence 없는
합성 문장이 끼어들지 않는지 검증한다. LLM 응답 자체는 비결정적이지만 스키마
invariant(필수 키, enum 값, id 형식, 종결문 anti-pattern 부재)는 결정적으로 검사 가능.

또한 get_timeline의 event_meaning 라벨이 LLM에 의해 보존되어 issue_created /
issue_closed 같은 의미가 정확히 인용되는지 sanity-check 한다.

실행 전 선행 작업: 하위 1/2 회귀 테스트와 동일 — 그래프가 새 정책 적용된 상태.

실행 방법:
  cd services/ai-engine
  OPENAI_API_KEY=sk-... NEO4J_PASSWORD=... python test_phase3_regression.py

종료 코드:
  0  모든 케이스 통과
  1  하나 이상 실패
"""

import asyncio
import os
import sys

from graph.builder import close_driver
from agent import orchestrator


# ─── 기대 enum (orchestrator schema와 일관 유지) ────────────────────────────────

ALLOWED_TYPES = {"commit", "pull_request", "issue", "message", "document"}
ALLOWED_EVENT_MEANINGS = {
    "issue_created", "issue_updated", "issue_closed",
    "commit_authored",
    "pr_opened", "pr_merged",
    "message_posted",
    "document_created", "document_updated",
}

# 종결문 anti-pattern — 1차 분석에서 LLM이 임의 생성한 마무리/일반론 문구들.
# (너무 일반적인 단어는 false positive를 부르므로 보수적으로 선택)
ANTIPATTERN_PHRASES = [
    "추가적으로 궁금",
    "추가 궁금한",
    "더 알고 싶은",
    "이 정보를 통해",
    "모든 작업이 완료되어",
    "말씀해 주세요",
]


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


def _assert_structured_shape(r: CaseResult, structured: dict | None, ctx: str) -> bool:
    """structured가 grounded_answer 스키마를 따르는지 검사. 진행 가능하면 True."""
    if structured is None:
        r.assert_(False, f"{ctx}: structured가 None — Structured Output 호출 실패")
        return False
    for key in ("summary", "evidence", "unknown_aspects"):
        if key not in structured:
            r.assert_(False, f"{ctx}: '{key}' 키 누락")
            return False
    if not isinstance(structured["evidence"], list):
        r.assert_(False, f"{ctx}: evidence가 list 아님 → {type(structured['evidence'])}")
        return False
    if not isinstance(structured["unknown_aspects"], list):
        r.assert_(False, f"{ctx}: unknown_aspects가 list 아님")
        return False
    return True


# LLM 호출 캐시 — 한 질문은 한 번만 호출해서 테스트 시간 단축.
_RESPONSE_CACHE: dict[str, tuple[str, dict | None]] = {}


async def _ask(question: str) -> tuple[str, dict | None]:
    if question not in _RESPONSE_CACHE:
        print(f"     [LLM 호출] {question}")
        _RESPONSE_CACHE[question] = await orchestrator.run(question)
    return _RESPONSE_CACHE[question]


# ─── 테스트 케이스 ─────────────────────────────────────────────────────────────


async def case_response_has_structured_fields() -> CaseResult:
    r = CaseResult("응답에 grounded_answer 구조 (summary/evidence/unknown_aspects) 존재")
    answer, structured = await _ask("HT-37은 어떤 작업이야?")
    print(f"     answer 길이: {len(answer)}자, structured: {bool(structured)}")
    _assert_structured_shape(r, structured, "HT-37 query")
    return r


async def case_evidence_type_and_event_meaning_enum() -> CaseResult:
    r = CaseResult("evidence[*].type / event_meaning이 정의된 enum 값")
    answer, structured = await _ask("PR #18은 어떤 작업이고 어떤 이슈와 연결돼?")
    if not _assert_structured_shape(r, structured, "PR #18 query"):
        return r
    for i, e in enumerate(structured["evidence"]):
        t = e.get("type")
        em = e.get("event_meaning")
        r.assert_(
            t in ALLOWED_TYPES,
            f"evidence[{i}].type='{t}' 가 enum 밖 (허용: {sorted(ALLOWED_TYPES)})"
        )
        r.assert_(
            em in ALLOWED_EVENT_MEANINGS,
            f"evidence[{i}].event_meaning='{em}' 가 enum 밖"
        )
    return r


async def case_evidence_id_format() -> CaseResult:
    r = CaseResult("evidence[*].id 형식이 타입별 규칙 준수 (issue_key / #N / commit 7+ / conversation_id)")
    answer, structured = await _ask("PR #18은 어떤 작업이고 어떤 이슈와 연결돼?")
    if not _assert_structured_shape(r, structured, "PR #18 query"):
        return r
    for i, e in enumerate(structured["evidence"]):
        t, id_ = e.get("type"), (e.get("id") or "")
        if t == "pull_request":
            r.assert_(
                id_.startswith("#") or id_.isdigit(),
                f"evidence[{i}] pull_request id='{id_}' 형식 비표준 (기대 '#N' 또는 'N')"
            )
        elif t == "issue":
            r.assert_(
                "-" in id_ and id_.split("-", 1)[0].isalpha(),
                f"evidence[{i}] issue id='{id_}' issue_key 형식 아님"
            )
        elif t == "commit":
            r.assert_(
                len(id_) >= 7,
                f"evidence[{i}] commit id='{id_}' 너무 짧음 (앞 7자 이상 필요)"
            )
        elif t == "message":
            r.assert_(
                len(id_) > 0,
                f"evidence[{i}] message id가 비어있음 (conversation_id 필요)"
            )
    return r


async def case_evidence_quote_not_empty() -> CaseResult:
    r = CaseResult("evidence[*].quote 비어있지 않음 (직접 인용 의무)")
    answer, structured = await _ask("PR #18은 어떤 작업이고 어떤 이슈와 연결돼?")
    if not _assert_structured_shape(r, structured, "PR #18 query"):
        return r
    for i, e in enumerate(structured["evidence"]):
        quote = (e.get("quote") or "").strip()
        r.assert_(
            len(quote) > 0,
            f"evidence[{i}].quote 비어있음 (LLM이 인용 의무를 우회)"
        )
    return r


async def case_non_existent_issue() -> CaseResult:
    """HT-99(존재 X): 할루시네이션 차단 — evidence 비어있고 unknown 또는 not-found 명시."""
    r = CaseResult("HT-99(존재 X): evidence 비어있고 unknown/not-found 명시")
    answer, structured = await _ask("HT-99 작업 진행 상황 알려줘")
    if not _assert_structured_shape(r, structured, "HT-99 query"):
        return r

    n_ev = len(structured["evidence"])
    r.assert_(
        n_ev == 0,
        f"HT-99에 evidence가 {n_ev}개 생성됨 (할루시네이션 의심)"
    )

    summary = structured.get("summary", "")
    has_unknown = bool(structured.get("unknown_aspects"))
    looks_not_found = any(p in summary for p in ["찾을 수 없", "확인할 수 없", "존재하지", "없습니다"])
    r.assert_(
        has_unknown or looks_not_found,
        f"unknown_aspects 비어있고 summary도 not-found 패턴 없음 → summary='{summary[:120]}'"
    )
    return r


async def case_summary_no_closing_filler() -> CaseResult:
    """1차 분석의 HT-5/HT-36 종결문 같은 LLM 합성 문구가 summary에 없는지."""
    r = CaseResult("summary에 종결문/일반론 anti-pattern 없음 (LLM 합성 마무리 차단)")
    answer, structured = await _ask("HT-5는 어떤 작업이고 어떻게 마무리됐어?")
    if not _assert_structured_shape(r, structured, "HT-5 query"):
        return r
    summary = structured.get("summary", "")
    print(f"     summary: {summary[:140]}{'…' if len(summary) > 140 else ''}")
    for p in ANTIPATTERN_PHRASES:
        r.assert_(
            p not in summary,
            f"summary에 종결문 anti-pattern 포함: '{p}'"
        )
    return r


async def case_timeline_event_meanings() -> CaseResult:
    """get_timeline 결과의 event_meaning이 LLM 답변에 잘 인용되는지 (간접 검증).

    HT-3(여러 자식 이슈) 또는 다른 큰 이슈에 timeline 질문하면 LLM이 get_timeline을 호출.
    evidence에 다양한 event_meaning이 나타나야 함 (created/closed/pr_merged/commit_authored 등).
    """
    r = CaseResult("타임라인 질문 시 evidence에 다양한 event_meaning이 등장")
    answer, structured = await _ask(
        "Slack 데이터 정규화 작업이 처음 어떻게 만들어졌는지 시간 순으로 정리해줘."
    )
    if not _assert_structured_shape(r, structured, "timeline query"):
        return r

    meanings = {e.get("event_meaning") for e in structured["evidence"]}
    print(f"     등장한 event_meaning: {sorted(meanings)}")
    # 최소 2종 이상 — 단일 이벤트 타입만 쓰면 그래프 라벨링이 LLM에 닿지 않은 것
    r.assert_(
        len(meanings) >= 2,
        f"event_meaning이 {len(meanings)}종만 등장 (기대 ≥ 2 — 라벨이 LLM 답변에 반영되지 않음 의심)"
    )
    # 모두 enum 안에 있어야 함
    for m in meanings:
        r.assert_(
            m in ALLOWED_EVENT_MEANINGS,
            f"event_meaning='{m}' 가 enum 밖"
        )
    return r


# ─── main ────────────────────────────────────────────────────────────────────


CASES = [
    case_response_has_structured_fields,
    case_evidence_type_and_event_meaning_enum,
    case_evidence_id_format,
    case_evidence_quote_not_empty,
    case_non_existent_issue,
    case_summary_no_closing_filler,
    case_timeline_event_meanings,
]


async def main() -> None:
    if not os.environ.get("OPENAI_API_KEY"):
        print("실행 방법: OPENAI_API_KEY=sk-... NEO4J_PASSWORD=... python test_phase3_regression.py")
        sys.exit(1)

    print("=" * 70)
    print("하위 3 회귀 테스트 — Structured Output 환각 방지")
    print("=" * 70)
    print("선행 작업이 완료된 그래프 상태에서 실행하세요 (하위 1/2 회귀와 동일).")
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
    print(f"LLM 호출 횟수(캐싱 후): {len(_RESPONSE_CACHE)}건")

    await close_driver()
    sys.exit(0 if failed == 0 else 1)


if __name__ == "__main__":
    asyncio.run(main())
