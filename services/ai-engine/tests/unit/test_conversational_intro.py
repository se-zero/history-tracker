"""그래프와 무관한 말(인사·감사·잡담)은 Neo4j를 검색하지 않고 intro 답을 돌린다.

계약:
- 문장 전체가 인사/감사면 LLM 없이 고정 문장. reply_kind=intro, evidence/unknown 빈 배열.
- 잡담은 분류용 LLM을 따로 부르지 않고, 도구 루프 첫 _call_llm 의 respond_conversational
  한 번으로 kind·ack를 받아 서버가 문장을 조립한다. execute·structured LLM은 타지 않는다.
- 그래프 도구와 섞이거나, 이미 그래프 도구를 실행한 뒤면 intro로 바꾸지 않는다.
모킹 패턴은 test_answer_mode.py / test_query_debug.py를 따른다.
"""

import json
import os
import unittest
from contextlib import contextmanager
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

os.environ.setdefault("OPENAI_API_KEY", "test-key")

from agent import orchestrator


_GREETING = (
    "안녕하세요. 저는 whycode예요. 이 프로젝트에서 코드가 왜 그렇게 바뀌었는지 같이 찾아볼게요. "
    "무엇을 도와드릴까요?"
)
_THANKS = "도움이 됐다니 다행이에요. 또 무엇을 도와드릴까요?"
_SMALLTALK_FALLBACK = "그렇게요. 무엇을 도와드릴까요?"
_REDIRECT = (
    "그 내용은 이 프로젝트 기록 밖이에요. 코드가 왜 그렇게 바뀌었는지는 같이 찾아볼게요. "
    "무엇을 도와드릴까요?"
)

_FAIL = AssertionError("호출되면 안 되는 LLM/도구 경로")


def _tool_call(call_id: str, name: str, arguments: dict):
    return SimpleNamespace(
        id=call_id,
        function=SimpleNamespace(name=name, arguments=json.dumps(arguments)),
    )


def _message(tool_calls=None, content="fallback"):
    return SimpleNamespace(
        choices=[SimpleNamespace(message=SimpleNamespace(tool_calls=tool_calls, content=content))]
    )


def _assert_intro(test, answer, structured, expected_text):
    test.assertEqual(expected_text, answer)
    test.assertEqual(expected_text, structured["summary"])
    test.assertEqual("intro", structured["reply_kind"])
    test.assertEqual("grounded", structured["answer_mode"])
    test.assertEqual([], structured["evidence"])
    test.assertEqual([], structured["unknown_aspects"])


class GreetingThanksShortcutTest(unittest.IsolatedAsyncioTestCase):
    @contextmanager
    def _forbidden(self):
        with (
            patch.object(orchestrator, "_rewrite_question", AsyncMock(side_effect=_FAIL)),
            patch.object(orchestrator, "_call_llm", AsyncMock(side_effect=_FAIL)),
            patch.object(orchestrator, "_call_llm_structured", AsyncMock(side_effect=_FAIL)),
            patch.object(orchestrator, "execute", AsyncMock(side_effect=_FAIL)),
        ):
            yield

    async def test_hello_skips_llm(self):
        with self._forbidden():
            answer, structured = await orchestrator.run("안녕하세요")
        _assert_intro(self, answer, structured, _GREETING)

    async def test_thanks_skips_llm(self):
        with self._forbidden():
            answer, structured = await orchestrator.run("감사합니다")
        _assert_intro(self, answer, structured, _THANKS)

    async def test_hello_with_trailing_question_mark(self):
        with self._forbidden():
            answer, structured = await orchestrator.run("안녕?")
        _assert_intro(self, answer, structured, _GREETING)


class ShortcutDoesNotFireTest(unittest.IsolatedAsyncioTestCase):
    async def _assert_enters_tool_loop(self, question, **run_kwargs):
        llm = AsyncMock(return_value=_message())
        structured_llm = AsyncMock(
            return_value={"summary": "그래프 답", "evidence": [], "unknown_aspects": []}
        )
        with (
            patch.object(orchestrator, "_call_llm", llm),
            patch.object(orchestrator, "_call_llm_structured", structured_llm),
            patch.object(orchestrator, "execute", AsyncMock(side_effect=_FAIL)),
        ):
            answer, structured = await orchestrator.run(question, **run_kwargs)
        llm.assert_awaited()
        self.assertNotEqual("intro", structured.get("reply_kind"))
        self.assertEqual("그래프 답", answer)

    async def test_issue_question_does_not_take_shortcut(self):
        await self._assert_enters_tool_loop("HT-12 왜 닫혔어?")

    async def test_greeting_prefix_with_issue_does_not_take_shortcut(self):
        await self._assert_enters_tool_loop("안녕, HT-12 왜 닫혔어?")

    async def test_focus_evidence_skips_hello_shortcut(self):
        await self._assert_enters_tool_loop(
            "안녕",
            focus_evidence=[{"type": "issue", "id": "HT-12"}],
        )


class ConversationalToolInterceptTest(unittest.IsolatedAsyncioTestCase):
    async def _run(self, responses, structured=None, execute_side_effect=_FAIL):
        pending = list(responses)

        async def fake_llm(messages, with_tools=True):
            if not pending:
                raise AssertionError("도구 루프가 한 번 더 LLM을 호출했다")
            return pending.pop(0)

        execute_mock = AsyncMock(side_effect=execute_side_effect)
        if structured is None:
            structured_mock = AsyncMock(side_effect=_FAIL)
        else:
            structured_mock = AsyncMock(return_value=dict(structured))
        with (
            patch.object(orchestrator, "_call_llm", side_effect=fake_llm),
            patch.object(orchestrator, "_call_llm_structured", structured_mock),
            patch.object(orchestrator, "execute", execute_mock),
        ):
            answer, result = await orchestrator.run("오늘 날씨 좋다")
        return answer, result, execute_mock, structured_mock

    async def test_smalltalk_uses_ack_and_skips_graph(self):
        ack = "오늘 날씨가 좋네요."
        answer, structured, execute_mock, structured_mock = await self._run(
            [_message([_tool_call("1", "respond_conversational", {"kind": "smalltalk", "ack": ack})])],
        )
        _assert_intro(self, answer, structured, f"{ack} 무엇을 도와드릴까요?")
        execute_mock.assert_not_awaited()
        structured_mock.assert_not_awaited()

    async def test_greeting_kind_discards_model_ack(self):
        answer, structured, execute_mock, structured_mock = await self._run(
            [_message([_tool_call("1", "respond_conversational", {
                "kind": "greeting",
                "ack": "안녕이라고 해볼게요.",
            })])],
        )
        _assert_intro(self, answer, structured, _GREETING)
        execute_mock.assert_not_awaited()
        structured_mock.assert_not_awaited()

    async def test_smalltalk_fallback_when_ack_unusable(self):
        cases = [
            {"kind": "smalltalk"},
            {"kind": "smalltalk", "ack": ""},
            {"kind": "smalltalk", "ack": "가" * 121},
            {"kind": "smalltalk", "ack": "한 줄\n두 줄"},
        ]
        for args in cases:
            with self.subTest(args=args):
                answer, structured, execute_mock, structured_mock = await self._run(
                    [_message([_tool_call("1", "respond_conversational", args)])],
                )
                _assert_intro(self, answer, structured, _SMALLTALK_FALLBACK)
                execute_mock.assert_not_awaited()
                structured_mock.assert_not_awaited()

    async def test_redirect_kind_uses_fixed_sentence(self):
        answer, structured, execute_mock, structured_mock = await self._run(
            [_message([_tool_call("1", "respond_conversational", {"kind": "redirect"})])],
        )
        _assert_intro(self, answer, structured, _REDIRECT)
        execute_mock.assert_not_awaited()
        structured_mock.assert_not_awaited()


class ConversationalDoesNotOverrideGraphTest(unittest.IsolatedAsyncioTestCase):
    async def test_mixed_with_graph_tool_continues_search(self):
        graph_structured = {"summary": "HT-1 요약", "evidence": [], "unknown_aspects": []}
        execute_mock = AsyncMock(return_value="[]")
        responses = [
            _message([
                _tool_call("1", "get_issue_context", {"issue_key": "HT-1"}),
                _tool_call("2", "respond_conversational", {"kind": "smalltalk", "ack": "잡담"}),
            ]),
            _message(),
        ]
        pending = list(responses)

        async def fake_llm(messages, with_tools=True):
            return pending.pop(0)

        with (
            patch.object(orchestrator, "_call_llm", side_effect=fake_llm),
            patch.object(orchestrator, "_call_llm_structured", AsyncMock(return_value=dict(graph_structured))),
            patch.object(orchestrator, "execute", execute_mock),
        ):
            answer, structured = await orchestrator.run("HT-1 왜 닫혔어?")

        self.assertEqual(["get_issue_context"], [c.args[0] for c in execute_mock.await_args_list])
        self.assertNotEqual("intro", structured.get("reply_kind"))
        self.assertEqual("HT-1 요약", structured["summary"])
        self.assertEqual("HT-1 요약", answer)

    async def test_conversational_after_graph_tool_is_not_intro(self):
        graph_structured = {
            "summary": "이 기록에서는 그에 해당하는 항목을 찾지 못했어요",
            "evidence": [],
            "unknown_aspects": ["HT-999"],
        }
        execute_mock = AsyncMock(return_value="[]")
        responses = [
            _message([_tool_call("1", "get_issue_context", {"issue_key": "HT-999"})]),
            _message([_tool_call("2", "respond_conversational", {"kind": "greeting", "ack": "안녕"})]),
            _message(),
        ]
        pending = list(responses)

        async def fake_llm(messages, with_tools=True):
            return pending.pop(0)

        with (
            patch.object(orchestrator, "_call_llm", side_effect=fake_llm),
            patch.object(orchestrator, "_call_llm_structured", AsyncMock(return_value=dict(graph_structured))),
            patch.object(orchestrator, "execute", execute_mock),
        ):
            answer, structured = await orchestrator.run("HT-999 왜 닫혔어?")

        self.assertEqual(1, execute_mock.await_count)
        self.assertEqual("get_issue_context", execute_mock.await_args_list[0].args[0])
        self.assertNotEqual("intro", structured.get("reply_kind"))
        self.assertEqual(graph_structured["summary"], structured["summary"])
        self.assertIn(graph_structured["summary"], answer)
        self.assertNotEqual(_GREETING, answer)


if __name__ == "__main__":
    unittest.main()
