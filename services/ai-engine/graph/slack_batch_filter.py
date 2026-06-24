import logging
from collections import defaultdict

from graph.builder import (
    delete_communication,
    fetch_unfiltered_communications,
    mark_communication_llm_filtered,
)
from graph.slack_llm_filter import filter_messages

logger = logging.getLogger(__name__)

_STANDALONE_BATCH_SIZE = 50


async def run_slack_llm_filter(project_context: str = "", project_id: str | None = None) -> dict:
    """Neo4j의 llm_filtered=False Communication에 LLM 필터를 일괄 적용한다.

    - 스레드(conversation_id 공유 2개 이상): 스레드 단위로 LLM 1회 호출
    - 개별 메시지: (channel, date) 기준 그룹핑 후 50개씩 LLM 호출

    project_id를 주면 그 프로젝트 메시지만 필터한다(per-project 빌드).

    반환: {"kept": int, "deleted": int}
    """
    communications = await fetch_unfiltered_communications(project_id)
    if not communications:
        logger.info("필터 대상 Communication 없음")
        return {"kept": 0, "deleted": 0}

    # (project_id, conversation_id)로 1차 그룹핑 — conversation_id(Slack ts)는
    # 프로젝트 간 충돌 가능하므로 스레드 묶음이 프로젝트를 넘지 않게 한다.
    by_conv: dict[tuple, list[dict]] = defaultdict(list)
    for comm in communications:
        by_conv[(comm.get("project_id") or "", comm["conversation_id"])].append(comm)

    threads   = {key: msgs for key, msgs in by_conv.items() if len(msgs) > 1}
    standalones = [msgs[0] for msgs in by_conv.values() if len(msgs) == 1]

    kept = 0
    deleted = 0

    # 스레드 단위 처리 (occurred_at 기준 정렬 후 LLM 호출)
    for (project_id, cid), msgs in threads.items():
        msgs.sort(key=lambda m: m["occurred_at"] or 0)
        bodies = [m["body"] for m in msgs]
        try:
            keep_flags = await filter_messages(bodies, project_context, True)
        except Exception:
            logger.exception("스레드 LLM 필터 실패, 전체 보존: conversation_id=%s", cid)
            keep_flags = [True] * len(msgs)

        for msg, keep in zip(msgs, keep_flags):
            if keep:
                await mark_communication_llm_filtered(project_id, msg["url"])
                kept += 1
            else:
                await delete_communication(project_id, msg["url"])
                deleted += 1

    # 개별 메시지: (project_id, channel, date) 기준 2차 그룹핑
    by_channel_date: dict[tuple, list[dict]] = defaultdict(list)
    for msg in standalones:
        date_str = msg["occurred_at"].date().isoformat() if msg["occurred_at"] else "unknown"
        by_channel_date[(msg.get("project_id") or "", msg["channel"], date_str)].append(msg)

    # 50개씩 청크해서 LLM 호출
    for (project_id, channel, date), msgs in by_channel_date.items():
        for i in range(0, len(msgs), _STANDALONE_BATCH_SIZE):
            chunk = msgs[i : i + _STANDALONE_BATCH_SIZE]
            bodies = [m["body"] for m in chunk]
            try:
                keep_flags = await filter_messages(bodies, project_context)
            except Exception:
                logger.exception(
                    "개별 메시지 LLM 필터 실패, 전체 보존: channel=%s date=%s", channel, date
                )
                keep_flags = [True] * len(chunk)

            for msg, keep in zip(chunk, keep_flags):
                if keep:
                    await mark_communication_llm_filtered(project_id, msg["url"])
                    kept += 1
                else:
                    await delete_communication(project_id, msg["url"])
                    deleted += 1

    logger.info("Slack LLM 배치 필터 완료: kept=%d deleted=%d", kept, deleted)
    return {"kept": kept, "deleted": deleted}
