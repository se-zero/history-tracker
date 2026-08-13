"""세션 메모리 개선 2단계(AE-2) — 질문 재작성(_rewrite_question) 단위 테스트.

지시대명사·생략이 있는 후속 질문을 tool 루프 진입 전에 자립형으로 재작성하는 경로를 검증한다.
- run() 경유: 재작성 결과가 탐색 messages의 현재 질문에 원문과 병기되어 실리는지, 도구 실행에는
  자립형 단독으로 넘어가는지.
- _rewrite_question 자체: 실패·빈 history·changed=false 시 안전하게 None을 반환하는지.
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


class OrchestratorQuestionRewriteRunTest(unittest.IsolatedAsyncioTestCase):
    async def test_run_uses_rewritten_question_with_original_appended_in_exploration(self):
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
            patch.object(
                orchestrator,
                "_rewrite_question",
                AsyncMock(return_value="PR #34 이전 PR은?"),
            ),
        ):
            await orchestrator.run(
                "그 전 PR은 뭐야?",
                history=[
                    {"role": "user", "content": "PR #34 봤어?"},
                    {"role": "assistant", "content": "네, PR #34입니다."},
                ],
            )

        current_question_message = captured_exploration_messages[-1]
        self.assertEqual("user", current_question_message["role"])
        self.assertEqual(
            "PR #34 이전 PR은?\n\n(원문 질문: 그 전 PR은 뭐야?)",
            current_question_message["content"],
        )

    async def test_run_passes_standalone_rewritten_question_to_tool_executor(self):
        tool_call = SimpleNamespace(
            id="call-1",
            function=SimpleNamespace(name="search_by_keyword", arguments='{"keyword":"PR"}'),
        )
        tool_call_message = SimpleNamespace(role="assistant", tool_calls=[tool_call], content=None)
        completed_message = SimpleNamespace(role="assistant", tool_calls=None, content="fallback")
        execute_mock = AsyncMock(return_value='{"id":"#33"}')

        async def capture_structured(messages, debug=None):
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
            patch.object(orchestrator, "execute", execute_mock),
            patch.object(orchestrator, "_call_llm_structured", side_effect=capture_structured),
            patch.object(
                orchestrator,
                "_rewrite_question",
                AsyncMock(return_value="PR #34 이전 PR은?"),
            ),
        ):
            await orchestrator.run(
                "그 전 PR은 뭐야?",
                history=[
                    {"role": "user", "content": "PR #34 봤어?"},
                    {"role": "assistant", "content": "네, PR #34입니다."},
                ],
            )

        self.assertEqual("PR #34 이전 PR은?", execute_mock.call_args.kwargs["question"])


class RewriteQuestionTest(unittest.IsolatedAsyncioTestCase):
    async def test_swallows_chat_completion_exception_and_returns_none(self):
        with patch.object(orchestrator, "chat_completion", AsyncMock(side_effect=RuntimeError("boom"))):
            result = await orchestrator._rewrite_question(
                "그 전 PR은 뭐야?",
                [{"role": "user", "content": "PR #34 봤어?"}],
                None,
            )

        self.assertIsNone(result)

    async def test_empty_history_skips_llm_call(self):
        chat_completion_mock = AsyncMock()

        with patch.object(orchestrator, "chat_completion", chat_completion_mock):
            result = await orchestrator._rewrite_question("질문", [], None)

        chat_completion_mock.assert_not_called()
        self.assertIsNone(result)

    async def test_changed_false_returns_none(self):
        response = SimpleNamespace(
            choices=[SimpleNamespace(message=SimpleNamespace(
                content='{"changed": false, "rewritten_question": ""}'
            ))]
        )

        with patch.object(orchestrator, "chat_completion", AsyncMock(return_value=response)):
            result = await orchestrator._rewrite_question(
                "이미 자립적인 질문이야?",
                [{"role": "user", "content": "이전 질문"}],
                None,
            )

        self.assertIsNone(result)


if __name__ == "__main__":
    unittest.main()
