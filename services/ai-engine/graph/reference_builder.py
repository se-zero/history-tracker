"""
REFERENCE 엣지 배치 생성 (Layer 4).

그래프 스키마의 Layer 4:
  (ChangeSet)-[MODIFIED]->(File) 엣지의 diffSummary 임베딩
  ↔ (Communication) 노드의 body 임베딩
  코사인 유사도 ≥ threshold 인 쌍에 REFERENCE 엣지 생성.

실행 타이밍:
  이벤트 처리와 별개로 수동 또는 스케줄 호출.
  Neo4j에 embedding이 충분히 쌓인 뒤 실행하는 것을 권장.

Neo4j 직접 호출 없이 ReferenceStore 인터페이스로 주입받아 테스트 가능하게 설계.
"""

import logging
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Awaitable, Callable

import numpy as np

from graph.embedder import embed_batch, similarity_matrix

logger = logging.getLogger(__name__)

DEFAULT_THRESHOLD = 0.30
TIME_WINDOW_DAYS = 5
# 커밋당 유지할 최대 스레드 수 (fan-out 컷). 커밋의 배경 논의는 팀 규모와 무관하게 원래 소수라
# 고정 개수가 정당하다. 골든 쌍의 진짜 스레드는 커밋 기준 1~4위(스레드 단위)에 들어온다.
DEFAULT_TOP_K = 4


def _group_by_project(rows: list[dict]) -> dict[str, list[dict]]:
    """project_id 기준 그룹핑 — 프로젝트 간 임베딩 비교(크로스 테넌트 엣지)를 차단한다."""
    grouped: dict[str, list[dict]] = defaultdict(list)
    for row in rows:
        grouped[row.get("project_id") or ""].append(row)
    return grouped


@dataclass
class ReferenceStore:
    """Neo4j 조회·생성 함수 묶음. 테스트 시 mock으로 교체 가능."""

    fetch_modified_embeddings: Callable[[], Awaitable[list[dict]]]
    """embedding이 있는 모든 MODIFIED 엣지 반환.
    Returns:
        [{"project_id": str, "changeset_id": str, "file_path": str, "diff_summary": str,
          "embedding": list[float], "occurred_at": datetime}, ...]
    """

    fetch_communication_embeddings: Callable[[], Awaitable[list[dict]]]
    """embedding이 있는 모든 Communication 노드 반환.
    Returns:
        [{"project_id": str, "id": str, "conversation_id": str | None, "body": str,
          "embedding": list[float], "occurred_at": datetime}, ...]
        conversation_id는 top-k 스레드 컷의 그룹 키다 (없으면 그 메시지가 곧 스레드).
    """

    create_reference_edge: Callable[[str, str, str, float], Awaitable[None]]
    """REFERENCE 엣지 생성 또는 갱신.
    Args:
        project_id:        프로젝트 UUID (노드 매칭 스코프)
        changeset_id:      ChangeSet 노드 ID
        communication_id:  Communication 노드 ID
        confidence:        코사인 유사도 값 (0.0~1.0)
    """

    fetch_unembedded_communications: Callable[[], Awaitable[list[dict]]]
    """embedding 프로퍼티가 없는 Communication 노드 반환 (보정용).
    Returns:
        [{"project_id": str, "id": str, "body": str}, ...]
    """

    save_communication_embedding: Callable[[str, str, list[float]], Awaitable[None]]
    """Communication 노드에 embedding 저장.
    Args:
        project_id:       프로젝트 UUID
        communication_id: Communication 노드 ID
        embedding:        임베딩 벡터
    """


async def build_reference_edges(
    store: ReferenceStore,
    threshold: float = DEFAULT_THRESHOLD,
    top_k: int = DEFAULT_TOP_K,
) -> int:
    """MODIFIED.embedding ↔ Communication.embedding 코사인 유사도로 REFERENCE 엣지 생성.

    Args:
        store:     Neo4j 접근 인터페이스
        threshold: 엣지 생성 최소 유사도
        top_k:     커밋당 유지할 최대 스레드 수 (fan-out 컷). 자르는 단위가 메시지가 아니라
                   스레드인 이유 — 수다스러운 스레드 하나가 k칸을 독식하면 다른 스레드의
                   진짜 배경 논의가 밀려난다.

    Returns:
        생성(또는 갱신)된 REFERENCE 엣지 수
    """
    modified_list = await store.fetch_modified_embeddings()
    comm_list = await store.fetch_communication_embeddings()

    if not modified_list or not comm_list:
        logger.info("REFERENCE 엣지 생성 스킵: modified=%d, comm=%d", len(modified_list), len(comm_list))
        return 0

    window_s = timedelta(days=TIME_WINDOW_DAYS).total_seconds()

    # 같은 프로젝트 안에서만 비교 — 다른 프로젝트의 커밋과 메시지가 의미상 비슷해도
    # 엣지를 만들면 안 된다 (그래프 격리 위반).
    mods_by_project = _group_by_project(modified_list)
    comms_by_project = _group_by_project(comm_list)

    created = 0
    for project_id, mods in mods_by_project.items():
        comms = comms_by_project.get(project_id, [])
        # 빈 임베딩은 제외 — 유사도 0이라 어차피 임계값 미달이고, 행렬화도 깨진다
        p_mods  = [m for m in mods if m.get("embedding")]
        p_comms = [c for c in comms if c.get("embedding")]
        if not p_mods or not p_comms:
            continue

        # 전체 쌍 코사인 유사도를 numpy로 일괄 계산 (MODIFIED × communications)
        sim = similarity_matrix(
            [m["embedding"] for m in p_mods],
            [c["embedding"] for c in p_comms],
        )

        # 시간 윈도우(5일) 마스크 + 임계값.
        mod_ts  = np.array([m["occurred_at"].timestamp() for m in p_mods])
        comm_ts = np.array([c["occurred_at"].timestamp() for c in p_comms])
        in_window = np.abs(mod_ts[:, None] - comm_ts[None, :]) <= window_s
        valid = in_window & (sim >= threshold)

        # 비교 단위는 파일(MODIFIED)이지만 엣지는 커밋 단위다 — 한 커밋의 여러 파일이 같은
        # 메시지에 매칭되면 같은 엣지에 파일별 점수를 덮어써 "마지막에 계산된 값"이 남는다.
        # 쌍의 대표값은 파일 중 최고 점수이므로 (changeset, communication)별 max로 집계한 뒤 1회 생성.
        best_per_pair: dict[tuple[str, str], float] = {}
        for i, j in np.argwhere(valid):
            i, j = int(i), int(j)
            pair = (p_mods[i]["changeset_id"], p_comms[j]["id"])
            score = float(sim[i, j])
            if score > best_per_pair.get(pair, -1.0):
                best_per_pair[pair] = score

        # fan-out 컷 — 커밋당 상위 top_k개 스레드만 남긴다. 스레드의 대표값은 그 안의 최고 점수.
        thread_of = {c["id"]: (c.get("conversation_id") or c["id"]) for c in p_comms}
        thread_best: dict[str, dict[str, float]] = defaultdict(dict)
        for (changeset_id, comm_id), score in best_per_pair.items():
            threads = thread_best[changeset_id]
            thread = thread_of[comm_id]
            if score > threads.get(thread, -1.0):
                threads[thread] = score
        kept_threads = {
            changeset_id: {t for t, _ in sorted(threads.items(), key=lambda x: -x[1])[:top_k]}
            for changeset_id, threads in thread_best.items()
        }

        for (changeset_id, comm_id), score in best_per_pair.items():
            if thread_of[comm_id] not in kept_threads[changeset_id]:
                continue
            await store.create_reference_edge(project_id, changeset_id, comm_id, score)
            created += 1
            logger.debug(
                "REFERENCE 생성: changeset=%s comm=%s score=%.3f",
                changeset_id, comm_id, score,
            )

    logger.info("REFERENCE 엣지 생성 완료: %d개 (threshold=%.2f, top_k=%d)", created, threshold, top_k)
    return created


async def backfill_communication_embeddings(store: ReferenceStore) -> int:
    """embedding이 없는 Communication 노드를 배치 임베딩으로 보정.

    실시간 처리 중 임베딩이 누락된 노드나 초기 대량 데이터 적재 후 실행.

    Returns:
        임베딩이 저장된 노드 수
    """
    nodes = await store.fetch_unembedded_communications()
    if not nodes:
        logger.info("보정할 Communication 노드 없음")
        return 0

    bodies = [n["body"] for n in nodes]
    vectors = await embed_batch(bodies)

    saved = 0
    for node, vec in zip(nodes, vectors):
        if vec:
            await store.save_communication_embedding(node.get("project_id") or "", node["id"], vec)
            saved += 1

    logger.info("Communication 임베딩 보정 완료: %d/%d개", saved, len(nodes))
    return saved
