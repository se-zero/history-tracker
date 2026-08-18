import asyncio
import base64
import logging
import os

import httpx

from openai_client import Priority, chat_completion

logger = logging.getLogger(__name__)

# (owner, repo) → 요약 결과 캐시. None은 "요약 불가" 캐시값.
_summary_cache: dict[tuple[str, str], str | None] = {}

_MIN_README_LENGTH = 100
_MAX_README_CHARS = 30000

_SUMMARIZE_PROMPT = """\
아래는 GitHub 레포지토리의 README입니다.
이 프로젝트가 무엇인지, 어떤 기술을 사용하는지, 어떤 기능을 다루는지 구체적으로 설명하세요.

단, README가 자동 생성된 보일러플레이트이거나 프로젝트 고유 설명이 없는 경우
(예: NestJS/CRA 기본 README, "# Project Name" 한 줄짜리 등) 정확히 아래 한 단어만 반환하세요:
null

설명이 있는 경우 3~5문장으로 자유롭게 서술하세요. 포함할 내용:
- 프로젝트 목적과 핵심 기능
- 사용 기술 스택
- 주요 도메인 개념 (있는 경우)

마크다운, 불릿, 헤더 없이 plain text로만 작성하세요.
"""


def _fetch_readme(owner: str, repo: str) -> str | None:
    token = os.environ.get("GITHUB_TOKEN", "")
    headers = {"Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"}
    if token:
        headers["Authorization"] = f"Bearer {token}"

    url = f"https://api.github.com/repos/{owner}/{repo}/readme"
    response = httpx.get(url, headers=headers, timeout=10)
    if response.status_code == 404:
        return None
    response.raise_for_status()

    content = response.json().get("content", "")
    return base64.b64decode(content).decode("utf-8")


def _is_null_response(text: str) -> bool:
    """LLM 응답이 'null 의도' 인지 보수적으로 판단.
    'null', 'Null.', 'null입니다' 등 'null' 토큰으로 시작하는 짧은 응답은 모두 null로 처리.
    """
    stripped = text.strip().lower()
    if not stripped:
        return True
    # 30자 이내이면서 'null'로 시작하면 null 의도로 간주
    return len(stripped) <= 30 and stripped.startswith("null")


async def _summarize(readme: str, priority: Priority) -> str | None:
    if len(readme) > _MAX_README_CHARS:
        logger.info("README 크기 초과 (%d자), 앞 %d자만 사용", len(readme), _MAX_README_CHARS)
        readme = readme[:_MAX_README_CHARS]

    try:
        response = await chat_completion(
            priority=priority,
            model="gpt-4o-mini",
            messages=[
                {"role": "system", "content": _SUMMARIZE_PROMPT},
                {"role": "user", "content": readme},
            ],
            temperature=0,
        )
    except Exception:
        logger.exception("프로젝트 요약 LLM 호출 실패")
        return None

    result = response.choices[0].message.content.strip()
    return None if _is_null_response(result) else result


async def get_project_summary(owner: str, repo: str, *, priority: Priority = Priority.BACKGROUND) -> str | None:
    """README를 가져와 LLM으로 요약한 프로젝트 설명 반환. (owner, repo) 단위로 캐시.

    priority: 라이브 질의 경로(캐시 미스 시 사용자가 대기)는 INTERACTIVE,
              pre-warm·운영 트리거는 BACKGROUND(기본).

    Returns:
        요약 문자열, 또는 README가 없거나 의미 없는 경우 None
    """
    key = (owner, repo)
    if key in _summary_cache:
        return _summary_cache[key]

    summary: str | None = None
    try:
        # _fetch_readme는 동기 httpx 호출이라 이벤트 루프를 막지 않게 to_thread로 분리.
        readme = await asyncio.to_thread(_fetch_readme, owner, repo)
        if readme is None:
            logger.info("README 없음: %s/%s", owner, repo)
        elif len(readme.strip()) < _MIN_README_LENGTH:
            logger.info("README 너무 짧음 (%d자): %s/%s", len(readme.strip()), owner, repo)
        else:
            summary = await _summarize(readme, priority)
            if summary is None:
                logger.info("README 내용 불충분 또는 요약 실패: %s/%s", owner, repo)
            else:
                logger.info("프로젝트 컨텍스트 로드 완료: %s/%s", owner, repo)
    except Exception:
        logger.exception("README 로드 실패: %s/%s", owner, repo)

    _summary_cache[key] = summary
    return summary
