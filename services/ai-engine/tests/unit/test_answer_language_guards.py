"""답변 언어 — 언어별로 갈리는 공용 가드 (docs/answer-language.md §4-2).

계약:
- 용어집 영어판은 한국어판과 키가 같고 라벨·정의에 한글이 없다. prompt_glossary("en")은 영어 블록을 내고,
  기본값(ko)은 지금 한국어 블록을 그대로 낸다.
- 영어 답의 내부 용어 치환은 영어 라벨로 한다. 일반 영어 단어인 confidence·relevance·weight·excerpt·candidates·
  truncated는 영어 답에서 치환하지 않는다 — 영어 문장에 정상적으로 등장하는 낱말이라 바꾸면 멀쩡한 문장이 깨진다
  ("with high confidence" → "with high link confidence"). 한국어 답에서는 지금처럼 치환한다.
- 대화 기록 정리(_strip_evidence_section)는 한국어·영어 근거 제목 중 먼저 나오는 쪽에서 자른다 — 저장된 지난 답은
  한국어이고, 한 대화 안에서 답 언어가 섞일 수 있다.

tools.executor 스텁은 두지 않는다 — 이 파일은 execute를 부르지 않고, 이름순으로 진짜 모듈을 import하는
파일(test_answer_mode)보다 먼저 수집돼 스텁이 진짜 모듈을 가린다(test_executor_truncation이 import 실패).
"""

import copy
import json
import os
import re
import unittest

os.environ.setdefault("OPENAI_API_KEY", "test-key")

from agent import glossary, orchestrator

_HANGUL = re.compile(r"[가-힣ㄱ-ㅎㅏ-ㅣ]")
_PLAIN_ENGLISH = ["confidence", "relevance", "weight", "excerpt", "candidates", "truncated"]


def _tool_messages(payload: dict) -> list[dict]:
    """도구 결과 메시지 1건짜리 haystack을 만든다."""
    return [{"role": "tool", "content": json.dumps(payload, ensure_ascii=False)}]


class EnglishGlossaryTest(unittest.TestCase):
    def test_english_terms_cover_the_same_keys(self):
        self.assertEqual(set(glossary.METRIC_TERMS), set(glossary.METRIC_TERMS_EN))
        self.assertEqual(set(glossary.FIELD_TERMS), set(glossary.FIELD_TERMS_EN))

    def test_english_labels_and_definitions_have_no_hangul(self):
        for token, (label, definition) in glossary.METRIC_TERMS_EN.items():
            with self.subTest(token=token):
                self.assertTrue(label.strip())
                self.assertTrue(definition.strip())
                self.assertIsNone(_HANGUL.search(label))
                self.assertIsNone(_HANGUL.search(definition))
        for token, label in glossary.FIELD_TERMS_EN.items():
            with self.subTest(token=token):
                self.assertTrue(label.strip())
                self.assertIsNone(_HANGUL.search(label))

    def test_key_labels_read_naturally_in_english(self):
        english = glossary.replacements("en")
        self.assertEqual("linked message count", english["discussion_count"])
        self.assertEqual("duration (days)", english["duration_days"])
        self.assertEqual("event time", english["occurredAt"])

    def test_korean_glossary_is_unchanged_by_default(self):
        self.assertEqual(glossary.prompt_glossary(), glossary.prompt_glossary("ko"))
        self.assertTrue(glossary.prompt_glossary().startswith("[지표·필드 용어집"))
        self.assertEqual(glossary.REPLACEMENTS, glossary.replacements("ko"))
        self.assertEqual(glossary.REPLACEMENTS, glossary.replacements())

    def test_english_prompt_glossary_lists_every_term_in_english(self):
        block = glossary.prompt_glossary("en")
        self.assertIsNone(_HANGUL.search(block))
        for token, (label, definition) in glossary.METRIC_TERMS_EN.items():
            with self.subTest(token=token):
                self.assertIn(f"- {token} → {label}", block)
                self.assertIn(definition, block)
        for token, label in glossary.FIELD_TERMS_EN.items():
            with self.subTest(token=token):
                self.assertIn(f"- {token} → {label}", block)

    def test_plain_english_tokens_are_replaced_only_in_korean(self):
        english, korean = glossary.replacements("en"), glossary.replacements("ko")
        for token in _PLAIN_ENGLISH:
            with self.subTest(token=token):
                self.assertNotIn(token, english)
                self.assertIn(token, korean)

    def test_english_replacements_respect_never_replace(self):
        self.assertFalse(set(glossary.replacements("en")) & glossary.NEVER_REPLACE)


class SanitizeEnglishAnswerTest(unittest.TestCase):
    def test_field_name_replaced_with_english_label(self):
        messages = _tool_messages({"issue_key": "HT-129", "discussion_count": 12})
        structured = {"summary": "HT-129 has the highest discussion_count at 12.", "evidence": [], "unknown_aspects": []}
        debug: dict = {}

        orchestrator._sanitize_internal_terms(structured, messages, 0, debug, language="en")

        self.assertEqual("HT-129 has the highest linked message count at 12.", structured["summary"])
        self.assertEqual(
            [{"token": "discussion_count", "label": "linked message count", "count": 1}],
            debug["internal_terms"]["replaced"],
        )

    def test_plain_english_words_stay_even_when_they_are_our_keys(self):
        messages = _tool_messages({"issue_key": "HT-3", "confidence": 0.62, "truncated": True})
        structured = {
            "summary": "HT-3 appears to be linked with moderate confidence.",
            "evidence": [],
            "unknown_aspects": ["The truncated list may hide some items."],
        }
        before = copy.deepcopy(structured)

        orchestrator._sanitize_internal_terms(structured, messages, 0, None, language="en")

        self.assertEqual(before, structured)

    def test_same_payload_is_still_replaced_in_korean(self):
        messages = _tool_messages({"issue_key": "HT-3", "confidence": 0.62, "truncated": True})
        structured = {"summary": "HT-3의 confidence는 낮습니다.", "evidence": [], "unknown_aspects": ["truncated 상태입니다."]}

        orchestrator._sanitize_internal_terms(structured, messages, 0, None, language="ko")

        self.assertEqual("HT-3의 연결 신뢰도는 낮습니다.", structured["summary"])
        self.assertEqual(["일부 생략됨 상태입니다."], structured["unknown_aspects"])

    def test_language_defaults_to_korean(self):
        messages = _tool_messages({"discussion_count": 12})
        default = {"summary": "discussion_count가 12건입니다.", "evidence": [], "unknown_aspects": []}
        explicit = copy.deepcopy(default)

        orchestrator._sanitize_internal_terms(default, messages, 0, None)
        orchestrator._sanitize_internal_terms(explicit, messages, 0, None, language="ko")

        self.assertEqual("관련 대화 메시지 수가 12건입니다.", default["summary"])
        self.assertEqual(default, explicit)


class StripEvidenceSectionTest(unittest.TestCase):
    def test_korean_header(self):
        self.assertEqual("본문입니다.", orchestrator._strip_evidence_section("본문입니다.\n## 근거\n- **[commit]** `abc1234`"))

    def test_english_header(self):
        self.assertEqual("The body.", orchestrator._strip_evidence_section("The body.\n## Evidence\n- **[commit]** `abc1234`"))

    def test_earliest_header_wins(self):
        self.assertEqual("Body", orchestrator._strip_evidence_section("Body\n## Evidence\n- a\n## 근거\n- b"))
        self.assertEqual("본문", orchestrator._strip_evidence_section("본문\n## 근거\n- a\n## Evidence\n- b"))

    def test_no_header_returns_original(self):
        self.assertEqual("No evidence here.", orchestrator._strip_evidence_section("No evidence here."))


if __name__ == "__main__":
    unittest.main()
