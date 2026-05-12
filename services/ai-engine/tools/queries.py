from graph.builder import get_driver


async def get_issue_context(jira_key: str) -> dict:
    async with get_driver().session() as session:
        # 1단계: 이슈 + creator + assignee
        result = await session.run(
            """
            MATCH (i:Issue {jira_key: $jira_key})
            OPTIONAL MATCH (creator:Actor)-[:CREATED]->(i)
            OPTIONAL MATCH (assignee:Actor)<-[:ASSIGNED_TO]-(i)
            RETURN i.jira_key AS jira_key, i.title AS title, i.body AS body,
                   i.status AS status, i.issue_type AS issue_type,
                   i.priority AS priority, toString(i.occurredAt) AS occurredAt,
                   creator.name AS creator, assignee.name AS assignee
            """,
            jira_key=jira_key,
        )
        row = await result.single()
        if not row:
            return {"message": f"이슈를 찾을 수 없습니다: {jira_key}"}
        base = dict(row)

        # 2단계: 커밋 + PR (cs × pr은 1:1이므로 cross product 없음)
        result = await session.run(
            """
            MATCH (i:Issue {jira_key: $jira_key})
            OPTIONAL MATCH (cs:ChangeSet)-[tb:TRIGGERED_BY]->(i)
            OPTIONAL MATCH (cs_author:Actor)-[:AUTHORED]->(cs)
            OPTIONAL MATCH (pr:PullRequest)-[:CONTAINS]->(cs)
            RETURN collect(DISTINCT {
                hash: cs.hash, message: cs.message,
                occurredAt: toString(cs.occurredAt),
                author: cs_author.name,
                confidence: tb.confidence
            }) AS changesets,
            collect(DISTINCT {
                pr_number: pr.pr_number, title: pr.title, url: pr.url,
                occurredAt: toString(pr.occurredAt)
            }) AS pull_requests
            """,
            jira_key=jira_key,
        )
        row2 = await result.single()
        base["changesets"] = row2["changesets"] if row2 else []
        base["pull_requests"] = row2["pull_requests"] if row2 else []

        # 3단계: 논의 (이슈 × 논의는 독립 — 별도 WITH로 분리)
        result = await session.run(
            """
            MATCH (i:Issue {jira_key: $jira_key})-[disc:DISCUSSED_IN]->(c:Communication)
            OPTIONAL MATCH (c_author:Actor)-[:WROTE]->(c)
            RETURN collect(DISTINCT {
                body: c.body, channel: c.channel, source: c.source,
                occurredAt: toString(c.occurredAt),
                author: c_author.name,
                confidence: disc.confidence
            }) AS discussions
            """,
            jira_key=jira_key,
        )
        row3 = await result.single()
        base["discussions"] = row3["discussions"] if row3 else []

        return base


async def get_changeset_context(hash: str) -> dict:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (cs:ChangeSet {hash: $hash})
            MATCH (a:Actor)-[:AUTHORED]->(cs)
            OPTIONAL MATCH (cs)-[tb:TRIGGERED_BY]->(i:Issue)
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
                       confidence: tb.confidence
                   }) AS issues,
                   collect(DISTINCT {
                       body: c.body, channel: c.channel, source: c.source,
                       occurredAt: toString(c.occurredAt),
                       author: c_author.name,
                       confidence: ref.confidence
                   }) AS communications,
                   {pr_number: pr.pr_number, title: pr.title, url: pr.url} AS pull_request,
                   collect(DISTINCT {path: f.path, diffSummary: m.diffSummary}) AS file_changes
            """,
            hash=hash,
        )
        row = await result.single()
        if not row:
            return {"message": f"커밋을 찾을 수 없습니다: {hash}"}
        return dict(row)


async def find_expert(path_prefix: str) -> list[dict]:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (a:Actor)-[:AUTHORED]->(cs:ChangeSet)-[:MODIFIED]->(f:File)
            WHERE f.path STARTS WITH $path_prefix
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
            path_prefix=path_prefix,
        )
        rows = await result.data()
        if not rows:
            return [{"message": f"해당 경로에 대한 커밋 이력이 없습니다: {path_prefix}"}]
        return rows


async def get_timeline(jira_key: str) -> list[dict]:
    async with get_driver().session() as session:
        # 커밋 + PR 수집 (cs × pr은 1:1)
        result = await session.run(
            """
            MATCH (i:Issue {jira_key: $jira_key})
            OPTIONAL MATCH (cs:ChangeSet)-[:TRIGGERED_BY]->(i)
            OPTIONAL MATCH (pr:PullRequest)-[:CONTAINS]->(cs)
            WITH i,
                 collect(DISTINCT {
                     type: 'ChangeSet', occurredAt: toString(cs.occurredAt),
                     data: {hash: cs.hash, message: cs.message}
                 }) AS cs_events,
                 collect(DISTINCT {
                     type: 'PullRequest', occurredAt: toString(pr.occurredAt),
                     data: {pr_number: pr.pr_number, title: pr.title, url: pr.url}
                 }) AS pr_events
            RETURN i, cs_events, pr_events
            """,
            jira_key=jira_key,
        )
        row = await result.single()
        if not row:
            return [{"message": f"이슈를 찾을 수 없습니다: {jira_key}"}]

        i = row["i"]
        all_events = [
            {
                "type": "Issue",
                "occurredAt": str(i.get("occurredAt", "")),
                "data": {
                    "jira_key": i.get("jira_key"),
                    "title": i.get("title"),
                    "status": i.get("status"),
                },
            }
        ] + row["cs_events"] + row["pr_events"]

        # 논의 수집 (이슈와 독립적이므로 별도 쿼리)
        result2 = await session.run(
            """
            MATCH (i:Issue {jira_key: $jira_key})-[:DISCUSSED_IN]->(c:Communication)
            RETURN collect(DISTINCT {
                type: 'Communication', occurredAt: toString(c.occurredAt),
                data: {body: c.body, channel: c.channel, source: c.source}
            }) AS comm_events
            """,
            jira_key=jira_key,
        )
        row2 = await result2.single()
        if row2:
            all_events += row2["comm_events"]

        # null occurredAt 제거 후 정렬
        valid = [e for e in all_events if e.get("occurredAt") and e["occurredAt"] != "None"]
        return sorted(valid, key=lambda e: e["occurredAt"])


async def search_by_keyword(embedding: list[float], top_k: int = 5, threshold: float = 0.30) -> list[dict]:
    async with get_driver().session() as session:
        # Communication 인덱스 검색
        result = await session.run(
            """
            CALL db.index.vector.queryNodes('comm_embedding', $top_k, $embedding)
            YIELD node AS c, score
            WHERE score >= $threshold
            OPTIONAL MATCH (cs:ChangeSet)-[:REFERENCE]->(c)
            OPTIONAL MATCH (i:Issue)-[:DISCUSSED_IN]->(c)
            RETURN 'Communication' AS type,
                   left(c.body, 300) AS text,
                   c.channel AS channel,
                   c.source AS source,
                   toString(c.occurredAt) AS occurredAt,
                   score,
                   collect(DISTINCT cs.hash) AS related_changesets,
                   collect(DISTINCT i.jira_key) AS related_issues
            ORDER BY score DESC
            """,
            embedding=embedding,
            top_k=top_k,
            threshold=threshold,
        )
        comm_rows = await result.data()

        # Issue 인덱스 검색
        result = await session.run(
            """
            CALL db.index.vector.queryNodes('issue_embedding', $top_k, $embedding)
            YIELD node AS i, score
            WHERE score >= $threshold
            OPTIONAL MATCH (cs:ChangeSet)-[:TRIGGERED_BY]->(i)
            RETURN 'Issue' AS type,
                   (i.title + ': ' + coalesce(i.body, '')) AS text,
                   null AS channel,
                   'JIRA' AS source,
                   toString(i.occurredAt) AS occurredAt,
                   score,
                   collect(DISTINCT cs.hash) AS related_changesets,
                   collect(DISTINCT i.jira_key) AS related_issues
            ORDER BY score DESC
            """,
            embedding=embedding,
            top_k=top_k,
            threshold=threshold,
        )
        issue_rows = await result.data()

        combined = comm_rows + issue_rows
        if not combined:
            return [{"message": "유사한 컨텍스트를 찾지 못했습니다. threshold를 낮추거나 다른 키워드를 시도하세요."}]
        return sorted(combined, key=lambda r: r["score"], reverse=True)


async def get_actor_activity(
    identifier: str,
    from_time: str | None = None,
    limit: int = 20,
) -> dict:
    async with get_driver().session() as session:
        # Actor 확인
        result = await session.run(
            """
            MATCH (a:Actor)
            WHERE a.name = $identifier
               OR $identifier IN a.aliases
               OR $identifier IN a.emails
            RETURN a.name AS name, a.aliases AS aliases, a.emails AS emails
            LIMIT 1
            """,
            identifier=identifier,
        )
        actor_row = await result.single()
        if not actor_row:
            return {"message": f"Actor를 찾을 수 없습니다: {identifier}"}
        actor = dict(actor_row)

        # 커밋 (최신순)
        result = await session.run(
            """
            MATCH (a:Actor)-[:AUTHORED]->(cs:ChangeSet)
            WHERE (a.name = $identifier OR $identifier IN a.aliases OR $identifier IN a.emails)
              AND ($from_time IS NULL OR cs.occurredAt >= datetime($from_time))
            WITH cs ORDER BY cs.occurredAt DESC
            RETURN collect(cs)[0..$limit] AS changesets_raw
            """,
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
            MATCH (a:Actor)-[:AUTHORED]->(pr:PullRequest)
            WHERE (a.name = $identifier OR $identifier IN a.aliases OR $identifier IN a.emails)
              AND ($from_time IS NULL OR pr.occurredAt >= datetime($from_time))
            WITH pr ORDER BY pr.occurredAt DESC
            RETURN collect(pr)[0..$limit] AS prs_raw
            """,
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
            MATCH (a:Actor)-[:WROTE]->(c:Communication)
            WHERE (a.name = $identifier OR $identifier IN a.aliases OR $identifier IN a.emails)
              AND ($from_time IS NULL OR c.occurredAt >= datetime($from_time))
            WITH c ORDER BY c.occurredAt DESC
            RETURN collect(c)[0..$limit] AS comms_raw
            """,
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
            MATCH (a:Actor)
            WHERE a.name = $identifier OR $identifier IN a.aliases OR $identifier IN a.emails
            OPTIONAL MATCH (a)-[:CREATED]->(i:Issue)
            OPTIONAL MATCH (assigned:Issue)-[:ASSIGNED_TO]->(a)
            RETURN collect(DISTINCT {jira_key: i.jira_key, title: i.title}) AS issues_created,
                   collect(DISTINCT {jira_key: assigned.jira_key, title: assigned.title}) AS issues_assigned
            """,
            identifier=identifier,
        )
        row = await result.single()
        if row:
            actor["issues_created"] = row["issues_created"]
            actor["issues_assigned"] = row["issues_assigned"]

        return actor


async def get_file_history(path: str, limit: int = 20) -> list[dict]:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (f:File {path: $path})<-[m:MODIFIED]-(cs:ChangeSet)
            MATCH (a:Actor)-[:AUTHORED]->(cs)
            OPTIONAL MATCH (cs)-[:TRIGGERED_BY]->(i:Issue)
            OPTIONAL MATCH (pr:PullRequest)-[:CONTAINS]->(cs)
            RETURN cs.hash AS hash,
                   cs.message AS message,
                   toString(cs.occurredAt) AS occurredAt,
                   a.name AS author,
                   m.diffSummary AS diff_summary,
                   i.jira_key AS jira_key,
                   i.title AS issue_title,
                   pr.pr_number AS pr_number,
                   pr.url AS pr_url
            ORDER BY cs.occurredAt DESC
            LIMIT $limit
            """,
            path=path,
            limit=limit,
        )
        rows = await result.data()
        if not rows:
            return [{"message": f"해당 파일의 변경 이력이 없습니다: {path}"}]
        return rows


async def check_missing_context(
    from_time: str | None = None,
    to_time: str | None = None,
    limit: int = 50,
) -> list[dict]:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (cs:ChangeSet)
            WHERE NOT (cs)-[:TRIGGERED_BY]->(:Issue)
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
            from_time=from_time,
            to_time=to_time,
            limit=limit,
        )
        rows = await result.data()
        if not rows:
            return [{"message": "컨텍스트 없는 커밋이 없습니다."}]
        return rows


async def inspect_actor(identifier: str) -> dict:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (a:Actor)
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
            identifier=identifier,
        )
        row = await result.single()
        if not row:
            return {"message": f"Actor를 찾을 수 없습니다: {identifier}"}
        return dict(row)


async def get_conflict_context(hash: str) -> dict:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (cs:ChangeSet {hash: $hash})
            OPTIONAL MATCH (cs)-[tb:TRIGGERED_BY]->(i:Issue)
            OPTIONAL MATCH (cs)-[ref:REFERENCE]->(c:Communication)
            OPTIONAL MATCH (pr:PullRequest)-[:CONTAINS]->(cs)
            OPTIONAL MATCH (cs)-[m:MODIFIED]->(f:File)
            RETURN cs.hash AS hash,
                   cs.message AS commit_message,
                   toString(cs.occurredAt) AS occurredAt,
                   collect(DISTINCT {
                       source: 'Jira',
                       id: i.jira_key,
                       text: i.title + '\n' + coalesce(i.body, ''),
                       confidence: coalesce(tb.confidence, 1.0)
                   }) AS jira_contexts,
                   collect(DISTINCT {
                       source: c.source,
                       channel: c.channel,
                       text: c.body,
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
            hash=hash,
        )
        row = await result.single()
        if not row:
            return {"message": f"커밋을 찾을 수 없습니다: {hash}"}
        return dict(row)


async def get_recent_activity(
    from_time: str,
    to_time: str | None = None,
    limit: int = 30,
) -> list[dict]:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (n)
            WHERE (n:ChangeSet OR n:PullRequest OR n:Communication OR n:Issue)
              AND n.occurredAt >= datetime($from_time)
              AND ($to_time IS NULL OR n.occurredAt <= datetime($to_time))
            WITH n, labels(n)[0] AS node_type
            OPTIONAL MATCH (a:Actor)-[:AUTHORED|WROTE|CREATED]->(n)
            RETURN node_type AS type,
                   toString(n.occurredAt) AS occurredAt,
                   a.name AS actor,
                   CASE node_type
                     WHEN 'ChangeSet'     THEN n.hash
                     WHEN 'PullRequest'   THEN toString(n.pr_number)
                     WHEN 'Communication' THEN n.url
                     WHEN 'Issue'         THEN n.jira_key
                   END AS id,
                   CASE node_type
                     WHEN 'ChangeSet'     THEN n.message
                     WHEN 'PullRequest'   THEN n.title
                     WHEN 'Communication' THEN left(n.body, 200)
                     WHEN 'Issue'         THEN n.title
                   END AS summary
            ORDER BY occurredAt DESC
            LIMIT $limit
            """,
            from_time=from_time,
            to_time=to_time,
            limit=limit,
        )
        rows = await result.data()
        if not rows:
            return [{"message": "해당 기간에 활동이 없습니다."}]
        return rows


async def get_pr_context(pr_number: int) -> dict:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (pr:PullRequest {pr_number: $pr_number})
            OPTIONAL MATCH (author:Actor)-[:AUTHORED]->(pr)
            OPTIONAL MATCH (pr)-[:CONTAINS]->(cs:ChangeSet)
            OPTIONAL MATCH (cs_author:Actor)-[:AUTHORED]->(cs)
            OPTIONAL MATCH (cs)-[tb:TRIGGERED_BY]->(i:Issue)
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
                       status: i.status, confidence: tb.confidence
                   }) AS issues,
                   collect(DISTINCT {
                       body: c.body, channel: c.channel, source: c.source,
                       occurredAt: toString(c.occurredAt),
                       author: c_author.name, confidence: ref.confidence
                   }) AS discussions,
                   collect(DISTINCT {path: f.path, diff_summary: m.diffSummary}) AS file_changes
            """,
            pr_number=pr_number,
        )
        row = await result.single()
        if not row:
            return {"message": f"PR을 찾을 수 없습니다: #{pr_number}"}
        return dict(row)


async def get_thread_context(conversation_id: str) -> list[dict]:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (c:Communication {conversation_id: $conversation_id})
            OPTIONAL MATCH (a:Actor)-[:WROTE]->(c)
            OPTIONAL MATCH (i:Issue)-[:DISCUSSED_IN]->(c)
            RETURN c.body AS body,
                   toString(c.occurredAt) AS occurredAt,
                   c.source AS source,
                   c.url AS url,
                   a.name AS author,
                   collect(DISTINCT {jira_key: i.jira_key, title: i.title}) AS related_issues
            ORDER BY c.occurredAt ASC
            """,
            conversation_id=conversation_id,
        )
        rows = await result.data()
        if not rows:
            return [{"message": f"해당 conversation_id의 메시지가 없습니다: {conversation_id}"}]
        return rows
