"""코드 변경 컨텍스트 조회 — 커밋(ChangeSet)·PR·충돌·맥락 누락 커밋."""

from tools.queries._common import (
    _MIN_CONFIDENCE,
    _group_communications_by_thread,
    get_driver,
)


async def get_changeset_context(project_id: str, hash: str) -> dict:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (cs:ChangeSet {project_id: $project_id, hash: $hash})
            MATCH (a:Actor)-[:AUTHORED]->(cs)
            OPTIONAL MATCH (cs)-[tb:TRIGGERED_BY]->(i:Issue)
                WHERE coalesce(tb.confidence, 1.0) >= $min_conf
            OPTIONAL MATCH (cs)-[ref:REFERENCE]->(c:Communication)
            OPTIONAL MATCH (c_author:Actor)-[:WROTE]->(c)
            OPTIONAL MATCH (pr:PullRequest)-[:CONTAINS]->(cs)
            OPTIONAL MATCH (cs)-[m:MODIFIED]->(f:File)
            RETURN cs.hash AS hash,
                   cs.message AS commit_message,
                   toString(cs.occurredAt) AS occurredAt,
                   a.name AS author,
                   collect(DISTINCT {
                       jira_key: i.jira_key, title: i.title,
                       body: i.body, status: i.status,
                       confidence: tb.confidence,
                       link_source: tb.source
                   }) AS issues,
                   collect(DISTINCT {
                       body: c.body, channel: c.channel, source: c.source,
                       occurredAt: toString(c.occurredAt),
                       conversation_id: c.conversation_id,
                       author: c_author.name,
                       confidence: ref.confidence
                   }) AS communications,
                   {pr_number: pr.pr_number, title: pr.title, url: pr.url} AS pull_request,
                   collect(DISTINCT {path: f.path, diffSummary: m.diffSummary}) AS file_changes
            """,
            project_id=project_id,
            hash=hash,
            min_conf=_MIN_CONFIDENCE,
        )
        row = await result.single()
        if not row:
            return {"message": f"커밋을 찾을 수 없습니다: {hash}"}
        out = dict(row)
        # Slack 스레드 경계 보존 — communications를 conversation_id별로 그룹핑.
        out["communications"] = _group_communications_by_thread(out.get("communications") or [])
        return out

async def check_missing_context(
    project_id: str,
    from_time: str | None = None,
    to_time: str | None = None,
    limit: int = 50,
) -> list[dict]:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (cs:ChangeSet {project_id: $project_id})
            WHERE NOT EXISTS {
                MATCH (cs)-[tb:TRIGGERED_BY]->(:Issue)
                WHERE coalesce(tb.confidence, 1.0) >= $min_conf
              }
              AND NOT (cs)-[:REFERENCE]->(:Communication)
              AND ($from_time IS NULL OR cs.occurredAt >= datetime($from_time))
              AND ($to_time IS NULL OR cs.occurredAt <= datetime($to_time))
            MATCH (a:Actor)-[:AUTHORED]->(cs)
            OPTIONAL MATCH (cs)-[:MODIFIED]->(f:File)
            RETURN cs.hash AS hash,
                   cs.message AS message,
                   toString(cs.occurredAt) AS occurredAt,
                   a.name AS author,
                   collect(f.path) AS files
            ORDER BY cs.occurredAt DESC
            LIMIT $limit
            """,
            project_id=project_id,
            from_time=from_time,
            to_time=to_time,
            limit=limit,
            min_conf=_MIN_CONFIDENCE,
        )
        rows = await result.data()
        if not rows:
            return [{"message": "컨텍스트 없는 커밋이 없습니다."}]
        return rows

async def get_conflict_context(project_id: str, hash: str) -> dict:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (cs:ChangeSet {project_id: $project_id, hash: $hash})
            OPTIONAL MATCH (cs)-[tb:TRIGGERED_BY]->(i:Issue)
                WHERE coalesce(tb.confidence, 1.0) >= $min_conf
            OPTIONAL MATCH (cs)-[ref:REFERENCE]->(c:Communication)
            OPTIONAL MATCH (c_author:Actor)-[:WROTE]->(c)
            OPTIONAL MATCH (pr:PullRequest)-[:CONTAINS]->(cs)
            OPTIONAL MATCH (cs)-[m:MODIFIED]->(f:File)
            RETURN cs.hash AS hash,
                   cs.message AS commit_message,
                   toString(cs.occurredAt) AS occurredAt,
                   collect(DISTINCT {
                       source: 'Jira',
                       id: i.jira_key,
                       text: i.title + '\n' + coalesce(i.body, ''),
                       confidence: tb.confidence,
                       link_source: tb.source
                   }) AS jira_contexts,
                   collect(DISTINCT {
                       source: c.source,
                       channel: c.channel,
                       conversation_id: c.conversation_id,
                       body: c.body,
                       author: c_author.name,
                       occurredAt: toString(c.occurredAt),
                       confidence: ref.confidence
                   }) AS comm_contexts,
                   collect(DISTINCT {
                       source: 'GitHub PR',
                       id: toString(pr.pr_number),
                       text: pr.title + '\n' + coalesce(pr.body, ''),
                       confidence: 1.0
                   }) AS pr_contexts,
                   collect(DISTINCT {path: f.path, diff_summary: m.diffSummary}) AS file_changes
            """,
            project_id=project_id,
            hash=hash,
            min_conf=_MIN_CONFIDENCE,
        )
        row = await result.single()
        if not row:
            return {"message": f"커밋을 찾을 수 없습니다: {hash}"}
        out = dict(row)
        # Slack 스레드 경계 보존. (기존 comm_contexts는 text 키로 본문을 노출했지만,
        # 그룹핑 결과에서는 messages[*].body로 정규화 — _group_communications_by_thread가
        # GROUP_KEYS 외 모든 필드를 메시지 dict에 그대로 넘김.)
        out["comm_contexts"] = _group_communications_by_thread(out.get("comm_contexts") or [])
        return out

async def get_pr_context(project_id: str, pr_number: int) -> dict:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (pr:PullRequest {project_id: $project_id, pr_number: $pr_number})
            OPTIONAL MATCH (author:Actor)-[:AUTHORED]->(pr)
            OPTIONAL MATCH (pr)-[:CONTAINS]->(cs:ChangeSet)
            OPTIONAL MATCH (cs_author:Actor)-[:AUTHORED]->(cs)
            OPTIONAL MATCH (cs)-[tb:TRIGGERED_BY]->(i:Issue)
                WHERE coalesce(tb.confidence, 1.0) >= $min_conf
            OPTIONAL MATCH (cs)-[ref:REFERENCE]->(c:Communication)
            OPTIONAL MATCH (c_author:Actor)-[:WROTE]->(c)
            OPTIONAL MATCH (cs)-[m:MODIFIED]->(f:File)
            RETURN pr.pr_number AS pr_number,
                   pr.title AS title,
                   pr.body AS body,
                   toString(pr.occurredAt) AS merged_at,
                   toString(pr.createdAt) AS created_at,
                   pr.url AS url,
                   author.name AS author,
                   collect(DISTINCT {
                       hash: cs.hash, message: cs.message,
                       occurredAt: toString(cs.occurredAt),
                       author: cs_author.name
                   }) AS changesets,
                   collect(DISTINCT {
                       jira_key: i.jira_key, title: i.title,
                       status: i.status,
                       confidence: tb.confidence,
                       link_source: tb.source
                   }) AS issues,
                   collect(DISTINCT {
                       body: c.body, channel: c.channel, source: c.source,
                       occurredAt: toString(c.occurredAt),
                       conversation_id: c.conversation_id,
                       author: c_author.name, confidence: ref.confidence
                   }) AS discussions,
                   collect(DISTINCT {path: f.path, diff_summary: m.diffSummary}) AS file_changes
            """,
            project_id=project_id,
            pr_number=pr_number,
            min_conf=_MIN_CONFIDENCE,
        )
        row = await result.single()
        if not row:
            return {"message": f"PR을 찾을 수 없습니다: #{pr_number}"}
        out = dict(row)
        out["discussions"] = _group_communications_by_thread(out.get("discussions") or [])
        return out
