import base64
import logging
import os

import httpx
from openai import OpenAI

logger = logging.getLogger(__name__)

_client = OpenAI(api_key=os.environ["OPENAI_API_KEY"])

_NOT_LOADED = object()
_cached_summary: str | None = _NOT_LOADED  # type: ignore[assignment]

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

_MIN_README_LENGTH = 100


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


def _summarize(readme: str) -> str | None:
    response = _client.chat.completions.create(
        model="gpt-4o-mini",
        messages=[
            {"role": "system", "content": _SUMMARIZE_PROMPT},
            {"role": "user", "content": readme},
        ],
        temperature=0,
    )
    result = response.choices[0].message.content.strip()
    return None if result.lower() == "null" else result


def get_project_summary(owner: str, repo: str) -> str | None:
    """README를 가져와 LLM으로 요약한 프로젝트 설명 반환. 최초 1회만 실행 후 캐시.

    Returns:
        요약 문자열, 또는 README가 없거나 의미 없는 경우 None
    """
    global _cached_summary
    if _cached_summary is not _NOT_LOADED:
        return _cached_summary  # type: ignore[return-value]

    try:
        readme = _fetch_readme(owner, repo)
        if readme is None:
            logger.info("README 없음: %s/%s", owner, repo)
            _cached_summary = None
        elif len(readme.strip()) < _MIN_README_LENGTH:
            logger.info("README 너무 짧음 (%d자): %s/%s", len(readme.strip()), owner, repo)
            _cached_summary = None
        else:
            _cached_summary = _summarize(readme)
            if _cached_summary is None:
                logger.info("README 내용 불충분 (LLM 판단): %s/%s", owner, repo)
            else:
                logger.info("프로젝트 컨텍스트 로드 완료: %s/%s", owner, repo)
    except Exception as e:
        logger.warning("README 로드 실패: %s", e)
        _cached_summary = None

    return _cached_summary  # type: ignore[return-value]
