"""답변 언어 — 정한 언어를 답변 경로 끝까지 들고 가는지 (docs/answer-language.md §4-1·§4-2).

계약:
- 언어는 발화 판별 LLM이 정한다(language: ko | en | none). none·이상한 값·판별 실패·판별을 건너뜀(그래프 노드 지정)이면
  대비책 코드 판정(agent.language.decide_answer_language — 질문 → 대화의 사용자 질문 → 화면 언어 힌트 → 한국어)이 정한다.
  정한 언어와 근거는 debug.answer_language = {language, source}에 남고(source: router | question | history | ui | default),
  structured.language에 실린다. 응답 최상위에는 language를 두지 않는다.
- 판별은 (잡담이면 바로 돌려줄 답 | None, 판별이 정한 언어 | None)을 돌려준다. 잡담 문장에는 그 언어의 제안 문장
  ("무엇을 도와드릴까요?" / "How can I help?")을 붙인다. 판별이 언어를 정하지 못한 잡담은 한국어다 — 판별 지시가 그렇게 쓰게 한다.
- 영어면 첫 시스템 메시지가 본 지시 전체 영어판(_SYSTEM_PROMPT_EN)이고, 최종 지시·재작성 지시·원문 병기·고정 문구·근거 제목·
  내부 용어 치환이 영어판을 쓴다. 한국어 경로는 지금 그대로다.
- 요청의 ui_language는 관대하게 받는다 — 형식이 틀려도 422 없이 버리고, en-US 같은 값은 en으로 줄인다.

conftest가 판별을 (그래프, ko) 대역으로 바꿔 두므로, 판별 자체를 보는 테스트는 원래 함수를 다시 붙인다.
"""

import json
import os
import re
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

os.environ.setdefault("OPENAI_API_KEY", "test-key")

from agent import orchestrator, prompt_en
from query_models import QueryRequest
from routers.query import query as query_endpoint

# conftest의 대역이 붙기 전(수집 시점)에 원래 판별 함수를 잡아 둔다
_REAL_ROUTE = orchestrator._route_utterance
_HANGUL_RE = re.compile(r"[가-힣ㄱ-ㅎㅏ-ㅣ]")

_KO_OFFER = "무엇을 도와드릴까요?"
_EN_OFFER = "How can I help?"
_EN_NOTICE = (
    "> ⚠️ No dedicated lookup fits this question, so I explored the graph directly. "
    "Some evidence links may be weak, and parts of this answer may be inferred."
)
_EN_FALLBACK = "I couldn't generate an answer."
_EN_FAILURE = "Failed to generate an AI response. Please try again shortly."
_PLAIN_ANSWER = {"summary": "S", "evidence": [], "unknown_aspects": []}


def _completion(payload) -> SimpleNamespace:
    """chat_completion 응답 모양 — 판별·최종 답·재작성 호출이 content(JSON 문자열)만 읽는다."""
    content = json.dumps(payload, ensure_ascii=False)
    return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=content))], usage=None)


def _done(content: str | None = "fallback") -> SimpleNamespace:
    """도구 호출 없이 끝나는 탐색 응답."""
    message = SimpleNamespace(role="assistant", tool_calls=None, content=content)
    return SimpleNamespace(choices=[SimpleNamespace(message=message)], usage=None)


def _tool_call(name: str, arguments: str) -> SimpleNamespace:
    call = SimpleNamespace(id="call-1", function=SimpleNamespace(name=name, arguments=arguments))
    message = SimpleNamespace(role="assistant", tool_calls=[call], content=None)
    return SimpleNamespace(choices=[SimpleNamespace(message=message)], usage=None)


def _messages_of(mock: AsyncMock) -> list:
    call = mock.call_args
    return call.args[0] if call.args else call.kwargs["messages"]


class RouteContractLanguageTest(unittest.TestCase):
    def test_route_schema_requires_language(self):
        schema = orchestrator._UTTERANCE_ROUTE_SCHEMA["schema"]
        self.assertEqual({"type": "string", "enum": ["ko", "en", "none"]}, schema["properties"]["language"])
        self.assertIn("language", schema["required"])

    def test_route_instruction_has_language_rule_and_english_replies(self):
        text = orchestrator._UTTERANCE_ROUTE_INSTRUCTION
        for phrase in ["language", "none", _EN_OFFER, "I can't share that information."]:
            with self.subTest(phrase=phrase):
                self.assertIn(phrase, text)


class RouteLanguageTest(unittest.IsolatedAsyncioTestCase):
    async def _route(self, payload):
        with patch.object(orchestrator, "chat_completion", AsyncMock(return_value=_completion(payload))):
            return await _REAL_ROUTE("question", None)

    async def test_english_chat_reply_gets_english_offer(self):
        intro, language = await self._route({"route": "chat", "reply": "Hi! I'm whycode.", "language": "en"})
        answer, structured = intro
        self.assertEqual("en", language)
        self.assertEqual(f"Hi! I'm whycode. {_EN_OFFER}", answer)
        self.assertEqual(answer, structured["summary"])
        self.assertEqual("intro", structured["reply_kind"])
        self.assertEqual("en", structured["language"])

    async def test_english_offer_is_not_repeated_whatever_its_case(self):
        reply = "Glad it helped. how can I help?"
        intro, _ = await self._route({"route": "chat", "reply": reply, "language": "en"})
        self.assertEqual(reply, intro[0])

    async def test_unusable_english_reply_is_english_offer_only(self):
        for reply in ["", "line one\nline two", "a" * 401]:
            with self.subTest(reply=reply[:20]):
                intro, _ = await self._route({"route": "chat", "reply": reply, "language": "en"})
                self.assertEqual(_EN_OFFER, intro[0])

    async def test_chat_without_a_language_is_korean(self):
        intro, language = await self._route({"route": "chat", "reply": "반가워요.", "language": "none"})
        self.assertEqual("ko", language)
        self.assertEqual(f"반가워요. {_KO_OFFER}", intro[0])
        self.assertEqual("ko", intro[1]["language"])

    async def test_graph_route_returns_only_the_language(self):
        for language in ["ko", "en"]:
            with self.subTest(language=language):
                result = await self._route({"route": "graph", "reply": "", "language": language})
                self.assertEqual((None, language), tuple(result))

    async def test_undecided_or_invalid_language_is_none(self):
        for payload in [
            {"route": "graph", "reply": "", "language": "none"},
            {"route": "graph", "reply": "", "language": "fr"},
            {"route": "graph", "reply": "", "language": 1},
            {"route": "graph", "reply": ""},
        ]:
            with self.subTest(payload=payload):
                self.assertEqual((None, None), tuple(await self._route(payload)))

    async def test_route_debug_keeps_the_routers_own_language(self):
        # 언어를 못 정한 잡담은 한국어로 답하지만, 관측 기록에는 판별이 실제로 낸 값(None)을 남긴다
        debug: dict = {}
        payload = {"route": "chat", "reply": "반가워요.", "language": "none"}
        with patch.object(orchestrator, "chat_completion", AsyncMock(return_value=_completion(payload))):
            await _REAL_ROUTE("question", None, debug)
        self.assertIsNone(debug["utterance_route"]["language"])

    async def test_route_failure_decides_nothing(self):
        with patch.object(orchestrator, "chat_completion", AsyncMock(side_effect=RuntimeError("upstream"))):
            self.assertEqual((None, None), tuple(await _REAL_ROUTE("question", None)))


class AnswerLanguageDecisionTest(unittest.IsolatedAsyncioTestCase):
    async def _run(self, question, routed, **run_kwargs):
        route = AsyncMock(return_value=routed)
        structured_llm = AsyncMock(return_value=dict(_PLAIN_ANSWER))
        debug: dict = {}
        with (
            patch.object(orchestrator, "_route_utterance", route),
            patch.object(orchestrator, "_call_llm", AsyncMock(return_value=_done())),
            patch.object(orchestrator, "_call_llm_structured", structured_llm),
            patch.object(orchestrator, "_rewrite_question", AsyncMock(return_value=None)),
        ):
            answer, structured = await orchestrator.run(question, debug=debug, **run_kwargs)
        return answer, structured, debug, route, structured_llm

    async def test_router_language_wins_over_the_code_rule(self):
        _, structured, debug, _, structured_llm = await self._run("커밋 왜 바꿨어?", (None, "en"))
        self.assertEqual({"language": "en", "source": "router"}, debug["answer_language"])
        self.assertEqual("en", structured["language"])
        self.assertEqual("en", structured_llm.call_args.kwargs["language"])

    async def test_question_decides_when_the_router_has_no_language(self):
        _, structured, debug, _, _ = await self._run("Why was ENG-42 closed?", (None, None))
        self.assertEqual({"language": "en", "source": "question"}, debug["answer_language"])
        self.assertEqual("en", structured["language"])

    async def test_ui_hint_decides_when_nothing_else_does(self):
        _, _, debug, _, _ = await self._run("HT-12?", (None, None), ui_language="en")
        self.assertEqual({"language": "en", "source": "ui"}, debug["answer_language"])

    async def test_korean_is_the_default(self):
        _, structured, debug, _, structured_llm = await self._run("HT-12?", (None, None))
        self.assertEqual({"language": "ko", "source": "default"}, debug["answer_language"])
        self.assertEqual("ko", structured["language"])
        self.assertEqual("ko", structured_llm.call_args.kwargs["language"])

    async def test_focus_evidence_skips_the_router_and_falls_back(self):
        _, _, debug, route, _ = await self._run(
            "Explain the attached item.", (None, "ko"), focus_evidence=[{"type": "issue", "id": "HT-12"}],
        )
        route.assert_not_awaited()
        self.assertEqual({"language": "en", "source": "question"}, debug["answer_language"])

    async def test_chat_intro_records_its_language(self):
        text = f"Hi. {_EN_OFFER}"
        intro = (text, {**_PLAIN_ANSWER, "summary": text, "answer_mode": "grounded", "reply_kind": "intro", "language": "en"})
        answer, _, debug, _, structured_llm = await self._run("hi", (intro, "en"))
        self.assertEqual(text, answer)
        self.assertEqual({"language": "en", "source": "router"}, debug["answer_language"])
        structured_llm.assert_not_awaited()


class EnglishAnswerPathTest(unittest.IsolatedAsyncioTestCase):
    async def _run(self, language, question="Why was ENG-42 closed?", llm=None, structured=None, rewrite=None, **run_kwargs):
        explored: list = []
        responses = list(llm or [_done()])

        async def explore(messages, with_tools=True):
            explored.append(list(messages))
            return responses.pop(0)

        structured_llm = AsyncMock(return_value=dict(structured) if structured else dict(_PLAIN_ANSWER))
        rewrite = rewrite or AsyncMock(return_value=None)
        with (
            patch.object(orchestrator, "_route_utterance", AsyncMock(return_value=(None, language))),
            patch.object(orchestrator, "_call_llm", side_effect=explore),
            patch.object(orchestrator, "_call_llm_structured", structured_llm),
            patch.object(orchestrator, "_rewrite_question", rewrite),
            patch.object(orchestrator, "execute", AsyncMock(return_value='{"issue_key": "HT-1", "discussion_count": 12}')),
        ):
            answer, result = await orchestrator.run(question, **run_kwargs)
        return answer, result, explored, structured_llm

    async def test_english_answer_uses_the_english_prompt_from_search_to_final_call(self):
        _, _, explored, structured_llm = await self._run("en")
        self.assertEqual({"role": "system", "content": orchestrator._SYSTEM_PROMPT_EN}, explored[0][0])
        self.assertEqual(orchestrator._SYSTEM_PROMPT_EN, _messages_of(structured_llm)[0]["content"])

    async def test_korean_answer_keeps_the_korean_prompt(self):
        _, _, explored, structured_llm = await self._run("ko", question="커밋 왜 바꿨어?")
        self.assertEqual({"role": "system", "content": orchestrator._SYSTEM_PROMPT}, explored[0][0])
        self.assertEqual(orchestrator._SYSTEM_PROMPT, _messages_of(structured_llm)[0]["content"])

    async def test_english_rewrite_keeps_the_original_question_in_english(self):
        rewrite = AsyncMock(return_value="What was the PR before PR #34?")
        _, _, explored, _ = await self._run(
            "en",
            question="and the one before?",
            rewrite=rewrite,
            history=[
                {"role": "user", "content": "Did you see PR #34?"},
                {"role": "assistant", "content": "Yes, PR #34."},
            ],
        )
        self.assertEqual(
            "What was the PR before PR #34?\n\n(original question: and the one before?)",
            explored[0][-1]["content"],
        )
        self.assertEqual("en", rewrite.call_args.kwargs["language"])

    async def test_english_llm_failure_message(self):
        with (
            patch.object(orchestrator, "_route_utterance", AsyncMock(return_value=(None, "en"))),
            patch.object(orchestrator, "_call_llm", AsyncMock(return_value=None)),
            patch.object(orchestrator, "_rewrite_question", AsyncMock(return_value=None)),
        ):
            answer, structured = await orchestrator.run("Why was ENG-42 closed?")
        self.assertEqual(_EN_FAILURE, answer)
        self.assertIsNone(structured)

    async def test_english_fallback_when_nothing_was_generated(self):
        with (
            patch.object(orchestrator, "_route_utterance", AsyncMock(return_value=(None, "en"))),
            patch.object(orchestrator, "_call_llm", AsyncMock(return_value=_done(content=None))),
            patch.object(orchestrator, "_call_llm_structured", AsyncMock(return_value=None)),
            patch.object(orchestrator, "_rewrite_question", AsyncMock(return_value=None)),
        ):
            answer, structured = await orchestrator.run("Why was ENG-42 closed?")
        self.assertEqual(_EN_FALLBACK, answer)
        self.assertIsNone(structured)

    async def test_english_answer_replaces_leaked_field_names_with_english_labels(self):
        answer, _, _, _ = await self._run(
            "en",
            llm=[_tool_call("rank_issues", '{"by": "discussion"}'), _done()],
            structured={"summary": "HT-1 has the highest discussion_count.", "evidence": [], "unknown_aspects": []},
        )
        self.assertEqual("HT-1 has the highest linked message count.", answer)


class EnglishFixedTextTest(unittest.TestCase):
    _STRUCTURED = {
        "summary": "It was closed as a duplicate.",
        "evidence": [{
            "type": "issue", "id": "ENG-42", "occurredAt": "2026-10-01T09:00:00Z",
            "event_meaning": "issue_closed", "author": "dev", "quote": "Duplicate of ENG-41.",
        }],
        "unknown_aspects": ["Who made the call is unclear."],
        "answer_mode": "exploratory",
    }

    def test_english_render_uses_english_notice_and_section_titles(self):
        text = orchestrator._render_structured(dict(self._STRUCTURED), "en")
        self.assertTrue(text.startswith(_EN_NOTICE))
        self.assertIn("\n## Evidence\n", text)
        self.assertIn("\n## Unverified aspects\n- Who made the call is unclear.", text)
        self.assertIsNone(_HANGUL_RE.search(text))

    def test_korean_render_is_unchanged_by_default(self):
        text = orchestrator._render_structured(dict(self._STRUCTURED))
        self.assertTrue(text.startswith(orchestrator._EXPLORATORY_NOTICE))
        self.assertIn("\n## 근거\n", text)
        self.assertIn("\n## 확인되지 않은 측면\n", text)

    def test_empty_english_render_is_english_fallback(self):
        self.assertEqual(_EN_FALLBACK, orchestrator._render_structured({}, "en"))

    def test_english_finalize_without_structured_keeps_english_notice(self):
        self.assertEqual((f"{_EN_NOTICE}\n\nfree text", None), orchestrator._finalize(None, "free text", True, "en"))

    def test_finalize_records_the_language_on_structured(self):
        _, english = orchestrator._finalize(dict(_PLAIN_ANSWER), "x", False, "en")
        _, korean = orchestrator._finalize(dict(_PLAIN_ANSWER), "x", False)
        self.assertEqual("en", english["language"])
        self.assertEqual("ko", korean["language"])


class FinalInstructionTest(unittest.IsolatedAsyncioTestCase):
    async def _final_instruction(self, **kwargs) -> str:
        completion = AsyncMock(return_value=_completion(_PLAIN_ANSWER))
        with patch.object(orchestrator, "chat_completion", completion):
            await orchestrator._call_llm_structured([{"role": "user", "content": "q"}], **kwargs)
        return completion.call_args.kwargs["messages"][-1]["content"]

    async def test_korean_answer_uses_the_korean_final_instruction(self):
        self.assertEqual(orchestrator._STRUCTURED_FINAL_INSTRUCTION, await self._final_instruction())

    async def test_english_final_instruction_asks_for_english_and_verbatim_quotes(self):
        text = await self._final_instruction(language="en")
        # 영어판은 본 지시 영어판과 한곳(prompt_en)에 두어 같은 동기화 가드를 받는다
        self.assertEqual(prompt_en.STRUCTURED_FINAL_INSTRUCTION_EN, text)
        self.assertIsNone(_HANGUL_RE.search(text))
        for phrase in ["grounded_answer", "in English", "verbatim"]:
            with self.subTest(phrase=phrase):
                self.assertIn(phrase, text)


class RewriteInstructionTest(unittest.IsolatedAsyncioTestCase):
    async def _system_instruction(self, **kwargs) -> str:
        completion = AsyncMock(return_value=_completion({"changed": False, "rewritten_question": ""}))
        with patch.object(orchestrator, "chat_completion", completion):
            await orchestrator._rewrite_question(
                "and before that?", [{"role": "user", "content": "Did PR #34 merge?"}], None, **kwargs,
            )
        return completion.call_args.kwargs["messages"][0]["content"]

    async def test_korean_question_uses_the_korean_rewrite_instruction(self):
        self.assertEqual(orchestrator._REWRITE_INSTRUCTION, await self._system_instruction())

    async def test_english_rewrite_instruction_adds_a_line_to_keep_english(self):
        text = await self._system_instruction(language="en")
        self.assertTrue(text.startswith(orchestrator._REWRITE_INSTRUCTION))
        extra = text[len(orchestrator._REWRITE_INSTRUCTION):]
        self.assertIn("영어", extra)
        self.assertIn("식별자", extra)


class QueryContractTest(unittest.IsolatedAsyncioTestCase):
    def test_ui_language_is_lenient(self):
        self.assertIsNone(QueryRequest(question="q").ui_language)
        for value, expected in [("en-US", "en"), ("ko", "ko"), ("fr", None), ("", None), (5, None), (["en"], None)]:
            with self.subTest(value=value):
                self.assertEqual(expected, QueryRequest(question="q", ui_language=value).ui_language)

    async def test_router_passes_ui_language_and_keeps_the_response_shape(self):
        captured: dict = {}

        async def fake_run(question, **kwargs):
            captured.update(kwargs)
            return "answer", {**_PLAIN_ANSWER, "language": "en"}

        with patch.object(orchestrator, "run", side_effect=fake_run):
            body = await query_endpoint(QueryRequest(question="q", project_id="p", ui_language="en-US"))
        self.assertEqual("en", captured["ui_language"])
        self.assertEqual({"answer", "structured"}, set(body))
        self.assertEqual("en", body["structured"]["language"])


if __name__ == "__main__":
    unittest.main()
