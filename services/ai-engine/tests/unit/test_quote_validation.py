"""세션 메모리 개선 4단계(AE-4) — evidence quote 후검증(_drop_unverified_quotes) 단위 테스트.

AE-3로 최종 답변 호출에 대화 맥락 카드가 실리면서, 답변 모델이 카드·과거 대화의 텍스트를
quote로 베껴 "이번 턴 근거"처럼 위장할 통로가 생겼다. 프롬프트 지시만으로는 막지 못하므로,
서버가 evidence[*].quote가 이번 턴 tool 결과에 실제로 있는지 검증해 없으면 제거한다.

모킹 패턴은 test_multiturn_history.py / test_answer_mode.py를 그대로 따른다
(모듈 상단 tools.executor 스텁 + patch.object로 orchestrator 내부 호출 가로채기).
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


def _evidence(quote: str, **overrides) -> dict:
    item = {
        "type": "commit",
        "id": "abc1234",
        "occurredAt": "2024-01-01T00:00:00Z",
        "event_meaning": "commit_authored",
        "author": "alice",
        "quote": quote,
    }
    item.update(overrides)
    return item


class DropUnverifiedQuotesTest(unittest.TestCase):
    def test_exact_quote_from_tool_content_passes(self):
        # tool content는 json.dumps 산출물이라 큰따옴표·개행이 리터럴 이스케이프로 남는다.
        original = 'Fixed bug in "auth" module.\nDetails here.'
        tool_content = json.dumps({"body": original}, ensure_ascii=False)
        messages = [
            {"role": "user", "content": "why?"},
            {"role": "tool", "content": tool_content},
        ]
        structured = {"summary": "s", "evidence": [_evidence(original)], "unknown_aspects": []}

        result = orchestrator._drop_unverified_quotes(structured, messages, 0, None)

        self.assertEqual(1, len(result["evidence"]))
        self.assertEqual(original, result["evidence"][0]["quote"])

    def test_whitespace_newline_case_variation_passes(self):
        original = 'Fixed bug in "auth" module.\nDetails here.'
        tool_content = json.dumps({"body": original}, ensure_ascii=False)
        messages = [{"role": "tool", "content": tool_content}]
        variant_quote = 'FIXED bug   in "auth" module.\n\n  details HERE.'
        structured = {"summary": "s", "evidence": [_evidence(variant_quote)], "unknown_aspects": []}

        result = orchestrator._drop_unverified_quotes(structured, messages, 0, None)

        self.assertEqual(1, len(result["evidence"]))

    def test_fabricated_quote_dropped_others_kept(self):
        original = 'Fixed bug in "auth" module.'
        tool_content = json.dumps({"body": original}, ensure_ascii=False)
        messages = [{"role": "tool", "content": tool_content}]
        real_item = _evidence(original, id="real1")
        fake_item = _evidence("this text never appeared anywhere", id="fake1")
        structured = {"summary": "s", "evidence": [real_item, fake_item], "unknown_aspects": []}
        debug: dict = {}

        result = orchestrator._drop_unverified_quotes(structured, messages, 0, debug)

        self.assertEqual([real_item], result["evidence"])
        self.assertEqual(1, len(debug["dropped_evidence"]))
        self.assertEqual("fake1", debug["dropped_evidence"][0]["id"])

    def test_ellipsis_quote_both_fragments_present_passes(self):
        original = "Fixed the bug in the login flow. Added regression test coverage."
        tool_content = json.dumps({"body": original}, ensure_ascii=False)
        messages = [{"role": "tool", "content": tool_content}]
        quote = "Fixed the bug in the login flow...Added regression test coverage."
        structured = {"summary": "s", "evidence": [_evidence(quote)], "unknown_aspects": []}

        result = orchestrator._drop_unverified_quotes(structured, messages, 0, None)

        self.assertEqual(1, len(result["evidence"]))

    def test_quote_spanning_two_tool_results_dropped(self):
        # 서로 다른 tool 결과의 끝·시작을 이어붙인 "이음새" 인용은 실존하지 않으므로 제거돼야 한다.
        # 구분자 없이(""-join) 이어붙이면 "로그인 기능"+"개선 작업"이 "기능개선"을 만들어
        # 우연히 통과한다 — NUL 구분자가 이 이음새 일치를 막는다.
        messages = [
            {"role": "tool", "content": "작업 내용은 로그인 기능"},
            {"role": "tool", "content": "개선 작업으로 이어졌다"},
        ]
        structured = {"summary": "s", "evidence": [_evidence("기능개선")], "unknown_aspects": []}

        result = orchestrator._drop_unverified_quotes(structured, messages, 0, None)

        self.assertEqual([], result["evidence"])

    def test_ellipsis_quote_one_fragment_missing_dropped(self):
        original = "Fixed the bug in the login flow. Added regression test coverage."
        tool_content = json.dumps({"body": original}, ensure_ascii=False)
        messages = [{"role": "tool", "content": tool_content}]
        quote = "Fixed the bug in the login flow...a sentence that never existed here"
        structured = {"summary": "s", "evidence": [_evidence(quote)], "unknown_aspects": []}

        result = orchestrator._drop_unverified_quotes(structured, messages, 0, None)

        self.assertEqual([], result["evidence"])


class OrchestratorQuoteVerificationTest(unittest.IsolatedAsyncioTestCase):
    async def test_run_drops_unverified_quote_in_final_structured_answer(self):
        real_quote = "Fix OAuth callback validation bug"
        tool_content = json.dumps({"title": real_quote}, ensure_ascii=False)
        tool_call = SimpleNamespace(
            id="call-1",
            function=SimpleNamespace(name="get_issue_context", arguments='{"issue_key":"HT-1"}'),
        )
        tool_call_message = SimpleNamespace(role="assistant", tool_calls=[tool_call], content=None)
        completed_message = SimpleNamespace(role="assistant", tool_calls=None, content="fallback")

        fake_structured = {
            "summary": "요약",
            "evidence": [
                _evidence(real_quote, type="issue", id="HT-1", event_meaning="issue_created"),
                _evidence(
                    "이 문장은 도구 결과 어디에도 없는 조작된 인용입니다",
                    type="issue", id="HT-1", event_meaning="issue_created",
                ),
            ],
            "unknown_aspects": [],
        }
        debug: dict = {}

        with (
            patch.object(
                orchestrator, "_call_llm",
                AsyncMock(side_effect=[
                    SimpleNamespace(choices=[SimpleNamespace(message=tool_call_message)]),
                    SimpleNamespace(choices=[SimpleNamespace(message=completed_message)]),
                ]),
            ),
            patch.object(orchestrator, "execute", AsyncMock(return_value=tool_content)),
            patch.object(
                orchestrator, "_call_llm_structured",
                AsyncMock(return_value=dict(fake_structured)),
            ),
            patch.object(orchestrator, "_rewrite_question", AsyncMock(return_value=None)),
        ):
            answer, structured = await orchestrator.run("이 이슈 왜 만들어졌어?", debug=debug)

        self.assertEqual(1, len(structured["evidence"]))
        self.assertEqual(real_quote, structured["evidence"][0]["quote"])
        self.assertEqual(1, len(debug["dropped_evidence"]))
        self.assertIn("조작된 인용", debug["dropped_evidence"][0]["quote"])


if __name__ == "__main__":
    unittest.main()
