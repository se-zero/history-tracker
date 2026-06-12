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


class OrchestratorHistoryTest(unittest.IsolatedAsyncioTestCase):
    async def test_run_places_history_between_system_and_current_question(self):
        history = [
            {"role": "user", "content": "previous question"},
            {"role": "assistant", "content": "previous answer"},
        ]
        response = SimpleNamespace(
            choices=[SimpleNamespace(message=SimpleNamespace(tool_calls=None, content="fallback"))]
        )
        captured_messages = []

        async def capture_structured(messages):
            captured_messages.extend(messages)
            return {"summary": "done", "evidence": [], "unknown_aspects": []}

        with (
            patch.object(orchestrator, "_call_llm", AsyncMock(return_value=response)),
            patch.object(orchestrator, "_call_llm_structured", side_effect=capture_structured),
        ):
            await orchestrator.run("current question", "project description", history)

        self.assertEqual("system", captured_messages[0]["role"])
        self.assertEqual(history, captured_messages[1:3])
        self.assertEqual(
            {"role": "user", "content": "current question"},
            captured_messages[3],
        )


if __name__ == "__main__":
    unittest.main()
