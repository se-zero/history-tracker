"""Slack LLM 필터에 넘길 프로젝트 프로필을 Neo4j 그래프에서 자동 도출한다.

README나 온보딩 설문 같은 별도 입력 없이, 이미 수집된 PR·이슈·커밋·파일·문서 노드만으로
"이 프로젝트가 무엇을 하는 프로젝트인지"를 LLM이 요약한다 — 다른 서비스(backend·
pipeline-worker)를 변경하지 않고 ai-engine 안에서 완결된다.

요약은 그래프 후처리 빌드마다(활발한 프로젝트는 하루 수십 번) 다시 만들지 않고, 프로젝트별로
메모리에 캐시해 PROJECT_PROFILE_TTL_SECONDS(기본 24시간) 동안 재사용한다. 재료 부족·LLM null로
빈 문자열이 캐시된 경우는 EMPTY_PROFILE_TTL_SECONDS(기본 1시간)로 더 짧게 캐시한다 — 신규·소규모
프로젝트가 그날 그래프를 채워도 하루 종일 컨텍스트 없이 판정하지 않도록 한다.

재료(PR·이슈·커밋 제목 합)가 MIN_MATERIAL_ITEMS 미만이거나 LLM이 설명 불가로 판단하면 빈
문자열을 반환한다 — 호출자(slack_llm_filter.filter_messages)는 project_context가 빈
문자열이면 컨텍스트 블록 없이 기존 방식대로 동작하므로, 신규·소규모 프로젝트도 안전하다.

각 재료는 최신 절반 + 가장 오래된 절반을 섞어 뽑는다 — 최신 N개만 쓰면 최근 며칠 작업만
반영돼 프로젝트 정체성(초기 목적, 핵심 도메인)이 빠지기 때문이다.
"""

import logging
import os
import re
import time
from collections import Counter

from graph.driver import close_driver, get_driver
from openai_client import Priority, chat_completion

logger = logging.getLogger(__name__)

# pr_titles + issue_titles + commit_messages 합이 이 미만이면 LLM 없이 빈 문자열을 반환한다
# (재료가 부실하면 LLM이 뭉뚱그린 설명을 지어낼 위험이 더 크다).
MIN_MATERIAL_ITEMS = 10
PROFILE_MODEL = "gpt-4o-mini"

# 빈 프로필(재료 부족·LLM null)의 캐시 TTL — 이 경우 LLM을 부르지 않으므로 재조회 비용은
# Cypher 쿼리 한 번뿐이라, 정상 프로필보다 짧게 잡아 그래프가 채워지면 금방 반영되게 한다.
EMPTY_PROFILE_TTL_SECONDS = 3600.0

# 재료별 한도 (최신 절반 + 가장 오래된 절반으로 나눠 뽑는다 — mixed_sample 참고)
PR_LIMIT = 20
ISSUE_LIMIT = 30
COMMIT_LIMIT = 30

_SYSTEM_PROMPT = (
    "당신은 소프트웨어 프로젝트의 협업 기록(저장소 이름, 이슈·PR 제목, 커밋 메시지, "
    "디렉터리 구조, 문서 제목)을 보고 그 프로젝트를 설명하는 도우미입니다."
)

_REPO_URL_RE = re.compile(r"github\.com/([^/]+)/([^/]+)/")

# 프로젝트별 (프로필, 캐시 시각) — 캐시 시각은 time.monotonic() 기준
_profile_cache: dict[str, tuple[str, float]] = {}


def repo_names_from_urls(urls: list[str]) -> list[str]:
    """PR url에서 "owner/repo"를 파싱한다. 등장 순서를 유지하며 중복은 제거하고,
    github.com 패턴이 아닌 url은 무시한다 (저장소·프로젝트 노드가 없어 PR url이 유일한 출처)."""
    names: list[str] = []
    seen: set[str] = set()
    for url in urls:
        if not url:
            continue
        match = _REPO_URL_RE.search(url)
        if not match:
            continue
        name = f"{match.group(1)}/{match.group(2)}"
        if name not in seen:
            seen.add(name)
            names.append(name)
    return names


def top_dirs_from_paths(paths: list[str], depth: int = 2, k: int = 10) -> list[str]:
    """파일 경로의 디렉터리 부분을 depth 단계까지 잘라 빈도 상위 k개를 반환한다.

    디렉터리가 없는 경로(예: "README.md")는 제외한다. 빈도 내림차순, 동률은 이름순.
    """
    counts: Counter[str] = Counter()
    for path in paths:
        if not path:
            continue
        parts = path.split("/")[:-1]
        if not parts:
            continue
        counts["/".join(parts[:depth])] += 1
    ranked = sorted(counts.items(), key=lambda item: (-item[1], item[0]))
    return [name for name, _ in ranked[:k]]


def mixed_sample(latest: list, oldest: list, key) -> list:
    """latest 뒤에 oldest 중 key(x)가 latest에 없는 항목만 순서대로 붙인다(중복 제거).

    전체 개수가 한도 이하라 최신/최초 두 쿼리 결과가 겹치는 경우를 이렇게 처리한다.
    """
    seen = {key(item) for item in latest}
    merged = list(latest)
    for item in oldest:
        item_key = key(item)
        if item_key not in seen:
            seen.add(item_key)
            merged.append(item)
    return merged


def material_item_count(material: dict) -> int:
    return (
        len(material.get("pr_titles") or [])
        + len(material.get("issue_titles") or [])
        + len(material.get("commit_messages") or [])
    )


def build_summary_prompt(material: dict) -> str:
    """summarize_material의 user 메시지 본문 — 순수 함수(재료 나열 + 출력 지시)."""
    sections: list[str] = []
    if material.get("repo_names"):
        sections.append("저장소: " + ", ".join(material["repo_names"]))
    if material.get("pr_titles"):
        sections.append("PR 제목:\n" + "\n".join(f"- {t}" for t in material["pr_titles"]))
    if material.get("issue_titles"):
        sections.append("이슈 제목:\n" + "\n".join(f"- {t}" for t in material["issue_titles"]))
    if material.get("commit_messages"):
        sections.append("커밋 메시지:\n" + "\n".join(f"- {m}" for m in material["commit_messages"]))
    if material.get("top_dirs"):
        sections.append("주요 디렉터리: " + ", ".join(material["top_dirs"]))
    if material.get("document_titles"):
        sections.append("문서 제목:\n" + "\n".join(f"- {t}" for t in material["document_titles"]))

    body = "\n\n".join(sections)
    return (
        f"{body}\n\n"
        "이 프로젝트가 무엇을 만드는지, 어떤 기술 스택과 도메인 용어를 쓰는지 3~5문장으로 "
        "서술하세요. 마크다운·불릿·헤더 없이 plain text로만 씁니다. 자료가 부족해 설명할 수 "
        "없으면 다른 말 없이 정확히 null 한 단어만 답하세요."
    )


def _truncate_first_line(text: str, limit: int = 120) -> str:
    lines = (text or "").splitlines()
    return lines[0][:limit] if lines else ""


async def fetch_profile_material(project_id: str) -> dict:
    """project_id 그래프에서 프로필 요약 재료를 조회한다.

    PR·Issue·ChangeSet은 각각 최신 절반 + 가장 오래된 절반을 쿼리해 mixed_sample로 합친다
    (문서·파일은 한도가 작아 그대로 최신순 유지).
    """
    async with get_driver().session() as session:
        pr_latest_result = await session.run(
            """
            MATCH (pr:PullRequest {project_id: $project_id})
            RETURN pr.url AS url, pr.title AS title, pr.pr_number AS pr_number
            ORDER BY pr.occurredAt DESC
            LIMIT $limit
            """,
            project_id=project_id,
            limit=PR_LIMIT // 2,
        )
        pr_latest_rows = await pr_latest_result.data()

        pr_oldest_result = await session.run(
            """
            MATCH (pr:PullRequest {project_id: $project_id})
            RETURN pr.url AS url, pr.title AS title, pr.pr_number AS pr_number
            ORDER BY pr.occurredAt ASC
            LIMIT $limit
            """,
            project_id=project_id,
            limit=PR_LIMIT // 2,
        )
        pr_oldest_rows = await pr_oldest_result.data()
        pr_rows = mixed_sample(pr_latest_rows, pr_oldest_rows, key=lambda r: r["pr_number"])

        issue_latest_result = await session.run(
            """
            MATCH (i:Issue {project_id: $project_id})
            WHERE i.source <> '__stub__'
            RETURN i.title AS title, i.issue_type AS issue_type, i.external_id AS external_id
            ORDER BY i.occurredAt DESC
            LIMIT $limit
            """,
            project_id=project_id,
            limit=ISSUE_LIMIT // 2,
        )
        issue_latest_rows = await issue_latest_result.data()

        issue_oldest_result = await session.run(
            """
            MATCH (i:Issue {project_id: $project_id})
            WHERE i.source <> '__stub__'
            RETURN i.title AS title, i.issue_type AS issue_type, i.external_id AS external_id
            ORDER BY i.occurredAt ASC
            LIMIT $limit
            """,
            project_id=project_id,
            limit=ISSUE_LIMIT // 2,
        )
        issue_oldest_rows = await issue_oldest_result.data()
        issue_rows = mixed_sample(issue_latest_rows, issue_oldest_rows, key=lambda r: r["external_id"])

        changeset_latest_result = await session.run(
            """
            MATCH (c:ChangeSet {project_id: $project_id})
            RETURN c.message AS message, c.hash AS hash
            ORDER BY c.occurredAt DESC
            LIMIT $limit
            """,
            project_id=project_id,
            limit=COMMIT_LIMIT // 2,
        )
        changeset_latest_rows = await changeset_latest_result.data()

        changeset_oldest_result = await session.run(
            """
            MATCH (c:ChangeSet {project_id: $project_id})
            RETURN c.message AS message, c.hash AS hash
            ORDER BY c.occurredAt ASC
            LIMIT $limit
            """,
            project_id=project_id,
            limit=COMMIT_LIMIT // 2,
        )
        changeset_oldest_rows = await changeset_oldest_result.data()
        changeset_rows = mixed_sample(changeset_latest_rows, changeset_oldest_rows, key=lambda r: r["hash"])

        file_result = await session.run(
            """
            MATCH (f:File {project_id: $project_id})
            RETURN f.path AS path
            LIMIT 5000
            """,
            project_id=project_id,
        )
        file_rows = await file_result.data()

        document_result = await session.run(
            """
            MATCH (d:Document {project_id: $project_id})
            RETURN d.title AS title
            ORDER BY d.occurredAt DESC
            LIMIT 10
            """,
            project_id=project_id,
        )
        document_rows = await document_result.data()

    pr_urls = [r["url"] for r in pr_rows]
    issue_titles = [
        f"[{r['issue_type']}] {r['title']}" if r.get("issue_type") else r["title"]
        for r in issue_rows
        if r.get("title")
    ]

    return {
        "repo_names": repo_names_from_urls(pr_urls),
        "pr_titles": [r["title"] for r in pr_rows if r.get("title")],
        "issue_titles": issue_titles,
        "commit_messages": [m for m in (_truncate_first_line(r["message"]) for r in changeset_rows) if m],
        "top_dirs": top_dirs_from_paths([r["path"] for r in file_rows]),
        "document_titles": [r["title"] for r in document_rows if r.get("title")],
    }


async def summarize_material(material: dict) -> str:
    """재료를 LLM으로 3~5문장 요약한다. 재료가 부족하면 LLM을 부르지 않고 빈 문자열.
    LLM이 "null"이라고 답하면(설명 불가 판단) 빈 문자열로 정규화한다."""
    if material_item_count(material) < MIN_MATERIAL_ITEMS:
        return ""

    response = await chat_completion(
        priority=Priority.BACKGROUND,
        model=PROFILE_MODEL,
        temperature=0,
        messages=[
            {"role": "system", "content": _SYSTEM_PROMPT},
            {"role": "user", "content": build_summary_prompt(material)},
        ],
    )
    content = (response.choices[0].message.content or "").strip()
    if content.lower() == "null":
        return ""
    return content


def _ttl_seconds() -> float:
    """PROJECT_PROFILE_TTL_SECONDS를 float로 변환한다. 빈 값·숫자가 아닌 값·0 이하는
    경고를 남기고 기본값(24시간)으로 대체한다 — 이 레포의 compose는 미설정 env를 빈
    문자열로 넘기는 관행이 있어, 여기서 막지 않으면 ValueError가 전파돼 필터 단계
    전체가 스킵된다.
    """
    raw = os.environ.get("PROJECT_PROFILE_TTL_SECONDS", "86400")
    try:
        value = float(raw)
    except (TypeError, ValueError):
        logger.warning("PROJECT_PROFILE_TTL_SECONDS 값이 올바르지 않아 기본값을 사용합니다: %r", raw)
        return 86400.0
    if value <= 0:
        logger.warning("PROJECT_PROFILE_TTL_SECONDS 값이 0 이하라 기본값을 사용합니다: %r", raw)
        return 86400.0
    return value


async def get_project_profile(project_id: str) -> str:
    """project_id의 프로필 요약을 반환한다(캐시 우선). 실패는 로그만 남기고 빈 문자열을
    반환한다 — 프로필 조회 실패가 Slack 필터 전체를 막으면 안 되므로 예외를 전파하지 않는다.
    캐시된 프로필이 빈 문자열이면 EMPTY_PROFILE_TTL_SECONDS를, 아니면 _ttl_seconds()를
    만료 기준으로 쓴다.
    """
    now = time.monotonic()
    cached = _profile_cache.get(project_id)
    if cached is not None:
        cached_profile, cached_at = cached
        ttl_seconds = EMPTY_PROFILE_TTL_SECONDS if cached_profile == "" else _ttl_seconds()
        if (now - cached_at) < ttl_seconds:
            return cached_profile

    try:
        material = await fetch_profile_material(project_id)
        profile = await summarize_material(material)
    except Exception as exc:
        logger.warning("프로젝트 프로필 생성 실패: project_id=%s error=%s", project_id, exc)
        return ""

    _profile_cache[project_id] = (profile, now)
    return profile


def clear_profile_cache() -> None:
    """테스트용 — 모듈 캐시를 비운다."""
    _profile_cache.clear()


if __name__ == "__main__":
    # 실기동 육안 확인용: python -m graph.project_profile <project_id>
    import asyncio
    import sys

    async def _main(project_id: str) -> None:
        material = await fetch_profile_material(project_id)
        for key, items in material.items():
            print(f"{key}: {len(items)}건 {items[:5]}")
        profile = await summarize_material(material)
        print("\n[프로필]")
        print(profile or "(빈 문자열 — 재료 부족 또는 LLM이 null 판정)")
        await close_driver()

    asyncio.run(_main(sys.argv[1]))
