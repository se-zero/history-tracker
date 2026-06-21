"""Slack LLM 필터용 Communication 조회·정리.

graph.slack_batch_filter가 미필터 메시지를 가져와 필터 결과를 반영(마킹/삭제)할 때 사용한다.
"""

from graph.driver import get_driver


async def fetch_unfiltered_communications() -> list[dict]:
    """LLM 필터 미적용(llm_filtered=False) Slack Communication을 배치 필터용으로 조회한다.

    source='SLACK'로 스코프 — GitHub 이슈(source='GITHUB')도 Communication이고 수집 시
    llm_filtered=False로 들어오지만, 이는 Slack 노이즈 필터(삭제) 대상이 아니다.
    """
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (comm:Communication)
            WHERE comm.llm_filtered = false AND comm.source = 'SLACK'
            RETURN comm.project_id AS project_id,
                   comm.url AS url, comm.body AS body,
                   comm.channel AS channel,
                   comm.conversation_id AS conversation_id,
                   comm.occurredAt AS occurred_at
            """
        )
        rows = await result.data()
    return [
        {
            "project_id": r["project_id"],
            "url": r["url"],
            "body": r["body"],
            "channel": r["channel"] or "",
            "conversation_id": r["conversation_id"] or "",
            "occurred_at": r["occurred_at"].to_native() if r["occurred_at"] else None,
        }
        for r in rows
    ]


async def mark_communication_llm_filtered(project_id: str, url: str) -> None:
    async with get_driver().session() as session:
        await session.run(
            "MATCH (comm:Communication {project_id: $project_id, url: $url}) SET comm.llm_filtered = true",
            project_id=project_id,
            url=url,
        )


async def delete_communication(project_id: str, url: str) -> None:
    async with get_driver().session() as session:
        await session.run(
            "MATCH (comm:Communication {project_id: $project_id, url: $url}) DETACH DELETE comm",
            project_id=project_id,
            url=url,
        )
