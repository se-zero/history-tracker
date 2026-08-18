"""
하위 4 (검색 fallback) 회귀 테스트.

1차 분석에서 LLM이 anchor를 놓쳐 즉시 포기했던 두 케이스(Test 6 파일 확장자 추정 실패,
Test 8 모호 질문)를 회귀 가드.

결정적 테스트 (도구 직접 호출):
  - 잘못된 확장자로 get_file_history 호출해도 fuzzy fallback이 정답(.java)을 찾는지
  - 정확한 경로로 호출하면 strict 경로로 즉시 매칭(fuzzy 거치지 않음)
  - 동명 파일이 여러 디렉토리에 있으면 candidates로 disambiguation

LLM-soft 테스트 (end-to-end):
  - 자연어 파일 질문에서 LLM이 잘못된 확장자를 써도 답을 찾는지
  - 모호한 질문에서 LLM이 도구를 한 번이라도 호출해 evidence를 모으는지

실행 전 선행 작업: 하위 1/2/3 회귀 테스트와 동일 — 그래프가 새 정책 적용된 상태.

실행 방법:
  cd services/ai-engine
  OPENAI_API_KEY=sk-... NEO4J_PASSWORD=... python test_phase4_regression.py

종료 코드:
  0  모든 케이스 통과
  1  하나 이상 실패
"""

import asyncio
import logging
import os
import sys

from graph.builder import close_driver
from tools import queries


# ─── 기대값 ───────────────────────────────────────────────────────────────────

# 1차 분석 Test 6 — LLM은 'GitHubNormalizer.py'로 추측, 실제는 .java
GITHUB_NORMALIZER_WRONG_EXT = "GitHubNormalizer.py"
GITHUB_NORMALIZER_STEM      = "GitHubNormalizer"
GITHUB_NORMALIZER_EXPECTED_SUFFIX = "GitHubNormalizer.java"

# 정확한 strict 경로 — 그래프에 실제 저장된 경로 (회귀 시 hardcoded fallback이지만,
# 테스트는 ENDS WITH 검사라 디렉토리 정확히 같을 필요 없음)
GITHUB_NORMALIZER_FULL_HINT = "GitHubNormalizer.java"

VAGUE_QUERY = "프로젝트 초기에 데이터 수집 파이프라인 구조는 어떻게 정해졌어?"
FILE_QUERY  = "GitHubNormalizer는 어떤 이슈들 때문에 바뀌어 왔어?"


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


# LLM 호출 캐시
_RESPONSE_CACHE: dict[str, tuple[str, dict | None]] = {}
_TOOL_ERROR_CACHE: dict[str, list[str]] = {}


class _ToolErrorCollector(logging.Handler):
    """orchestrator.run 중 tools.executor에서 발생한 tool 실행 오류를 수집."""

    def __init__(self):
        super().__init__(level=logging.ERROR)
        self.messages: list[str] = []

    def emit(self, record: logging.LogRecord) -> None:
        self.messages.append(record.getMessage())


async def _ask(question: str) -> tuple[str, dict | None]:
    if question not in _RESPONSE_CACHE:
        print(f"     [LLM 호출] {question}")
        from agent import orchestrator

        collector = _ToolErrorCollector()
        tool_logger = logging.getLogger("tools.executor")
        tool_logger.addHandler(collector)
        try:
            _RESPONSE_CACHE[question] = await orchestrator.run(question)
        finally:
            tool_logger.removeHandler(collector)
            _TOOL_ERROR_CACHE[question] = collector.messages
    return _RESPONSE_CACHE[question]


def _assert_no_tool_errors(r: CaseResult, question: str) -> None:
    errors = _TOOL_ERROR_CACHE.get(question, [])
    if not errors:
        return
    preview = "; ".join(errors[:3])
    if len(errors) > 3:
        preview += f"; ...외 {len(errors) - 3}건"
    r.assert_(False, f"도구 실행 오류 발생: {preview}")


# ─── 결정적 테스트 케이스 (도구 직접 호출) ──────────────────────────────────────


async def case_fuzzy_file_match_wrong_extension() -> CaseResult:
    """잘못된 확장자(.py)로 호출해도 stem fallback이 .java 파일을 찾아줌."""
    r = CaseResult(f"fuzzy: '{GITHUB_NORMALIZER_WRONG_EXT}' 호출 → .java 파일 변경 이력 반환 (또는 candidates에 포함)")
    rows = await queries.get_file_history(path=GITHUB_NORMALIZER_WRONG_EXT)
    print(f"     반환 row 수: {len(rows)}")

    # 두 가지 정답 패턴 중 하나:
    #   (a) 단일 매칭: rows[*]가 _resolved_path = '...GitHubNormalizer.java'
    #   (b) 다중 매칭: rows[0]에 candidates 리스트, 그 안에 .java 파일 존재
    is_disambiguation = (
        len(rows) == 1 and "candidates" in rows[0]
    )

    if is_disambiguation:
        candidates = rows[0].get("candidates", [])
        print(f"     candidates: {candidates}")
        r.assert_(
            any(c.endswith(GITHUB_NORMALIZER_EXPECTED_SUFFIX) for c in candidates),
            f"candidates에 {GITHUB_NORMALIZER_EXPECTED_SUFFIX} 가 없음 → {candidates}"
        )
    else:
        resolved = next(
            (row.get("_resolved_path") for row in rows if row.get("_resolved_path")),
            None,
        )
        print(f"     _resolved_path: {resolved}")
        r.assert_(
            resolved is not None and resolved.endswith(GITHUB_NORMALIZER_EXPECTED_SUFFIX),
            f"_resolved_path가 {GITHUB_NORMALIZER_EXPECTED_SUFFIX}로 끝나지 않음 → {resolved}"
        )
        # 추가: _resolved_via 메타가 있는지
        via = next((row.get("_resolved_via") for row in rows if row.get("_resolved_via")), None)
        r.assert_(
            via in {"basename_match", "stem_match"},
            f"_resolved_via가 'basename_match'/'stem_match'가 아님 → {via}"
        )
    return r


async def case_fuzzy_file_match_no_extension() -> CaseResult:
    """확장자 없이 stem만으로 호출해도 stem fallback이 매칭."""
    r = CaseResult(f"fuzzy: '{GITHUB_NORMALIZER_STEM}' (확장자 없음) 호출 → 매칭 성공")
    rows = await queries.get_file_history(path=GITHUB_NORMALIZER_STEM)
    print(f"     반환 row 수: {len(rows)}")

    is_disambiguation = (
        len(rows) == 1 and "candidates" in rows[0]
    )

    if is_disambiguation:
        candidates = rows[0].get("candidates", [])
        r.assert_(
            any(c.endswith(GITHUB_NORMALIZER_EXPECTED_SUFFIX) for c in candidates),
            f"확장자 없는 호출 → candidates에 .java 없음 → {candidates}"
        )
    else:
        resolved = next(
            (row.get("_resolved_path") for row in rows if row.get("_resolved_path")),
            None,
        )
        r.assert_(
            resolved is not None and resolved.endswith(GITHUB_NORMALIZER_EXPECTED_SUFFIX),
            f"확장자 없는 호출 → _resolved_path가 .java로 끝나지 않음 → {resolved}"
        )
    return r


async def case_strict_match_no_fuzzy() -> CaseResult:
    """정확한 경로로 호출하면 strict 단계에서 매칭 — fuzzy 메타 없음."""
    r = CaseResult("strict path 매칭 시 _resolved_via 메타 부착 없음")

    # 그래프에서 GitHubNormalizer.java의 실제 경로를 찾아옴 (basename 매칭 활용)
    fuzzy_rows = await queries.get_file_history(path=GITHUB_NORMALIZER_FULL_HINT)
    real_path = None
    if fuzzy_rows and "_resolved_path" in fuzzy_rows[0]:
        real_path = fuzzy_rows[0]["_resolved_path"]
    elif fuzzy_rows and "candidates" in fuzzy_rows[0]:
        # 후보 중 .java 첫 번째 선택
        for c in fuzzy_rows[0]["candidates"]:
            if c.endswith(GITHUB_NORMALIZER_EXPECTED_SUFFIX):
                real_path = c
                break

    if real_path is None:
        r.assert_(False, f"{GITHUB_NORMALIZER_EXPECTED_SUFFIX} 실제 경로를 찾을 수 없음")
        return r

    print(f"     strict 경로: {real_path}")
    rows = await queries.get_file_history(path=real_path)

    # 결과가 있어야 하고, fuzzy 메타가 없어야 함
    r.assert_(
        len(rows) > 0,
        f"strict 경로로 결과가 0건 (그래프 상태 의심): {real_path}"
    )
    has_fuzzy_meta = any(row.get("_resolved_via") is not None for row in rows)
    r.assert_(
        not has_fuzzy_meta,
        f"strict 매칭인데 _resolved_via 메타가 부착됨 (불필요한 fuzzy fallback 거침)"
    )
    return r


# ─── LLM-soft 테스트 케이스 ────────────────────────────────────────────────────


async def case_vague_query_finds_evidence() -> CaseResult:
    """anchor 없는 vague 질문에도 LLM이 도구를 호출해 evidence를 모음."""
    r = CaseResult("vague 질문: evidence > 0 (LLM이 search/recent 호출했는지)")
    answer, structured = await _ask(VAGUE_QUERY)
    _assert_no_tool_errors(r, VAGUE_QUERY)
    if structured is None:
        r.assert_(False, "structured가 None — Structured 호출 실패")
        return r

    n_ev = len(structured.get("evidence", []))
    print(f"     evidence 수: {n_ev}, unknown_aspects: {structured.get('unknown_aspects', [])}")
    r.assert_(
        n_ev >= 1,
        f"vague 질문에서 evidence가 {n_ev}개 — LLM이 도구를 호출하지 않고 종료했을 가능성 (1차 Test 8 회귀)"
    )
    return r


async def case_file_query_resolves_extension() -> CaseResult:
    """자연어로 파일을 물어보면 LLM이 잘못된 확장자를 써도 fuzzy로 해결."""
    r = CaseResult(f"파일 질문: '{FILE_QUERY}' → evidence > 0 (fuzzy fallback이 동작)")
    answer, structured = await _ask(FILE_QUERY)
    _assert_no_tool_errors(r, FILE_QUERY)
    if structured is None:
        r.assert_(False, "structured가 None — Structured 호출 실패")
        return r

    n_ev = len(structured.get("evidence", []))
    print(f"     evidence 수: {n_ev}")
    r.assert_(
        n_ev >= 1,
        f"파일 질문에서 evidence가 {n_ev}개 — fuzzy fallback이 작동했어도 LLM이 활용하지 못했거나 호출 자체를 안 함"
    )
    return r


# ─── main ────────────────────────────────────────────────────────────────────


CASES = [
    case_fuzzy_file_match_wrong_extension,
    case_fuzzy_file_match_no_extension,
    case_strict_match_no_fuzzy,
    case_vague_query_finds_evidence,
    case_file_query_resolves_extension,
]


async def main() -> None:
    if not os.environ.get("OPENAI_API_KEY"):
        print("실행 방법: OPENAI_API_KEY=sk-... NEO4J_PASSWORD=... python test_phase4_regression.py")
        sys.exit(1)

    print("=" * 70)
    print("하위 4 회귀 테스트 — 검색 fallback (파일 fuzzy + 모호 질문 진입점)")
    print("=" * 70)
    print("선행 작업이 완료된 그래프 상태에서 실행하세요 (하위 1/2/3 회귀와 동일).")
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
