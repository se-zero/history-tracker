import logging
from collections import defaultdict

from graph.builder import (
    delete_communication,
    fetch_unfiltered_communications,
    mark_communication_llm_filtered,
)
from graph.project_profile import get_project_profile
from graph.slack_llm_filter import filter_messages

logger = logging.getLogger(__name__)

_STANDALONE_BATCH_SIZE = 50

# (project_id, is_thread, msgs) — LLM 필터 1회 호출 단위. eval/slack_filter_eval.py 측정
# 하네스가 프로덕션과 동일한 묶음으로 LLM을 호출하기 위해 group_for_filter를 그대로 가져다 쓴다.
Batch = tuple[str, bool, list[dict]]


def group_for_filter(communications: list[dict]) -> list[Batch]:
    """Communication을 LLM 필터 호출 단위(스레드 또는 (channel, date) 청크)로 묶는다.

    - 스레드(conversation_id 공유 2개 이상): 스레드 단위로 1배치, occurred_at 오름차순 정렬
    - 개별 메시지: (project_id, channel, date) 기준 그룹핑 후 50개씩 청크

    반환 순서는 스레드 배치들 먼저, 그다음 단독 청크들이다(실행 순서와 동일하게 유지).
    """
    # (project_id, conversation_id)로 1차 그룹핑 — conversation_id(Slack ts)는
    # 프로젝트 간 충돌 가능하므로 스레드 묶음이 프로젝트를 넘지 않게 한다.
    by_conv: dict[tuple, list[dict]] = defaultdict(list)
    for comm in communications:
        by_conv[(comm.get("project_id") or "", comm["conversation_id"])].append(comm)

    threads = {key: msgs for key, msgs in by_conv.items() if len(msgs) > 1}
    standalones = [msgs[0] for msgs in by_conv.values() if len(msgs) == 1]

    batches: list[Batch] = []

    for (project_id, _cid), msgs in threads.items():
        msgs.sort(key=lambda m: m["occurred_at"] or 0)
        batches.append((project_id, True, msgs))

    # 개별 메시지: (project_id, channel, date) 기준 2차 그룹핑
    by_channel_date: dict[tuple, list[dict]] = defaultdict(list)
    for msg in standalones:
        date_str = msg["occurred_at"].date().isoformat() if msg["occurred_at"] else "unknown"
        by_channel_date[(msg.get("project_id") or "", msg["channel"], date_str)].append(msg)

    # 50개씩 청크
    for (project_id, _channel, _date), msgs in by_channel_date.items():
        for i in range(0, len(msgs), _STANDALONE_BATCH_SIZE):
            batches.append((project_id, False, msgs[i : i + _STANDALONE_BATCH_SIZE]))

    return batches


async def run_slack_llm_filter(project_id: str | None = None) -> dict:
    """Neo4j의 llm_filtered=False Communication에 LLM 필터를 일괄 적용한다.

    project_id를 주면 그 프로젝트 메시지만 필터한다(per-project 빌드).

    반환: {"kept": int, "deleted": int}
    """
    communications = await fetch_unfiltered_communications(project_id)
    if not communications:
        logger.info("필터 대상 Communication 없음")
        return {"kept": 0, "deleted": 0}

    kept = 0
    deleted = 0
    # 배치가 project_id별로 여러 개 나올 수 있어(스레드/단독 청크), 프로필 조회를 한 번으로
    # 묶는다 — 후처리 빌드마다(활발한 프로젝트는 하루 수십 번) LLM 요약을 반복 호출하지 않도록.
    profile_cache: dict[str, str] = {}

    for batch_project_id, is_thread, msgs in group_for_filter(communications):
        bodies = [m["body"] for m in msgs]
        if batch_project_id not in profile_cache:
            profile_cache[batch_project_id] = (
                await get_project_profile(batch_project_id) if batch_project_id else ""
            )
        project_context = profile_cache[batch_project_id]
        try:
            keep_flags = await filter_messages(bodies, is_thread, project_context=project_context)
        except Exception:
            if is_thread:
                logger.exception(
                    "스레드 LLM 필터 실패, 전체 보존: conversation_id=%s", msgs[0]["conversation_id"]
                )
            else:
                date_str = msgs[0]["occurred_at"].date().isoformat() if msgs[0]["occurred_at"] else "unknown"
                logger.exception(
                    "개별 메시지 LLM 필터 실패, 전체 보존: channel=%s date=%s", msgs[0]["channel"], date_str
                )
            keep_flags = [True] * len(msgs)

        for msg, keep in zip(msgs, keep_flags):
            if keep:
                await mark_communication_llm_filtered(batch_project_id, msg["url"])
                kept += 1
            else:
                await delete_communication(batch_project_id, msg["url"])
                deleted += 1

    logger.info("Slack LLM 배치 필터 완료: kept=%d deleted=%d", kept, deleted)
    return {"kept": kept, "deleted": deleted}
