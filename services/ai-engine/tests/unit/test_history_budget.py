"""세션 메모리 개선 1단계(AE-1) — history 글자 수 예산 절단 + 요약 모델 분리 단위 테스트.

모킹 패턴은 test_multiturn_history.py를 그대로 따른다(모듈 상단 tools.executor 스텁,
patch.object(orchestrator, "_call_llm", ...) / "_call_llm_structured").
"""

import os
import sys
import unittest
from types import ModuleType, SimpleNamespace
from unittest.mock import AsyncMock, patch

os.environ.setdefault("OPENAI_API_KEY", "test-key")

executor_module = ModuleType("tools.executor")
executor_module.execute = AsyncMock()
sys.modules.setdefault("tools.executor", executor_module)

from agent import orchestrator
from openai_client import get_openai_client


class SplitTurnsTest(unittest.TestCase):
    def test_splits_alternating_user_assistant_into_turns(self):
        history = [
            {"role": "user", "content": "q1"},
            {"role": "assistant", "content": "a1"},
            {"role": "user", "content": "q2"},
            {"role": "assistant", "content": "a2"},
        ]

        turns = orchestrator._split_turns(history)

        self.assertEqual(
            [[history[0], history[1]], [history[2], history[3]]],
            turns,
        )

    def test_groups_leading_orphan_assistant_messages_into_one_turn(self):
        history = [
            {"role": "assistant", "content": "orphan1"},
            {"role": "assistant", "content": "orphan2"},
            {"role": "user", "content": "q1"},
            {"role": "assistant", "content": "a1"},
        ]

        turns = orchestrator._split_turns(history)

        self.assertEqual(
            [[history[0], history[1]], [history[2], history[3]]],
            turns,
        )


class RecentTurnsWithinBudgetTest(unittest.TestCase):
    def test_drops_oldest_turns_that_exceed_budget(self):
        turns = [
            [{"role": "user", "content": "x" * 100}, {"role": "assistant", "content": "y" * 100}],
            [{"role": "user", "content": "x" * 100}, {"role": "assistant", "content": "y" * 100}],
            [{"role": "user", "content": "x" * 100}, {"role": "assistant", "content": "y" * 100}],
        ]

        selected = orchestrator._recent_turns_within_budget(turns, 300)

        self.assertEqual([turns[2]], selected)

    def test_includes_latest_turn_even_when_it_alone_exceeds_budget(self):
        turns = [[{"role": "user", "content": "z" * 1000}]]

        selected = orchestrator._recent_turns_within_budget(turns, 10)

        self.assertEqual(turns, selected)


class OrchestratorHistoryBudgetTest(unittest.IsolatedAsyncioTestCase):
    async def test_run_truncates_old_turns_but_keeps_recent_turn_intact(self):
        history = [
            {"role": "user", "content": "old question " * 20},
            {"role": "assistant", "content": "old answer " * 20},
            {"role": "user", "content": "recent question marker"},
            {"role": "assistant", "content": "recent answer marker"},
        ]
        response = SimpleNamespace(
            choices=[SimpleNamespace(message=SimpleNamespace(tool_calls=None, content="fallback"))]
        )
        captured_exploration_messages = []

        async def capture_exploration(messages, with_tools=True):
            captured_exploration_messages.extend(messages)
            return response

        async def capture_structured(messages, debug=None):
            return {"summary": "done", "evidence": [], "unknown_aspects": []}

        with (
            patch.object(orchestrator, "_call_llm", side_effect=capture_exploration),
            patch.object(orchestrator, "_call_llm_structured", side_effect=capture_structured),
            patch.object(orchestrator, "_QUERY_HISTORY_BUDGET_CHARS", 60),
            patch.object(orchestrator, "_rewrite_question", AsyncMock(return_value=None)),
        ):
            await orchestrator.run("current question", history=history)

        transcript = str(captured_exploration_messages)
        self.assertIn("recent question marker", transcript)
        self.assertIn("recent answer marker", transcript)
        self.assertNotIn("old question", transcript)
        self.assertNotIn("old answer", transcript)
        # 경계 턴이 부분 절단되지 않는지 — 최근 턴은 user/assistant가 통째로 남아야 한다.
        self.assertIn(history[2], captured_exploration_messages)
        self.assertIn(history[3], captured_exploration_messages)

    async def test_run_includes_latest_turn_even_when_it_alone_exceeds_budget(self):
        history = [{"role": "user", "content": "z" * 1000}]
        response = SimpleNamespace(
            choices=[SimpleNamespace(message=SimpleNamespace(tool_calls=None, content="fallback"))]
        )
        captured_exploration_messages = []

        async def capture_exploration(messages, with_tools=True):
            captured_exploration_messages.extend(messages)
            return response

        async def capture_structured(messages, debug=None):
            return {"summary": "done", "evidence": [], "unknown_aspects": []}

        with (
            patch.object(orchestrator, "_call_llm", side_effect=capture_exploration),
            patch.object(orchestrator, "_call_llm_structured", side_effect=capture_structured),
            patch.object(orchestrator, "_QUERY_HISTORY_BUDGET_CHARS", 10),
            patch.object(orchestrator, "_rewrite_question", AsyncMock(return_value=None)),
        ):
            await orchestrator.run("current question", history=history)

        self.assertIn(history[0], captured_exploration_messages)

    async def test_summarize_history_uses_summary_model(self):
        response = SimpleNamespace(
            choices=[SimpleNamespace(message=SimpleNamespace(content='{"summary":"merged","entities":[],"unresolved_aspects":[]}'))]
        )

        with patch.object(get_openai_client().chat.completions, "create", return_value=response) as create:
            await orchestrator.summarize_history(
                {"summary": "existing", "entities": [], "unresolved_aspects": []},
                [{"role": "user", "content": "old question"}],
            )

        self.assertEqual(orchestrator._SUMMARY_MODEL, create.call_args.kwargs["model"])


if __name__ == "__main__":
    unittest.main()
