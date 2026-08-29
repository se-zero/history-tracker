"""include_debug 단위 테스트 (오프라인).

eval 러너가 run별 토큰 비용·도구 호출 트랜스크립트를 수집하는 경로를 검증한다.
- QueryRequest.include_debug 파싱 (기본 false).
- orchestrator.run(debug=dict)이 도구 호출(name/arguments/status/result)과
  LLM usage 합산을 debug dict에 누적하는지.
- debug 미지정(기본) 시 기존 동작이 그대로인지.
- /query 라우터가 include_debug=true일 때만 응답에 debug 키를 붙이는지.
LLM 호출은 test_multiturn_history.py와 동일하게 모킹한다.
"""

import json
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
from query_models import QueryRequest
from routers.query import query as query_endpoint


def _usage(prompt: int, completion: int) -> SimpleNamespace:
    return SimpleNamespace(
        prompt_tokens=prompt,
        completion_tokens=completion,
        total_tokens=prompt + completion,
    )


def _tool_call_response(name: str, arguments: str, usage: SimpleNamespace) -> SimpleNamespace:
    tool_call = SimpleNamespace(id="call_1", function=SimpleNamespace(name=name, arguments=arguments))
    return SimpleNamespace(
        choices=[SimpleNamespace(message=SimpleNamespace(tool_calls=[tool_call], content=None))],
        usage=usage,
    )


def _final_response(usage: SimpleNamespace) -> SimpleNamespace:
    return SimpleNamespace(
        choices=[SimpleNamespace(message=SimpleNamespace(tool_calls=None, content="fallback"))],
        usage=usage,
    )


class IncludeDebugRequestTest(unittest.TestCase):
    def test_include_debug_defaults_to_false(self):
        request = QueryRequest(question="q")
        self.assertFalse(request.include_debug)

    def test_include_debug_accepts_true(self):
        request = QueryRequest(question="q", include_debug=True)
        self.assertTrue(request.include_debug)


class OrchestratorDebugTest(unittest.IsolatedAsyncioTestCase):
    async def test_debug_collects_tool_calls_and_usage(self):
        responses = [
            _tool_call_response("search_nodes", '{"query": "DLQ"}', _usage(100, 20)),
            _final_response(_usage(200, 30)),
        ]

        async def fake_llm(messages, with_tools=True):
            return responses.pop(0)

        debug: dict = {}
        with (
            patch.object(orchestrator, "_call_llm", side_effect=fake_llm),
            patch.object(orchestrator, "execute", AsyncMock(return_value="검색 결과 원문")),
            patch.object(
                orchestrator,
                "_call_llm_structured",
                AsyncMock(return_value={"summary": "done", "evidence": [], "unknown_aspects": []}),
            ),
        ):
            answer, structured = await orchestrator.run("DLQ 왜 도입했어?", debug=debug)

        self.assertIsNotNone(structured)
        # 도구 호출 트랜스크립트: 이름·인자·결과 전문
        self.assertEqual(1, len(debug["tool_calls"]))
        call = debug["tool_calls"][0]
        self.assertEqual("search_nodes", call["name"])
        self.assertEqual({"query": "DLQ"}, call["arguments"])
        self.assertEqual("ok", call["status"])
        self.assertEqual("검색 결과 원문", call["result"])
        # usage: 탐색 2회 호출 합산
        self.assertEqual(300, debug["usage"]["prompt_tokens"])
        self.assertEqual(50, debug["usage"]["completion_tokens"])
        self.assertEqual(350, debug["usage"]["total_tokens"])
        self.assertEqual(2, debug["usage"]["llm_calls"])

    async def test_debug_records_parse_error_and_duplicate(self):
        responses = [
            _tool_call_response("search_nodes", "{잘못된 json", _usage(10, 1)),
            _tool_call_response("search_nodes", '{"query": "DLQ"}', _usage(10, 1)),
            _tool_call_response("search_nodes", '{"query": "DLQ"}', _usage(10, 1)),
            _final_response(_usage(10, 1)),
        ]

        async def fake_llm(messages, with_tools=True):
            return responses.pop(0)

        debug: dict = {}
        with (
            patch.object(orchestrator, "_call_llm", side_effect=fake_llm),
            patch.object(orchestrator, "execute", AsyncMock(return_value="결과")),
            patch.object(
                orchestrator,
                "_call_llm_structured",
                AsyncMock(return_value={"summary": "done", "evidence": [], "unknown_aspects": []}),
            ),
        ):
            await orchestrator.run("q", debug=debug)

        statuses = [c["status"] for c in debug["tool_calls"]]
        self.assertEqual(["args_parse_error", "ok", "duplicate"], statuses)
        # 파싱 실패 인자는 원문 문자열 그대로 보존한다
        self.assertEqual("{잘못된 json", debug["tool_calls"][0]["arguments"])

    async def test_no_debug_keeps_existing_behavior(self):
        # debug 미지정 시 아무 부작용 없이 기존 반환 계약 유지
        response = _final_response(_usage(10, 1))
        with (
            patch.object(orchestrator, "_call_llm", AsyncMock(return_value=response)),
            patch.object(
                orchestrator,
                "_call_llm_structured",
                AsyncMock(return_value={"summary": "done", "evidence": [], "unknown_aspects": []}),
            ),
        ):
            answer, structured = await orchestrator.run("그냥 질문")
        self.assertEqual("done", structured["summary"])


class StructuredCallUsageTest(unittest.IsolatedAsyncioTestCase):
    async def test_structured_call_records_usage(self):
        # _call_llm_structured도 usage를 debug에 합산해야 최종 답변 생성 비용이 잡힌다
        payload = json.dumps({"summary": "s", "evidence": [], "unknown_aspects": []})
        response = SimpleNamespace(
            choices=[SimpleNamespace(message=SimpleNamespace(content=payload))],
            usage=_usage(500, 40),
        )
        debug: dict = {}
        with patch.object(orchestrator, "chat_completion", AsyncMock(return_value=response)):
            structured = await orchestrator._call_llm_structured([{"role": "user", "content": "q"}], debug=debug)

        self.assertEqual("s", structured["summary"])
        self.assertEqual(540, debug["usage"]["total_tokens"])
        self.assertEqual(1, debug["usage"]["llm_calls"])


class QueryRouterDebugTest(unittest.IsolatedAsyncioTestCase):
    def _fake_run(self):
        async def fake_run(question, *, debug=None, **kwargs):
            if debug is not None:
                debug["tool_calls"] = [{"name": "t", "arguments": {}, "status": "ok", "result": "r"}]
                debug["usage"] = {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2, "llm_calls": 1}
            return "answer", {"summary": "s", "evidence": [], "unknown_aspects": []}

        return fake_run

    async def test_response_has_debug_only_when_requested(self):
        with patch.object(orchestrator, "run", side_effect=self._fake_run()):
            body = await query_endpoint(QueryRequest(question="q", project_id="p", include_debug=True))
        self.assertIn("debug", body)
        self.assertEqual(2, body["debug"]["usage"]["total_tokens"])
        self.assertEqual("answer", body["answer"])

    async def test_response_unchanged_without_flag(self):
        with patch.object(orchestrator, "run", side_effect=self._fake_run()):
            body = await query_endpoint(QueryRequest(question="q", project_id="p"))
        self.assertEqual({"answer", "structured"}, set(body.keys()))


if __name__ == "__main__":
    unittest.main()
