"""REFERENCE(ChangeSet ↔ Communication) 엣지의 LLM 검수 빌더 — 필터형.

임베딩 전용 빌더(reference_builder)의 선별 함수를 Stage 1으로 공유하고, Stage 2에서 LLM이 판정한다.
필터형은 임베딩이 채택 파라미터(t0.39)로 확정한 쌍만 검수해 0.7 미만이면 만들지 않는다 —
후보를 추가하지는 못한다. recall ≤ 임베딩 전용이 구조적으로 보장되고, precision 상승이
순수하게 LLM 몫으로 귀속된다. 비교 측정에서 REFERENCE에 채택된 방식이다.

엣지 confidence에는 코사인이 아니라 LLM 값을 저장한다 (판정 근거를 그래프에 남긴다).
"""

import logging

from graph.llm_judge import DEFAULT_LLM_THRESHOLD, JudgeStats, format_commit_text, judge_pair
from graph.reference_builder import (
    DEFAULT_MESSAGE_MODE,
    DEFAULT_THRESHOLD,
    DEFAULT_TOP_K,
    ReferenceStore,
    select_reference_pairs,
)

logger = logging.getLogger(__name__)


async def _fetch_rows(store: ReferenceStore, message_mode: str):
    """A와 동일한 message_mode 규칙으로 비교 행·대화 행을 읽고, LLM 입력용 원문도 함께 만든다."""
    if message_mode not in ("off", "max", "only"):
        raise ValueError(f"지원하지 않는 message_mode: {message_mode!r} (off/max/only)")

    modified_all = await store.fetch_modified_embeddings()
    message_all  = await store.fetch_changeset_message_embeddings()
    comm_list    = await store.fetch_communication_embeddings()

    # 임베딩 비교 열은 message_mode를 따른다 (자동구축과 동일 규칙).
    modified_list = [] if message_mode == "only" else modified_all
    message_list  = [] if message_mode == "off" else message_all

    # LLM 입력 원문은 message_mode와 무관하게 양쪽을 다 쓴다 — 비교 열 구성(임베딩)과
    # LLM에게 보여줄 실명(實名)은 별개다. 임베딩에서 어떤 열을 뺐든 판정 근거까지 뺄 이유는
    # 없다: off 모드에서도 LLM은 커밋 메시지를 봐야 변경 의도를 판단할 수 있다.
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
    comm_texts = {c["id"]: (c.get("body") or "") for c in comm_list}

    return modified_list + message_list, comm_list, commit_texts, comm_texts


async def _judge_and_create(
    store: ReferenceStore,
    pairs: list[tuple[str, str, str, float]],
    commit_texts: dict[str, str],
    comm_texts: dict[str, str],
    llm_threshold: float,
) -> tuple[int, JudgeStats]:
    """선별된 쌍을 LLM이 판정해 엣지를 만든다.

    일시 오류로 판정하지 못한 쌍은 코사인 값 그대로 남긴다 —
    LLM 장애가 임베딩이 확정한 골든 엣지를 지우면 안 된다.
    """
    stats = JudgeStats()
    created = 0
    for project_id, changeset_id, comm_id, cosine in pairs:
        confidence = await judge_pair(
            "커밋", commit_texts.get(changeset_id, ""),
            "Slack 메시지", comm_texts.get(comm_id, ""),
            stats=stats,
        )
        if confidence is None:
            confidence = cosine          # 판정 못 했으므로 임베딩 값을 유지
        elif confidence < llm_threshold:
            continue
        await store.create_reference_edge(project_id, changeset_id, comm_id, confidence)
        created += 1
    return created, stats


async def build_reference_edges_filtered(
    store: ReferenceStore,
    threshold: float = DEFAULT_THRESHOLD,
    top_k: int = DEFAULT_TOP_K,
    llm_threshold: float = DEFAULT_LLM_THRESHOLD,
    message_mode: str = DEFAULT_MESSAGE_MODE,
) -> int:
    """필터형 — 임베딩이 확정한 쌍을 LLM이 검수해 걸러내기만 한다 (추가 없음)."""
    candidate_rows, comm_list, commit_texts, comm_texts = await _fetch_rows(store, message_mode)
    if not candidate_rows or not comm_list:
        logger.info("REFERENCE(필터형) 스킵: candidates=%d, comm=%d", len(candidate_rows), len(comm_list))
        return 0

    pairs = select_reference_pairs(candidate_rows, comm_list, threshold, top_k)
    created, stats = await _judge_and_create(
        store, pairs, commit_texts, comm_texts, llm_threshold,
    )
    logger.info(
        "REFERENCE(필터형) 완료: %d개 유지 / 임베딩 확정 %d쌍 (threshold=%.2f, top_k=%d, llm_threshold=%.2f, %s)",
        created, len(pairs), threshold, top_k, llm_threshold, stats.summary(),
    )
    return created
