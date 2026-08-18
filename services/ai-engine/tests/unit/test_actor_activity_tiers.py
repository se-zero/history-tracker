"""get_actor_activity 2계층 조립(_build_activity_tiers) 단위 테스트 — Neo4j 없이 실행.

핵심 계약 둘: ① 메시지는 질문 관련도로 detail 승격(최신순 컷이 옛 관련 메시지를
떨어뜨리던 문제 해소) ② context stub에는 본문이 없어 대량 인용이 구조적으로 불가
(2026-07-14 limit 확대 롤백의 원인이던 인용 폭주 차단). 카테고리 예산 배분으로
한 카테고리의 detail 독식도 막는다.
"""

import unittest

from tools.queries.actor import _build_activity_tiers, _cap_issues, _comm_detail, _stub


def _commits(n=5):
    return [{"hash": f"c{i}", "message": f"commit {i}\nbody", "occurredAt": f"2026-06-{20-i:02d}"}
            for i in range(n)]


def _prs(n=3):
    return [{"pr_number": i, "title": f"pr {i}", "occurredAt": f"2026-06-{15-i:02d}"} for i in range(n)]


def _comms():
    # 최신순 — 가장 오래된 t3이 최고 관련도
    return [
        {"body": "recent chat", "channel": "ch", "conversation_id": "t1",
         "occurredAt": "2026-06-19", "relevance": 0.10},
        {"body": "mid", "channel": "ch", "conversation_id": "t2",
         "occurredAt": "2026-05-10", "relevance": 0.20},
        {"body": "the relevant old one", "channel": "ch", "conversation_id": "t3",
         "occurredAt": "2026-04-01", "relevance": 0.90},
    ]


class BuildActivityTiersTest(unittest.TestCase):
    def test_ranked_promotes_relevant_old_message(self):
        # 메시지 예산을 1건 수준으로 좁힘 — 관련도 최고(t3, 가장 오래됨)가 승격돼야 한다
        detail, context, _ = _build_activity_tiers(
            [], [], _comms(), comm_ranked=True, budget=300, context_cap=40,
        )
        msg_detail = [r for r in detail if r["kind"] == "message"]
        self.assertEqual(msg_detail[0]["conversation_id"], "t3")
        self.assertIn("t1", [s["conversation_id"] for s in context])  # 최신·저관련은 stub

    def test_unranked_messages_fall_back_to_recency(self):
        detail, _, _ = _build_activity_tiers([], [], _comms(), comm_ranked=False, budget=300)
        msg_detail = [r for r in detail if r["kind"] == "message"]
        self.assertEqual(msg_detail[0]["conversation_id"], "t1")  # 최신순

    def test_message_stub_has_no_body_but_keeps_drilldown_id(self):
        _, context, _ = _build_activity_tiers([], [], _comms(), comm_ranked=True, budget=300)
        stub = next(s for s in context if s["kind"] == "message")
        self.assertNotIn("body", stub)                      # 본문 없음 → 인용 불가
        self.assertTrue(stub["conversation_id"])            # get_thread_context 드릴다운 가능

    def test_commit_stub_first_line_only(self):
        _, context, _ = _build_activity_tiers(_commits(), [], [], comm_ranked=False, budget=120)
        stub = next(s for s in context if s["kind"] == "commit")
        self.assertNotIn("message", stub)
        self.assertNotIn("\n", stub["title"])               # 첫 줄만

    def test_budget_split_prevents_category_starvation(self):
        # 커밋이 아무리 많아도 메시지 예산은 남는다 — 커밋 독식 방지
        detail, _, _ = _build_activity_tiers(
            _commits(50), [], _comms(), comm_ranked=False, budget=2000,
        )
        kinds = {r["kind"] for r in detail}
        self.assertIn("message", kinds)
        self.assertIn("commit", kinds)

    def test_small_activity_fits_entirely_in_detail(self):
        # 예산에 다 들어가는 소규모 액터는 전량 detail (구 동작 보존) — context 없음
        detail, context, overflow = _build_activity_tiers(
            _commits(2), _prs(2), _comms(), comm_ranked=False, budget=5000,
        )
        self.assertEqual(len(detail), 7)
        self.assertEqual(context, [])
        self.assertEqual(overflow, 0)

    def test_detail_and_context_sorted_desc(self):
        detail, context, _ = _build_activity_tiers(
            _commits(), _prs(), _comms(), comm_ranked=False, budget=600, context_cap=40,
        )
        for rows in (detail, context):
            dates = [r.get("occurredAt") or "" for r in rows]
            self.assertEqual(dates, sorted(dates, reverse=True))

    def test_context_cap_overflow_counted(self):
        _, context, overflow = _build_activity_tiers(
            _commits(30), [], [], comm_ranked=False, budget=120, context_cap=5,
        )
        self.assertEqual(len(context), 5)
        self.assertGreater(overflow, 0)

    def test_comm_detail_relevance_rounded_only_when_ranked(self):
        row = {"body": "b", "channel": "c", "conversation_id": "t", "occurredAt": "x", "relevance": 0.87654}
        self.assertEqual(_comm_detail(row, True)["relevance"], 0.877)
        self.assertNotIn("relevance", _comm_detail(row, False))

    def test_stub_shapes_carry_kind(self):
        self.assertEqual(_stub("commit", {"hash": "h", "message": "m"})["kind"], "commit")
        self.assertEqual(_stub("pull_request", {"pr_number": 1, "title": "t"})["kind"], "pull_request")
        self.assertEqual(_stub("message", {"conversation_id": "t"})["kind"], "message")


class CapIssuesTest(unittest.TestCase):
    def test_caps_by_recent_jira_number_and_reports_total(self):
        issues = [{"issue_key": f"HT-{i}", "title": f"issue {i}"} for i in range(1, 31)]
        capped, total, overflow = _cap_issues(issues, cap=5)
        self.assertEqual(total, 30)
        self.assertEqual([i["issue_key"] for i in capped], ["HT-30", "HT-29", "HT-28", "HT-27", "HT-26"])
        # 잘린 오래된 이슈도 key로는 전부 보인다 — 초기 대표작 증발 방지
        self.assertIn("HT-1", overflow)
        self.assertIn("HT-25", overflow)

    def test_drops_null_placeholder_rows(self):
        # OPTIONAL MATCH 미매치 잔재({issue_key: null})는 집계에서 제외
        capped, total, overflow = _cap_issues([{"issue_key": None, "title": None}, {"issue_key": "HT-1", "title": "t"}])
        self.assertEqual(total, 1)
        self.assertEqual(len(capped), 1)
        self.assertEqual(overflow, "")

    def test_title_clipped(self):
        capped, _, _ = _cap_issues([{"issue_key": "HT-1", "title": "x" * 200}])
        self.assertLessEqual(len(capped[0]["title"]), 40)


if __name__ == "__main__":
    unittest.main()
