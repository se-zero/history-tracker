"""장기 문서를 DocumentSection 임베딩 단위로 분할하는 순수 함수.

Notion normalizer가 보존한 Markdown 유사 heading을 경계로 쓰되, 임베딩 입력 한 건이
너무 길어지지 않도록 문단 단위로 다시 자른다. 이 모듈은 Neo4j·OpenAI를 모르므로
문서 구조 규칙만 단위 테스트로 고정할 수 있다.
"""

from dataclasses import dataclass
import re


MAX_SECTION_CHARS = 1_500
MIN_SECTION_CHARS = 200
_HEADING = re.compile(r"^(#{1,3})\s+(.+?)\s*$")


@dataclass(frozen=True)
class DocumentSection:
    """저장 전 섹션 초안. ordinal과 embedding은 소비 경로가 추가한다."""

    heading_path: str
    text: str


def _split_long_text(text: str) -> list[str]:
    """문단 우선으로 MAX_SECTION_CHARS 이하 조각을 만든다.

    한 문단 자체가 긴 코드/표인 경우에는 줄 경계, 마지막에는 문자 경계로 나눈다.
    내용 손실보다 상한 보장이 우선이다.
    """
    if len(text) <= MAX_SECTION_CHARS:
        return [text] if text else []

    pieces: list[str] = []
    current = ""
    for paragraph in re.split(r"\n{2,}", text):
        paragraph = paragraph.strip()
        if not paragraph:
            continue
        candidate = f"{current}\n\n{paragraph}" if current else paragraph
        if len(candidate) <= MAX_SECTION_CHARS:
            current = candidate
            continue
        if current:
            pieces.append(current)
            current = ""

        # 긴 문단은 줄 단위로 먼저 자른다.
        for line in paragraph.splitlines() or [paragraph]:
            line = line.strip()
            if not line:
                continue
            candidate = f"{current}\n{line}" if current else line
            if len(candidate) <= MAX_SECTION_CHARS:
                current = candidate
                continue
            if current:
                pieces.append(current)
                current = ""
            while len(line) > MAX_SECTION_CHARS:
                pieces.append(line[:MAX_SECTION_CHARS])
                line = line[MAX_SECTION_CHARS:]
            current = line

    if current:
        pieces.append(current)
    return pieces


def _merge_short_sections(sections: list[DocumentSection]) -> list[DocumentSection]:
    """200자 미만 조각을 다음 섹션에 병합한다.

    다른 heading으로 넘어가는 짧은 조각은 원래 heading을 본문에 남긴다. 그래서 다음
    섹션의 heading_path를 검색 근거로 쓰더라도 짧은 전제·목차가 사라지지 않는다.
    병합 뒤 길이가 상한을 넘으면 다시 분할한다.
    """
    merged: list[DocumentSection] = []
    index = 0
    while index < len(sections):
        section = sections[index]
        if len(section.text) >= MIN_SECTION_CHARS or index == len(sections) - 1:
            merged.append(section)
            index += 1
            continue

        following = sections[index + 1]
        combined = f"{section.heading_path}\n{section.text}\n\n{following.text}"
        merged.extend(
            DocumentSection(following.heading_path, chunk)
            for chunk in _split_long_text(combined)
        )
        index += 2
    return merged


def chunk_document(title: str, body: str) -> list[DocumentSection]:
    """본문을 heading 경계 섹션으로 분리한다.

    heading 앞 서두와 heading 없는 문서는 문서 제목을 heading_path로 쓴다. h1~h3의
    계층은 `상위 > 하위` 형태로 보존해 섹션 텍스트만으로 빠지는 맥락을 임베딩 입력에
    다시 보탠다.
    """
    document_title = title.strip() or "제목 없는 문서"
    groups: list[tuple[str, str]] = []
    heading_stack: list[str | None] = [None, None, None]
    current_path = document_title
    current_lines: list[str] = []

    def flush() -> None:
        text = "\n".join(current_lines).strip()
        if text:
            groups.append((current_path, text))

    for line in (body or "").splitlines():
        matched = _HEADING.match(line)
        if not matched:
            current_lines.append(line)
            continue

        flush()
        current_lines = []
        level = len(matched.group(1))
        heading_stack[level - 1] = matched.group(2).strip()
        for deeper in range(level, len(heading_stack)):
            heading_stack[deeper] = None
        current_path = " > ".join(part for part in heading_stack if part)

    flush()

    sections = [
        DocumentSection(heading_path, chunk)
        for heading_path, text in groups
        for chunk in _split_long_text(text)
    ]
    return _merge_short_sections(sections)
