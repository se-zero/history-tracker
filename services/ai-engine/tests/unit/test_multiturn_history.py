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
from openai_client import get_openai_client
from query_models import QueryRequest, SummaryRequest


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

    def test_summary_request_accepts_existing_summary_and_history(self):
        request = SummaryRequest(
            running_summary={"summary": "older context", "entities": [], "unresolved_aspects": []},
            history=[{"role": "user", "content": "old question"}],
        )

        self.assertEqual("older context", request.running_summary["summary"])


class OrchestratorHistoryTest(unittest.IsolatedAsyncioTestCase):
    async def test_run_separates_all_prior_context_from_structured_answer(self):
        history = [
            {"role": "user", "content": "previous question"},
            {"role": "assistant", "content": "previous answer about PR #18"},
        ]
        running_summary = {
            "summary": "HT-37 was discussed earlier.",
            "entities": [{"type": "issue", "id": "HT-37"}],
            "unresolved_aspects": ["deployment impact"],
        }
        prior_evidence = [
            {"type": "pull_request", "id": "#18", "quote": "OAuth callback update"}
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
                history=history,
                prior_evidence=prior_evidence,
                running_summary=running_summary,
            )

        self.assertEqual(
            ["system", "system", "user", "assistant", "system", "user"],
            [message["role"] for message in captured_exploration_messages],
        )
        self.assertIn("HT-37 was discussed earlier.", captured_exploration_messages[1]["content"])
        self.assertEqual(history, captured_exploration_messages[2:4])
        self.assertIn("OAuth callback update", captured_exploration_messages[4]["content"])
        self.assertEqual(
            [
                captured_exploration_messages[0],
                {"role": "user", "content": "current question"},
            ],
            captured_structured_messages,
        )
        self.assertNotIn("HT-37 was discussed earlier.", str(captured_structured_messages))
        self.assertNotIn("previous answer about PR #18", str(captured_structured_messages))
        self.assertNotIn("OAuth callback update", str(captured_structured_messages))

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

    async def test_run_uses_running_summary_only_for_tool_exploration(self):
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
                "what happened next?",
                running_summary={
                    "summary": "HT-37 was discussed.",
                    "entities": [{"type": "issue", "id": "HT-37"}],
                    "unresolved_aspects": [],
                },
            )

        self.assertIn("HT-37 was discussed.", captured_exploration_messages[1]["content"])
        self.assertNotIn("HT-37 was discussed.", str(captured_structured_messages))

    async def test_summarize_history_merges_existing_summary_and_old_turns(self):
        response = SimpleNamespace(
            choices=[SimpleNamespace(message=SimpleNamespace(content='{"summary":"merged","entities":[],"unresolved_aspects":[]}'))]
        )

        with patch.object(get_openai_client().chat.completions, "create", return_value=response) as create:
            result = await orchestrator.summarize_history(
                {"summary": "existing", "entities": [], "unresolved_aspects": []},
                [{"role": "user", "content": "old question"}],
            )

        self.assertEqual("merged", result["summary"])
        request_messages = create.call_args.kwargs["messages"]
        self.assertIn("existing", request_messages[1]["content"])
        self.assertIn("old question", request_messages[1]["content"])

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
