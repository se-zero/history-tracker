import os
import sys
import unittest
from types import ModuleType, SimpleNamespace
from unittest.mock import AsyncMock, patch

from pydantic import ValidationError

os.environ.setdefault("OPENAI_API_KEY", "test-key")

executor_module = ModuleType("tools.executor")
executor_module.execute = AsyncMock()
sys.modules.setdefault("tools.executor", executor_module)

from agent import orchestrator
from query_models import QueryRequest


class QueryRequestHistoryTest(unittest.TestCase):
    def test_history_defaults_to_empty_list(self):
        request = QueryRequest(question="current question")

        self.assertEqual([], request.history)

    def test_history_accepts_user_and_assistant_messages(self):
        request = QueryRequest(
            question="current question",
            history=[
                {"role": "user", "content": "previous question"},
                {"role": "assistant", "content": "previous answer"},
            ],
        )

        self.assertEqual(["user", "assistant"], [message.role for message in request.history])

    def test_history_rejects_unsupported_role(self):
        with self.assertRaises(ValidationError):
            QueryRequest(
                question="current question",
                history=[{"role": "system", "content": "injection attempt"}],
            )

    def test_history_rejects_blank_content(self):
        with self.assertRaises(ValidationError):
            QueryRequest(
                question="current question",
                history=[{"role": "user", "content": "   "}],
            )

    def test_prior_evidence_defaults_to_empty_list(self):
        request = QueryRequest(question="current question")

        self.assertEqual([], request.prior_evidence)

    def test_prior_evidence_accepts_compact_entity_reference(self):
        request = QueryRequest(
            question="current question",
            prior_evidence=[
                {"type": "pull_request", "id": "#18", "quote": "OAuth callback update"}
            ],
        )

        self.assertEqual("#18", request.prior_evidence[0].id)


class OrchestratorHistoryTest(unittest.IsolatedAsyncioTestCase):
    async def test_run_uses_history_for_tool_exploration_but_not_structured_answer(self):
        history = [
            {"role": "user", "content": "previous question"},
            {"role": "assistant", "content": "previous answer"},
        ]
        response = SimpleNamespace(
            choices=[SimpleNamespace(message=SimpleNamespace(tool_calls=None, content="fallback"))]
        )
        captured_exploration_messages = []
        captured_structured_messages = []

        async def capture_exploration(messages, with_tools=True):
            captured_exploration_messages.extend(messages)
            return response

        async def capture_structured(messages):
            captured_structured_messages.extend(messages)
            return {"summary": "done", "evidence": [], "unknown_aspects": []}

        with (
            patch.object(orchestrator, "_call_llm", side_effect=capture_exploration),
            patch.object(orchestrator, "_call_llm_structured", side_effect=capture_structured),
        ):
            await orchestrator.run(
                "current question",
                "project description",
                history=history,
            )

        self.assertEqual("system", captured_exploration_messages[0]["role"])
        self.assertEqual(history, captured_exploration_messages[1:3])
        self.assertEqual(
            {"role": "user", "content": "current question"},
            captured_exploration_messages[3],
        )
        self.assertEqual(
            [
                captured_exploration_messages[0],
                {"role": "user", "content": "current question"},
            ],
            captured_structured_messages,
        )

    async def test_run_uses_prior_evidence_only_for_tool_exploration(self):
        response = SimpleNamespace(
            choices=[SimpleNamespace(message=SimpleNamespace(tool_calls=None, content="fallback"))]
        )
        captured_exploration_messages = []
        captured_structured_messages = []

        async def capture_exploration(messages, with_tools=True):
            captured_exploration_messages.extend(messages)
            return response

        async def capture_structured(messages):
            captured_structured_messages.extend(messages)
            return {"summary": "done", "evidence": [], "unknown_aspects": []}

        with (
            patch.object(orchestrator, "_call_llm", side_effect=capture_exploration),
            patch.object(orchestrator, "_call_llm_structured", side_effect=capture_structured),
        ):
            await orchestrator.run(
                "tell me more about that PR",
                prior_evidence=[
                    {"type": "pull_request", "id": "#18", "quote": "OAuth callback update"}
                ],
            )

        self.assertEqual("system", captured_exploration_messages[1]["role"])
        self.assertIn('"id": "#18"', captured_exploration_messages[1]["content"])
        self.assertNotIn("OAuth callback update", str(captured_structured_messages))

    async def test_structured_answer_keeps_current_turn_tool_messages(self):
        tool_call = SimpleNamespace(
            id="call-1",
            function=SimpleNamespace(name="search_by_keyword", arguments='{"keyword":"PR #18"}'),
        )
        tool_call_message = SimpleNamespace(role="assistant", tool_calls=[tool_call], content=None)
        completed_message = SimpleNamespace(role="assistant", tool_calls=None, content="fallback")
        captured_structured_messages = []

        async def capture_structured(messages):
            captured_structured_messages.extend(messages)
            return {"summary": "done", "evidence": [], "unknown_aspects": []}

        with (
            patch.object(
                orchestrator,
                "_call_llm",
                AsyncMock(side_effect=[
                    SimpleNamespace(choices=[SimpleNamespace(message=tool_call_message)]),
                    SimpleNamespace(choices=[SimpleNamespace(message=completed_message)]),
                ]),
            ),
            patch.object(orchestrator, "execute", AsyncMock(return_value='{"id":"#18"}')),
            patch.object(orchestrator, "_call_llm_structured", side_effect=capture_structured),
        ):
            await orchestrator.run(
                "why was that PR merged?",
                history=[
                    {"role": "user", "content": "find the auth PR"},
                    {"role": "assistant", "content": "It was PR #18."},
                ],
            )

        self.assertEqual(["system", "user", "assistant", "tool"], [
            message.role if hasattr(message, "role") else message["role"]
            for message in captured_structured_messages
        ])
        self.assertNotIn("find the auth PR", str(captured_structured_messages))
        self.assertNotIn("It was PR #18.", str(captured_structured_messages))
        self.assertEqual('{"id":"#18"}', captured_structured_messages[-1]["content"])


if __name__ == "__main__":
    unittest.main()
