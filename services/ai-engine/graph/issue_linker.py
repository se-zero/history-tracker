"""
방안 A — 임베딩 유사도 기반 Issue 연결 (Layer 4 확장).

refs 텍스트 매칭에 의존하지 않고 임베딩 유사도로
Issue와 ChangeSet / Communication을 연결한다.

  Issue.embedding ↔ MODIFIED.embedding       → TRIGGERED_BY (ChangeSet → Issue)
  Issue.embedding ↔ Communication.embedding  → DISCUSSED_IN (Issue → Communication)

실행 타이밍:
  이벤트 처리와 별개로 수동 또는 스케줄 호출.
  Issue / ChangeSet / Communication 임베딩이 충분히 쌓인 뒤 실행.

정밀도 정책 (TRIGGERED_BY):
  - 임계값: 0.55 (false positive 감소)
  - 시간 윈도우: 비대칭 — [createdAt - 1d, closedAt(+3d 유예) or now]
    이슈 종료 후 작성된 커밋은 사실상 그 이슈의 작업이 아니므로 차단
  - ChangeSet당 top-1 매칭만 유지: 같은 커밋이 여러 이슈에 동시 연결되지 않음
  - text TRIGGERED_BY가 이미 있는 ChangeSet은 시맨틱 매칭 스킵 (store fetch 단계에서 제외)
"""

import logging
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Awaitable, Callable

from graph.embedder import cosine_similarity

logger = logging.getLogger(__name__)

# TRIGGERED_BY 시맨틱 매칭 임계값 — 정밀도 우선
TRIGGERED_BY_THRESHOLD = 0.55
# DISCUSSED_IN은 별도 — 기존 값 유지 (스레드 그룹핑은 하위 2 쿼리 단에서 처리)
DISCUSSED_IN_THRESHOLD = 0.40
# 외부 코드 호환용 alias (issue_verifier 등에서 사용). 신규 코드는 위 두 상수를 직접 참조.
DEFAULT_THRESHOLD = DISCUSSED_IN_THRESHOLD

TIME_WINDOW_DAYS = 30                              # DISCUSSED_IN 대칭 윈도우용 (변경 없음)
ISSUE_PRE_BUFFER_DAYS  = 1                         # createdAt 이전 허용 (사전 작업)
ISSUE_POST_BUFFER_DAYS = 3                         # closedAt 이후 허용 (마무리 커밋)

TERMINAL_STATUSES = {"완료", "Done", "Closed", "Resolved", "해결됨"}


def _compute_issue_window(issue: dict) -> tuple[datetime, datetime]:
    """이슈의 작업 가능 시간 범위 [start, end] 를 비대칭으로 계산.

    start: createdAt(or occurredAt) - ISSUE_PRE_BUFFER_DAYS
    end:   closedAt + ISSUE_POST_BUFFER_DAYS  (closedAt이 명시되어 있을 때)
           or occurredAt + ISSUE_POST_BUFFER_DAYS  (status가 terminal일 때 fallback)
           or now (그 외 — 진행 중인 이슈)
    """
    occurred = issue["occurred_at"]
    created  = issue.get("created_at") or occurred
    closed   = issue.get("closed_at")
    status   = issue.get("status")

    start = created - timedelta(days=ISSUE_PRE_BUFFER_DAYS)

    if closed is not None:
        end = closed + timedelta(days=ISSUE_POST_BUFFER_DAYS)
    elif status in TERMINAL_STATUSES:
        # pipeline-worker가 closedAt을 아직 안 보내는 경우 fallback
        end = occurred + timedelta(days=ISSUE_POST_BUFFER_DAYS)
    else:
        # 진행 중인 이슈는 상한 없음
        end = datetime.now(timezone.utc)

    return start, end


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
    threshold: float = TRIGGERED_BY_THRESHOLD,
) -> int:
    """Issue.embedding ↔ MODIFIED.embedding 유사도로 TRIGGERED_BY 엣지 생성.

    정책:
      - 비대칭 시간 윈도우: 이슈 종료 후 +3일까지만 허용 (`_compute_issue_window`)
      - ChangeSet당 top-1 매칭만 저장: 한 커밋이 여러 이슈로 분산되지 않음
      - store.fetch_modified_embeddings는 text TRIGGERED_BY가 이미 있는 ChangeSet을 제외해서
        반환한다고 가정 (텍스트 hard-link 우선)

    Returns:
        생성(또는 갱신)된 TRIGGERED_BY 엣지 수
    """
    issues   = await store.fetch_issue_embeddings()
    modified = await store.fetch_modified_embeddings()

    if not issues or not modified:
        logger.info("TRIGGERED_BY 생성 스킵: issues=%d, modified=%d", len(issues), len(modified))
        return 0

    # changeset_id → (best_jira_key, best_score) — top-1 보장
    best_per_cs: dict[str, tuple[str, float]] = {}

    for issue in issues:
        issue_vec = issue["embedding"]
        start, end = _compute_issue_window(issue)

        for mod in modified:
            mod_time = mod["occurred_at"]
            if mod_time < start or mod_time > end:
                continue

            score = cosine_similarity(issue_vec, mod["embedding"])
            if score < threshold:
                continue

            cs_id   = mod["changeset_id"]
            current = best_per_cs.get(cs_id)
            if current is None or score > current[1]:
                best_per_cs[cs_id] = (issue["id"], score)

    created = 0
    for cs_id, (jira_key, confidence) in best_per_cs.items():
        await store.create_triggered_by_edge(cs_id, jira_key, confidence)
        created += 1
        logger.debug("TRIGGERED_BY 생성: changeset=%s issue=%s score=%.3f",
                     cs_id, jira_key, confidence)

    logger.info(
        "TRIGGERED_BY 엣지 생성 완료: %d개 (threshold=%.2f, scope=%d issues × %d changesets)",
        created, threshold, len(issues), len(modified),
    )
    return created


async def build_issue_communication_links(
    store: IssueLinkStore,
    threshold: float = DISCUSSED_IN_THRESHOLD,
) -> int:
    """Issue.embedding ↔ Communication.embedding 유사도로 DISCUSSED_IN 엣지 생성.

    DISCUSSED_IN은 TRIGGERED_BY와 달리 대칭 윈도우 + 별도 임계값 사용 (스코프 분리).

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
