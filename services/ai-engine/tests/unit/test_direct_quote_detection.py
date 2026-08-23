"""summary 직접 인용 검출기(_count_direct_quotes) 단위 테스트.

답변 summary는 원문(Slack 메시지·이슈 본문 등)을 따옴표째 옮기지 말고 간접 인용으로
풀어 써야 한다(프롬프트 규칙). 이 검출기는 그 위반을 프롬프트만으로는 확실히 막을 수
없어 관측용으로 존재한다 — summary가 도구 결과 원문을 따옴표로 감싸 그대로 옮겼는지
세어 debug/로그에 남길 뿐, structured(summary·evidence)는 절대 변형하지 않는다.
evidence[*].quote 검증(_drop_unverified_quotes)과 달리 여기서는 아무것도 제거하지 않는다.

모킹·import 패턴은 tests/unit/test_quote_validation.py를 그대로 따른다.
"""

import json
import os
import sys
import unittest
from types import ModuleType
from unittest.mock import AsyncMock

os.environ.setdefault("OPENAI_API_KEY", "test-key")

executor_module = ModuleType("tools.executor")
executor_module.execute = AsyncMock()
sys.modules.setdefault("tools.executor", executor_module)

from agent import orchestrator


class CountDirectQuotesTest(unittest.TestCase):
    def test_direct_quote_of_slack_message_detected(self):
        original = "그럼 나중에 추상화 하는걸로 할까?"
        tool_content = json.dumps({"body": original}, ensure_ascii=False)
        messages = [{"role": "tool", "content": tool_content}]
        structured = {
            "summary": f'"{original}"라고 물었다.',
            "evidence": [],
            "unknown_aspects": [],
        }
        debug: dict = {}

        orchestrator._count_direct_quotes(structured, messages, 0, debug)

        self.assertEqual(1, len(debug["direct_quotes"]))
        self.assertIn("그럼", debug["direct_quotes"][0]["span"])

    def test_paraphrased_summary_no_findings(self):
        original = "그럼 나중에 추상화 하는걸로 할까?"
        tool_content = json.dumps({"body": original}, ensure_ascii=False)
        messages = [{"role": "tool", "content": tool_content}]
        structured = {
            "summary": "추상화를 지금 하지 말고 뒤로 미루자고 제안했다.",
            "evidence": [],
            "unknown_aspects": [],
        }
        debug: dict = {}

        orchestrator._count_direct_quotes(structured, messages, 0, debug)

        self.assertNotIn("direct_quotes", debug)

    def test_issue_key_title_notation_exempted(self):
        # HT-26 '그래프 생성 파이프라인 개선'처럼 식별자에 붙은 제목 표기는 원문 유지가 규칙이라
        # 도구 결과에 그대로 있어도 위반으로 세지 않는다.
        title = "그래프 생성 파이프라인 개선"
        tool_content = json.dumps({"id": "HT-26", "title": title}, ensure_ascii=False)
        messages = [{"role": "tool", "content": tool_content}]
        structured = {
            "summary": f"HT-26 '{title}' 작업을 진행했다.",
            "evidence": [],
            "unknown_aspects": [],
        }
        debug: dict = {}

        orchestrator._count_direct_quotes(structured, messages, 0, debug)

        self.assertNotIn("direct_quotes", debug)

    def test_pr_number_title_notation_exempted(self):
        title = "그래프 생성 파이프라인 안정성 개선"
        tool_content = json.dumps({"pr_number": 20, "title": title}, ensure_ascii=False)
        messages = [{"role": "tool", "content": tool_content}]
        structured = {
            "summary": f'PR #20 "{title}" 작업이었다.',
            "evidence": [],
            "unknown_aspects": [],
        }
        debug: dict = {}

        orchestrator._count_direct_quotes(structured, messages, 0, debug)

        self.assertNotIn("direct_quotes", debug)

    def test_short_identifier_quote_exempted(self):
        # 토큰 3개 미만(짧은 식별자·용어)은 "풀어 썼는가"를 물을 대상이 아니라 예외다.
        tool_content = json.dumps({"path": "orchestrator.py"}, ensure_ascii=False)
        messages = [{"role": "tool", "content": tool_content}]
        structured = {
            "summary": "'orchestrator.py' 파일을 수정했다.",
            "evidence": [],
            "unknown_aspects": [],
        }
        debug: dict = {}

        orchestrator._count_direct_quotes(structured, messages, 0, debug)

        self.assertNotIn("direct_quotes", debug)

    def test_quoted_text_absent_from_tool_results_not_counted(self):
        # 도구 결과에 없는 인용은 "원문 복사"가 아니므로(예: LLM이 지어낸 표현) 세지 않는다.
        tool_content = json.dumps({"body": "전혀 다른 내용"}, ensure_ascii=False)
        messages = [{"role": "tool", "content": tool_content}]
        structured = {
            "summary": '"이 문장은 도구 결과 어디에도 존재하지 않는다"라고 말했다.',
            "evidence": [],
            "unknown_aspects": [],
        }
        debug: dict = {}

        orchestrator._count_direct_quotes(structured, messages, 0, debug)

        self.assertNotIn("direct_quotes", debug)

    def test_commit_reference_not_exempted_from_far_hash(self):
        # 리뷰 지적 1 가드: "커밋 8cdb0cc 관련해 "...""처럼 식별자가 있어도 따옴표 바로 앞이
        # 아니라 3글자 조사("관련해")를 거쳐 떨어져 있으면 면제되지 않고 검출돼야 한다.
        original = "그럼 나중에 추상화 하는걸로 할까?"
        full_hash = "8cdb0ccabc1234567890abcdef1234567890abcd"
        tool_content = json.dumps({"hash": full_hash, "body": original}, ensure_ascii=False)
        messages = [{"role": "tool", "content": tool_content}]
        structured = {
            "summary": f'커밋 8cdb0cc 관련해 "{original}"라고 논의했다.',
            "evidence": [],
            "unknown_aspects": [],
        }
        debug: dict = {}

        orchestrator._count_direct_quotes(structured, messages, 0, debug)

        self.assertEqual(1, len(debug["direct_quotes"]))

    def test_thread_reference_not_exempted_from_far_conversation_id(self):
        # 리뷰 지적 1 가드: 스레드 conversation_id도 마찬가지로 따옴표 바로 앞이 아니면 면제되지
        # 않는다. 게다가 "344509" 구간(마지막 6자리, "." 뒤)은 7자 미만이라 애초에 식별자
        # 패턴에도 안 걸린다.
        original = "그럼 나중에 추상화 하는걸로 할까?"
        tool_content = json.dumps(
            {"conversation_id": "1781946322.344509", "body": original}, ensure_ascii=False
        )
        messages = [{"role": "tool", "content": tool_content}]
        structured = {
            "summary": f'스레드 1781946322.344509에서 "{original}"라고 물었다.',
            "evidence": [],
            "unknown_aspects": [],
        }
        debug: dict = {}

        orchestrator._count_direct_quotes(structured, messages, 0, debug)

        self.assertEqual(1, len(debug["direct_quotes"]))

    def test_issue_key_title_notation_with_particle_exempted(self):
        # 식별자 뒤에 한글 조사("의")가 붙어도 따옴표 바로 앞이면 여전히 면제돼야 한다.
        title = "그래프 생성 파이프라인 개선"
        tool_content = json.dumps({"id": "HT-26", "title": title}, ensure_ascii=False)
        messages = [{"role": "tool", "content": tool_content}]
        structured = {
            "summary": f"HT-26의 '{title}' 작업을 진행했다.",
            "evidence": [],
            "unknown_aspects": [],
        }
        debug: dict = {}

        orchestrator._count_direct_quotes(structured, messages, 0, debug)

        self.assertNotIn("direct_quotes", debug)

    def test_english_contraction_apostrophes_not_counted(self):
        # 리뷰 지적 2 가드: "don't ... it's" 안의 단어 내부 아포스트로피 두 개가 '…' 쌍으로
        # 잘못 짝지어지면 안 된다. haystack에 문장을 그대로 실어, 예외가 없었다면 실제로
        # 검출됐을 상황(haystack 불일치가 아니라 예외 로직 덕분에 0건)임을 보장한다.
        sentence = "don't do this now, it's fine"
        tool_content = json.dumps({"body": sentence}, ensure_ascii=False)
        messages = [{"role": "tool", "content": tool_content}]
        structured = {
            "summary": f"리뷰어는 {sentence} 이라고 했다.",
            "evidence": [],
            "unknown_aspects": [],
        }
        debug: dict = {}

        orchestrator._count_direct_quotes(structured, messages, 0, debug)

        self.assertNotIn("direct_quotes", debug)

    def test_direct_quote_between_english_contractions_detected(self):
        # PR #102 봇 리뷰(Major) 가드: '…' 후보가 greedy라 don't의 '에서 it's의 '까지 한 번에
        # 매칭되면, 그 사이에 낀 "…" 직접 인용이 매치 범위에 삼켜져 검사조차 되지 않는다
        # (finditer가 매치 끝 이후부터 재개하므로). 축약형 예외가 진짜 인용을 가리면 안 된다.
        original = "그럼 나중에 추상화 하는걸로 할까?"
        tool_content = json.dumps({"body": original}, ensure_ascii=False)
        messages = [{"role": "tool", "content": tool_content}]
        structured = {
            "summary": f"리뷰어는 don't 라며 \"{original}\"라고 물었고 it's fine이라 했다.",
            "evidence": [],
            "unknown_aspects": [],
        }
        debug: dict = {}

        orchestrator._count_direct_quotes(structured, messages, 0, debug)

        self.assertEqual(1, len(debug["direct_quotes"]))

    def test_conversation_id_without_fraction_not_exempted(self):
        # PR #102 봇 리뷰(Minor) 가드: 식별자 인접 예외는 이슈·PR 제목 표기를 위한 것이다.
        # 소수부 없는 Slack ts(순수 숫자 10자리)가 해시 패턴에 걸려 면제되면, 그 뒤의 대화
        # 원문 인용이 검출되지 않는다.
        original = "그럼 나중에 추상화 하는걸로 할까?"
        tool_content = json.dumps({"body": original}, ensure_ascii=False)
        messages = [{"role": "tool", "content": tool_content}]
        structured = {
            "summary": f'스레드 1781946322에서 "{original}"라고 물었다.',
            "evidence": [],
            "unknown_aspects": [],
        }
        debug: dict = {}

        orchestrator._count_direct_quotes(structured, messages, 0, debug)

        self.assertEqual(1, len(debug["direct_quotes"]))

    def test_korean_particle_after_closing_quote_still_detected(self):
        # PR #102 봇 리뷰 2차(Major) 가드: 한글 음절은 str.isalnum()이 True라, 닫는 ' 뒤에
        # 조사가 붙는 한국어 표준 인용("'…'라고")이 영어 축약형으로 오판돼 통째로 면제됐다.
        # 축약형 판정은 ASCII 영숫자에 붙은 아포스트로피로 한정해야 한다.
        original = "그럼 나중에 추상화 하는걸로 할까?"
        tool_content = json.dumps({"body": original}, ensure_ascii=False)
        messages = [{"role": "tool", "content": tool_content}]

        for summary in (
            f"'{original}'라고 물었다.",
            f"'{original}'이라고 했다.",
            f"'{original}'에서 논의됐다.",
        ):
            with self.subTest(summary=summary):
                structured = {"summary": summary, "evidence": [], "unknown_aspects": []}
                debug: dict = {}

                orchestrator._count_direct_quotes(structured, messages, 0, debug)

                self.assertEqual(1, len(debug["direct_quotes"]))

    def test_debug_none_does_not_raise(self):
        original = "그럼 나중에 추상화 하는걸로 할까?"
        tool_content = json.dumps({"body": original}, ensure_ascii=False)
        messages = [{"role": "tool", "content": tool_content}]
        structured = {
            "summary": f'"{original}"라고 물었다.',
            "evidence": [{"type": "message", "id": "1"}],
            "unknown_aspects": [],
        }

        orchestrator._count_direct_quotes(structured, messages, 0, None)

        self.assertEqual(f'"{original}"라고 물었다.', structured["summary"])
        self.assertEqual([{"type": "message", "id": "1"}], structured["evidence"])

    def test_structured_unchanged_even_when_findings_detected(self):
        original = "그럼 나중에 추상화 하는걸로 할까?"
        tool_content = json.dumps({"body": original}, ensure_ascii=False)
        messages = [{"role": "tool", "content": tool_content}]
        summary = f'"{original}"라고 물었다.'
        evidence = [{"type": "message", "id": "1", "quote": "quoted text"}]
        structured = {"summary": summary, "evidence": evidence, "unknown_aspects": []}
        debug: dict = {}

        orchestrator._count_direct_quotes(structured, messages, 0, debug)

        self.assertEqual(1, len(debug["direct_quotes"]))
        self.assertEqual(summary, structured["summary"])
        self.assertEqual(evidence, structured["evidence"])


if __name__ == "__main__":
    unittest.main()
