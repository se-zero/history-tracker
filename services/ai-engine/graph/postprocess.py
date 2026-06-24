"""
그래프 후처리(Layer 4) 디바운스 트리거.

수집 이벤트는 노드와 Layer 1~3 엣지만 만든다. 소스를 잇는 시맨틱 엣지
(TRIGGERED_BY / DISCUSSED_IN / REFERENCE)는 노드 쌍을 전수 비교하는 배치라
이벤트마다 돌릴 수 없다. 대신:

  - 이벤트가 처리될 때마다 mark_dirty()로 "후처리 필요한 새 데이터 있음"을 표시
  - 백그라운드 루프가 큐가 DEBOUNCE_SECONDS 이상 잠잠해지면 후처리 시퀀스를 1회 실행

수집은 비동기·증분(webhook 포함)이라 "완료 시점"이 없으므로, 완료 신호 대신
유휴(idle) 감지로 트리거한다. 모든 빌더는 MERGE 기반 idempotent라 반복 실행해도 안전.
"""

import asyncio
import logging
import os
import time

logger = logging.getLogger(__name__)

# 큐가 이만큼 잠잠하면 수집이 일단락된 것으로 보고 후처리 실행
DEBOUNCE_SECONDS = float(os.environ.get("GRAPH_BUILD_DEBOUNCE_SECONDS", "30"))
# 유휴 여부를 확인하는 주기
CHECK_INTERVAL_SECONDS = float(os.environ.get("GRAPH_BUILD_CHECK_INTERVAL_SECONDS", "10"))
# 쿨다운 — 마지막 빌드 이후 최소 이 간격이 지나야 디바운스 빌드를 다시 실행.
# webhook 등으로 버스트가 잦아도 이 주기당 최대 1회로 제한한다 (O(n²) 재스캔 과다 방지).
MIN_BUILD_INTERVAL_SECONDS = float(os.environ.get("GRAPH_BUILD_MIN_INTERVAL_SECONDS", "300"))

_last_event_at: float = 0.0
_last_build_at: float = 0.0
_dirty: bool = False
_build_lock = asyncio.Lock()  # 디바운스 루프와 (향후) 수동 트리거의 동시 실행 방지


def _memoize_async(fetch):
    """인자 없는 async fetch의 결과를 캐시해 1회만 실행되게 한다 (per-시퀀스 스코프).

    한 후처리 시퀀스 안에서 여러 빌더가 같은 임베딩 집합을 각자 다시 조회하는
    중복 로드를 막는다. 시퀀스 동안 노드 임베딩은 변하지 않으므로(엣지만 추가됨)
    캐시는 안전하다. 시퀀스마다 새 wrapper를 만들어 빌드 간 stale 캐시를 방지한다.
    """
    cache: dict[str, object] = {}

    async def cached():
        if "value" not in cache:
            cache["value"] = await fetch()
        return cache["value"]

    return cached


def mark_dirty() -> None:
    """이벤트 처리 직후 호출 — 후처리가 필요한 새 데이터가 들어왔음을 표시한다."""
    global _last_event_at, _dirty
    _last_event_at = time.monotonic()
    _dirty = True


async def run_postprocess_sequence(verify: bool = False) -> dict:
    """후처리(Layer 4) 시퀀스를 순서대로 1회 실행한다.

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

    모든 단계가 idempotent. 동시 실행은 _build_lock으로 직렬화한다.

    수동/자동 어느 경로로 실행되든 _last_build_at을 갱신한다 — 수동 빌드 직후
    디바운스가 곧바로 또 돌지 않도록 쿨다운 기준점을 공유한다.
    """
    global _last_build_at
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

    async with _build_lock:
        ref_store = make_neo4j_reference_store()
        link_store = make_neo4j_issue_link_store()

        # 같은 시퀀스 안에서 동일 임베딩을 두 번 읽지 않도록 공유 메모이즈한다.
        #   - issue 임베딩: triggered_by·discussed_in 두 빌더가 동일 쿼리로 읽음
        #   - communication 임베딩: discussed_in·reference 두 빌더가 동일 쿼리로 읽음
        #     (comm 메모이즈는 backfill 이후 첫 호출되므로 보정된 임베딩까지 반영됨)
        # modified 임베딩은 issue-linking용(text TRIGGERED_BY 제외)과 reference용(전체)이
        # 서로 다른 쿼리라 공유 대상이 아니다 — 각자 1회 그대로 둔다.
        shared_issue_fetch = _memoize_async(link_store.fetch_issue_embeddings)
        shared_comm_fetch = _memoize_async(ref_store.fetch_communication_embeddings)
        link_store.fetch_issue_embeddings = shared_issue_fetch
        link_store.fetch_communication_embeddings = shared_comm_fetch
        ref_store.fetch_communication_embeddings = shared_comm_fetch

        # 0) Slack 노이즈 정제 — 신규 Slack 메시지만 LLM을 거친다(llm_filtered로 증분).
        # 링크보다 먼저 돌려 노이즈가 backfill/링크 대상에 끼지 않게 한다.
        # 실패해도 링크 단계는 진행 — 연결이 더 중요해 격리한다 (project_context는 배치라 생략).
        try:
            slack = await run_slack_llm_filter()
        except Exception:
            logger.exception("Slack LLM 필터 실패 — 링크 단계는 계속 진행")
            slack = {"kept": 0, "deleted": 0}

        # 0.5) 방안 D: A가 남긴 시맨틱 엣지를 먼저 비운다 — D의 정밀도가 A의
        # false positive로 희석되지 않도록. text(refs)·스레드 전파 엣지는 보존된다.
        if verify:
            await clear_semantic_triggered_by()
            await clear_semantic_discussed_in()

        results = {
            "slack_kept":        slack["kept"],
            "slack_deleted":     slack["deleted"],
            "backfilled":        await backfill_communication_embeddings(ref_store),
            "triggered_by":      await build_triggered_by(link_store),
            "discussed_in":      await build_discussed_in(link_store),
            "reference":         await build_reference_edges(ref_store),
            "thread_propagated": await propagate_thread_discussed_in(),
        }
        _last_build_at = time.monotonic()
        return results


async def start_debounce_loop() -> None:
    """큐 유휴 감지 → 디바운스 후 후처리 시퀀스 실행. 무한 루프 (lifespan 태스크)."""
    global _dirty
    logger.info(
        "그래프 후처리 디바운스 루프 시작 (debounce=%.0fs, check=%.0fs, min_interval=%.0fs)",
        DEBOUNCE_SECONDS, CHECK_INTERVAL_SECONDS, MIN_BUILD_INTERVAL_SECONDS,
    )
    while True:
        try:
            await asyncio.sleep(CHECK_INTERVAL_SECONDS)

            if not _dirty:
                continue
            idle = time.monotonic() - _last_event_at
            if idle < DEBOUNCE_SECONDS:
                continue

            # 쿨다운: 마지막 빌드 후 MIN_BUILD_INTERVAL_SECONDS가 안 지났으면 대기.
            # dirty는 유지 → 쿨다운이 끝나고도 유휴면 그때 1회 실행 (그 사이 버스트는 합쳐짐).
            since_build = time.monotonic() - _last_build_at
            if since_build < MIN_BUILD_INTERVAL_SECONDS:
                continue

            # 유휴 확정. 빌드 시작 전에 dirty를 내려, 빌드 도중 도착한 이벤트가
            # 다음 주기 빌드를 다시 예약하도록 한다 (새 데이터 유실 방지).
            _dirty = False
            logger.info("큐 유휴 %.0fs 감지 — 그래프 후처리 시작", idle)
            results = await run_postprocess_sequence()
            logger.info("그래프 후처리 완료: %s", results)

        except asyncio.CancelledError:
            logger.info("그래프 후처리 디바운스 루프 종료")
            raise
        except Exception:
            # 실패해도 루프는 유지. dirty를 다시 켜 다음 주기에 재시도.
            logger.exception("그래프 후처리 실행 실패 — 다음 주기에 재시도")
            _dirty = True
