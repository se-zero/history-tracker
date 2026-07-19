"""방안 D — REFERENCE(ChangeSet ↔ Communication) 엣지의 두 변형.

방안 A(reference_builder)의 선별 함수를 Stage 1으로 공유하고, Stage 2에서 LLM이 판정한다.
변형이 갈리는 지점은 "최종 선택권이 임베딩에 있나 LLM에 있나" 하나뿐이다.

  변형 1 · D-추천  build_reference_edges_verified
      A가 노이즈만 남는다고 포기한 0.30~0.39 구간까지 후보로 올려 LLM이 최종 선택한다.
      "임베딩이 못 가르는 구간을 LLM은 가를 수 있는가"를 시험한다 — recall 상방이 있는 대신
      후보가 1,000쌍대로 늘어 run당 호출 비용이 크다.

  변형 2 · D-필터  build_reference_edges_filtered
      A가 채택 파라미터(t0.39)로 확정한 쌍만 검수해 0.7 미만이면 지운다 — 추가는 못 한다.
      recall ≤ A가 구조적으로 보장되고 precision 상승이 순수하게 LLM 몫으로 귀속된다.

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

# 변형 1의 REF 후보 임계값. A의 채택값(0.39)이 아니라 0.30인 것은 의도된 설정이다 —
# 0.30~0.39는 A에서 노이즈만 남아 포기한 구간이고, 그 구간을 LLM에게 돌려주는 것이
# 이 변형의 핵심 질문이다 (회수 후보: 골든 5715fca, t0.32에서 회수됐던 bff2648).
VARIANT1_THRESHOLD = 0.30


async def _fetch_rows(store: ReferenceStore, message_mode: str):
    """A와 동일한 message_mode 규칙으로 비교 행·대화 행을 읽고, LLM 입력용 원문도 함께 만든다."""
    if message_mode not in ("off", "max", "only"):
        raise ValueError(f"지원하지 않는 message_mode: {message_mode!r} (off/max/only)")

    modified_all = await store.fetch_modified_embeddings()
    message_all  = await store.fetch_changeset_message_embeddings()
    comm_list    = await store.fetch_communication_embeddings()

    # 임베딩 비교 열은 message_mode를 따른다 (방안 A와 동일 규칙).
    modified_list = [] if message_mode == "only" else modified_all
    message_list  = [] if message_mode == "off" else message_all

    # LLM 입력 원문은 message_mode와 무관하게 양쪽을 다 쓴다 — 비교 열 구성(임베딩)과
    # LLM에게 보여줄 실명(實名)은 별개다. off 모드에서도 LLM은 메시지를 봐야
    # 통제 조건("두 변형 다 같은 입력")이 지켜진다.
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
    keep_on_skip: bool,
) -> tuple[int, JudgeStats]:
    """선별된 쌍을 LLM이 판정해 엣지를 만든다.

    keep_on_skip: 일시 오류로 판정하지 못한 쌍의 처리.
        False(변형 1) — 만들지 않는다. 확인 못 한 쌍을 새로 긋지 않는다.
        True (변형 2) — 코사인 값 그대로 남긴다. 장애가 A의 골든 엣지를 지우면 안 된다.
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
            if not keep_on_skip:
                continue
            confidence = cosine          # 판정 못 했으므로 A의 값을 유지
        elif confidence < llm_threshold:
            continue
        await store.create_reference_edge(project_id, changeset_id, comm_id, confidence)
        created += 1
    return created, stats


async def build_reference_edges_verified(
    store: ReferenceStore,
    threshold: float = VARIANT1_THRESHOLD,
    top_k: int = DEFAULT_TOP_K,
    llm_threshold: float = DEFAULT_LLM_THRESHOLD,
    message_mode: str = DEFAULT_MESSAGE_MODE,
) -> int:
    """변형 1(D-추천) — 임베딩이 넓게 추천하고 LLM이 최종 선택한다."""
    candidate_rows, comm_list, commit_texts, comm_texts = await _fetch_rows(store, message_mode)
    if not candidate_rows or not comm_list:
        logger.info("REFERENCE(D-추천) 스킵: candidates=%d, comm=%d", len(candidate_rows), len(comm_list))
        return 0

    pairs = select_reference_pairs(candidate_rows, comm_list, threshold, top_k)
    created, stats = await _judge_and_create(
        store, pairs, commit_texts, comm_texts, llm_threshold, keep_on_skip=False,
    )
    logger.info(
        "REFERENCE(D-추천) 완료: %d개 생성 / 후보 %d쌍 (threshold=%.2f, top_k=%d, llm_threshold=%.2f, %s)",
        created, len(pairs), threshold, top_k, llm_threshold, stats.summary(),
    )
    return created


async def build_reference_edges_filtered(
    store: ReferenceStore,
    threshold: float = DEFAULT_THRESHOLD,
    top_k: int = DEFAULT_TOP_K,
    llm_threshold: float = DEFAULT_LLM_THRESHOLD,
    message_mode: str = DEFAULT_MESSAGE_MODE,
) -> int:
    """변형 2(D-필터) — A가 확정한 쌍을 LLM이 검수해 지우기만 한다."""
    candidate_rows, comm_list, commit_texts, comm_texts = await _fetch_rows(store, message_mode)
    if not candidate_rows or not comm_list:
        logger.info("REFERENCE(D-필터) 스킵: candidates=%d, comm=%d", len(candidate_rows), len(comm_list))
        return 0

    pairs = select_reference_pairs(candidate_rows, comm_list, threshold, top_k)
    created, stats = await _judge_and_create(
        store, pairs, commit_texts, comm_texts, llm_threshold, keep_on_skip=True,
    )
    logger.info(
        "REFERENCE(D-필터) 완료: %d개 유지 / A 확정 %d쌍 (threshold=%.2f, top_k=%d, llm_threshold=%.2f, %s)",
        created, len(pairs), threshold, top_k, llm_threshold, stats.summary(),
    )
    return created
