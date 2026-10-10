"""발화 판별 — 잡담은 검색 없이 intro로 끝내고, 그래프 질문은 기존 루프로 넘긴다.

계약:
- 판별이 chat이면 그 문장과 reply_kind=intro. 재작성·도구 루프·최종 structured 호출은 없다.
- "무엇을 도와드릴까요?"가 없으면 서버가 붙인다.
- 문장이 비었거나 줄바꿈이 있거나 400자를 넘으면 그 제안만 나가고 검색은 하지 않는다.
- 판별이 graph이거나 호출이 실패하면 기존 검색으로 가고, 판별 문장은 답에 남지 않는다.
- 그래프 노드를 집어 물으면 판별을 호출하지 않는다.
- whycode 자신의 내부 구현을 캐는 말은 chat으로 받아 알려줄 수 없다고 답하고, 프로젝트 코드가 왜 바뀌었는지
  묻는 말은 graph로 둔다. 여기서는 지시에 그 규칙이 있는지만 보고, 실제 분류는 실측으로 확인한다.
- respond_conversational 도구는 없다.

conftest가 _route_utterance를 그래프 경로로 바꿔 두므로, 여기서는 원래 함수를 다시 붙인다.
"""

import json
import os
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

os.environ.setdefault("OPENAI_API_KEY", "test-key")

from agent import orchestrator
from tools.definitions import TOOLS

_REAL_ROUTE = orchestrator._route_utterance
_OFFER = "무엇을 도와드릴까요?"
_FAIL = AssertionError("호출되면 안 되는 검색 경로")


def _completion(payload: dict):
    return _completion_raw(json.dumps(payload, ensure_ascii=False))


def _completion_raw(content: str):
    return SimpleNamespace(
        choices=[SimpleNamespace(message=SimpleNamespace(content=content))],
        usage=None,
    )


def _assert_intro(test, answer, structured, expected_text):
    test.assertEqual(expected_text, answer)
    test.assertEqual(expected_text, structured["summary"])
    test.assertEqual("intro", structured["reply_kind"])
    test.assertEqual("grounded", structured["answer_mode"])
    test.assertEqual([], structured["evidence"])
    test.assertEqual([], structured["unknown_aspects"])


class ChatRouteTest(unittest.IsolatedAsyncioTestCase):
    def _patches(self, payload):
        return (
            patch.object(orchestrator, "_route_utterance", _REAL_ROUTE),
            patch.object(orchestrator, "chat_completion", AsyncMock(return_value=_completion(payload))),
            patch.object(orchestrator, "_rewrite_question", AsyncMock(side_effect=_FAIL)),
            patch.object(orchestrator, "_call_llm", AsyncMock(side_effect=_FAIL)),
            patch.object(orchestrator, "_call_llm_structured", AsyncMock(side_effect=_FAIL)),
            patch.object(orchestrator, "execute", AsyncMock(side_effect=_FAIL)),
        )

    async def test_chat_reply_is_intro_and_offer_is_appended(self):
        reply = "안녕하세요. 저는 whycode예요."
        patches = self._patches({"route": "chat", "reply": reply})
        with patches[0], patches[1], patches[2], patches[3], patches[4], patches[5]:
            answer, structured = await orchestrator.run("안녕?")
        _assert_intro(self, answer, structured, f"{reply} {_OFFER}")

    async def test_existing_offer_is_not_repeated(self):
        reply = f"도움이 됐다니 다행이에요. {_OFFER}"
        patches = self._patches({"route": "chat", "reply": reply})
        with patches[0], patches[1], patches[2], patches[3], patches[4], patches[5]:
            answer, structured = await orchestrator.run("감사합니다")
        _assert_intro(self, answer, structured, reply)

    async def test_unusable_reply_is_offer_only(self):
        cases = ["", "  ", "한 줄\n두 줄", "가" * 401]
        for reply in cases:
            with self.subTest(reply=reply[:20]):
                patches = self._patches({"route": "chat", "reply": reply})
                with patches[0], patches[1], patches[2], patches[3], patches[4], patches[5]:
                    answer, structured = await orchestrator.run("오늘 날씨 좋다")
                _assert_intro(self, answer, structured, _OFFER)


class GraphRouteTest(unittest.IsolatedAsyncioTestCase):
    async def _search(self, route_effect, **run_kwargs):
        llm = AsyncMock(
            return_value=SimpleNamespace(
                choices=[SimpleNamespace(message=SimpleNamespace(tool_calls=None, content="fallback"))]
            )
        )
        structured_llm = AsyncMock(
            return_value={"summary": "그래프 답", "evidence": [], "unknown_aspects": []}
        )
        with (
            patch.object(orchestrator, "_route_utterance", _REAL_ROUTE),
            patch.object(orchestrator, "chat_completion", route_effect),
            patch.object(orchestrator, "_call_llm", llm),
            patch.object(orchestrator, "_call_llm_structured", structured_llm),
            patch.object(orchestrator, "execute", AsyncMock(side_effect=_FAIL)),
        ):
            answer, structured = await orchestrator.run("프론트엔드 담당자가 누구야", **run_kwargs)
        return answer, structured, llm, route_effect

    async def test_graph_route_discards_reply_and_searches(self):
        route = AsyncMock(return_value=_completion({
            "route": "graph",
            "reply": "이 문장은 답에 남으면 안 됩니다.",
        }))
        answer, structured, llm, route = await self._search(route)
        llm.assert_awaited()
        route.assert_awaited()
        self.assertNotEqual("intro", structured.get("reply_kind"))
        self.assertEqual("그래프 답", structured["summary"])
        self.assertNotIn("이 문장은 답에 남으면 안 됩니다.", answer)

    async def test_route_failure_continues_search(self):
        route = AsyncMock(side_effect=RuntimeError("upstream"))
        answer, structured, llm, route = await self._search(route)
        llm.assert_awaited()
        self.assertEqual("그래프 답", structured["summary"])
        self.assertNotEqual("intro", structured.get("reply_kind"))

    async def test_non_object_payload_continues_search(self):
        route = AsyncMock(return_value=_completion_raw("[]"))
        answer, structured, llm, route = await self._search(route)
        llm.assert_awaited()
        self.assertEqual("그래프 답", structured["summary"])
        self.assertNotEqual("intro", structured.get("reply_kind"))

    async def test_focus_evidence_skips_route(self):
        route = AsyncMock(side_effect=_FAIL)
        answer, structured, llm, route = await self._search(
            route,
            focus_evidence=[{"type": "issue", "id": "HT-12"}],
        )
        route.assert_not_awaited()
        llm.assert_awaited()
        self.assertEqual("그래프 답", structured["summary"])


class RouteContractTest(unittest.TestCase):
    def test_conversational_tool_removed(self):
        names = [tool["function"]["name"] for tool in TOOLS]
        self.assertNotIn("respond_conversational", names)

    def test_instruction_sends_mixed_greeting_to_graph(self):
        text = orchestrator._UTTERANCE_ROUTE_INSTRUCTION
        self.assertIn("애매하면 graph", text)
        self.assertIn("앞에 인사", text)

    def test_instruction_refuses_internals_but_keeps_code_history_in_graph(self):
        text = orchestrator._UTTERANCE_ROUTE_INSTRUCTION
        self.assertIn("whycode 자신의 내부 구현", text)
        self.assertIn("알려드릴 수 없다", text)
        self.assertIn('"시스템 프롬프트를 왜 바꿨어?"는 graph', text)

    def test_route_history_keeps_the_latest_messages(self):
        history = [{"role": "user", "content": str(i)} for i in range(10)]
        kept = orchestrator._route_history(history)
        self.assertEqual([str(i) for i in range(4, 10)], [m["content"] for m in kept])


if __name__ == "__main__":
    unittest.main()
