"""get_file_history 2계층 분할(_split_tiers) 단위 테스트 — Neo4j 없이 실행.

핵심 계약: 관련도 랭킹이 켜지면(question_embedding 존재) 관련 있는 '옛' 커밋이
최신순 컷에 밀리지 않고 detail(인용 대상)로 승격돼야 한다 — case-27 계열 recall 손실의
구조적 해소. 나머지는 context stub(개요)으로 내려 전체 이력은 보존하되 대량 인용은 막는다.
"""

import unittest

from tools.queries.files import (
    _detail_count_for_budget,
    _detail_row,
    _split_tiers,
    _stub_row,
)


def _rows() -> list[dict]:
    # Cypher 반환 순서 = 최신순(occurredAt desc). relevance는 무작위로 흩뿌려 둠.
    return [
        {"hash": "c5", "occurredAt": "2026-05-01", "message": "newest\nbody", "relevance": 0.10,
         "diff_summary": "d5", "author": "a", "issues": [{"jira_key": "HT-5"}], "prs": []},
        {"hash": "c4", "occurredAt": "2026-04-01", "message": "c4", "relevance": 0.20,
         "diff_summary": "d4", "author": "a", "issues": [], "prs": []},
        {"hash": "c3", "occurredAt": "2026-03-01", "message": "the relevant OLD one", "relevance": 0.90,
         "diff_summary": "d3", "author": "a", "issues": [{"jira_key": "HT-3"}], "prs": []},
        {"hash": "c2", "occurredAt": "2026-02-01", "message": "c2", "relevance": 0.15,
         "diff_summary": "d2", "author": "a", "issues": [], "prs": []},
        {"hash": "c1", "occurredAt": "2026-01-01", "message": "c1", "relevance": 0.05,
         "diff_summary": "d1", "author": "a", "issues": [], "prs": []},
    ]


class SplitTiersTest(unittest.TestCase):
    def test_ranked_promotes_relevant_old_commit_into_detail(self):
        detail, context, overflow = _split_tiers(_rows(), ranked=True, detail_k=2, context_cap=10)
        detail_hashes = {r["hash"] for r in detail}
        # c3(0.90, 가장 오래됨)이 detail로 승격, c5(최신·저관련)는 탈락
        self.assertIn("c3", detail_hashes)
        self.assertNotIn("c5", detail_hashes)
        self.assertEqual(detail_hashes, {"c3", "c4"})
        self.assertEqual(overflow, 0)

    def test_ranked_detail_ordered_chronological_desc(self):
        detail, _, _ = _split_tiers(_rows(), ranked=True, detail_k=3, context_cap=10)
        occurred = [r["occurredAt"] for r in detail]
        self.assertEqual(occurred, sorted(occurred, reverse=True))  # 시간순 desc로 읽기 흐름 보존

    def test_recency_fallback_takes_most_recent(self):
        detail, context, _ = _split_tiers(_rows(), ranked=False, detail_k=2, context_cap=10)
        self.assertEqual({r["hash"] for r in detail}, {"c5", "c4"})  # 최신 2건
        # 나머지는 context, 최신순 유지
        self.assertEqual([s["hash"] for s in context], ["c3", "c2", "c1"])

    def test_no_overlap_between_tiers(self):
        detail, context, _ = _split_tiers(_rows(), ranked=True, detail_k=2, context_cap=10)
        self.assertFalse({r["hash"] for r in detail} & {s["hash"] for s in context})

    def test_context_cap_overflow_counted(self):
        detail, context, overflow = _split_tiers(_rows(), ranked=False, detail_k=2, context_cap=1)
        self.assertEqual(len(context), 1)
        self.assertEqual(overflow, 2)  # c3,c2,c1 중 1건만 노출 → 2건 생략

    def test_stub_shape_has_no_body(self):
        # ranked=True, detail_k=1 → detail={c3}, 저관련 최신 c5는 context로 내려간다
        _, context, _ = _split_tiers(_rows(), ranked=True, detail_k=1, context_cap=10)
        stub = next(s for s in context if s["hash"] == "c5")
        self.assertEqual(stub["title"], "newest")           # 첫 줄만
        self.assertEqual(stub["issues"], ["HT-5"])          # jira_key만 평탄화
        self.assertNotIn("diff_summary", stub)              # 본문 없음 → 대량 인용 불가
        self.assertNotIn("message", stub)

    def test_detail_row_carries_rounded_relevance_when_ranked(self):
        row = _detail_row({"hash": "x", "message": "m", "relevance": 0.87654}, ranked=True)
        self.assertEqual(row["relevance"], 0.877)
        self.assertIn("diff_summary", row)

    def test_detail_row_omits_relevance_when_not_ranked(self):
        row = _detail_row({"hash": "x", "message": "m", "relevance": 0.5}, ranked=False)
        self.assertNotIn("relevance", row)

    def test_stub_title_truncated(self):
        stub = _stub_row({"hash": "x", "message": "z" * 200, "issues": []})
        self.assertLessEqual(len(stub["title"]), 100)


class DetailBudgetTest(unittest.TestCase):
    def test_large_budget_admits_all_rows(self):
        # 다 담아도 예산에 맞으면 전량 detail (나열형 recall 보존 = 구 동작)
        k = _detail_count_for_budget(_rows(), ranked=False, budget_chars=10_000, k_max=30)
        self.assertEqual(k, 5)

    def test_small_budget_limits_count(self):
        # 예산을 넘으면 앞에서부터 예산까지만 (최소 1건 보장)
        one = len(str(_detail_row(_rows()[0], False)))
        k = _detail_count_for_budget(_rows(), ranked=False, budget_chars=one * 2, k_max=30)
        self.assertGreaterEqual(k, 1)
        self.assertLess(k, 5)

    def test_k_max_caps_count(self):
        k = _detail_count_for_budget(_rows(), ranked=False, budget_chars=10_000, k_max=2)
        self.assertEqual(k, 2)

    def test_at_least_one_even_if_row_exceeds_budget(self):
        k = _detail_count_for_budget(_rows(), ranked=False, budget_chars=1, k_max=30)
        self.assertEqual(k, 1)

    def test_empty_rows_zero(self):
        self.assertEqual(_detail_count_for_budget([], ranked=False, budget_chars=100, k_max=30), 0)


if __name__ == "__main__":
    unittest.main()
