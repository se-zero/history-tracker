"""답변 언어 — 대비책 코드 판정 (docs/answer-language.md §4-1).

언어는 판별 LLM이 정하고, 판별이 실패하거나 none을 내면 이 코드 판정이 정한다.

계약:
- 따옴표·코드 안의 글은 보지 않는다. 이슈 제목·인용은 원문 언어를 따른다.
- 식별자 형식 목록은 두지 않는다. 영어 단어로 세는 것은 알파벳만으로 된 2자 이상 단어뿐이고, 숫자·기호가 든 토큰,
  camelCase, 전부 대문자 약어는 세지 않는다 — 어떤 트래커·프로젝트의 ID도 형식을 몰라도 빠진다.
- 한글 토큰 H, 영단어 E: H=0·E=0 → 애매(None) / H=0 → E≥2이거나 E=1이 신호어면 en / H≥1 → E≥2이고 E≥2H면 en, 아니면 ko.
- 판정 순서: 질문 → 대화의 사용자 질문(최근부터, 저장된 답은 보지 않음) → 화면 언어 힌트 → 한국어.
"""

import unicodedata
import unittest

from agent.language import LanguageDecision, decide_answer_language, detect_language, normalize_ui_language

# MCP explain_commit이 language별로 만드는 질문 — backend McpQueryService의 템플릿과 같은 문장이어야 한다(서비스 간 계약)
_MCP_EXPLAIN_COMMIT_KO = "커밋 3e1d00e가 왜 이렇게 바뀌었는지 근거와 함께 설명해줘"
_MCP_EXPLAIN_COMMIT_EN = "Explain, with evidence, why commit 3e1d00e changed the way it did."

KOREAN = [
    "useEffect 왜 바꿨어?",
    "OAuth callback 수정 이유",
    "PR #20 CI 실패 원인?",
    "event_handler.py 변경 이력",
    "text-embedding-3-large로 바꾼 이유",
    "ENG-42 왜 닫혔어?",
    "PROJ-1234 진행 상황",
    "CU-8693abc 담당자 누구야?",
    "1786776420.322659 스레드 요약해줘",
    "v1.2.3 릴리스에서 뭐 바뀌었어?",
    "https://linear.app/acme/issue/ENG-42 이거 왜 이렇게 됐어?",
    "@se-zero가 올린 PR 뭐야?",
    "HT-12는 왜 닫혔어?",
    "ㅋㅋ 고마워",
    _MCP_EXPLAIN_COMMIT_KO,
]

ENGLISH = [
    "Why was 배포 script changed?",
    "What did 서준수 work on in April 2026?",
    "why HT-12?",
    "Why was 'HT-26 그래프 생성' closed?",
    "What changed in `UI_LOCALE`?",
    "don't revert it — why?",
    "Why did we switch to text-embedding-3-large?",
    "Why was ENG-42 closed?",
    "What is the status of PROJ-1234?",
    "Summarize thread 1786776420.322659",
    "What changed in v1.2.3?",
    "Explain https://linear.app/acme/issue/ENG-42",
    "What did @se-zero open last week?",
    "hi",
    "thanks",
    _MCP_EXPLAIN_COMMIT_EN,
]

AMBIGUOUS = [
    "HT-12?",
    "PR #20?",
    "https://github.com/acme/repo/pull/20",
    "ありがとう",
    "ok",
    "and?",
    "",
    "   ",
    None,
]

# 비율 규칙의 알려진 동작 — 판별 LLM이 정하지 못할 때만 쓰이는 대비책이라 바로잡지 않고 고정해 둔다(§3-3)
KNOWN_BEHAVIOR = [
    ("API rate limit 설정", "en"),
    ("Who created HT-26 그래프 생성?", "ko"),
]


class DetectLanguageTest(unittest.TestCase):
    def test_korean_questions(self):
        for text in KOREAN:
            with self.subTest(text=text):
                self.assertEqual("ko", detect_language(text))

    def test_english_questions(self):
        for text in ENGLISH:
            with self.subTest(text=text):
                self.assertEqual("en", detect_language(text))

    def test_no_language_signal_is_ambiguous(self):
        for text in AMBIGUOUS:
            with self.subTest(text=text):
                self.assertIsNone(detect_language(text))

    def test_known_behavior_of_ratio_rule(self):
        for text, expected in KNOWN_BEHAVIOR:
            with self.subTest(text=text):
                self.assertEqual(expected, detect_language(text))

    # 아래 입력들은 해당 규칙을 빼면 판정이 뒤집히도록 골랐다 — 규칙이 빠져도 통과하는 예문으로는 규칙을 지키지 못한다
    def test_hangul_inside_quotes_is_not_a_signal(self):
        for text in [
            'Why was "글로벌 검색 추가" reopened?',
            "Why was “글로벌 검색 추가” reopened?",
            "Why was ‘글로벌 검색 추가’ reopened?",
            "Who wrote 「그래프 생성」 first?",
            "Who wrote 『그래프 생성』 first?",
        ]:
            with self.subTest(text=text):
                self.assertEqual("en", detect_language(text))

    def test_english_inside_code_is_not_a_signal(self):
        self.assertEqual("ko", detect_language("`why is this failing` 왜?"))

    def test_contractions_are_english_words_not_quotes(self):
        self.assertEqual("en", detect_language("don't it's"))
        self.assertEqual("en", detect_language("Why don't they say it's 그래프 생성 now?"))

    def test_code_style_names_acronyms_and_single_letters_are_not_english_words(self):
        for text in ["useEffect useState 왜?", "PR CI API 왜?", "a b 왜?"]:
            with self.subTest(text=text):
                self.assertEqual("ko", detect_language(text))

    def test_edge_punctuation_is_stripped_before_counting(self):
        self.assertEqual("en", detect_language("Okay, sure."))

    def test_jamo_only_is_korean(self):
        self.assertEqual("ko", detect_language("ㅋㅋㅋ"))

    def test_decomposed_hangul_is_korean(self):
        # macOS 파일 이름이나 Finder에서 복사한 글은 한글이 자모로 분해된(NFD) 형태일 수 있다 — 완성형만 보면
        # 한글 토큰을 하나도 못 세서 영어 용어가 섞인 한국어 질문이 영어로 뒤집힌다
        for text in ["커밋 왜 바꿨어?", "OAuth callback 수정 이유"]:
            with self.subTest(text=text):
                self.assertEqual("ko", detect_language(unicodedata.normalize("NFD", text)))

    def test_dashes_attached_to_words_separate_them(self):
        for text in ["Why—what changed?", "Why–what changed?"]:
            with self.subTest(text=text):
                self.assertEqual("en", detect_language(text))

    def test_full_width_punctuation_is_stripped(self):
        for text in ["why？", "Okay！ sure。"]:
            with self.subTest(text=text):
                self.assertEqual("en", detect_language(text))


class NormalizeUiLanguageTest(unittest.TestCase):
    def test_supported_primary_subtags(self):
        for value, expected in [("ko", "ko"), ("en", "en"), ("en-US", "en"), ("ko_KR", "ko"), (" EN ", "en"), ("ko-kr", "ko")]:
            with self.subTest(value=value):
                self.assertEqual(expected, normalize_ui_language(value))

    def test_unsupported_or_malformed_values_are_none(self):
        for value in ["fr", "english", "", "   ", None, 5, ["en"], {"lang": "en"}]:
            with self.subTest(value=value):
                self.assertIsNone(normalize_ui_language(value))


class DecideAnswerLanguageTest(unittest.TestCase):
    def test_question_decides_first(self):
        self.assertEqual(LanguageDecision("en", "question"), decide_answer_language("Why was ENG-42 closed?"))
        self.assertEqual(LanguageDecision("ko", "question"), decide_answer_language("ENG-42 왜 닫혔어?"))

    def test_question_beats_history_and_ui_hint(self):
        history = [{"role": "user", "content": "Why was ENG-42 closed?"}]
        self.assertEqual(
            LanguageDecision("ko", "question"),
            decide_answer_language("커밋 왜 바꿨어?", history=history, ui_language="en"),
        )

    def test_ambiguous_question_inherits_latest_decidable_user_message(self):
        history = [
            {"role": "user", "content": "Why was ENG-42 closed?"},
            {"role": "assistant", "content": "ENG-42는 중복 이슈라서 닫혔어요."},
            {"role": "user", "content": "HT-12?"},
            {"role": "assistant", "content": "HT-12는 아직 진행 중이에요."},
        ]
        self.assertEqual(LanguageDecision("en", "history"), decide_answer_language("HT-13?", history=history))

    def test_latest_user_message_wins_over_older_ones(self):
        history = [
            {"role": "user", "content": "Why was ENG-42 closed?"},
            {"role": "user", "content": "ENG-43은 왜 열렸어?"},
        ]
        self.assertEqual(LanguageDecision("ko", "history"), decide_answer_language("and?", history=history))

    def test_assistant_messages_are_ignored(self):
        # 저장된 답은 한국어라 섞어 보면 영어 대화의 애매한 후속이 한국어로 뒤집힌다 — 영어 답이어도 보지 않는다
        history = [{"role": "assistant", "content": "It was merged in PR #18 to fix the callback."}]
        self.assertEqual(LanguageDecision("ko", "default"), decide_answer_language("HT-12?", history=history))

    def test_ui_hint_applies_when_question_and_history_have_no_signal(self):
        history = [{"role": "user", "content": "HT-12?"}]
        self.assertEqual(
            LanguageDecision("en", "ui"),
            decide_answer_language("PR #20?", history=history, ui_language="en-US"),
        )

    def test_invalid_ui_hint_falls_back_to_korean(self):
        for hint in ["fr", 5, "", None]:
            with self.subTest(hint=hint):
                self.assertEqual(LanguageDecision("ko", "default"), decide_answer_language("HT-12?", ui_language=hint))

    def test_empty_question_and_no_history(self):
        self.assertEqual(LanguageDecision("ko", "default"), decide_answer_language(None))
        self.assertEqual(LanguageDecision("ko", "default"), decide_answer_language("", history=[]))

    def test_malformed_history_items_are_skipped(self):
        history = [{"role": "user"}, {"content": "Why?"}, "not a dict", {"role": "user", "content": None}]
        self.assertEqual(LanguageDecision("ko", "default"), decide_answer_language("HT-12?", history=history))


if __name__ == "__main__":
    unittest.main()
