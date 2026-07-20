"""Issue 링크(TRIGGERED_BY / DISCUSSED_IN)의 LLM 검수 빌더.

임베딩 전용 빌더(issue_linker)와 판정기(llm_judge)를 공유한다. 비교 측정에서 엣지 타입별로
다른 방식이 채택돼, 여기에는 채택된 조합만 남아 있다. REF 쪽 대응은 reference_verifier 참고.

  TRIGGERED_BY · 추천형  build_issue_changeset_links_verified
      임베딩이 이슈당 top-k 커밋을 추천하고 LLM이 최종 선택한다 (엣지를 새로 추가할 수 있음).
  DISCUSSED_IN · 필터형  build_issue_communication_links_filtered
      임베딩이 확정한 쌍만 검수해 0.7 미만이면 만들지 않는다 (추가 없음).

엣지 confidence에는 코사인 대신 LLM 값을 저장한다.
"""

import logging

from graph.embedder import cosine_similarity
from graph.issue_linker import (
    DEFAULT_DISCUSSED_IN_MARGIN,
    DISCUSSED_IN_POST_BUFFER_DAYS,
    DISCUSSED_IN_PRE_BUFFER_DAYS,
    DISCUSSED_IN_THRESHOLD,
    TRIGGERED_BY_MESSAGE_MODE,
    TRIGGERED_BY_THRESHOLD,
    IssueLinkStore,
    _compute_issue_window,
    _group_by_project,
    select_discussed_in_pairs,
)
from graph.llm_judge import DEFAULT_LLM_THRESHOLD, JudgeStats, format_commit_text, judge_pair

logger = logging.getLogger(__name__)

DEFAULT_TOP_K = 5


def _issue_text(issue: dict) -> str:
    return f"제목: {issue.get('title', '')}\n내용: {issue.get('body', '')}"


async def _fetch_rows(store: IssueLinkStore, message_mode: str):
    """A와 동일한 message_mode 규칙으로 비교 열을 만들고, LLM 입력용 커밋 원문도 함께 만든다."""
    if message_mode not in ("off", "max", "only"):
        raise ValueError(f"지원하지 않는 message_mode: {message_mode!r} (off/max/only)")

    modified_all = await store.fetch_modified_embeddings()
    message_all  = await store.fetch_changeset_message_embeddings()

    modified_list = [] if message_mode == "only" else modified_all
    message_list  = [] if message_mode == "off" else message_all

    # LLM 입력 원문은 message_mode와 무관하게 양쪽을 다 쓴다 (reference_verifier와 같은 이유).
    messages_by_cs = {r["changeset_id"]: r.get("message", "") for r in message_all}
    diffs_by_cs: dict[str, list[str]] = {}
    for row in modified_all:
        diffs_by_cs.setdefault(row["changeset_id"], []).append(row.get("diff_summary") or "")

    commit_texts = {
        cs_id: format_commit_text(
            messages_by_cs.get(cs_id, ""), " / ".join(t for t in diffs_by_cs.get(cs_id, []) if t)
        )
        for cs_id in set(messages_by_cs) | set(diffs_by_cs)
    }
    return modified_list + message_list, commit_texts


# ── TRIGGERED_BY · 추천형 ──────────────────────────────────────────────────


async def build_issue_changeset_links_verified(
    store: IssueLinkStore,
    threshold: float = TRIGGERED_BY_THRESHOLD,
    top_k: int = DEFAULT_TOP_K,
    llm_threshold: float = DEFAULT_LLM_THRESHOLD,
    message_mode: str = TRIGGERED_BY_MESSAGE_MODE,
    project_context: str = "",
) -> int:
    """추천형 — 이슈당 top_k 커밋을 LLM이 판정하고, 커밋당 top-1만 남긴다."""
    issues = await store.fetch_issue_embeddings()
    candidate_rows, commit_texts = await _fetch_rows(store, message_mode)

    if not issues or not candidate_rows:
        logger.info("TRIGGERED_BY(추천형) 스킵: issues=%d, candidates=%d", len(issues), len(candidate_rows))
        return 0

    issues_by_project = _group_by_project(issues)
    mods_by_project   = _group_by_project(candidate_rows)

    stats = JudgeStats()
    judged_pairs = 0
    best_per_cs: dict[tuple[str, str], tuple[str, float]] = {}

    for project_id, project_issues in issues_by_project.items():
        project_mods = mods_by_project.get(project_id, [])
        for issue in project_issues:
            issue_vec  = issue.get("embedding")
            start, end = _compute_issue_window(issue)
            if not issue_vec:
                continue

            # Stage 1: 윈도우 + 임계값 → 커밋 단위로 집계(쌍별 max) → top_k.
            # 커밋 단위로 먼저 접는 이유: max 주입에서는 한 커밋이 diff 행과 메시지 행 둘로
            # 나뉘어 들어와, 접지 않으면 같은 커밋이 top_k 슬롯을 두 칸 먹고 LLM 호출도 두 번 된다.
            best_by_cs: dict[str, float] = {}
            for mod in project_mods:
                if not mod.get("embedding"):
                    continue
                if mod["occurred_at"] < start or mod["occurred_at"] > end:
                    continue
                score = cosine_similarity(issue_vec, mod["embedding"])
                if score < threshold:
                    continue
                cs_id = mod["changeset_id"]
                if score > best_by_cs.get(cs_id, -1.0):
                    best_by_cs[cs_id] = score

            top = sorted(best_by_cs.items(), key=lambda x: -x[1])[:top_k]

            # Stage 2: LLM 판정 → 커밋당 top-1로 누적
            for cs_id, _cosine in top:
                judged_pairs += 1
                confidence = await judge_pair(
                    "Issue", _issue_text(issue),
                    "커밋", commit_texts.get(cs_id, ""),
                    project_context=project_context, stats=stats,
                )
                if confidence is None or confidence < llm_threshold:
                    continue
                cs_key  = (project_id, cs_id)
                current = best_per_cs.get(cs_key)
                if current is None or confidence > current[1]:
                    best_per_cs[cs_key] = (issue["id"], confidence)

    created = 0
    for (project_id, cs_id), (jira_key, confidence) in best_per_cs.items():
        await store.create_triggered_by_edge(project_id, cs_id, jira_key, confidence)
        created += 1

    logger.info(
        "TRIGGERED_BY(추천형) 완료: %d개 생성 / 후보 %d쌍 (threshold=%.2f, top_k=%d, llm_threshold=%.2f, %s)",
        created, judged_pairs, threshold, top_k, llm_threshold, stats.summary(),
    )
    return created


# ── DISCUSSED_IN · 필터형 ──────────────────────────────────────────────────


async def build_issue_communication_links_filtered(
    store: IssueLinkStore,
    threshold: float = DISCUSSED_IN_THRESHOLD,
    margin: float = DEFAULT_DISCUSSED_IN_MARGIN,
    pre_days: int = DISCUSSED_IN_PRE_BUFFER_DAYS,
    post_days: int = DISCUSSED_IN_POST_BUFFER_DAYS,
    llm_threshold: float = DEFAULT_LLM_THRESHOLD,
    project_context: str = "",
) -> int:
    """필터형 — 임베딩의 마진 컷 결과를 LLM이 검수한다 (추가 없음)."""
    issues = await store.fetch_issue_embeddings()
    comms  = await store.fetch_communication_embeddings()

    if not issues or not comms:
        logger.info("DISCUSSED_IN(필터형) 스킵: issues=%d, comms=%d", len(issues), len(comms))
        return 0

    issue_by_key = {(i.get("project_id") or "", i["id"]): i for i in issues}
    comm_texts   = {c["id"]: (c.get("body") or "") for c in comms}
    pairs = select_discussed_in_pairs(issues, comms, threshold, margin, pre_days, post_days)

    stats = JudgeStats()
    created = 0
    for project_id, jira_key, comm_id, cosine in pairs:
        issue = issue_by_key.get((project_id, jira_key), {})
        confidence = await judge_pair(
            "Issue", _issue_text(issue),
            "Slack 메시지", comm_texts.get(comm_id, ""),
            project_context=project_context, stats=stats,
        )
        if confidence is None:
            confidence = cosine          # 판정 못 한 엣지는 남긴다 (장애가 골든을 지우면 안 된다)
        elif confidence < llm_threshold:
            continue
        await store.create_discussed_in_edge(project_id, jira_key, comm_id, confidence)
        created += 1

    logger.info(
        "DISCUSSED_IN(필터형) 완료: %d개 유지 / 임베딩 확정 %d쌍 (threshold=%.2f, margin=%.2f, llm_threshold=%.2f, %s)",
        created, len(pairs), threshold, margin, llm_threshold, stats.summary(),
    )
    return created
