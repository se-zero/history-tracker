"""방안 D — Issue 링크(TRIGGERED_BY / DISCUSSED_IN)의 두 변형.

방안 A(issue_linker)와 판정기(llm_judge)를 공유하고, 변형은 "최종 선택권이 임베딩에 있나
LLM에 있나"로만 갈린다. 설계 근거와 REF 쪽 대응은 reference_verifier 참고.

  변형 1 · D-추천  *_verified   임베딩이 이슈당 top-k를 추천 → LLM이 최종 선택
  변형 2 · D-필터  *_filtered   A가 확정한 쌍만 검수 → 0.7 미만이면 제거 (추가 없음)

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
    select_triggered_by_pairs,
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


# ── 변형 1 · D-추천 ────────────────────────────────────────────────────────


async def build_issue_changeset_links_verified(
    store: IssueLinkStore,
    threshold: float = TRIGGERED_BY_THRESHOLD,
    top_k: int = DEFAULT_TOP_K,
    llm_threshold: float = DEFAULT_LLM_THRESHOLD,
    message_mode: str = TRIGGERED_BY_MESSAGE_MODE,
    project_context: str = "",
) -> int:
    """변형 1 — 이슈당 top_k 커밋을 LLM이 판정하고, 커밋당 top-1만 남긴다."""
    issues = await store.fetch_issue_embeddings()
    candidate_rows, commit_texts = await _fetch_rows(store, message_mode)

    if not issues or not candidate_rows:
        logger.info("TRIGGERED_BY(D-추천) 스킵: issues=%d, candidates=%d", len(issues), len(candidate_rows))
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
        "TRIGGERED_BY(D-추천) 완료: %d개 생성 / 후보 %d쌍 (threshold=%.2f, top_k=%d, llm_threshold=%.2f, %s)",
        created, judged_pairs, threshold, top_k, llm_threshold, stats.summary(),
    )
    return created


async def build_issue_communication_links_verified(
    store: IssueLinkStore,
    threshold: float = DISCUSSED_IN_THRESHOLD,
    top_k: int = DEFAULT_TOP_K,
    llm_threshold: float = DEFAULT_LLM_THRESHOLD,
    project_context: str = "",
) -> int:
    """변형 1 — 이슈당 top_k 대화를 LLM이 판정하고, 0.7 통과분을 전부 연결한다."""
    issues = await store.fetch_issue_embeddings()
    comms  = await store.fetch_communication_embeddings()

    if not issues or not comms:
        logger.info("DISCUSSED_IN(D-추천) 스킵: issues=%d, comms=%d", len(issues), len(comms))
        return 0

    issues_by_project = _group_by_project(issues)
    comms_by_project  = _group_by_project(comms)

    stats = JudgeStats()
    judged_pairs = 0
    created = 0

    for project_id, project_issues in issues_by_project.items():
        project_comms = comms_by_project.get(project_id, [])
        for issue in project_issues:
            issue_vec  = issue.get("embedding")
            start, end = _compute_issue_window(
                issue, DISCUSSED_IN_PRE_BUFFER_DAYS, DISCUSSED_IN_POST_BUFFER_DAYS
            )
            if not issue_vec:
                continue

            candidates = []
            for comm in project_comms:
                if not comm.get("embedding"):
                    continue
                if comm["occurred_at"] < start or comm["occurred_at"] > end:
                    continue
                score = cosine_similarity(issue_vec, comm["embedding"])
                if score >= threshold:
                    candidates.append((score, comm))
            candidates.sort(key=lambda x: -x[0])

            for _score, comm in candidates[:top_k]:
                judged_pairs += 1
                confidence = await judge_pair(
                    "Issue", _issue_text(issue),
                    "Slack 메시지", comm.get("body", ""),
                    project_context=project_context, stats=stats,
                )
                if confidence is None or confidence < llm_threshold:
                    continue
                await store.create_discussed_in_edge(project_id, issue["id"], comm["id"], confidence)
                created += 1

    logger.info(
        "DISCUSSED_IN(D-추천) 완료: %d개 생성 / 후보 %d쌍 (threshold=%.2f, top_k=%d, llm_threshold=%.2f, %s)",
        created, judged_pairs, threshold, top_k, llm_threshold, stats.summary(),
    )
    return created


# ── 변형 2 · D-필터 ────────────────────────────────────────────────────────


async def build_issue_changeset_links_filtered(
    store: IssueLinkStore,
    threshold: float = TRIGGERED_BY_THRESHOLD,
    llm_threshold: float = DEFAULT_LLM_THRESHOLD,
    message_mode: str = TRIGGERED_BY_MESSAGE_MODE,
    project_context: str = "",
) -> int:
    """변형 2 — A의 커밋당 top-1 결과를 LLM이 검수한다 (재선택 없음)."""
    issues = await store.fetch_issue_embeddings()
    candidate_rows, commit_texts = await _fetch_rows(store, message_mode)

    if not issues or not candidate_rows:
        logger.info("TRIGGERED_BY(D-필터) 스킵: issues=%d, candidates=%d", len(issues), len(candidate_rows))
        return 0

    issue_by_key = {(i.get("project_id") or "", i["id"]): i for i in issues}
    pairs = select_triggered_by_pairs(issues, candidate_rows, threshold)

    stats = JudgeStats()
    created = 0
    for project_id, cs_id, jira_key, cosine in pairs:
        issue = issue_by_key.get((project_id, jira_key), {})
        confidence = await judge_pair(
            "Issue", _issue_text(issue),
            "커밋", commit_texts.get(cs_id, ""),
            project_context=project_context, stats=stats,
        )
        if confidence is None:
            confidence = cosine          # 판정 못 한 A 엣지는 남긴다 (장애가 골든을 지우면 안 된다)
        elif confidence < llm_threshold:
            continue
        await store.create_triggered_by_edge(project_id, cs_id, jira_key, confidence)
        created += 1

    logger.info(
        "TRIGGERED_BY(D-필터) 완료: %d개 유지 / A 확정 %d쌍 (threshold=%.2f, llm_threshold=%.2f, %s)",
        created, len(pairs), threshold, llm_threshold, stats.summary(),
    )
    return created


async def build_issue_communication_links_filtered(
    store: IssueLinkStore,
    threshold: float = DISCUSSED_IN_THRESHOLD,
    margin: float = DEFAULT_DISCUSSED_IN_MARGIN,
    pre_days: int = DISCUSSED_IN_PRE_BUFFER_DAYS,
    post_days: int = DISCUSSED_IN_POST_BUFFER_DAYS,
    llm_threshold: float = DEFAULT_LLM_THRESHOLD,
    project_context: str = "",
) -> int:
    """변형 2 — A의 마진 컷 결과를 LLM이 검수한다."""
    issues = await store.fetch_issue_embeddings()
    comms  = await store.fetch_communication_embeddings()

    if not issues or not comms:
        logger.info("DISCUSSED_IN(D-필터) 스킵: issues=%d, comms=%d", len(issues), len(comms))
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
            confidence = cosine
        elif confidence < llm_threshold:
            continue
        await store.create_discussed_in_edge(project_id, jira_key, comm_id, confidence)
        created += 1

    logger.info(
        "DISCUSSED_IN(D-필터) 완료: %d개 유지 / A 확정 %d쌍 (threshold=%.2f, margin=%.2f, llm_threshold=%.2f, %s)",
        created, len(pairs), threshold, margin, llm_threshold, stats.summary(),
    )
    return created
