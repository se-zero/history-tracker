"""세션 메모리 개선 4단계(AE-4)·5단계(AE-5) — evidence quote·id 후검증(_drop_unverified_quotes) 단위 테스트.

AE-3로 최종 답변 호출에 대화 맥락 카드가 실리면서, 답변 모델이 카드·과거 대화의 텍스트를
quote로 베껴 "이번 턴 근거"처럼 위장할 통로가 생겼다. 프롬프트 지시만으로는 막지 못하므로,
서버가 evidence[*].quote가 이번 턴 tool 결과에 실제로 있는지 검증해 없으면 제거한다.

AE-5는 같은 함수를 확장해 evidence[*].id(커밋 해시·PR 번호·이슈 키)도 대조한다 — quote만
검증하면 모델이 낸 id 오타(실기 사례: 답변 근거에 커밋 해시가 8cdb0ca로 찍혔는데 실제는
8cdb0cc)가 그대로 통과해, UI에서 그 id를 클릭하면 존재하지 않는 대상을 가리키게 된다.

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
        # id 검증이 함께 도는 evidence[*].id("abc1234", _evidence 기본값)를 tool 결과에 포함시켜야
        # 이 테스트가 통과한다(quote만 있던 시절과 달리 id 검사도 haystack을 대조한다).
        tool_content = json.dumps({"id": "abc1234", "body": original}, ensure_ascii=False)
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
        tool_content = json.dumps({"id": "abc1234", "body": original}, ensure_ascii=False)
        messages = [{"role": "tool", "content": tool_content}]
        variant_quote = 'FIXED bug   in "auth" module.\n\n  details HERE.'
        structured = {"summary": "s", "evidence": [_evidence(variant_quote)], "unknown_aspects": []}

        result = orchestrator._drop_unverified_quotes(structured, messages, 0, None)

        self.assertEqual(1, len(result["evidence"]))

    def test_fabricated_quote_dropped_others_kept(self):
        original = 'Fixed bug in "auth" module.'
        tool_content = json.dumps({"id": "real1", "body": original}, ensure_ascii=False)
        messages = [{"role": "tool", "content": tool_content}]
        real_item = _evidence(original, id="real1")
        fake_item = _evidence("this text never appeared anywhere", id="fake1")
        structured = {"summary": "s", "evidence": [real_item, fake_item], "unknown_aspects": []}
        debug: dict = {}

        result = orchestrator._drop_unverified_quotes(structured, messages, 0, debug)

        self.assertEqual([real_item], result["evidence"])
        self.assertEqual(1, len(debug["dropped_evidence"]))
        self.assertEqual("fake1", debug["dropped_evidence"][0]["id"])
        self.assertEqual("quote", debug["dropped_evidence"][0]["reason"])

    def test_ellipsis_quote_both_fragments_present_passes(self):
        original = "Fixed the bug in the login flow. Added regression test coverage."
        tool_content = json.dumps({"id": "abc1234", "body": original}, ensure_ascii=False)
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

    def test_id_short_prefix_of_full_hash_in_tool_content_passes(self):
        # 짧은 해시는 자동 해결된다 — tool 결과에 전체 해시가 있으면 7자리 앞부분은 부분
        # 문자열로 매칭된다.
        original = "Fix null pointer in parser"
        full_hash = "8cdb0ccabc1234567890abcdef1234567890abcd"
        tool_content = json.dumps({"hash": full_hash, "body": original}, ensure_ascii=False)
        messages = [{"role": "tool", "content": tool_content}]
        structured = {
            "summary": "s",
            "evidence": [_evidence(original, id=full_hash[:7])],
            "unknown_aspects": [],
        }

        result = orchestrator._drop_unverified_quotes(structured, messages, 0, None)

        self.assertEqual(1, len(result["evidence"]))

    def test_typo_commit_hash_id_dropped_with_id_reason(self):
        # 실기 사례 재현: 도구 결과는 8cdb0cc...인데 evidence가 8cdb0ca(마지막 글자 오타)를 냄.
        original = "Fix null pointer in parser"
        full_hash = "8cdb0ccabc1234567890abcdef1234567890abcd"
        tool_content = json.dumps({"hash": full_hash, "body": original}, ensure_ascii=False)
        messages = [{"role": "tool", "content": tool_content}]
        structured = {
            "summary": "s",
            "evidence": [_evidence(original, id="8cdb0ca")],
            "unknown_aspects": [],
        }
        debug: dict = {}

        result = orchestrator._drop_unverified_quotes(structured, messages, 0, debug)

        self.assertEqual([], result["evidence"])
        self.assertEqual(1, len(debug["dropped_evidence"]))
        self.assertEqual("id", debug["dropped_evidence"][0]["reason"])

    def test_correct_quote_with_wrong_id_dropped(self):
        # quote는 실존하지만 id만 오타인 항목 — 검사 독립성 확인(quote 검사만으로는 못 잡는다).
        original = "Fix null pointer in parser"
        tool_content = json.dumps({"id": "abc1234", "body": original}, ensure_ascii=False)
        messages = [{"role": "tool", "content": tool_content}]
        correct_item = _evidence(original, id="abc1234")
        wrong_id_item = _evidence(original, id="zzz9999")
        structured = {
            "summary": "s",
            "evidence": [correct_item, wrong_id_item],
            "unknown_aspects": [],
        }

        result = orchestrator._drop_unverified_quotes(structured, messages, 0, None)

        self.assertEqual([correct_item], result["evidence"])

    def test_empty_id_passes(self):
        original = "Fix null pointer in parser"
        tool_content = json.dumps({"body": original}, ensure_ascii=False)
        messages = [{"role": "tool", "content": tool_content}]
        structured = {
            "summary": "s",
            "evidence": [_evidence(original, id="")],
            "unknown_aspects": [],
        }

        result = orchestrator._drop_unverified_quotes(structured, messages, 0, None)

        self.assertEqual(1, len(result["evidence"]))


class OrchestratorQuoteVerificationTest(unittest.IsolatedAsyncioTestCase):
    async def test_run_drops_unverified_quote_in_final_structured_answer(self):
        real_quote = "Fix OAuth callback validation bug"
        # 도구 호출 arguments(issue_key)는 haystack에 안 들어간다 — id 검증을 통과하려면
        # 결과 content 쪽에 "HT-1"이 있어야 한다.
        tool_content = json.dumps({"id": "HT-1", "title": real_quote}, ensure_ascii=False)
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
