"""시간축 이벤트 펼치기(EVENT_SPECS/expand_events/sort_events) 단위 테스트.

노드 → 이벤트 매핑은 docs/timeline-scope.md의 표, agent/orchestrator.py의 타임스탬프
의미 사전과 함께 움직여야 한다. 여기가 그 규칙의 회귀 안전망이다.
"""

import unittest

from tools.queries._common import EVENT_SPECS, expand_events, normalize_time, sort_events


class TestUtcNormalization(unittest.TestCase):
    """그래프의 시각 표기가 섞여 있다 — Issue.createdAt만 +09:00, 나머지는 Z.
    사전순 정렬이 시간순과 일치하려면 표기를 하나로 통일해야 한다."""

    def test_offset_converted_to_utc(self):
        events = expand_events("Issue", {"createdAt": "2026-05-16T19:47:57.124+09:00"}, {})
        self.assertEqual(events[0]["occurredAt"], "2026-05-16T10:47:57.124Z")

    def test_already_utc_gets_same_shape(self):
        """이미 Z여도 같은 경로로 다시 찍어야 표기가 통일된다."""
        events = expand_events("ChangeSet", {"occurredAt": "2026-05-16T11:07:48Z"}, {})
        self.assertEqual(events[0]["occurredAt"], "2026-05-16T11:07:48.000Z")

    def test_naive_treated_as_utc(self):
        self.assertEqual(normalize_time("2026-05-16T11:07:48"), "2026-05-16T11:07:48.000Z")

    def test_unparsable_kept_as_is(self):
        self.assertEqual(normalize_time("not-a-time"), "not-a-time")

    def test_offset_ordering_matches_real_time(self):
        """HT-45(case-43) 회귀 가드 — 정규화 전에는 이슈 생성이 PR 머지 뒤로 밀렸다.

        원문: 이슈 생성 T19:47+09:00(=10:47Z) / PR 오픈 10:52Z / PR 머지 11:07Z.
        사전순으로 그냥 비교하면 "T19:47..." > "T11:07Z"라 순서가 뒤집힌다.
        """
        events = sort_events(
            expand_events("Issue", {"createdAt": "2026-05-16T19:47:57.124+09:00"},
                          {"jira_key": "HT-45"})
            + expand_events("PullRequest",
                            {"createdAt": "2026-05-16T10:52:04Z",
                             "occurredAt": "2026-05-16T11:07:48Z"},
                            {"pr_number": 21})
        )
        self.assertEqual(
            [e["event_meaning"] for e in events],
            ["issue_created", "pr_opened", "pr_merged"],
        )


class TestEventSpecs(unittest.TestCase):
    def test_issue_splits_created_and_closed(self):
        """Issue.occurredAt(최종 업데이트)은 이벤트가 아니다 — 생성/종료만."""
        self.assertEqual(
            EVENT_SPECS["Issue"],
            (("createdAt", "issue_created"), ("closedAt", "issue_closed")),
        )
        self.assertNotIn("occurredAt", [p for p, _ in EVENT_SPECS["Issue"]])

    def test_pr_splits_opened_and_merged(self):
        self.assertEqual(
            EVENT_SPECS["PullRequest"],
            (("createdAt", "pr_opened"), ("occurredAt", "pr_merged")),
        )

    def test_single_event_node_types(self):
        self.assertEqual(EVENT_SPECS["ChangeSet"], (("occurredAt", "commit_authored"),))
        self.assertEqual(EVENT_SPECS["Communication"], (("occurredAt", "message_posted"),))


class TestExpandEvents(unittest.TestCase):
    def test_issue_with_both_timestamps_yields_two_events(self):
        events = expand_events(
            "Issue",
            {"createdAt": "2026-05-16T19:47:00Z", "closedAt": "2026-05-17T16:56:00Z"},
            {"jira_key": "HT-45"},
        )
        self.assertEqual([e["event_meaning"] for e in events],
                         ["issue_created", "issue_closed"])
        # 반환 시각은 UTC·밀리초 고정 표기로 정규화된다 (TestUtcNormalization 참고)
        self.assertEqual([e["occurredAt"] for e in events],
                         ["2026-05-16T19:47:00.000Z", "2026-05-17T16:56:00.000Z"])
        self.assertTrue(all(e["type"] == "Issue" for e in events))
        self.assertTrue(all(e["data"]["jira_key"] == "HT-45" for e in events))

    def test_open_issue_yields_only_created(self):
        """closedAt은 terminal status에서만 존재 — 없으면 종료 이벤트도 없다."""
        events = expand_events("Issue", {"createdAt": "2026-05-16T19:47:00Z"}, {})
        self.assertEqual([e["event_meaning"] for e in events], ["issue_created"])

    def test_open_pr_yields_only_opened(self):
        """머지 전 PR은 occurredAt(merged_at)이 null."""
        events = expand_events(
            "PullRequest",
            {"createdAt": "2026-05-16T10:52:00Z", "occurredAt": None},
            {"pr_number": 21},
        )
        self.assertEqual([e["event_meaning"] for e in events], ["pr_opened"])

    def test_null_and_none_string_timestamps_are_dropped(self):
        """OPTIONAL MATCH 미매치는 null 또는 문자열 'None'으로 들어온다."""
        self.assertEqual(expand_events("ChangeSet", {"occurredAt": None}, {}), [])
        self.assertEqual(expand_events("ChangeSet", {"occurredAt": "None"}, {}), [])
        self.assertEqual(expand_events("ChangeSet", {"occurredAt": "  "}, {}), [])
        self.assertEqual(expand_events("ChangeSet", {}, {}), [])

    def test_unknown_node_type_yields_nothing(self):
        self.assertEqual(expand_events("File", {"occurredAt": "2026-05-16T00:00:00Z"}, {}), [])

    def test_data_payload_is_shared_by_events_of_same_node(self):
        data = {"pr_number": 21, "title": "Feat/auth"}
        events = expand_events(
            "PullRequest",
            {"createdAt": "2026-05-16T10:52:00Z", "occurredAt": "2026-05-16T11:07:00Z"},
            data,
        )
        self.assertEqual(len(events), 2)
        self.assertTrue(all(e["data"] == data for e in events))


class TestSortEvents(unittest.TestCase):
    def test_sorted_ascending_by_occurred_at(self):
        events = [
            {"occurredAt": "2026-05-17T16:56:00Z", "event_meaning": "issue_closed"},
            {"occurredAt": "2026-05-15T14:05:00Z", "event_meaning": "commit_authored"},
            {"occurredAt": "2026-05-16T11:07:00Z", "event_meaning": "pr_merged"},
        ]
        self.assertEqual(
            [e["event_meaning"] for e in sort_events(events)],
            ["commit_authored", "pr_merged", "issue_closed"],
        )

    def test_drops_events_without_time(self):
        events = [
            {"occurredAt": "2026-05-15T14:05:00Z"},
            {"occurredAt": None},
            {"occurredAt": "None"},
            {},
        ]
        self.assertEqual(len(sort_events(events)), 1)

    def test_commit_before_issue_created_is_preserved(self):
        """HT-45(case-43) 구조 — 구현이 티켓보다 앞선 역전을 뒤집지 않는다."""
        events = sort_events([
            {"occurredAt": "2026-05-16T19:47:00Z", "event_meaning": "issue_created"},
            {"occurredAt": "2026-05-15T14:05:00Z", "event_meaning": "commit_authored"},
            {"occurredAt": "2026-05-16T11:07:00Z", "event_meaning": "pr_merged"},
        ])
        self.assertEqual(
            [e["event_meaning"] for e in events],
            ["commit_authored", "pr_merged", "issue_created"],
        )


if __name__ == "__main__":
    unittest.main()
