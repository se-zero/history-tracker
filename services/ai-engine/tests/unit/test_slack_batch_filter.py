"""slack_batch_filter의 묶음 로직(group_for_filter)·run_slack_llm_filter 오프라인 단위 테스트.

group_for_filter는 eval/slack_filter_eval.py 측정 하네스와 공유하는 순수 함수라, 여기서
그룹핑 규칙이 깨지면 프로덕션 필터와 측정 하네스의 배치 단위가 조용히 어긋난다.
"""

import unittest
from datetime import datetime
from unittest.mock import AsyncMock, patch

from graph.slack_batch_filter import group_for_filter, run_slack_llm_filter


def _msg(project_id="p1", conversation_id="c1", channel="general", occurred_at=None,
         url="u1", body="body"):
    return {
        "project_id": project_id,
        "url": url,
        "body": body,
        "channel": channel,
        "conversation_id": conversation_id,
        "occurred_at": occurred_at,
    }


class GroupForFilterTest(unittest.TestCase):
    def test_same_conversation_two_or_more_becomes_sorted_thread(self):
        m1 = _msg(conversation_id="c1", occurred_at=datetime(2026, 7, 5, 10, 0), url="u1")
        m2 = _msg(conversation_id="c1", occurred_at=datetime(2026, 7, 5, 9, 0), url="u2")
        batches = group_for_filter([m1, m2])

        self.assertEqual(len(batches), 1)
        project_id, is_thread, msgs = batches[0]
        self.assertEqual(project_id, "p1")
        self.assertTrue(is_thread)
        self.assertEqual([m["url"] for m in msgs], ["u2", "u1"])  # occurred_at 오름차순

    def test_same_conversation_id_different_project_does_not_merge(self):
        m1 = _msg(project_id="p1", conversation_id="c1", url="u1")
        m2 = _msg(project_id="p1", conversation_id="c1", url="u2")
        m3 = _msg(project_id="p2", conversation_id="c1", url="u3")
        batches = group_for_filter([m1, m2, m3])

        thread_batches = [b for b in batches if b[1]]
        self.assertEqual(len(thread_batches), 1)
        self.assertEqual({m["url"] for m in thread_batches[0][2]}, {"u1", "u2"})
        # p2/c1은 프로젝트가 달라 위 스레드와 묶이지 않고 단독 메시지로 남는다
        standalone_urls = {m["url"] for _, is_thread, msgs in batches if not is_thread for m in msgs}
        self.assertIn("u3", standalone_urls)

    def test_standalone_grouped_by_project_channel_date_chunked_by_50(self):
        msgs = [
            _msg(conversation_id=f"c{i}", channel="general",
                 occurred_at=datetime(2026, 7, 5, 0, 0), url=f"u{i}")
            for i in range(51)
        ]
        batches = group_for_filter(msgs)

        self.assertEqual(len(batches), 2)
        sizes = sorted(len(msgs) for _, _, msgs in batches)
        self.assertEqual(sizes, [1, 50])
        for _, is_thread, _ in batches:
            self.assertFalse(is_thread)

    def test_standalone_without_occurred_at_uses_unknown_date_key(self):
        m1 = _msg(conversation_id="c1", channel="general", occurred_at=None, url="u1")
        m2 = _msg(conversation_id="c2", channel="general", occurred_at=None, url="u2")
        batches = group_for_filter([m1, m2])

        self.assertEqual(len(batches), 1)
        self.assertEqual({m["url"] for m in batches[0][2]}, {"u1", "u2"})

    def test_thread_batches_come_before_standalone_batches(self):
        thread1 = _msg(conversation_id="c1", url="t1")
        thread2 = _msg(conversation_id="c1", url="t2")
        standalone = _msg(conversation_id="c3", url="s1")
        batches = group_for_filter([standalone, thread1, thread2])

        self.assertTrue(batches[0][1])
        self.assertFalse(batches[-1][1])


class RunSlackLlmFilterTest(unittest.IsolatedAsyncioTestCase):
    async def test_no_target_returns_zero_and_skips_llm(self):
        with patch(
            "graph.slack_batch_filter.fetch_unfiltered_communications", AsyncMock(return_value=[])
        ), patch("graph.slack_batch_filter.filter_messages", AsyncMock()) as mock_filter:
            result = await run_slack_llm_filter()

        self.assertEqual(result, {"kept": 0, "deleted": 0})
        mock_filter.assert_not_called()

    async def test_thread_batch_calls_filter_with_is_thread_true_and_marks_by_flag(self):
        m1 = _msg(conversation_id="c1", occurred_at=datetime(2026, 7, 5, 9, 0), url="u1")
        m2 = _msg(conversation_id="c1", occurred_at=datetime(2026, 7, 5, 10, 0), url="u2")
        with patch(
            "graph.slack_batch_filter.fetch_unfiltered_communications", AsyncMock(return_value=[m1, m2])
        ), patch(
            "graph.slack_batch_filter.get_project_profile", AsyncMock(return_value="")
        ), patch(
            "graph.slack_batch_filter.filter_messages", AsyncMock(return_value=[True, False])
        ) as mock_filter, patch(
            "graph.slack_batch_filter.mark_communication_llm_filtered", AsyncMock()
        ) as mock_mark, patch(
            "graph.slack_batch_filter.delete_communication", AsyncMock()
        ) as mock_delete:
            result = await run_slack_llm_filter()

        mock_filter.assert_awaited_once_with(["body", "body"], True, project_context="")
        mock_mark.assert_awaited_once_with("p1", "u1")
        mock_delete.assert_awaited_once_with("p1", "u2")
        self.assertEqual(result, {"kept": 1, "deleted": 1})

    async def test_filter_exception_preserves_entire_batch(self):
        m1 = _msg(conversation_id="c1", url="u1")
        with patch(
            "graph.slack_batch_filter.fetch_unfiltered_communications", AsyncMock(return_value=[m1])
        ), patch(
            "graph.slack_batch_filter.get_project_profile", AsyncMock(return_value="")
        ), patch(
            "graph.slack_batch_filter.filter_messages", AsyncMock(side_effect=RuntimeError("boom"))
        ), patch(
            "graph.slack_batch_filter.mark_communication_llm_filtered", AsyncMock()
        ) as mock_mark, patch(
            "graph.slack_batch_filter.delete_communication", AsyncMock()
        ) as mock_delete:
            result = await run_slack_llm_filter()

        mock_mark.assert_awaited_once_with("p1", "u1")
        mock_delete.assert_not_called()
        self.assertEqual(result, {"kept": 1, "deleted": 0})


class RunSlackLlmFilterProjectProfileTest(unittest.IsolatedAsyncioTestCase):
    """run_slack_llm_filter가 배치의 project_id로 프로젝트 프로필을 조회해 filter_messages에
    넘기는지, 프로필 조회가 project_id당 1회로 묶이는지 검증한다."""

    async def test_profile_is_fetched_and_passed_as_project_context(self):
        m1 = _msg(conversation_id="c1", occurred_at=datetime(2026, 7, 5, 9, 0), url="u1")
        m2 = _msg(conversation_id="c1", occurred_at=datetime(2026, 7, 5, 10, 0), url="u2")
        with patch(
            "graph.slack_batch_filter.fetch_unfiltered_communications", AsyncMock(return_value=[m1, m2])
        ), patch(
            "graph.slack_batch_filter.get_project_profile", AsyncMock(return_value="프로필")
        ) as mock_profile, patch(
            "graph.slack_batch_filter.filter_messages", AsyncMock(return_value=[True, True])
        ) as mock_filter, patch(
            "graph.slack_batch_filter.mark_communication_llm_filtered", AsyncMock()
        ), patch(
            "graph.slack_batch_filter.delete_communication", AsyncMock()
        ):
            await run_slack_llm_filter()

        mock_profile.assert_awaited_once_with("p1")
        mock_filter.assert_awaited_once_with(["body", "body"], True, project_context="프로필")

    async def test_same_project_id_across_batches_fetches_profile_once(self):
        thread1 = _msg(conversation_id="c1", occurred_at=datetime(2026, 7, 5, 9, 0), url="u1")
        thread2 = _msg(conversation_id="c1", occurred_at=datetime(2026, 7, 5, 10, 0), url="u2")
        standalone = _msg(conversation_id="c3", channel="random", url="u3")
        with patch(
            "graph.slack_batch_filter.fetch_unfiltered_communications",
            AsyncMock(return_value=[thread1, thread2, standalone]),
        ), patch(
            "graph.slack_batch_filter.get_project_profile", AsyncMock(return_value="프로필")
        ) as mock_profile, patch(
            "graph.slack_batch_filter.filter_messages",
            AsyncMock(side_effect=[[True, True], [True]]),
        ), patch(
            "graph.slack_batch_filter.mark_communication_llm_filtered", AsyncMock()
        ), patch(
            "graph.slack_batch_filter.delete_communication", AsyncMock()
        ):
            await run_slack_llm_filter()

        mock_profile.assert_awaited_once_with("p1")

    async def test_no_target_does_not_fetch_profile(self):
        with patch(
            "graph.slack_batch_filter.fetch_unfiltered_communications", AsyncMock(return_value=[])
        ), patch(
            "graph.slack_batch_filter.get_project_profile", AsyncMock()
        ) as mock_profile:
            await run_slack_llm_filter()

        mock_profile.assert_not_called()


if __name__ == "__main__":
    unittest.main()
