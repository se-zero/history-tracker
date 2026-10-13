"""답변 언어 — 본 지시 전체 영어판과 한국어판의 동기화 가드 (docs/answer-language.md §4-2).

계약:
- 영어 답의 첫 시스템 메시지는 본 지시 전체 영어판(orchestrator._SYSTEM_PROMPT_EN)이다. 영어판 원문은
  agent/prompt_en.py의 SYSTEM_PROMPT_EN_TEMPLATE이고, 한국어판(_SYSTEM_PROMPT_TEMPLATE)과 같은 자리표시자로 수치·용어집을
  채운다. 스키마 카드는 영어판이 번역해 품고 있어 자리표시자가 없다.
- 영어판은 번역 기준이 된 한국어 본 지시의 지문(prompt_en.TRANSLATED_FROM)을 갖는다. 지문은 한국어 템플릿(수치·용어집은
  자리표시자 그대로)과 SCHEMA_CARD로 만든다. 한국어 쪽이 바뀌면 이 테스트가 실패한다 — 영어판도 같은 뜻으로 고치고 지문을
  갱신해야 통과한다. 용어집은 glossary.py가 언어별로 만들어 붙이므로 지문에서 빠진다(용어 수정이 가드를 깨지 않게).
- 두 판은 단원 수가 같고, 단원마다 한국어판이 이름을 든 도구를 영어판도 든다. 단원 단위로는 영어판이 더 들 수 있다 —
  영어판 [Answer rules]에만 있는 "영어 검색이 약하면 한국어로 한 번 더" 줄이 검색 도구 둘을 더 든다. 프롬프트 전체가 이름을
  드는 도구 집합은 두 판이 같다(그 검색 도구들은 한국어판 다른 단원에도 나온다). 영어 전용 줄이 한국어판 어디에도 없는 도구를
  들게 되면 전체 집합 검사를 그에 맞게 고친다.
- 영어판에 남는 한글은 실제 기록 값을 보여 주는 데이터 예시('그래프 생성', '완료')뿐이다.
"""

import hashlib
import os
import re
import unittest

os.environ.setdefault("OPENAI_API_KEY", "test-key")

from agent import orchestrator, prompt_en
from agent.glossary import prompt_glossary
from tools.definitions import TOOLS
from tools.queries import SCHEMA_CARD

_TOOL_NAMES = sorted((tool["function"]["name"] for tool in TOOLS), key=len, reverse=True)
_TOOL_RE = re.compile(r"(?<![A-Za-z0-9_])(?:" + "|".join(map(re.escape, _TOOL_NAMES)) + r")(?![A-Za-z0-9_])")
_HANGUL_RUN_RE = re.compile(r"[가-힣ㄱ-ㅎㅏ-ㅣ](?:[가-힣ㄱ-ㅎㅏ-ㅣ ]*[가-힣ㄱ-ㅎㅏ-ㅣ])?")
_PLACEHOLDER_RE = re.compile(r"__[A-Z_]+__")
_ALLOWED_HANGUL = {"그래프 생성", "완료"}


def _korean_fingerprint() -> str:
    """번역 기준 지문 — 한국어 본 지시 템플릿과 스키마 카드. 용어집은 자리표시자로만 들어간다."""
    body = orchestrator._SYSTEM_PROMPT_TEMPLATE + "\n" + SCHEMA_CARD
    return hashlib.sha256(body.encode("utf-8")).hexdigest()[:16]


def _sections(prompt: str) -> list[str]:
    """'['로 시작하는 줄을 단원 제목으로 보고, 단원마다 제목과 본문을 이은 글을 돌려준다(첫 제목 앞 머리말은 뺀다)."""
    sections: list[list[str]] = []
    for line in prompt.splitlines():
        if line.startswith("["):
            sections.append([line])
        elif sections:
            sections[-1].append(line)
    return ["\n".join(lines) for lines in sections]


class TranslationSyncGuardTest(unittest.TestCase):
    def test_translation_base_matches_current_korean_prompt(self):
        current = _korean_fingerprint()
        self.assertEqual(
            current,
            prompt_en.TRANSLATED_FROM,
            "한국어 본 지시(_SYSTEM_PROMPT_TEMPLATE) 또는 SCHEMA_CARD가 바뀌었다. agent/prompt_en.py의 영어판을 같은 뜻으로 "
            f'고친 뒤 TRANSLATED_FROM을 "{current}"로 갱신한다 (docs/answer-language.md §4-2).',
        )

    def test_same_number_of_sections(self):
        self.assertEqual(
            len(_sections(orchestrator._SYSTEM_PROMPT)),
            len(_sections(orchestrator._SYSTEM_PROMPT_EN)),
        )

    def test_each_english_section_names_the_tools_its_korean_section_names(self):
        korean, english = _sections(orchestrator._SYSTEM_PROMPT), _sections(orchestrator._SYSTEM_PROMPT_EN)
        for index, (ko_section, en_section) in enumerate(zip(korean, english)):
            with self.subTest(section=ko_section.splitlines()[0]):
                missing = set(_TOOL_RE.findall(ko_section)) - set(_TOOL_RE.findall(en_section))
                self.assertEqual(set(), missing, f"영어판 {index}번째 단원에 빠진 도구")

    def test_both_prompts_name_the_same_tools(self):
        self.assertEqual(
            set(_TOOL_RE.findall(orchestrator._SYSTEM_PROMPT)),
            set(_TOOL_RE.findall(orchestrator._SYSTEM_PROMPT_EN)),
        )


class EnglishPromptRenderingTest(unittest.TestCase):
    def test_english_template_shares_the_korean_placeholders(self):
        for placeholder in ["__MIN_CONF__", "__MAX_GQ__", "__GLOSSARY__"]:
            with self.subTest(placeholder=placeholder):
                self.assertEqual(1, orchestrator._SYSTEM_PROMPT_TEMPLATE.count(placeholder))
                self.assertEqual(1, prompt_en.SYSTEM_PROMPT_EN_TEMPLATE.count(placeholder))
        self.assertEqual(1, orchestrator._SYSTEM_PROMPT_TEMPLATE.count("__SCHEMA_CARD__"))
        self.assertNotIn("__SCHEMA_CARD__", prompt_en.SYSTEM_PROMPT_EN_TEMPLATE)

    def test_english_prompt_fills_placeholders_with_shared_values(self):
        # 자리표시자 앞뒤 글을 템플릿에서 그대로 가져와 확인한다 — 영어 문구를 고쳐도 테스트를 고칠 필요가 없다
        template, rendered = prompt_en.SYSTEM_PROMPT_EN_TEMPLATE, orchestrator._SYSTEM_PROMPT_EN
        for placeholder, value in [
            ("__MIN_CONF__", f"{orchestrator._MIN_CONFIDENCE:g}"),
            ("__MAX_GQ__", str(orchestrator._MAX_GRAPH_QUERY_CALLS)),
            ("__GLOSSARY__", prompt_glossary("en")),
        ]:
            with self.subTest(placeholder=placeholder):
                before, after = template.split(placeholder)
                self.assertIn(before[-40:] + value + after[:40], rendered)

    def test_rendered_prompts_have_no_leftover_placeholders(self):
        for name in ["_SYSTEM_PROMPT", "_SYSTEM_PROMPT_EN"]:
            with self.subTest(prompt=name):
                self.assertIsNone(_PLACEHOLDER_RE.search(getattr(orchestrator, name)))

    def test_english_prompt_has_no_hangul_except_data_examples(self):
        found = set(_HANGUL_RUN_RE.findall(orchestrator._SYSTEM_PROMPT_EN))
        self.assertEqual(set(), found - _ALLOWED_HANGUL)

    def test_korean_prompt_keeps_the_korean_glossary(self):
        self.assertIn(prompt_glossary("ko"), orchestrator._SYSTEM_PROMPT)
        self.assertNotIn(prompt_glossary("en"), orchestrator._SYSTEM_PROMPT)


if __name__ == "__main__":
    unittest.main()
