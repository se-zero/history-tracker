"""
그래프 후처리(Layer 4) — per-project 빌드 + 디바운스 트리거.

수집 이벤트는 노드와 Layer 1~3 엣지만 만든다. 소스를 잇는 시맨틱 엣지
(TRIGGERED_BY / DISCUSSED_IN / REFERENCE)는 노드 쌍을 전수 비교하는 배치라
이벤트마다 돌릴 수 없다. 대신:

  - 이벤트가 처리될 때마다 mark_dirty(project_id)로 "후처리 필요한 새 데이터 있음"을 표시
  - 백그라운드 루프가 어떤 프로젝트의 큐가 DEBOUNCE_SECONDS 이상 잠잠해지면 그 프로젝트만 1회 빌드

빌드는 **프로젝트 단위**다. 빌더 엣지 로직은 이미 project_id로 격리돼 있고
(_group_by_project), store fetch가 project_id로 스코프된 데이터만 넘긴다.

동시성:
  - 같은 프로젝트는 동시에 한 번만 빌드(coalesce) — running이면 현재 상태를 그대로 반환.
  - 다른 프로젝트는 동시 빌드 허용. 단 메모리/Neo4j 보호를 위해 _build_semaphore로
    전체 동시 빌드 수를 MAX_CONCURRENCY로 제한한다. OpenAI 호출은 rate_limiter가 페이싱한다.

상태/타이밍은 모두 in-process다 — ai-engine 다중 인스턴스 시 인스턴스별로 격리되므로,
수평 확장 시 공유 저장소+분산 락으로 교체해야 한다 (단계 1 rate_limiter와 동일한 제약).
"""

import asyncio
import logging
import os
import time
from datetime import datetime, timezone

logger = logging.getLogger(__name__)

# 큐가 이만큼 잠잠하면 수집이 일단락된 것으로 보고 후처리 실행
DEBOUNCE_SECONDS = float(os.environ.get("GRAPH_BUILD_DEBOUNCE_SECONDS", "30"))
# 유휴 여부를 확인하는 주기
CHECK_INTERVAL_SECONDS = float(os.environ.get("GRAPH_BUILD_CHECK_INTERVAL_SECONDS", "10"))
# 쿨다운 — 프로젝트별 마지막 빌드 이후 최소 이 간격이 지나야 디바운스 빌드를 다시 실행.
# webhook 등으로 버스트가 잦아도 이 주기당 최대 1회로 제한한다 (O(n²) 재스캔 과다 방지).
MIN_BUILD_INTERVAL_SECONDS = float(os.environ.get("GRAPH_BUILD_MIN_INTERVAL_SECONDS", "300"))
# 동시에 진행 가능한 빌드 수 상한 — 여러 프로젝트가 동시에 재구축돼도 부하를 제한한다.
MAX_CONCURRENCY = max(1, int(os.environ.get("GRAPH_BUILD_MAX_CONCURRENCY", "2")))

_last_event_at: dict[str, float] = {}    # project_id -> 마지막 이벤트 monotonic 시각
_last_build_at: dict[str, float] = {}    # project_id -> 마지막 빌드 완료 monotonic 시각
_dirty: set[str] = set()                 # 후처리가 필요한 project_id 집합
_build_status: dict[str, dict] = {}      # project_id -> 빌드 상태(state/verify/started_at/result/error)
_build_tasks: set[asyncio.Task] = set()  # create_task 참조 보관 (GC로 태스크가 중간에 사라지지 않게)

# 이벤트 루프 밖(import 시점)에서 만들면 루프에 묶일 수 있어 첫 사용 시점에 lazy 생성한다.
_build_semaphore: asyncio.Semaphore | None = None


def _get_semaphore() -> asyncio.Semaphore:
    global _build_semaphore
    if _build_semaphore is None:
        _build_semaphore = asyncio.Semaphore(MAX_CONCURRENCY)
    return _build_semaphore


# ── per-project 빌드 상태 ──────────────────────────────────────────────────


def _idle_status() -> dict:
    return {"state": "idle", "verify": None, "started_at": None, "result": None, "error": None}


def get_build_status(project_id: str) -> dict:
    """project_id의 현재 빌드 상태를 반환한다. 빌드 이력이 없으면 idle.

    state: idle | running | succeeded | failed
    """
    return _build_status.get(project_id) or _idle_status()


def is_build_running(project_id: str) -> bool:
    status = _build_status.get(project_id)
    return bool(status) and status["state"] == "running"


def mark_build_running(project_id: str, verify: bool) -> None:
    _build_status[project_id] = {
        "state": "running",
        "verify": verify,
        "started_at": datetime.now(timezone.utc).isoformat(),
        "result": None,
        "error": None,
    }


def _try_start_build(project_id: str, verify: bool) -> bool:
    """빌드 중이 아니면 running으로 표시하고 True. 이미 빌드 중이면 False(coalesce).

    check(is_build_running)와 set(mark_build_running) 사이에 await가 없어 단일
    이벤트 루프에서 원자적이다 — 동시 트리거가 같은 프로젝트를 두 번 시작하지 못한다.
    """
    if is_build_running(project_id):
        return False
    mark_build_running(project_id, verify)
    return True


def mark_dirty(project_id: str) -> None:
    """이벤트 처리 직후 호출 — 해당 프로젝트에 후처리가 필요한 새 데이터가 들어왔음을 표시한다."""
    if not project_id:
        return
    _last_event_at[project_id] = time.monotonic()
    _dirty.add(project_id)


# ── 빌드 실행 ──────────────────────────────────────────────────────────────


async def run_postprocess_sequence(project_id: str, verify: bool = False) -> dict:
    """project_id에 한정해 후처리(Layer 4) 시퀀스를 순서대로 1회 실행한다.

    verify=False: 방안 A — 임베딩 유사도만 (LLM 비용 없음). 디바운스 자동 빌드 기본값.
    verify=True:  방안 D — 시맨틱 엣지를 비운 뒤 임베딩 후보 + LLM 검증으로 재구축.
                  A의 false positive가 남지 않도록 clear가 선행한다. 호출당 LLM 비용 발생.
                  수동 '정밀 재구축'에서만 사용.

    순서:
      0. Slack LLM 노이즈 필터 (llm_filtered=False인 신규 Slack 메시지만, 증분) — 링크 전에
         노이즈를 먼저 제거해 backfill/링크 대상에 끼지 않게 한다
      0.5(verify만). 시맨틱 TRIGGERED_BY/DISCUSSED_IN clear — A의 결과를 비우고 D로 재구축
      1. 임베딩 누락 Communication 보정 (이후 비교 대상에 포함되도록)
      2. TRIGGERED_BY + DISCUSSED_IN 시맨틱 링크 (GitHub↔Jira, Jira↔Slack)
      3. REFERENCE 시맨틱 링크 (GitHub↔Slack/GitHub이슈)
      4. DISCUSSED_IN 스레드 전파 (2에서 만든 엣지를 같은 스레드로 확장)

    모든 단계가 project_id로 스코프되고 idempotent. 동시성·상태 관리는 호출자
    (_execute_build / 디바운스 루프)가 담당한다 — 이 함수는 순수 시퀀스다.
    """
    from graph.builder import (
        clear_semantic_discussed_in,
        clear_semantic_triggered_by,
        make_neo4j_issue_link_store,
        make_neo4j_reference_store,
        propagate_thread_discussed_in,
    )
    from graph.reference_builder import (
        backfill_communication_embeddings,
        build_reference_edges,
    )
    from graph.slack_batch_filter import run_slack_llm_filter

    # verify에 따라 방안 A(임베딩만) 또는 방안 D(LLM 검증) 링커를 고른다.
    if verify:
        from graph.issue_verifier import (
            build_issue_changeset_links_verified as build_triggered_by,
            build_issue_communication_links_verified as build_discussed_in,
        )
    else:
        from graph.issue_linker import (
            build_issue_changeset_links as build_triggered_by,
            build_issue_communication_links as build_discussed_in,
        )

    ref_store = make_neo4j_reference_store(project_id)
    link_store = make_neo4j_issue_link_store(project_id)

    # 0) Slack 노이즈 정제 — 신규 Slack 메시지만 LLM을 거친다(llm_filtered로 증분).
    # 링크보다 먼저 돌려 노이즈가 backfill/링크 대상에 끼지 않게 한다.
    # 실패해도 링크 단계는 진행 — 연결이 더 중요해 격리한다 (project_context는 배치라 생략).
    try:
        slack = await run_slack_llm_filter(project_id=project_id)
    except Exception:
        logger.exception("Slack LLM 필터 실패 — 링크 단계는 계속 진행 (project=%s)", project_id)
        slack = {"kept": 0, "deleted": 0}

    # 0.5) 방안 D: A가 남긴 시맨틱 엣지를 먼저 비운다 — D의 정밀도가 A의
    # false positive로 희석되지 않도록. text(refs)·스레드 전파 엣지는 보존된다.
    if verify:
        await clear_semantic_triggered_by(project_id)
        await clear_semantic_discussed_in(project_id)

    results = {
        "slack_kept":        slack["kept"],
        "slack_deleted":     slack["deleted"],
        "backfilled":        await backfill_communication_embeddings(ref_store),
        "triggered_by":      await build_triggered_by(link_store),
        "discussed_in":      await build_discussed_in(link_store),
        "reference":         await build_reference_edges(ref_store),
        "thread_propagated": await propagate_thread_discussed_in(project_id),
    }
    return results


async def _execute_build(project_id: str, verify: bool, *, requeue_on_failure: bool = False) -> bool:
    """빌드를 1회 실행하고 결과를 _build_status에 기록한다. 성공하면 True.

    동시 빌드 수는 _build_semaphore로 제한한다(대기 동안에도 상태는 running 유지).
    호출 전에 mark_build_running이 선행돼 있어야 한다(_try_start_build 경유).
    어떤 경우에도 _last_build_at을 갱신해 쿨다운 기준점을 남긴다.

    requeue_on_failure=True(자동 디바운스 경로): 실패 시 dirty에 다시 올려 다음 주기에 재시도한다.
    수동 트리거는 False — 실패를 사용자가 보고 직접 재트리거하게 둔다(조용한 재시도 금지).
    """
    started_at = _build_status[project_id]["started_at"]
    try:
        async with _get_semaphore():
            result = await run_postprocess_sequence(project_id, verify)
        _build_status[project_id] = {
            "state": "succeeded", "verify": verify,
            "started_at": started_at, "result": result, "error": None,
        }
        logger.info("그래프 후처리 완료: project=%s result=%s", project_id, result)
        return True
    except Exception as e:
        logger.exception("그래프 후처리 실패: project=%s", project_id)
        _build_status[project_id] = {
            "state": "failed", "verify": verify,
            "started_at": started_at, "result": None, "error": str(e),
        }
        if requeue_on_failure:
            # 다음 디바운스 주기에 재시도 (쿨다운이 _last_build_at로 적용돼 폭주하지 않음)
            _dirty.add(project_id)
        return False
    finally:
        _last_build_at[project_id] = time.monotonic()


def _spawn_tracked(coro) -> None:
    """코루틴을 백그라운드 태스크로 띄우고 참조를 보관한다(완료 시 자동 정리).

    참조를 들고 있지 않으면 asyncio가 실행 도중 태스크를 GC할 수 있어 _build_tasks에 보관한다.
    """
    task = asyncio.create_task(coro)
    _build_tasks.add(task)
    task.add_done_callback(_build_tasks.discard)


def trigger_build(project_id: str, verify: bool) -> dict:
    """수동 빌드 트리거 — 백그라운드 태스크로 빌드를 시작하고 즉시 현재 상태를 반환한다(202용).

    이미 빌드 중이면 새로 시작하지 않고 진행 중인 상태를 반환한다(coalesce).
    """
    if _try_start_build(project_id, verify):
        _spawn_tracked(_execute_build(project_id, verify))
    return get_build_status(project_id)


async def start_debounce_loop() -> None:
    """큐 유휴 감지 → 디바운스 후 dirty 프로젝트별 후처리 실행. 무한 루프 (lifespan 태스크).

    자동 빌드도 _build_status를 갱신한다(수동/자동 동일 상태 머신) — 같은 프로젝트의
    수동 트리거와 coalesce되고, 폴링하는 프론트가 자동 빌드도 관찰할 수 있다.

    빌드는 fire-and-forget로 띄운다(수동 경로와 동일) — 한 프로젝트 빌드가 느리거나
    hang해도 루프가 막히지 않고 다른 프로젝트의 idle 감지·빌드가 계속된다. 동시 빌드 수는
    _build_semaphore가 제한한다. 실패 재시도는 _execute_build(requeue_on_failure=True)가 처리.
    """
    logger.info(
        "그래프 후처리 디바운스 루프 시작 (debounce=%.0fs, check=%.0fs, min_interval=%.0fs, max_concurrency=%d)",
        DEBOUNCE_SECONDS, CHECK_INTERVAL_SECONDS, MIN_BUILD_INTERVAL_SECONDS, MAX_CONCURRENCY,
    )
    while True:
        try:
            await asyncio.sleep(CHECK_INTERVAL_SECONDS)

            for project_id in list(_dirty):
                now = time.monotonic()
                # 해당 프로젝트의 큐가 아직 잠잠하지 않으면 대기
                if now - _last_event_at.get(project_id, 0.0) < DEBOUNCE_SECONDS:
                    continue
                # 쿨다운: 마지막 빌드 후 MIN_BUILD_INTERVAL이 안 지났으면 대기 (dirty 유지)
                if now - _last_build_at.get(project_id, 0.0) < MIN_BUILD_INTERVAL_SECONDS:
                    continue
                # 같은 프로젝트가 (수동 등으로) 이미 빌드 중이면 dirty를 유지한 채 다음 주기로 미룬다
                if not _try_start_build(project_id, verify=False):
                    continue

                # 빌드 시작 전에 dirty에서 내려, 빌드 도중 도착한 이벤트가 다음 주기 빌드를
                # 다시 예약하도록 한다 (새 데이터 유실 방지). 실패 시 _execute_build가 다시 올린다.
                _dirty.discard(project_id)
                logger.info("큐 유휴 감지 — 그래프 후처리 시작: project=%s", project_id)
                _spawn_tracked(_execute_build(project_id, verify=False, requeue_on_failure=True))

        except asyncio.CancelledError:
            logger.info("그래프 후처리 디바운스 루프 종료")
            raise
        except Exception:
            # 실패해도 루프는 유지한다.
            logger.exception("그래프 후처리 디바운스 루프 오류 — 계속 진행")
