"""
방안 A — 임베딩 유사도 기반 Issue 연결 (Layer 4 확장).

refs 텍스트 매칭에 의존하지 않고 임베딩 유사도로
Issue와 ChangeSet / Communication을 연결한다.

  Issue.embedding ↔ MODIFIED.embedding       → TRIGGERED_BY (ChangeSet → Issue)
  Issue.embedding ↔ Communication.embedding  → DISCUSSED_IN (Issue → Communication)

실행 타이밍:
  이벤트 처리와 별개로 수동 또는 스케줄 호출.
  Issue / ChangeSet / Communication 임베딩이 충분히 쌓인 뒤 실행.
"""

import logging
from dataclasses import dataclass
from datetime import timedelta
from typing import Awaitable, Callable

from graph.embedder import cosine_similarity

logger = logging.getLogger(__name__)

DEFAULT_THRESHOLD = 0.40
TIME_WINDOW_DAYS  = 30


@dataclass
class IssueLinkStore:
    """Neo4j 조회·생성 함수 묶음. 테스트 시 mock으로 교체 가능."""

    fetch_issue_embeddings: Callable[[], Awaitable[list[dict]]]
    """embedding이 있는 모든 Issue 노드 반환.
    Returns:
        [{"id": str (jira_key), "embedding": list[float], "occurred_at": datetime}, ...]
    """

    fetch_modified_embeddings: Callable[[], Awaitable[list[dict]]]
    """embedding이 있는 모든 MODIFIED 엣지 반환.
    Returns:
        [{"changeset_id": str, "embedding": list[float], "occurred_at": datetime}, ...]
    """

    fetch_communication_embeddings: Callable[[], Awaitable[list[dict]]]
    """embedding이 있는 모든 Communication 노드 반환.
    Returns:
        [{"id": str (url), "embedding": list[float], "occurred_at": datetime}, ...]
    """

    create_triggered_by_edge: Callable[[str, str, float], Awaitable[None]]
    """TRIGGERED_BY 엣지 생성 또는 갱신.
    Args:
        changeset_id: ChangeSet hash
        jira_key:     Issue jira_key
        confidence:   코사인 유사도 (0~1)
    """

    create_discussed_in_edge: Callable[[str, str, float], Awaitable[None]]
    """DISCUSSED_IN 엣지 생성 또는 갱신.
    Args:
        jira_key: Issue jira_key
        comm_url: Communication url
        confidence: 코사인 유사도 (0~1)
    """


async def build_issue_changeset_links(
    store: IssueLinkStore,
    threshold: float = DEFAULT_THRESHOLD,
) -> int:
    """Issue.embedding ↔ MODIFIED.embedding 유사도로 TRIGGERED_BY 엣지 생성.

    같은 ChangeSet에서 여러 파일이 Issue와 매칭될 경우 최고 confidence만 저장.

    Returns:
        생성(또는 갱신)된 TRIGGERED_BY 엣지 수
    """
    issues   = await store.fetch_issue_embeddings()
    modified = await store.fetch_modified_embeddings()

    if not issues or not modified:
        logger.info("TRIGGERED_BY 생성 스킵: issues=%d, modified=%d", len(issues), len(modified))
        return 0

    window = timedelta(days=TIME_WINDOW_DAYS)

    # (changeset_id, jira_key) → 최고 confidence
    best: dict[tuple[str, str], float] = {}

    for issue in issues:
        issue_vec  = issue["embedding"]
        issue_time = issue["occurred_at"]

        for mod in modified:
            if abs(issue_time - mod["occurred_at"]) > window:
                continue

            score = cosine_similarity(issue_vec, mod["embedding"])
            if score < threshold:
                continue

            pair = (mod["changeset_id"], issue["id"])
            if score > best.get(pair, 0.0):
                best[pair] = score

    created = 0
    for (changeset_id, jira_key), confidence in best.items():
        await store.create_triggered_by_edge(changeset_id, jira_key, confidence)
        created += 1
        logger.debug("TRIGGERED_BY 생성: changeset=%s issue=%s score=%.3f",
                     changeset_id, jira_key, confidence)

    logger.info("TRIGGERED_BY 엣지 생성 완료: %d개 (threshold=%.2f)", created, threshold)
    return created


async def build_issue_communication_links(
    store: IssueLinkStore,
    threshold: float = DEFAULT_THRESHOLD,
) -> int:
    """Issue.embedding ↔ Communication.embedding 유사도로 DISCUSSED_IN 엣지 생성.

    Returns:
        생성(또는 갱신)된 DISCUSSED_IN 엣지 수
    """
    issues = await store.fetch_issue_embeddings()
    comms  = await store.fetch_communication_embeddings()

    if not issues or not comms:
        logger.info("DISCUSSED_IN 생성 스킵: issues=%d, comms=%d", len(issues), len(comms))
        return 0

    window = timedelta(days=TIME_WINDOW_DAYS)

    created = 0
    for issue in issues:
        issue_vec  = issue["embedding"]
        issue_time = issue["occurred_at"]

        for comm in comms:
            if abs(issue_time - comm["occurred_at"]) > window:
                continue

            score = cosine_similarity(issue_vec, comm["embedding"])
            if score >= threshold:
                await store.create_discussed_in_edge(issue["id"], comm["id"], confidence=score)
                created += 1
                logger.debug("DISCUSSED_IN 생성: issue=%s comm=%s score=%.3f",
                             issue["id"], comm["id"], score)

    logger.info("DISCUSSED_IN 엣지 생성 완료: %d개 (threshold=%.2f)", created, threshold)
    return created
