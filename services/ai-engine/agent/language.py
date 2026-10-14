"""답변 언어 판정 — 판별 LLM이 정하지 못할 때 쓰는 대비책 (docs/answer-language.md §4-1).

언어는 발화 판별 LLM 호출이 정한다. 이 모듈은 그 호출을 건너뛰거나(그래프 노드를 집어 물음), 호출이
실패하거나, 결과가 none일 때만 쓰이는 결정적 규칙이다. 그래서 순수 함수만 두고 OpenAI·I/O·import 시점
부작용을 갖지 않는다 — 오프라인 단위 테스트로 동작 전체를 고정할 수 있다.

판정은 식별자 형식을 하나도 모른 채 한다. 이슈 키(HT-12·ENG-42·CU-8693abc)나 URL 형식은 트래커·프로젝트마다
달라 목록으로 관리할 수 없다. 대신 "영어 단어로 셀 자격"을 모양으로 좁힌다 — 알파벳만으로 된 2자 이상 단어만
세고, 숫자·기호가 든 토큰, 코드식 대소문자(useEffect), 전부 대문자 약어(PR·CI)는 세지 않는다. 그러면 어떤
형식의 ID도 형식을 몰라도 저절로 빠진다.

한글 토큰 H와 영단어 E를 견주는 이유: 한국어 문장은 영어 용어를 섞어도 조사·어미가 한글 토큰에 남고, 영어
문장은 한국어를 이름·제목 정도로만 섞는다. 이 비율 규칙에는 알려진 오판이 있다(`API rate limit 설정`→en,
`Who created HT-26 그래프 생성?`→ko). 판정의 주인이 판별 LLM이라 여기서는 특례를 늘리지 않고, 테스트에
알려진 동작으로 고정해 두었다(§3-3).
"""

import re
import unicodedata
from dataclasses import dataclass

DEFAULT_LANGUAGE = "ko"
SUPPORTED_LANGUAGES = ("ko", "en")

# 한글 없이 영단어가 하나뿐인 질문("why HT-12?", "thanks")을 영어로 볼 수 있는 단어 — 의문사·명령 동사·인사.
# 이 밖의 단어 하나("ok", "and")는 어느 언어의 문장인지 알 수 없어 애매로 남긴다.
_SIGNAL_WORDS = frozenset({
    "why", "what", "how", "when", "where", "who", "which",
    "explain", "describe", "summarize", "show", "list", "tell",
    "hi", "hello", "hey", "thanks",
})

# 따옴표·코드 안의 글은 질문자의 언어가 아니라 원문(이슈 제목·인용·코드)의 언어를 따른다. 짝이 맞는 구간만
# 지운다 — 짝 없는 따옴표를 만났다고 뒤쪽 글을 통째로 잃으면 안 된다.
# '…'만 조건이 붙는다. 여는 따옴표 앞이나 닫는 따옴표 뒤에 ASCII 알파벳이 붙어 있으면 don't·it's의
# 아포스트로피라 인용이 아니다. \b/\B를 쓰지 않는 것은 한글이 \w라서 `제목'그래프'`나 `'그래프'은`처럼
# 한글에 붙은 따옴표를 영어 단어에 붙은 것과 구분하지 못하기 때문이다.
_QUOTED_SPAN_RE = re.compile(
    r"`[^`]*`"
    r'|"[^"]*"'
    r"|“[^”]*”"
    r"|‘[^’]*’"
    r"|「[^」]*」"
    r"|『[^』]*』"
    r"|(?<![A-Za-z])'[^']*'(?![A-Za-z])"
)

# 토큰 가장자리의 문장부호(전각 포함). 짝이 안 맞아 남은 따옴표도 여기서 벗긴다. - _ / # @ 같은 식별자 문자는
# 넣지 않는다 — 넣으면 `--force`·`@alice`의 접두 기호가 벗겨져 식별자 조각이 영단어로 둔갑한다.
_EDGE_PUNCTUATION = ".,!?;:()[]\"'“”‘’「」『』…？！。，、：；（）"
# 줄표는 단어 사이를 가르는 문장부호라 공백으로 바꾼다("Why—what"). 하이픈(-)은 식별자(HT-12,
# text-embedding-3-large)를 한 토큰으로 지켜야 하므로 건드리지 않는다.
_DASHES_TO_SPACE = str.maketrans({"—": " ", "–": " "})

# 한글 음절과 자모. 자음/모음만 쓴 "ㅋㅋ"도 한국어 신호다.
_HANGUL_RE = re.compile(r"[가-힣ㄱ-ㅎㅏ-ㅣ]")
# 알파벳만으로 된 단어(축약형 아포스트로피 하나까지 허용). 숫자·기호가 하나라도 있으면 이슈 키·해시·경로·
# URL·버전일 수 있어 단어로 세지 않는다.
_ENGLISH_WORD_RE = re.compile(r"[A-Za-z]+(?:'[A-Za-z]+)?")
# 소문자 바로 뒤의 대문자 — useEffect·GitHub 같은 코드식 표기. 한국어 문장에 섞이는 영어 용어이지 영어
# 문장의 단어가 아니다.
_CAMEL_CASE_RE = re.compile(r"[a-z][A-Z]")


@dataclass(frozen=True)
class LanguageDecision:
    """정한 언어와 그 근거. source는 question | history | ui | default."""

    language: str
    source: str


def _is_english_word(token: str) -> bool:
    return (
        len(token) >= 2
        and _ENGLISH_WORD_RE.fullmatch(token) is not None
        and not token.isupper()
        and _CAMEL_CASE_RE.search(token) is None
    )


def detect_language(text: str | None) -> str | None:
    """글의 언어를 "ko" | "en"으로 판정한다. 언어 신호가 없으면 None(애매).

    None은 "한국어"가 아니라 "모른다"다 — 호출부가 대화 기록·화면 언어 힌트로 넘어갈 수 있게 구분한다.
    """
    if not text:
        return None
    # macOS 파일 이름·Finder에서 복사한 글은 한글이 자모로 분해된(NFD) 형태일 수 있다. 완성형으로 모으지 않으면
    # 한글 토큰을 못 세서 영어 용어가 섞인 한국어 질문이 영어로 뒤집힌다.
    text = unicodedata.normalize("NFC", text).translate(_DASHES_TO_SPACE)

    hangul_tokens = 0
    english_words: list[str] = []
    # 지운 구간은 빈 문자열이 아니라 공백으로 바꾼다 — 앞뒤 글이 한 토큰으로 붙지 않게 한다.
    for raw in _QUOTED_SPAN_RE.sub(" ", text).split():
        token = raw.strip(_EDGE_PUNCTUATION)
        # `HT-12는`·`text-embedding-3-large로`처럼 조사가 붙은 식별자도 한글 토큰이다 — 한국어 문장의
        # 조사·어미를 세려는 것이라 식별자 모양은 따지지 않는다.
        if _HANGUL_RE.search(token):
            hangul_tokens += 1
        elif _is_english_word(token):
            english_words.append(token)

    english = len(english_words)
    if hangul_tokens == 0:
        if english >= 2 or (english == 1 and english_words[0].lower() in _SIGNAL_WORDS):
            return "en"
        return None
    return "en" if english >= 2 and english >= 2 * hangul_tokens else "ko"


def normalize_ui_language(value: object) -> str | None:
    """화면 언어 힌트(`en-US`, `ko_KR` 등)를 지원 언어로 줄인다. 쓸 수 없는 값은 None.

    힌트는 참고용이라 이상한 값을 오류로 막지 않고 버린다 — 요청 전체가 422로 실패할 일이 아니다.
    """
    if not isinstance(value, str):
        return None
    primary = value.strip().lower().replace("_", "-").split("-", 1)[0]
    return primary if primary in SUPPORTED_LANGUAGES else None


def decide_answer_language(
    question: str | None,
    history: list[dict[str, str]] | None = None,
    ui_language: object = None,
) -> LanguageDecision:
    """질문 → 대화의 사용자 질문(최근부터) → 화면 언어 힌트 → 기본(한국어) 순으로 답변 언어를 정한다."""
    language = detect_language(question)
    if language:
        return LanguageDecision(language, "question")

    # 사용자 말만 본다. 저장된 답은 한국어라 섞어 보면 영어 대화의 애매한 후속("HT-12?")이 한국어로
    # 뒤집힌다. 영어 답이 저장돼 있어도 보지 않는다 — 근거를 사용자 말 하나로 두어 규칙을 단순하게 유지한다.
    for message in reversed(history or []):
        if not isinstance(message, dict) or message.get("role") != "user":
            continue
        content = message.get("content")
        if not isinstance(content, str):
            continue
        language = detect_language(content)
        if language:
            return LanguageDecision(language, "history")

    language = normalize_ui_language(ui_language)
    if language:
        return LanguageDecision(language, "ui")
    return LanguageDecision(DEFAULT_LANGUAGE, "default")
