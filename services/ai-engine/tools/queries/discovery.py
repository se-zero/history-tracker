"""키워드/최근/스레드 탐색 — 특정 엔티티 키에 묶이지 않은 폭넓은 조회."""

from tools.queries._common import (
    _MIN_CONFIDENCE,
    _VECTOR_OVERFETCH,
    _VECTOR_OVERFETCH_CAP,
    get_driver,
)


async def search_by_keyword(project_id: str, embedding: list[float], top_k: int = 5, threshold: float = 0.30) -> list[dict]:
    fetch_k = min(top_k * _VECTOR_OVERFETCH, _VECTOR_OVERFETCH_CAP)
    async with get_driver().session() as session:
        # Communication 인덱스 검색 — 전역 fetch_k 후보를 project_id로 필터하고 top_k로 자른다.
        result = await session.run(
            """
            CALL db.index.vector.queryNodes('comm_embedding', $fetch_k, $embedding)
            YIELD node AS c, score
            WHERE score >= $threshold AND c.project_id = $project_id
            OPTIONAL MATCH (cs:ChangeSet)-[:REFERENCE]->(c)
            OPTIONAL MATCH (i:Issue)-[:DISCUSSED_IN]->(c)
            RETURN 'Communication' AS type,
                   left(c.body, 300) AS text,
                   c.channel AS channel,
                   c.source AS source,
                   c.conversation_id AS conversation_id,
                   toString(c.occurredAt) AS occurredAt,
                   score,
                   collect(DISTINCT cs.hash) AS related_changesets,
                   collect(DISTINCT i.issue_key) AS related_issues
            ORDER BY score DESC
            LIMIT $top_k
            """,
            project_id=project_id,
            embedding=embedding,
            fetch_k=fetch_k,
            top_k=top_k,
            threshold=threshold,
        )
        comm_rows = await result.data()

        # 같은 스레드의 여러 메시지가 검색에 잡혔을 때 dedupe — 대표 메시지(최고 score) 한 건만 유지.
        # 후속으로 LLM이 conversation_id를 가지고 get_thread_context를 호출해 전체 스레드 맥락을 얻도록.
        seen_threads: set[str] = set()
        deduped_comm_rows: list[dict] = []
        for r in comm_rows:
            cid = r.get("conversation_id")
            if cid and cid in seen_threads:
                continue
            if cid:
                seen_threads.add(cid)
            deduped_comm_rows.append(r)
        comm_rows = deduped_comm_rows

        # Issue 인덱스 검색 — 동일하게 over-fetch 후 project_id 필터 + top_k.
        result = await session.run(
            """
            CALL db.index.vector.queryNodes('issue_embedding', $fetch_k, $embedding)
            YIELD node AS i, score
            WHERE score >= $threshold AND i.project_id = $project_id
            OPTIONAL MATCH (cs:ChangeSet)-[tb:TRIGGERED_BY]->(i)
                WHERE coalesce(tb.confidence, 1.0) >= $min_conf
            RETURN 'Issue' AS type,
                   (i.title + ': ' + coalesce(i.body, '')) AS text,
                   null AS channel,
                   i.source AS source,
                   toString(i.occurredAt) AS occurredAt,
                   score,
                   collect(DISTINCT cs.hash) AS related_changesets,
                   collect(DISTINCT i.issue_key) AS related_issues
            ORDER BY score DESC
            LIMIT $top_k
            """,
            project_id=project_id,
            embedding=embedding,
            fetch_k=fetch_k,
            top_k=top_k,
            threshold=threshold,
            min_conf=_MIN_CONFIDENCE,
        )
        issue_rows = await result.data()

        combined = comm_rows + issue_rows
        if not combined:
            return [{"message": "유사한 컨텍스트를 찾지 못했습니다. threshold를 낮추거나 다른 키워드를 시도하세요."}]
        return sorted(combined, key=lambda r: r["score"], reverse=True)

async def get_recent_activity(
    project_id: str,
    from_time: str,
    to_time: str | None = None,
    limit: int = 30,
) -> list[dict]:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (n)
            WHERE (n:ChangeSet OR n:PullRequest OR n:Communication OR n:Issue OR n:Document)
              AND n.project_id = $project_id
              AND n.occurredAt >= datetime($from_time)
              AND ($to_time IS NULL OR n.occurredAt <= datetime($to_time))
            WITH n, labels(n)[0] AS node_type
            // Document의 EDITED는 누적 관계라 여러 Actor가 붙을 수 있어 UNION에 넣지 않는다
            // (넣으면 편집자 수만큼 같은 문서가 중복 행으로 나온다) — WROTE(작성자)만 취한다.
            OPTIONAL MATCH (a:Actor)-[:AUTHORED|WROTE|CREATED]->(n)
            RETURN node_type AS type,
                   toString(n.occurredAt) AS occurredAt,
                   a.name AS actor,
                   CASE node_type
                     WHEN 'ChangeSet'     THEN n.hash
                     WHEN 'PullRequest'   THEN toString(n.pr_number)
                     WHEN 'Communication' THEN n.url
                     WHEN 'Issue'         THEN n.issue_key
                     WHEN 'Document'      THEN n.external_id
                   END AS id,
                   CASE node_type
                     WHEN 'ChangeSet'     THEN n.message
                     WHEN 'PullRequest'   THEN n.title
                     WHEN 'Communication' THEN left(n.body, 200)
                     WHEN 'Issue'         THEN n.title
                     WHEN 'Document'      THEN n.title
                   END AS summary
            ORDER BY occurredAt DESC
            LIMIT $limit
            """,
            project_id=project_id,
            from_time=from_time,
            to_time=to_time,
            limit=limit,
        )
        rows = await result.data()
        if not rows:
            return [{"message": "해당 기간에 활동이 없습니다."}]
        return rows

async def get_thread_context(project_id: str, conversation_id: str) -> list[dict]:
    async with get_driver().session() as session:
        # collect(DISTINCT ...) 때문에 RETURN이 aggregation으로 처리됨 →
        # ORDER BY는 RETURN의 projected alias만 참조 가능 (c.occurredAt 직접 참조 시 SyntaxError).
        # occurredAt이 ISO 문자열이라 lexicographic = chronological 정렬.
        result = await session.run(
            """
            MATCH (c:Communication {project_id: $project_id, conversation_id: $conversation_id})
            OPTIONAL MATCH (a:Actor)-[:WROTE]->(c)
            OPTIONAL MATCH (i:Issue)-[:DISCUSSED_IN]->(c)
            RETURN c.body AS body,
                   toString(c.occurredAt) AS occurredAt,
                   c.source AS source,
                   c.url AS url,
                   a.name AS author,
                   collect(DISTINCT {issue_key: i.issue_key, title: i.title}) AS related_issues
            ORDER BY occurredAt ASC
            """,
            project_id=project_id,
            conversation_id=conversation_id,
        )
        rows = await result.data()
        if not rows:
            return [{"message": f"해당 conversation_id의 메시지가 없습니다: {conversation_id}"}]
        return rows
