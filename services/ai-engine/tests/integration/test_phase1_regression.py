"""
하위 1 (이슈-커밋 연결 정확도 개선) 회귀 테스트.

사용자 1차 자연어 질문 테스트에서 드러난 실패 케이스를 도구(쿼리) 호출 단에서 결정적으로 검증한다.
LLM 응답 단의 비결정성을 우회하기 위해 그래프 쿼리 결과 JSON을 직접 assert한다.

실행 전 선행 작업 (그래프 상태 동기화):
  1. POST /migrations/triggered-by-source            # 기존 엣지에 source 라벨 부여
  2. POST /migrations/clear-semantic-triggered-by    # 시맨틱 엣지 정리 (텍스트는 보존)
  3. POST /issue-links/build                          # 새 정책으로 시맨틱 엣지 재구축

실행 방법:
  cd services/ai-engine
  OPENAI_API_KEY=sk-... NEO4J_PASSWORD=... python test_phase1_regression.py

종료 코드:
  0  모든 케이스 통과
  1  하나 이상 실패
"""

import asyncio
import os
import sys

from graph.builder import close_driver
from tools import queries


# ─── 기대값 (사용자 1차 분석 기반) ──────────────────────────────────────────────

# HT-5 (API 테스트, 완료일 2026-04-06) — 이후에 만들어진 5개 커밋이 잘못 매칭되어 있었음
HT5_FALSE_POSITIVE_HASHES = [
    "fabce0a2e2c064e438e0f38ee93c52909bfb6aaf",  # 03-21 — HT-5 완료(04-06) 전이지만 작업 무관
    "81314d244763358428665838f1e9cc892188a19f",  # 03-22 — 같은 사유
    "b9931ab753b38672b5cb83c91a865324b9faf61b",  # 03-24 — 같은 사유
    "4a1ced36fe672d4f2959d2b5aaaa90bd9d85caf0",  # 04-06 — 경계, "Controller에 Publisher 연결" 무관
    "8e800d4cc4ce5d86ca0781a78c13cc631c12c644",  # 04-10 — 완료 이후, "CLAUDE.md 파일 생성" 무관
]

# HT-34 (Tool calling) — 무관한 Slack LLM 필터 커밋이 잘못 매칭
HT34_FALSE_POSITIVE_HASH = "fa5686dddfbff43014106f35bbd8ab9818558b86"
HT34_EXPECTED_HASH       = "8b5d72d435330cbce257795c1f716b350df86068"

# PR #18 (HT-37) — 정답은 HT-37 단일, 8개 false positive가 있었음
PR18_EXPECTED_ISSUES        = {"HT-37"}
PR18_FALSE_POSITIVE_ISSUES  = {"HT-29", "HT-3", "HT-43", "HT-39", "HT-25", "HT-22", "HT-41", "HT-38"}

# HT-3 (대형 부모 이슈) — 자식 이슈가 응답에 집계되어야 함
HT3_EXPECTED_DESCENDANT_MIN = 1   # 최소 1개 자식 — 정확한 키는 환경마다 다를 수 있어 보수적

# HT-99 — 존재하지 않는 이슈 (할루시네이션 방지 확인)
HT99_KEY = "HT-99"


# ─── assert 헬퍼 ──────────────────────────────────────────────────────────────


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


def _short(h: str | None) -> str:
    return h[:7] if h else "(null)"


# ─── 테스트 케이스 ─────────────────────────────────────────────────────────────


async def case_ht5_no_unrelated_changesets() -> CaseResult:
    r = CaseResult("HT-5: 완료 이후/무관 커밋이 응답에 들어가지 않음")
    result = await queries.get_issue_context("HT-5")

    if "message" in result:
        r.assert_(False, f"이슈를 못 찾음: {result['message']}")
        return r

    actual = [cs.get("hash") for cs in result.get("changesets", []) if cs.get("hash")]
    print(f"     실제 연결된 changesets: {[_short(h) for h in actual]}")

    for bad in HT5_FALSE_POSITIVE_HASHES:
        r.assert_(
            bad not in actual,
            f"무관 커밋 {_short(bad)} ({bad[:8]})이 여전히 HT-5에 연결됨"
        )
    return r


async def case_ht34_no_unrelated_changesets() -> CaseResult:
    r = CaseResult("HT-34: Slack LLM 필터 커밋이 응답에 들어가지 않음")
    result = await queries.get_issue_context("HT-34")

    if "message" in result:
        r.assert_(False, f"이슈를 못 찾음: {result['message']}")
        return r

    actual = [cs.get("hash") for cs in result.get("changesets", []) if cs.get("hash")]
    print(f"     실제 연결된 changesets: {[_short(h) for h in actual]}")

    r.assert_(
        HT34_FALSE_POSITIVE_HASH not in actual,
        f"무관 커밋 {_short(HT34_FALSE_POSITIVE_HASH)} (Slack LLM 필터)가 HT-34에 연결됨"
    )
    # 실제 관련 커밋은 그래프 빌드 후 살아있어야 함 (정보용 — 본 케이스의 hard fail은 아님)
    if HT34_EXPECTED_HASH not in actual:
        print(f"     ⚠️  HT-34의 핵심 커밋 {_short(HT34_EXPECTED_HASH)}이(가) 연결되어 있지 않음 (경고 — 실제 그래프 상태 확인 필요)")
    return r


async def case_pr18_only_ht37() -> CaseResult:
    r = CaseResult("PR #18: 연관 이슈가 HT-37만 노출")
    result = await queries.get_pr_context(18)

    if "message" in result:
        r.assert_(False, f"PR을 못 찾음: {result['message']}")
        return r

    issues = result.get("issues", [])
    keys = {i.get("issue_key") for i in issues if i.get("issue_key")}
    print(f"     실제 연관 이슈: {sorted(keys)}")

    leaked = keys & PR18_FALSE_POSITIVE_ISSUES
    r.assert_(
        not leaked,
        f"PR #18에 false positive 이슈 노출: {sorted(leaked)}"
    )
    r.assert_(
        "HT-37" in keys,
        f"PR #18의 정답 이슈 HT-37이 빠짐 (확인 필요 — 텍스트 매칭 또는 PR.issue_keys 전파가 동작했는지)"
    )
    return r


async def case_ht3_descendants_populated() -> CaseResult:
    r = CaseResult("HT-3: 자식 이슈가 descendants에 집계됨")
    result = await queries.get_issue_context("HT-3")

    if "message" in result:
        r.assert_(False, f"이슈를 못 찾음: {result['message']}")
        return r

    descendants = result.get("descendants", [])
    keys = [d.get("issue_key") for d in descendants]
    print(f"     descendants 수: {len(descendants)}, 키: {keys}")

    r.assert_(
        len(descendants) >= HT3_EXPECTED_DESCENDANT_MIN,
        f"HT-3에 자식 이슈가 {len(descendants)}개만 잡힘 (최소 {HT3_EXPECTED_DESCENDANT_MIN}개 기대 — CHILD_OF 엣지 확인 필요)"
    )
    return r


async def case_ht99_not_found() -> CaseResult:
    r = CaseResult("HT-99: 존재하지 않는 이슈에 대해 NOT FOUND 응답 (할루시네이션 방지)")
    result = await queries.get_issue_context(HT99_KEY)

    r.assert_(
        "message" in result and "찾을 수 없" in result.get("message", ""),
        f"기대: 'message'에 '찾을 수 없' 포함 / 실제: {result}"
    )
    return r


async def case_filter_excludes_low_confidence() -> CaseResult:
    """전체 그래프에서 confidence < 0.5 인 TRIGGERED_BY가 응답에 노출되지 않는지 확인.

    (어떤 이슈/커밋이든 한 건 골라서 검사 — 그래프에 노이즈가 남아있어도 쿼리 단에서 차단되는지 본다.)
    """
    r = CaseResult("쿼리 필터: confidence < 0.5 엣지가 응답에서 차단됨")
    # 임의 케이스 — HT-5의 changesets 신뢰도만 봐도 충분
    result = await queries.get_issue_context("HT-5")
    if "message" in result:
        # 이슈 못 찾으면 본 케이스는 N/A
        print(f"     (HT-5 못 찾음 — 본 케이스 SKIP)")
        return r

    low_conf = [
        cs for cs in result.get("changesets", [])
        if cs.get("confidence") is not None and cs["confidence"] < 0.5
    ]
    r.assert_(
        not low_conf,
        f"confidence < 0.5 changesets가 응답에 포함됨: {[(_short(c.get('hash')), c.get('confidence')) for c in low_conf]}"
    )
    return r


# ─── main ────────────────────────────────────────────────────────────────────


CASES = [
    case_ht5_no_unrelated_changesets,
    case_ht34_no_unrelated_changesets,
    case_pr18_only_ht37,
    case_ht3_descendants_populated,
    case_ht99_not_found,
    case_filter_excludes_low_confidence,
]


async def main() -> None:
    if not os.environ.get("OPENAI_API_KEY"):
        print("실행 방법: OPENAI_API_KEY=sk-... NEO4J_PASSWORD=... python test_phase1_regression.py")
        sys.exit(1)

    print("=" * 70)
    print("하위 1 회귀 테스트 — 이슈-커밋 연결 정확도")
    print("=" * 70)
    print("선행 작업이 완료된 그래프 상태에서 실행하세요:")
    print("  1) POST /migrations/triggered-by-source")
    print("  2) POST /migrations/clear-semantic-triggered-by")
    print("  3) POST /issue-links/build")
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
