"""
자동구축 — 임베딩 유사도 기반 Issue 연결 (Layer 4 확장).

refs 텍스트 매칭에 의존하지 않고 임베딩 유사도로
Issue와 ChangeSet / Communication을 연결한다.

  Issue.embedding ↔ MODIFIED.embedding       → TRIGGERED_BY (ChangeSet → Issue)
  Issue.embedding ↔ Communication.embedding  → DISCUSSED_IN (Issue → Communication)

실행 타이밍:
  이벤트 처리와 별개로 수동 또는 스케줄 호출.
  Issue / ChangeSet / Communication 임베딩이 충분히 쌓인 뒤 실행.

정밀도 정책 (TRIGGERED_BY):
  - 임계값: 0.34 (근거는 상수 주석)
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

from graph.embedder import embed_batch, similarity_matrix

logger = logging.getLogger(__name__)


def _group_by_project(rows: list[dict]) -> dict[str, list[dict]]:
    """project_id 기준 그룹핑 — 프로젝트 간 임베딩 비교(크로스 테넌트 엣지)를 차단한다."""
    grouped: dict[str, list[dict]] = defaultdict(list)
    for row in rows:
        grouped[row.get("project_id") or ""].append(row)
    return grouped

# TRIGGERED_BY 시맨틱 매칭 임계값. 후보가 "text 링크 없는 커밋"뿐이라 풀이 작아 낮은 값이
# 안전하다 — 0.55는 시맨틱 엣지를 0개로 전멸시켰다. 단 키 관행이 없는 팀은 전 커밋이 후보라
# 노이즈가 커진다(admin 파라미터로 조정 가능). 0.34는 3-large@1536 재스윕값 — 무관 2개만
# 자르고 골든 손실 0(precision 0.417→0.500)이나 분모 10쌍이라 잠정. 모델을 바꾸면 재스윕 전제.
TRIGGERED_BY_THRESHOLD = 0.34
# TRIGGERED_BY 메시지 비교 방식 기본값 — REFERENCE와 동일하게 쌍별 max 주입으로 통일.
# 골든 측정에선 off가 근소 우위였지만 그 차이가 전부 응답 confidence 필터(0.5) 미만 구간이라
# 답변 레벨에선 동점 — 커밋 메시지가 이슈의 자연어와 어휘가 맞는 일반 배포 기준으로 max를 택했다.
TRIGGERED_BY_MESSAGE_MODE = "max"
# DISCUSSED_IN은 별도 (스레드 그룹핑은 하위 2 쿼리 단에서 처리). 0.48은 3-large@1536 재스윕에서
# 골든을 전수 보존하는 지점 — 0.50은 precision이 0.756→0.833으로 오르지만 골든 HT-23을 잃고,
# 그 스레드는 이슈 키가 없어 시맨틱 엣지가 유일한 연결이다. 마진 0.005. 모델을 바꾸면 재스윕 전제.
DISCUSSED_IN_THRESHOLD = 0.48
# 외부 코드 호환용 alias (issue_verifier 등에서 사용). 신규 코드는 위 두 상수를 직접 참조.
DEFAULT_THRESHOLD = DISCUSSED_IN_THRESHOLD

# DISCUSSED_IN fan-out 컷 — 이슈의 최고점 스레드와 이 폭 안에 드는 스레드만 유지한다.
# 개수(top-k)가 아니라 상대 거리로 자르는 이유: 이슈의 논의 스레드 수는 팀 활동량에 비례해서
# "이슈당 n개"라는 가정을 배포 제품에 박을 수 없다. 마진은 진짜 논의가 몇 개든 최고점 근처에
# 모이기만 하면 전부 살린다.
DEFAULT_DISCUSSED_IN_MARGIN = 0.10

ISSUE_PRE_BUFFER_DAYS  = 1                         # createdAt 이전 허용 (사전 작업)
ISSUE_POST_BUFFER_DAYS = 3                         # closedAt 이후 허용 (마무리 커밋)

# DISCUSSED_IN은 커밋(TRIGGERED_BY)보다 앞쪽 버퍼가 넓다 — 대화는 이슈를 파기 전에 먼저
# 오가는 게 흔하다("이거 티켓 파자"). 4일인 이유: 경계가 타임스탬프 비교라 3일이면 가장 흔한
# "주말 전 논의 → 주초 이슈 생성"(금→월)이 시각에 따라 잘린다. 반대로 뒤쪽은 좁게 유지한다:
# 이슈가 끝난 뒤의 대화는 대부분 다음 작업 얘기라 노이즈다.
DISCUSSED_IN_PRE_BUFFER_DAYS  = 4
DISCUSSED_IN_POST_BUFFER_DAYS = 3

TERMINAL_STATUSES = {"완료", "Done", "Closed", "Resolved", "해결됨"}


def _compute_issue_window(
    issue: dict,
    pre_days: int = ISSUE_PRE_BUFFER_DAYS,
    post_days: int = ISSUE_POST_BUFFER_DAYS,
) -> tuple[datetime, datetime]:
    """이슈의 작업 가능 시간 범위 [start, end] 를 비대칭으로 계산.

    start: createdAt(or occurredAt) - pre_days
    end:   closedAt + post_days  (closedAt이 명시되어 있을 때)
           or occurredAt + post_days  (status가 terminal일 때 fallback)
           or now (그 외 — 진행 중인 이슈)

    버퍼는 호출자가 정한다 — 커밋과 대화는 이슈 생애 대비 분포가 다르다 (위 상수 참고).
    """
    occurred = issue["occurred_at"]
    created  = issue.get("created_at") or occurred
    closed   = issue.get("closed_at")
    status   = issue.get("status")

    start = created - timedelta(days=pre_days)

    if closed is not None:
        end = closed + timedelta(days=post_days)
    elif status in TERMINAL_STATUSES:
        # pipeline-worker가 closedAt을 아직 안 보내는 경우 fallback
        end = occurred + timedelta(days=post_days)
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

    fetch_changeset_message_embeddings: Callable[[], Awaitable[list[dict]]]
    """embedding(커밋 메시지)이 있는 ChangeSet 노드 반환 — TRIGGERED_BY의 메시지 비교 열.
    fetch_modified_embeddings와 같은 제외 규칙(text TRIGGERED_BY 보유 커밋 제외)을 적용해야
    한다 — 아니면 무링크 풀 전제(정밀도 필터)가 메시지 열에서 무너진다.
    Returns:
        [{"project_id": str, "changeset_id": str, "message": str, "embedding": list[float],
          "occurred_at": datetime}, ...]
        message는 원문이다 — 수동 정밀 구축이 LLM에게 커밋을 보여줄 때 쓴다(임베딩 비교에는 불필요).
    """

    fetch_unembedded_issues: Callable[[bool], Awaitable[list[dict]]]
    """embedding 프로퍼티가 없는 Issue 노드 반환 (보정용).
    Args:
        force: True면 embedding이 이미 있는 노드까지 전부 반환 (모델 교체 시 전량 재임베딩)
    Returns:
        [{"project_id": str, "id": str (jira_key), "title": str, "body": str}, ...]
    """

    save_issue_embedding: Callable[[str, str, list[float]], Awaitable[None]]
    """Issue 노드에 embedding 저장.
    Args:
        project_id: 프로젝트 UUID
        jira_key:   Issue jira_key
        embedding:  임베딩 벡터
    """


def select_triggered_by_pairs(
    issues: list[dict],
    candidate_rows: list[dict],
    threshold: float = TRIGGERED_BY_THRESHOLD,
) -> list[tuple[str, str, str, float]]:
    """자동구축의 TRIGGERED_BY 후보 선별 — 비대칭 윈도우·임계값·커밋당 top-1.

    엣지 생성과 분리된 순수 함수다 (분리 이유는 select_reference_pairs 참고).

    Args:
        issues:         Issue 행 (임베딩 + 윈도우 계산용 메타)
        candidate_rows: 비교 열 (파일 diff 행 + 커밋 메시지 행 — message_mode에 따라 호출자가 구성)
        threshold:      엣지 생성 최소 유사도

    Returns:
        [(project_id, changeset_id, jira_key, score), ...]
    """
    # 같은 프로젝트 안에서만 비교 (크로스 테넌트 엣지 차단)
    issues_by_project = _group_by_project(issues)
    mods_by_project   = _group_by_project(candidate_rows)

    # (project_id, changeset_id) → (best_jira_key, best_score) — top-1 보장.
    # 같은 커밋의 diff 행과 메시지 행이 같은 키로 병합돼 쌍별 max가 된다(message_mode=max).
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

    return [
        (project_id, cs_id, jira_key, score)
        for (project_id, cs_id), (jira_key, score) in best_per_cs.items()
    ]


async def build_issue_changeset_links(
    store: IssueLinkStore,
    threshold: float = TRIGGERED_BY_THRESHOLD,
    message_mode: str = TRIGGERED_BY_MESSAGE_MODE,
) -> int:
    """Issue.embedding ↔ MODIFIED.embedding 유사도로 TRIGGERED_BY 엣지 생성.

    정책:
      - 비대칭 시간 윈도우: 이슈 종료 후 +3일까지만 허용 (`_compute_issue_window`)
      - ChangeSet당 top-1 매칭만 저장: 한 커밋이 여러 이슈로 분산되지 않음
      - store.fetch_modified_embeddings는 text TRIGGERED_BY가 이미 있는 ChangeSet을 제외해서
        반환한다고 가정 (텍스트 hard-link 우선)

    message_mode: 커밋 메시지 임베딩(ChangeSet.embedding)을 비교 열에 넣는 방식.
        max  — 기본: 메시지 행을 파일 행에 추가, changeset별 top-1 집계가 같은 키로
               병합해 diff·메시지 중 최고점을 취한다
        off  — 파일(diff) 임베딩만 비교 (도입 전 동작, 측정 재현용)
        only — 메시지 임베딩만 비교 (diff는 fetch하지 않음)

    Returns:
        생성(또는 갱신)된 TRIGGERED_BY 엣지 수
    """
    if message_mode not in ("off", "max", "only"):
        raise ValueError(f"지원하지 않는 message_mode: {message_mode!r} (off/max/only)")

    issues   = await store.fetch_issue_embeddings()
    modified = [] if message_mode == "only" else await store.fetch_modified_embeddings()
    messages = [] if message_mode == "off" else await store.fetch_changeset_message_embeddings()
    candidates = modified + messages

    if not issues or not candidates:
        logger.info("TRIGGERED_BY 생성 스킵: issues=%d, candidates=%d", len(issues), len(candidates))
        return 0

    created = 0
    for project_id, cs_id, jira_key, confidence in select_triggered_by_pairs(
        issues, candidates, threshold
    ):
        await store.create_triggered_by_edge(project_id, cs_id, jira_key, confidence)
        created += 1
        logger.debug("TRIGGERED_BY 생성: changeset=%s issue=%s score=%.3f",
                     cs_id, jira_key, confidence)

    logger.info(
        "TRIGGERED_BY 엣지 생성 완료: %d개 (threshold=%.2f, message_mode=%s, scope=%d issues × %d rows)",
        created, threshold, message_mode, len(issues), len(candidates),
    )
    return created


def select_discussed_in_pairs(
    issues: list[dict],
    comms: list[dict],
    threshold: float = DISCUSSED_IN_THRESHOLD,
    margin: float = DEFAULT_DISCUSSED_IN_MARGIN,
    pre_days: int = DISCUSSED_IN_PRE_BUFFER_DAYS,
    post_days: int = DISCUSSED_IN_POST_BUFFER_DAYS,
) -> list[tuple[str, str, str, float]]:
    """자동구축의 DISCUSSED_IN 후보 선별 — 이슈 생애 윈도우·임계값·스레드 마진 컷.

    엣지 생성과 분리된 순수 함수다 (분리 이유는 select_reference_pairs 참고).

    Returns:
        [(project_id, jira_key, communication_id, score), ...]
    """
    # 같은 프로젝트 안에서만 비교 (크로스 테넌트 엣지 차단)
    issues_by_project = _group_by_project(issues)
    comms_by_project  = _group_by_project(comms)

    selected: list[tuple[str, str, str, float]] = []
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

        # 이슈 생애 윈도우 마스크 + 바닥 임계값.
        windows  = [_compute_issue_window(it, pre_days, post_days) for it in p_issues]
        starts   = np.array([w[0].timestamp() for w in windows])
        ends     = np.array([w[1].timestamp() for w in windows])
        comm_ts  = np.array([c["occurred_at"].timestamp() for c in p_comms])
        in_window = (comm_ts[None, :] >= starts[:, None]) & (comm_ts[None, :] <= ends[:, None])
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
            selected.append((project_id, p_issues[i]["id"], p_comms[j]["id"], float(sim[i, j])))

    return selected


async def build_issue_communication_links(
    store: IssueLinkStore,
    threshold: float = DISCUSSED_IN_THRESHOLD,
    margin: float = DEFAULT_DISCUSSED_IN_MARGIN,
    pre_days: int = DISCUSSED_IN_PRE_BUFFER_DAYS,
    post_days: int = DISCUSSED_IN_POST_BUFFER_DAYS,
) -> int:
    """Issue.embedding ↔ Communication.embedding 유사도로 DISCUSSED_IN 엣지 생성.

    TRIGGERED_BY와 같은 이슈 생애 윈도우를 쓰되 버퍼·임계값은 따로 둔다 (스코프 분리).

    Args:
        store:     Neo4j 접근 인터페이스
        threshold: 엣지 생성 최소 유사도 (바닥선)
        margin:    이슈별 fan-out 컷 — 그 이슈의 최고점 스레드와 이 폭 안에 드는 스레드만 유지.
                   스레드의 대표값은 그 안의 최고 점수다 (수다스러운 스레드가 자리를 독식하지
                   않도록 메시지가 아니라 스레드 단위로 비교).
        pre_days:  이슈 생성 이전 몇 일까지 후보로 볼지
        post_days: 이슈 종료 이후 몇 일까지 후보로 볼지

    Returns:
        생성(또는 갱신)된 DISCUSSED_IN 엣지 수
    """
    issues = await store.fetch_issue_embeddings()
    comms  = await store.fetch_communication_embeddings()

    if not issues or not comms:
        logger.info("DISCUSSED_IN 생성 스킵: issues=%d, comms=%d", len(issues), len(comms))
        return 0

    created = 0
    for project_id, jira_key, comm_id, score in select_discussed_in_pairs(
        issues, comms, threshold, margin, pre_days, post_days
    ):
        await store.create_discussed_in_edge(project_id, jira_key, comm_id, confidence=score)
        created += 1
        logger.debug("DISCUSSED_IN 생성: issue=%s comm=%s score=%.3f", jira_key, comm_id, score)

    logger.info("DISCUSSED_IN 엣지 생성 완료: %d개 (threshold=%.2f, margin=%.2f, window=-%dd/+%dd)",
                created, threshold, margin, pre_days, post_days)
    return created


async def backfill_issue_embeddings(store: IssueLinkStore, force: bool = False) -> dict:
    """embedding이 없는 Issue 노드를 배치 임베딩으로 보정.

    임베딩 대상 텍스트는 수집 경로(event_handler)와 같은 "title\\n\\nbody"다 — 어긋나면
    수집으로 들어온 이슈와 백필로 채운 이슈의 벡터가 서로 다른 기준이 된다.

    Args:
        force: 이미 embedding이 있는 노드까지 다시 임베딩한다 (임베딩 모델 교체 시 전량 교체).
               기본 False는 기존 동작(수집 중 누락분만 보정) 유지.

    Returns:
        {"saved": 저장된 노드 수, "total": 대상 노드 수}
        embed_batch가 청크 실패를 빈 벡터로 삼키므로, saved != total로 미완주를 드러낸다.
    """
    nodes = await store.fetch_unembedded_issues(force)
    if not nodes:
        logger.info("보정할 Issue 노드 없음")
        return {"saved": 0, "total": 0}

    vectors = await embed_batch([f"{n['title']}\n\n{n['body']}" for n in nodes])

    saved = 0
    for node, vec in zip(nodes, vectors):
        if vec:
            await store.save_issue_embedding(node.get("project_id") or "", node["id"], vec)
            saved += 1

    logger.info("Issue 임베딩 보정 완료: %d/%d개", saved, len(nodes))
    return {"saved": saved, "total": len(nodes)}
