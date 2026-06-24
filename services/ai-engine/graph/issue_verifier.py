"""
방안 D — 임베딩 후보 선별 + LLM 검증 (Layer 4 확장).

방안 A와 동일하게 임베딩 유사도로 후보를 선별하되,
최종 판단은 LLM이 실제 텍스트를 읽고 내린다.

  Stage 1: 임베딩 유사도 ≥ threshold 인 쌍을 Issue당 top_k 후보로 압축
  Stage 2: 후보 쌍의 텍스트를 LLM에 넘겨 confidence 판단
           confidence ≥ llm_threshold 인 쌍에만 엣지 생성
"""

import json
import logging
from datetime import timedelta

from openai_client import Priority, chat_completion

from graph.embedder import cosine_similarity
from graph.issue_linker import (
    DISCUSSED_IN_THRESHOLD,
    TIME_WINDOW_DAYS,
    TRIGGERED_BY_THRESHOLD,
    IssueLinkStore,
    _compute_issue_window,
    _group_by_project,
)

logger = logging.getLogger(__name__)

DEFAULT_TOP_K = 5
DEFAULT_LLM_THRESHOLD = 0.7

_MAX_TEXT_LEN = 800


def _truncate(text: str) -> str:
    return text[:_MAX_TEXT_LEN] + "..." if len(text) > _MAX_TEXT_LEN else text


async def _verify_pair(
    issue_title: str,
    issue_body: str,
    target_type: str,
    target_text: str,
    project_context: str = "",
) -> float:
    """LLM으로 Issue와 대상 텍스트의 관련성 판단. confidence(0.0~1.0) 반환.

    project_context가 주어지면 도메인 용어 매칭 정확도 향상에 활용.
    """
    context_block = (
        f"[프로젝트 컨텍스트]\n{project_context.strip()}\n\n"
        if project_context.strip()
        else ""
    )
    prompt = (
        f"{context_block}"
        f"다음 Issue와 {target_type}이 실제로 관련이 있는지 판단해주세요.\n"
        f"위 프로젝트 컨텍스트의 도메인 용어가 양쪽에서 일치하는지 우선 확인하세요.\n\n"
        f"Issue:\n제목: {_truncate(issue_title)}\n내용: {_truncate(issue_body)}\n\n"
        f"{target_type}:\n{_truncate(target_text)}\n\n"
        f"JSON 형식으로만 응답: {{\"confidence\": 0.8, \"reason\": \"한 줄 이유\"}}\n"
        f"confidence는 0.0~1.0 (1.0: 직접 관련, 0.5: 간접 관련, 0.0: 무관)"
    )
    try:
        resp = await chat_completion(
            priority=Priority.BACKGROUND,
            model="gpt-4o-mini",
            messages=[{"role": "user", "content": prompt}],
            response_format={"type": "json_object"},
            temperature=0,
        )
        data = json.loads(resp.choices[0].message.content)
        return float(data.get("confidence", 0.0))
    except Exception:
        logger.exception("LLM 검증 실패 (0.0 반환)")
        return 0.0


async def build_issue_changeset_links_verified(
    store: IssueLinkStore,
    threshold: float = TRIGGERED_BY_THRESHOLD,
    top_k: int = DEFAULT_TOP_K,
    llm_threshold: float = DEFAULT_LLM_THRESHOLD,
    project_context: str = "",
) -> int:
    """방안 D — Issue ↔ ChangeSet: 임베딩 후보 선별 후 LLM 검증으로 TRIGGERED_BY 생성.

    정책 (방안 A와 동일):
      - 비대칭 시간 윈도우 (`_compute_issue_window`)
      - ChangeSet당 top-1 매칭 보장: 같은 커밋이 여러 이슈에 동시 연결되지 않음
      - store.fetch_modified_embeddings가 text TRIGGERED_BY 있는 ChangeSet을 제외해서 반환

    Returns:
        생성(또는 갱신)된 TRIGGERED_BY 엣지 수
    """
    issues   = await store.fetch_issue_embeddings()
    modified = await store.fetch_modified_embeddings()

    if not issues or not modified:
        logger.info("TRIGGERED_BY(D) 생성 스킵: issues=%d, modified=%d", len(issues), len(modified))
        return 0

    # 같은 프로젝트 안에서만 비교 (크로스 테넌트 엣지 차단)
    issues_by_project = _group_by_project(issues)
    mods_by_project   = _group_by_project(modified)

    # (project_id, changeset_id) → (best_jira_key, best_confidence) — top-1
    best_per_cs: dict[tuple[str, str], tuple[str, float]] = {}

    for project_id, project_issues in issues_by_project.items():
        project_mods = mods_by_project.get(project_id, [])
        for issue in project_issues:
            issue_vec   = issue["embedding"]
            issue_title = issue.get("title", "")
            issue_body  = issue.get("body", "")
            start, end  = _compute_issue_window(issue)

            # Stage 1: 비대칭 윈도우 + 유사도 필터 → Issue당 top_k 후보
            candidates = []
            for mod in project_mods:
                mod_time = mod["occurred_at"]
                if mod_time < start or mod_time > end:
                    continue
                score = cosine_similarity(issue_vec, mod["embedding"])
                if score < threshold:
                    continue
                candidates.append((score, mod))
            candidates.sort(key=lambda x: x[0], reverse=True)

            # Stage 2: LLM 검증 → top-1로 누적
            for _, mod in candidates[:top_k]:
                confidence = await _verify_pair(
                    issue_title, issue_body, "커밋 변경 요약",
                    mod.get("diff_summary", ""), project_context,
                )
                if confidence < llm_threshold:
                    continue
                cs_key  = (project_id, mod["changeset_id"])
                current = best_per_cs.get(cs_key)
                if current is None or confidence > current[1]:
                    best_per_cs[cs_key] = (issue["id"], confidence)

    created = 0
    for (project_id, cs_id), (jira_key, confidence) in best_per_cs.items():
        await store.create_triggered_by_edge(project_id, cs_id, jira_key, confidence)
        created += 1
        logger.debug("TRIGGERED_BY(D) 생성: changeset=%s issue=%s conf=%.2f",
                     cs_id, jira_key, confidence)

    logger.info("TRIGGERED_BY(D) 엣지 생성 완료: %d개 (threshold=%.2f, llm_threshold=%.2f)",
                created, threshold, llm_threshold)
    return created


async def build_issue_communication_links_verified(
    store: IssueLinkStore,
    threshold: float = DISCUSSED_IN_THRESHOLD,
    top_k: int = DEFAULT_TOP_K,
    llm_threshold: float = DEFAULT_LLM_THRESHOLD,
    project_context: str = "",
) -> int:
    """방안 D — Issue ↔ Communication: 임베딩 후보 선별 후 LLM 검증으로 DISCUSSED_IN 생성.

    Returns:
        생성(또는 갱신)된 DISCUSSED_IN 엣지 수
    """
    issues = await store.fetch_issue_embeddings()
    comms  = await store.fetch_communication_embeddings()

    if not issues or not comms:
        logger.info("DISCUSSED_IN(D) 생성 스킵: issues=%d, comms=%d", len(issues), len(comms))
        return 0

    window  = timedelta(days=TIME_WINDOW_DAYS)
    created = 0

    # 같은 프로젝트 안에서만 비교 (크로스 테넌트 엣지 차단)
    issues_by_project = _group_by_project(issues)
    comms_by_project  = _group_by_project(comms)

    for project_id, project_issues in issues_by_project.items():
        project_comms = comms_by_project.get(project_id, [])
        for issue in project_issues:
            issue_vec   = issue["embedding"]
            issue_time  = issue["occurred_at"]
            issue_title = issue.get("title", "")
            issue_body  = issue.get("body", "")

            # Stage 1: 시간 윈도우 + 유사도 필터 → Issue당 top_k 후보
            candidates = [
                (cosine_similarity(issue_vec, comm["embedding"]), comm)
                for comm in project_comms
                if abs(issue_time - comm["occurred_at"]) <= window
                and cosine_similarity(issue_vec, comm["embedding"]) >= threshold
            ]
            candidates.sort(key=lambda x: x[0], reverse=True)

            # Stage 2: LLM 검증
            for _, comm in candidates[:top_k]:
                confidence = await _verify_pair(
                    issue_title, issue_body, "Slack 메시지",
                    comm.get("body", ""), project_context,
                )
                if confidence >= llm_threshold:
                    await store.create_discussed_in_edge(project_id, issue["id"], comm["id"], confidence)
                    created += 1
                    logger.debug("DISCUSSED_IN(D) 생성: issue=%s comm=%s conf=%.2f",
                                 issue["id"], comm["id"], confidence)

    logger.info("DISCUSSED_IN(D) 엣지 생성 완료: %d개 (threshold=%.2f, llm_threshold=%.2f)",
                created, threshold, llm_threshold)
    return created
