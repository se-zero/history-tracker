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

_last_event_at: float = 0.0
_dirty: bool = False
_build_lock = asyncio.Lock()  # 디바운스 루프와 (향후) 수동 트리거의 동시 실행 방지


def mark_dirty() -> None:
    """이벤트 처리 직후 호출 — 후처리가 필요한 새 데이터가 들어왔음을 표시한다."""
    global _last_event_at, _dirty
    _last_event_at = time.monotonic()
    _dirty = True


async def run_postprocess_sequence() -> dict:
    """후처리(Layer 4) 시퀀스를 순서대로 1회 실행한다.

    순서:
      1. 임베딩 누락 Communication 보정 (이후 비교 대상에 포함되도록)
      2. TRIGGERED_BY + DISCUSSED_IN 시맨틱 링크 (GitHub↔Jira, Jira↔Slack)
      3. REFERENCE 시맨틱 링크 (GitHub↔Slack/GitHub이슈)
      4. DISCUSSED_IN 스레드 전파 (2에서 만든 엣지를 같은 스레드로 확장)

    모든 단계가 idempotent. 동시 실행은 _build_lock으로 직렬화한다.
    (향후 수동 버튼 엔드포인트도 이 함수를 호출하면 디바운스 루프와 안전하게 공존)
    """
    from graph.builder import (
        make_neo4j_issue_link_store,
        make_neo4j_reference_store,
        propagate_thread_discussed_in,
    )
    from graph.issue_linker import (
        build_issue_changeset_links,
        build_issue_communication_links,
    )
    from graph.reference_builder import (
        backfill_communication_embeddings,
        build_reference_edges,
    )

    async with _build_lock:
        ref_store = make_neo4j_reference_store()
        link_store = make_neo4j_issue_link_store()

        return {
            "backfilled":        await backfill_communication_embeddings(ref_store),
            "triggered_by":      await build_issue_changeset_links(link_store),
            "discussed_in":      await build_issue_communication_links(link_store),
            "reference":         await build_reference_edges(ref_store),
            "thread_propagated": await propagate_thread_discussed_in(),
        }


async def start_debounce_loop() -> None:
    """큐 유휴 감지 → 디바운스 후 후처리 시퀀스 실행. 무한 루프 (lifespan 태스크)."""
    global _dirty
    logger.info(
        "그래프 후처리 디바운스 루프 시작 (debounce=%.0fs, check=%.0fs)",
        DEBOUNCE_SECONDS, CHECK_INTERVAL_SECONDS,
    )
    while True:
        try:
            await asyncio.sleep(CHECK_INTERVAL_SECONDS)

            if not _dirty:
                continue
            idle = time.monotonic() - _last_event_at
            if idle < DEBOUNCE_SECONDS:
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
