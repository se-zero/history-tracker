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
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Awaitable, Callable

from graph.embedder import cosine_similarity, embed_batch

logger = logging.getLogger(__name__)

DEFAULT_THRESHOLD = 0.75
TIME_WINDOW_DAYS = 5


@dataclass
class ReferenceStore:
    """Neo4j 조회·생성 함수 묶음. 테스트 시 mock으로 교체 가능."""

    fetch_modified_embeddings: Callable[[], Awaitable[list[dict]]]
    """embedding이 있는 모든 MODIFIED 엣지 반환.
    Returns:
        [{"changeset_id": str, "file_path": str, "diff_summary": str,
          "embedding": list[float], "occurred_at": datetime}, ...]
    """

    fetch_communication_embeddings: Callable[[], Awaitable[list[dict]]]
    """embedding이 있는 모든 Communication 노드 반환.
    Returns:
        [{"id": str, "body": str, "embedding": list[float], "occurred_at": datetime}, ...]
    """

    create_reference_edge: Callable[[str, str, float], Awaitable[None]]
    """REFERENCE 엣지 생성 또는 갱신.
    Args:
        changeset_id:      ChangeSet 노드 ID
        communication_id:  Communication 노드 ID
        confidence:        코사인 유사도 값 (0.0~1.0)
    """

    fetch_unembedded_communications: Callable[[], Awaitable[list[dict]]]
    """embedding 프로퍼티가 없는 Communication 노드 반환 (보정용).
    Returns:
        [{"id": str, "body": str}, ...]
    """

    save_communication_embedding: Callable[[str, list[float]], Awaitable[None]]
    """Communication 노드에 embedding 저장.
    Args:
        communication_id: Communication 노드 ID
        embedding:        임베딩 벡터
    """


async def build_reference_edges(
    store: ReferenceStore,
    threshold: float = DEFAULT_THRESHOLD,
) -> int:
    """MODIFIED.embedding ↔ Communication.embedding 코사인 유사도로 REFERENCE 엣지 생성.

    Args:
        store:     Neo4j 접근 인터페이스
        threshold: 엣지 생성 최소 유사도 (기본 0.75)

    Returns:
        생성(또는 갱신)된 REFERENCE 엣지 수
    """
    modified_list = await store.fetch_modified_embeddings()
    comm_list = await store.fetch_communication_embeddings()

    if not modified_list or not comm_list:
        logger.info("REFERENCE 엣지 생성 스킵: modified=%d, comm=%d", len(modified_list), len(comm_list))
        return 0

    window = timedelta(days=TIME_WINDOW_DAYS)

    created = 0
    for mod in modified_list:
        mod_vec = mod["embedding"]
        mod_time = mod["occurred_at"]
        for comm in comm_list:
            # 시간 윈도우 필터: 5일 이상 차이나는 쌍은 비교 스킵
            if abs(mod_time - comm["occurred_at"]) > window:
                continue
            score = cosine_similarity(mod_vec, comm["embedding"])
            if score >= threshold:
                await store.create_reference_edge(mod["changeset_id"], comm["id"], score)
                created += 1
                logger.debug(
                    "REFERENCE 생성: changeset=%s comm=%s score=%.3f",
                    mod["changeset_id"], comm["id"], score,
                )

    logger.info("REFERENCE 엣지 생성 완료: %d개 (threshold=%.2f)", created, threshold)
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
            await store.save_communication_embedding(node["id"], vec)
            saved += 1

    logger.info("Communication 임베딩 보정 완료: %d/%d개", saved, len(nodes))
    return saved
