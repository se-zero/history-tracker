import logging

from openai_client import Priority, chat_completion

logger = logging.getLogger(__name__)

_MAX_DIFF_CHARS = 20000
# 요약 출력 토큰 상한. 데이터·문서 파일에서 1만 자 넘는 요약이 나와 임베딩 입력 상한을 넘겼다.
# 한국어로 25~40줄 분량.
_MAX_SUMMARY_TOKENS = 1000

SYSTEM_PROMPT = """\
당신은 코드 변경사항 요약가입니다. 소스 파일의 unified diff를 받아 한국어로 간결하게 요약하세요.

규칙:
- 각 줄은 [추가], [제거], [수정] 중 하나로 시작
- 함수명, 클래스명, 변수명은 백틱으로 감싸서 원문 그대로 유지
- diff 내 주석에 변경 이유가 있으면 포함
- diff의 `+`/`-` 기호는 줄 추가/제거를 나타내는 것이며 파일명이나 내용의 일부가 아님
- 공백, 포맷팅, 빈 줄만 바뀐 의미 없는 변경은 무시
- 한 줄에 하나의 변경사항
- 변경이 매우 많은 파일(데이터·문서·설정 등)은 항목을 전부 나열하지 말고 성격별로 묶어 15줄 이내로 요약
- 코드블록(```) 사용 금지, 마크다운 문법 사용 금지
- 의미 있는 변경사항이 없으면 빈 문자열 출력
"""


def _size_placeholder(path: str, additions: int, deletions: int, message: str) -> str:
    return f"[수정] `{path}` — {additions}줄 추가, {deletions}줄 제거 (diff 크기 초과, 커밋: \"{message}\")"


async def summarize_diff(path: str, diff: str, additions: int = 0, deletions: int = 0, message: str = "") -> str:
    if not diff or not diff.strip():
        if additions or deletions:
            return _size_placeholder(path, additions, deletions, message)
        return ""

    # null 바이트 및 제어 문자 제거 (바이너리성 diff에서 JSON 파싱 오류 방지)
    diff = diff.replace("\x00", "").encode("utf-8", errors="ignore").decode("utf-8")
    if not diff.strip():
        if additions or deletions:
            return _size_placeholder(path, additions, deletions, message)
        return ""

    # 임계치 초과 시 LLM 호출 없이 placeholder 반환 (토큰 초과/비용 방지)
    if len(diff) > _MAX_DIFF_CHARS:
        logger.info("diff 크기 초과로 LLM 요약 생략: path=%s size=%d", path, len(diff))
        return _size_placeholder(path, additions, deletions, message)

    try:
        response = await chat_completion(
            priority=Priority.BACKGROUND,
            model="gpt-4o-mini",
            messages=[
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": f"File: {path}\n\n{diff}"},
            ],
            temperature=0,
            max_completion_tokens=_MAX_SUMMARY_TOKENS,
        )
        choice = response.choices[0]
        text = (choice.message.content or "").strip()
        if choice.finish_reason == "length":
            # 상한에 걸려 잘린 응답은 마지막 줄이 문장 중간에서 끊겼을 수 있어 그 줄을 버린다
            logger.info("요약 출력 상한 도달, 마지막 줄 제거: path=%s", path)
            if "\n" in text:
                text = text.rsplit("\n", 1)[0].rstrip()
        return text
    except Exception:
        logger.exception("LLM 요약 실패, placeholder로 대체: path=%s", path)
        return _size_placeholder(path, additions, deletions, message)
