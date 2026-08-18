"""answer_mode 판정과 범용 조회 호출 상한 (오프라인).

계약 두 가지를 고정한다.
- answer_mode는 **서버가** 호출된 도구로 판정한다 (LLM 자기신고 아님).
  범용 조회(run_graph_query)가 답에 기여했으면 exploratory, 아니면 grounded.
- run_graph_query는 질의당 _MAX_GRAPH_QUERY_CALLS회까지만 실행된다. 중복 호출 가드는
  (도구명, 인자) 정확 일치라 쿼리를 미세하게 바꾸면 뚫리므로 별도 상한이 필요하다.

LLM 호출은 test_focus_evidence.py와 동일하게 모킹한다.
"""

import json
import os
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

os.environ.setdefault("OPENAI_API_KEY", "test-key")

# tools.executor를 가짜 모듈로 덮지 않는다 — 도구 실행은 orchestrator.execute 패치로 막고,
# sys.modules를 건드리면 같은 세션의 뒤 테스트(test_executor_truncation)가 진짜 모듈 대신
# 스텁을 보게 된다(수집 순서 의존 버그).
from agent import orchestrator


def _tool_call(call_id: str, name: str, arguments: dict):
    return SimpleNamespace(
        id=call_id,
        function=SimpleNamespace(name=name, arguments=json.dumps(arguments)),
    )


def _message(tool_calls=None, content="fallback"):
    return SimpleNamespace(
        choices=[SimpleNamespace(message=SimpleNamespace(tool_calls=tool_calls, content=content))]
    )


_STRUCTURED = {"summary": "요약", "evidence": [], "unknown_aspects": []}


class AnswerModeTest(unittest.IsolatedAsyncioTestCase):
    async def _run(self, responses, structured=_STRUCTURED, execute_result="[]"):
        """responses를 순서대로 돌려주는 LLM으로 orchestrator.run을 돌린다."""
        pending = list(responses)

        async def fake_llm(messages, with_tools=True):
            return pending.pop(0)

        async def fake_structured(messages, debug=None):
            return None if structured is None else dict(structured)

        execute_mock = AsyncMock(return_value=execute_result)
        with (
            patch.object(orchestrator, "_call_llm", side_effect=fake_llm),
            patch.object(orchestrator, "_call_llm_structured", side_effect=fake_structured),
            patch.object(orchestrator, "execute", execute_mock),
        ):
            answer, result = await orchestrator.run("질문")
        return answer, result, execute_mock

    async def test_specialized_tools_only_is_grounded(self):
        responses = [
            _message([_tool_call("1", "get_issue_context", {"issue_key": "HT-1"})]),
            _message(),
        ]
        answer, structured, _ = await self._run(responses)
        self.assertEqual("grounded", structured["answer_mode"])
        self.assertNotIn("⚠️", answer)

    async def test_graph_query_marks_exploratory(self):
        responses = [
            _message([_tool_call("1", "run_graph_query", {"cypher": "MATCH (i:Issue) RETURN i.issue_key", "purpose": "p"})]),
            _message(),
        ]
        answer, structured, _ = await self._run(responses)
        self.assertEqual("exploratory", structured["answer_mode"])
        # 배너는 답변 맨 앞에 선다 — 사용자가 요약보다 먼저 본다.
        self.assertTrue(answer.startswith(orchestrator._EXPLORATORY_NOTICE))

    async def test_exploratory_notice_survives_structured_failure(self):
        """structured 생성이 실패해 자유 텍스트로 떨어져도 경고는 남아야 한다."""
        responses = [
            _message([_tool_call("1", "run_graph_query", {"cypher": "MATCH (i:Issue) RETURN i", "purpose": "p"})]),
            _message(content="자유 텍스트 답변"),
        ]
        answer, structured, _ = await self._run(responses, structured=None)
        self.assertIsNone(structured)
        self.assertIn("⚠️", answer)
        self.assertIn("자유 텍스트 답변", answer)

    async def test_graph_query_call_cap(self):
        """상한을 넘는 호출은 실행되지 않고 LLM에게 사유가 전달된다."""
        over = orchestrator._MAX_GRAPH_QUERY_CALLS + 2
        calls = [
            _tool_call(str(n), "run_graph_query", {"cypher": f"MATCH (i:Issue) RETURN i.issue_key LIMIT {n}", "purpose": "p"})
            for n in range(over)
        ]
        responses = [_message(calls), _message()]
        _, structured, execute_mock = await self._run(responses)

        self.assertEqual(orchestrator._MAX_GRAPH_QUERY_CALLS, execute_mock.await_count)
        self.assertEqual("exploratory", structured["answer_mode"])

    async def test_cap_does_not_apply_to_specialized_tools(self):
        calls = [
            _tool_call(str(n), "get_changeset_context", {"hash": f"abc{n}"})
            for n in range(6)
        ]
        responses = [_message(calls), _message()]
        _, structured, execute_mock = await self._run(responses)

        self.assertEqual(6, execute_mock.await_count)
        self.assertEqual("grounded", structured["answer_mode"])


if __name__ == "__main__":
    unittest.main()
