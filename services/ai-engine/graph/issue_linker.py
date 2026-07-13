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
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Awaitable, Callable

import numpy as np

from graph.embedder import similarity_matrix

logger = logging.getLogger(__name__)


def _group_by_project(rows: list[dict]) -> dict[str, list[dict]]:
    """project_id 기준 그룹핑 — 프로젝트 간 임베딩 비교(크로스 테넌트 엣지)를 차단한다."""
    grouped: dict[str, list[dict]] = defaultdict(list)
    for row in rows:
        grouped[row.get("project_id") or ""].append(row)
    return grouped

# TRIGGERED_BY 시맨틱 매칭 임계값 — 정밀도 우선
TRIGGERED_BY_THRESHOLD = 0.55
# DISCUSSED_IN은 별도 — 기존 값 유지 (스레드 그룹핑은 하위 2 쿼리 단에서 처리)
DISCUSSED_IN_THRESHOLD = 0.40
# 외부 코드 호환용 alias (issue_verifier 등에서 사용). 신규 코드는 위 두 상수를 직접 참조.
DEFAULT_THRESHOLD = DISCUSSED_IN_THRESHOLD

# DISCUSSED_IN fan-out 컷 — 이슈의 최고점 스레드와 이 폭 안에 드는 스레드만 유지한다.
# 개수(top-k)가 아니라 상대 거리로 자르는 이유: 이슈의 논의 스레드 수는 팀 활동량에 비례해서
# "이슈당 n개"라는 가정을 배포 제품에 박을 수 없다. 마진은 진짜 논의가 몇 개든 최고점 근처에
# 모이기만 하면 전부 살린다.
DEFAULT_DISCUSSED_IN_MARGIN = 0.10

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
        [{"project_id": str, "id": str (jira_key), "embedding": list[float], "occurred_at": datetime}, ...]
    """

    fetch_modified_embeddings: Callable[[], Awaitable[list[dict]]]
    """embedding이 있는 모든 MODIFIED 엣지 반환.
    Returns:
        [{"project_id": str, "changeset_id": str, "embedding": list[float], "occurred_at": datetime}, ...]
    """

    fetch_communication_embeddings: Callable[[], Awaitable[list[dict]]]
    """embedding이 있는 모든 Communication 노드 반환.
    Returns:
        [{"project_id": str, "id": str (url), "conversation_id": str | None,
          "embedding": list[float], "occurred_at": datetime}, ...]
        conversation_id는 DISCUSSED_IN 마진 컷의 그룹 키다 (없으면 그 메시지가 곧 스레드).
    """

    create_triggered_by_edge: Callable[[str, str, str, float], Awaitable[None]]
    """TRIGGERED_BY 엣지 생성 또는 갱신.
    Args:
        project_id:   프로젝트 UUID (노드 매칭 스코프)
        changeset_id: ChangeSet hash
        jira_key:     Issue jira_key
        confidence:   코사인 유사도 (0~1)
    """

    create_discussed_in_edge: Callable[[str, str, str, float], Awaitable[None]]
    """DISCUSSED_IN 엣지 생성 또는 갱신.
    Args:
        project_id: 프로젝트 UUID (노드 매칭 스코프)
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

    # 같은 프로젝트 안에서만 비교 (크로스 테넌트 엣지 차단)
    issues_by_project = _group_by_project(issues)
    mods_by_project   = _group_by_project(modified)

    # (project_id, changeset_id) → (best_jira_key, best_score) — top-1 보장
    best_per_cs: dict[tuple[str, str], tuple[str, float]] = {}

    for project_id, project_issues in issues_by_project.items():
        project_mods = mods_by_project.get(project_id, [])
        # 빈 임베딩은 제외 — 유사도 0이라 어차피 임계값 미달이고, 행렬화도 깨진다
        p_issues = [it for it in project_issues if it.get("embedding")]
        p_mods   = [m for m in project_mods if m.get("embedding")]
        if not p_issues or not p_mods:
            continue

        # 전체 쌍 코사인 유사도를 numpy로 일괄 계산 (issues × changesets)
        sim = similarity_matrix(
            [it["embedding"] for it in p_issues],
            [m["embedding"] for m in p_mods],
        )

        # 비대칭 시간 윈도우 마스크 — issue마다 [start, end] 안의 changeset만 허용
        windows = [_compute_issue_window(it) for it in p_issues]
        starts  = np.array([w[0].timestamp() for w in windows])
        ends    = np.array([w[1].timestamp() for w in windows])
        mod_ts  = np.array([m["occurred_at"].timestamp() for m in p_mods])
        in_window = (mod_ts[None, :] >= starts[:, None]) & (mod_ts[None, :] <= ends[:, None])

        # 윈도우·임계값을 통과한 쌍만 남기고, changeset(열)별 top-1 issue를 고른다
        valid = in_window & (sim >= threshold)
        sim_valid = np.where(valid, sim, -np.inf)
        best_issue_idx = sim_valid.argmax(axis=0)  # 각 changeset의 최고 유사 issue
        best_score     = sim_valid.max(axis=0)

        for j, mod in enumerate(p_mods):
            score = float(best_score[j])
            if score < threshold:      # -inf 포함 (유효 매칭 없음)
                continue
            cs_key  = (project_id, mod["changeset_id"])
            current = best_per_cs.get(cs_key)
            if current is None or score > current[1]:
                best_per_cs[cs_key] = (p_issues[int(best_issue_idx[j])]["id"], score)

    created = 0
    for (project_id, cs_id), (jira_key, confidence) in best_per_cs.items():
        await store.create_triggered_by_edge(project_id, cs_id, jira_key, confidence)
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
    margin: float = DEFAULT_DISCUSSED_IN_MARGIN,
) -> int:
    """Issue.embedding ↔ Communication.embedding 유사도로 DISCUSSED_IN 엣지 생성.

    DISCUSSED_IN은 TRIGGERED_BY와 달리 대칭 윈도우 + 별도 임계값 사용 (스코프 분리).

    Args:
        store:     Neo4j 접근 인터페이스
        threshold: 엣지 생성 최소 유사도 (바닥선)
        margin:    이슈별 fan-out 컷 — 그 이슈의 최고점 스레드와 이 폭 안에 드는 스레드만 유지.
                   스레드의 대표값은 그 안의 최고 점수다 (수다스러운 스레드가 자리를 독식하지
                   않도록 메시지가 아니라 스레드 단위로 비교).

    Returns:
        생성(또는 갱신)된 DISCUSSED_IN 엣지 수
    """
    issues = await store.fetch_issue_embeddings()
    comms  = await store.fetch_communication_embeddings()

    if not issues or not comms:
        logger.info("DISCUSSED_IN 생성 스킵: issues=%d, comms=%d", len(issues), len(comms))
        return 0

    window_s = timedelta(days=TIME_WINDOW_DAYS).total_seconds()

    # 같은 프로젝트 안에서만 비교 (크로스 테넌트 엣지 차단)
    issues_by_project = _group_by_project(issues)
    comms_by_project  = _group_by_project(comms)

    created = 0
    for project_id, project_issues in issues_by_project.items():
        project_comms = comms_by_project.get(project_id, [])
        # 빈 임베딩은 제외 — 유사도 0이라 어차피 임계값 미달이고, 행렬화도 깨진다
        p_issues = [it for it in project_issues if it.get("embedding")]
        p_comms  = [c for c in project_comms if c.get("embedding")]
        if not p_issues or not p_comms:
            continue

        # 전체 쌍 코사인 유사도를 numpy로 일괄 계산 (issues × communications)
        sim = similarity_matrix(
            [it["embedding"] for it in p_issues],
            [c["embedding"] for c in p_comms],
        )

        # 대칭 시간 윈도우 마스크 + 바닥 임계값.
        issue_ts = np.array([it["occurred_at"].timestamp() for it in p_issues])
        comm_ts  = np.array([c["occurred_at"].timestamp() for c in p_comms])
        in_window = np.abs(issue_ts[:, None] - comm_ts[None, :]) <= window_s
        valid = in_window & (sim >= threshold)

        # fan-out 컷 — 이슈별로 스레드 최고점을 구하고, 최고점 − margin 안에 드는 스레드만 남긴다.
        thread_of = {c["id"]: (c.get("conversation_id") or c["id"]) for c in p_comms}
        thread_best: dict[int, dict[str, float]] = defaultdict(dict)
        for i, j in np.argwhere(valid):
            i, j = int(i), int(j)
            threads = thread_best[i]
            thread = thread_of[p_comms[j]["id"]]
            score = float(sim[i, j])
            if score > threads.get(thread, -1.0):
                threads[thread] = score
        kept_threads = {}
        for i, threads in thread_best.items():
            cut = max(threads.values()) - margin
            kept_threads[i] = {t for t, s in threads.items() if s >= cut}

        for i, j in np.argwhere(valid):
            i, j = int(i), int(j)
            if thread_of[p_comms[j]["id"]] not in kept_threads[i]:
                continue
            score = float(sim[i, j])
            await store.create_discussed_in_edge(project_id, p_issues[i]["id"], p_comms[j]["id"], confidence=score)
            created += 1
            logger.debug("DISCUSSED_IN 생성: issue=%s comm=%s score=%.3f",
                         p_issues[i]["id"], p_comms[j]["id"], score)

    logger.info("DISCUSSED_IN 엣지 생성 완료: %d개 (threshold=%.2f, margin=%.2f)",
                created, threshold, margin)
    return created
