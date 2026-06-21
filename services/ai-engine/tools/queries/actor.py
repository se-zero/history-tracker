"""사람(Actor) 컨텍스트 조회 — 전문가 추천, 활동 내역, 동일인 inspect."""

from tools.queries._common import get_driver


async def find_expert(project_id: str, path_prefix: str) -> list[dict]:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (a:Actor)-[:AUTHORED]->(cs:ChangeSet)-[:MODIFIED]->(f:File)
            WHERE f.path STARTS WITH $path_prefix AND cs.project_id = $project_id
            WITH a, cs,
                 CASE WHEN cs.occurredAt >= datetime() - duration('P180D')
                      THEN 2 ELSE 1 END AS weight
            WITH a,
                 count(cs) AS commit_count,
                 sum(weight) AS weighted_score,
                 max(cs.occurredAt) AS last_commit
            RETURN a.name AS author,
                   a.uuid AS actor_uuid,
                   commit_count,
                   weighted_score,
                   toString(last_commit) AS last_commit
            ORDER BY weighted_score DESC
            LIMIT 5
            """,
            project_id=project_id,
            path_prefix=path_prefix,
        )
        rows = await result.data()
        if not rows:
            return [{"message": f"해당 경로에 대한 커밋 이력이 없습니다: {path_prefix}"}]
        return rows

async def get_actor_activity(
    project_id: str,
    identifier: str,
    from_time: str | None = None,
    limit: int = 20,
) -> dict:
    async with get_driver().session() as session:
        # Actor 확인
        result = await session.run(
            """
            MATCH (a:Actor {project_id: $project_id})
            WHERE a.name = $identifier
               OR $identifier IN a.aliases
               OR $identifier IN a.emails
            RETURN a.name AS name, a.aliases AS aliases, a.emails AS emails
            LIMIT 1
            """,
            project_id=project_id,
            identifier=identifier,
        )
        actor_row = await result.single()
        if not actor_row:
            return {"message": f"Actor를 찾을 수 없습니다: {identifier}"}
        actor = dict(actor_row)

        # 커밋 (최신순)
        result = await session.run(
            """
            MATCH (a:Actor {project_id: $project_id})-[:AUTHORED]->(cs:ChangeSet)
            WHERE (a.name = $identifier OR $identifier IN a.aliases OR $identifier IN a.emails)
              AND ($from_time IS NULL OR cs.occurredAt >= datetime($from_time))
            WITH cs ORDER BY cs.occurredAt DESC
            RETURN collect(cs)[0..$limit] AS changesets_raw
            """,
            project_id=project_id,
            identifier=identifier,
            from_time=from_time,
            limit=limit,
        )
        row = await result.single()
        actor["changesets"] = [
            {"hash": c["hash"], "message": c["message"], "occurredAt": str(c.get("occurredAt", ""))}
            for c in (row["changesets_raw"] if row else [])
        ]

        # PR (최신순)
        result = await session.run(
            """
            MATCH (a:Actor {project_id: $project_id})-[:AUTHORED]->(pr:PullRequest)
            WHERE (a.name = $identifier OR $identifier IN a.aliases OR $identifier IN a.emails)
              AND ($from_time IS NULL OR pr.occurredAt >= datetime($from_time))
            WITH pr ORDER BY pr.occurredAt DESC
            RETURN collect(pr)[0..$limit] AS prs_raw
            """,
            project_id=project_id,
            identifier=identifier,
            from_time=from_time,
            limit=limit,
        )
        row = await result.single()
        actor["pull_requests"] = [
            {"pr_number": p["pr_number"], "title": p["title"], "occurredAt": str(p.get("occurredAt", ""))}
            for p in (row["prs_raw"] if row else [])
        ]

        # 메시지 (최신순)
        result = await session.run(
            """
            MATCH (a:Actor {project_id: $project_id})-[:WROTE]->(c:Communication)
            WHERE (a.name = $identifier OR $identifier IN a.aliases OR $identifier IN a.emails)
              AND ($from_time IS NULL OR c.occurredAt >= datetime($from_time))
            WITH c ORDER BY c.occurredAt DESC
            RETURN collect(c)[0..$limit] AS comms_raw
            """,
            project_id=project_id,
            identifier=identifier,
            from_time=from_time,
            limit=limit,
        )
        row = await result.single()
        actor["communications"] = [
            {"body": c["body"][:200] if c.get("body") else "", "channel": c.get("channel"), "occurredAt": str(c.get("occurredAt", ""))}
            for c in (row["comms_raw"] if row else [])
        ]

        # Jira 생성 / 담당
        result = await session.run(
            """
            MATCH (a:Actor {project_id: $project_id})
            WHERE a.name = $identifier OR $identifier IN a.aliases OR $identifier IN a.emails
            OPTIONAL MATCH (a)-[:CREATED]->(i:Issue)
            OPTIONAL MATCH (assigned:Issue)-[:ASSIGNED_TO]->(a)
            RETURN collect(DISTINCT {jira_key: i.jira_key, title: i.title}) AS issues_created,
                   collect(DISTINCT {jira_key: assigned.jira_key, title: assigned.title}) AS issues_assigned
            """,
            project_id=project_id,
            identifier=identifier,
        )
        row = await result.single()
        if row:
            actor["issues_created"] = row["issues_created"]
            actor["issues_assigned"] = row["issues_assigned"]

        return actor

async def inspect_actor(project_id: str, identifier: str) -> dict:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (a:Actor {project_id: $project_id})
            WHERE a.name = $identifier
               OR $identifier IN a.aliases
               OR $identifier IN a.emails
            RETURN a.uuid AS uuid,
                   a.name AS display_name,
                   a.normalized_name AS normalized_name,
                   a.aliases AS all_aliases,
                   a.emails AS emails,
                   a.confidence AS merge_confidence,
                   count { (a)-[:AUTHORED]->(:ChangeSet) } AS commit_count,
                   count { (a)-[:AUTHORED]->(:PullRequest) } AS pr_count,
                   count { (a)-[:WROTE]->(:Communication) } AS message_count,
                   count { (a)-[:CREATED]->(:Issue) } AS issue_created_count
            """,
            project_id=project_id,
            identifier=identifier,
        )
        row = await result.single()
        if not row:
            return {"message": f"Actor를 찾을 수 없습니다: {identifier}"}
        return dict(row)
