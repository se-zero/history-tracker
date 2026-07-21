"""시맨틱 이슈 링크(TRIGGERED_BY / DISCUSSED_IN)용 Neo4j IssueLinkStore 어댑터.

graph.issue_linker.IssueLinkStore에 주입할 Neo4j 조회/생성 콜백을 제공한다.
Communication 임베딩 조회는 reference_store와 동일 쿼리를 재사용한다.
"""

from graph.driver import get_driver
from graph.reference_store import _fetch_communication_embeddings


# ── IssueLinkStore Neo4j 구현체 ───────────────────────────────────────────


async def _fetch_issue_embeddings(project_id: str | None = None) -> list[dict]:
    """이슈 임베딩 + 비대칭 시간 윈도우 계산에 필요한 메타데이터 반환.

    closed_at은 NULL일 수 있다 (pipeline-worker가 아직 보내지 않으면).
    issue_linker._compute_issue_window가 status가 terminal일 때 occurred_at으로 fallback.
    """
    query = """
        MATCH (i:Issue)
        WHERE i.embedding IS NOT NULL AND i.occurredAt IS NOT NULL
        __PROJECT_FILTER__
        RETURN i.project_id AS project_id,
               i.jira_key AS id,
               i.title AS title,
               i.body AS body,
               i.embedding AS embedding,
               i.occurredAt AS occurred_at,
               i.createdAt  AS created_at,
               i.closedAt   AS closed_at,
               i.status     AS status
    """.replace("__PROJECT_FILTER__", "AND i.project_id = $project_id" if project_id else "")
    async with get_driver().session() as session:
        result = await session.run(query, project_id=project_id)
        rows = await result.data()
    return [
        {
            "project_id":  r["project_id"],
            "id":          r["id"],
            "title":       r["title"] or "",
            "body":        r["body"] or "",
            "embedding":   list(r["embedding"]),
            "occurred_at": r["occurred_at"].to_native(),
            "created_at":  r["created_at"].to_native() if r["created_at"] else None,
            "closed_at":   r["closed_at"].to_native()  if r["closed_at"]  else None,
            "status":      r["status"] or "",
        }
        for r in rows
    ]


async def _fetch_modified_embeddings_for_issue_linking(project_id: str | None = None) -> list[dict]:
    """이슈 시맨틱 연결용 — text TRIGGERED_BY가 이미 있는 ChangeSet은 제외.

    이미 텍스트 참조로 확정된 커밋은 시맨틱 매칭의 후보에서 빼서:
      1) 텍스트 매칭 결과가 시맨틱에 의해 다른 이슈로 덮어쓰이지 않게 보호 (semantic 가드 보완)
      2) Issue × ChangeSet 비교량 감소 → 처리 시간 단축
    """
    query = """
        MATCH (c:ChangeSet)-[r:MODIFIED]->(f:File)
        WHERE r.embedding IS NOT NULL AND c.occurredAt IS NOT NULL
          __PROJECT_FILTER__
          AND NOT EXISTS {
            MATCH (c)-[tb:TRIGGERED_BY]->(:Issue)
            WHERE tb.source = 'text'
          }
        RETURN c.project_id AS project_id,
               c.hash AS changeset_id,
               f.path AS file_path,
               r.diffSummary AS diff_summary,
               r.embedding AS embedding,
               c.occurredAt AS occurred_at
    """.replace("__PROJECT_FILTER__", "AND c.project_id = $project_id" if project_id else "")
    async with get_driver().session() as session:
        result = await session.run(query, project_id=project_id)
        rows = await result.data()
    return [
        {
            "project_id":   r["project_id"],
            "changeset_id": r["changeset_id"],
            "file_path":    r["file_path"],
            "diff_summary": r["diff_summary"],
            "embedding":    list(r["embedding"]),
            "occurred_at":  r["occurred_at"].to_native(),
        }
        for r in rows
    ]


async def _fetch_changeset_message_embeddings_for_issue_linking(project_id: str | None = None) -> list[dict]:
    """이슈 시맨틱 연결용 커밋 메시지 임베딩 — text TRIGGERED_BY 보유 커밋은 동일하게 제외.

    파일(diff) fetch와 제외 규칙을 맞추지 않으면 무링크 풀 전제(정밀도 필터)가
    메시지 비교 열에서 무너진다.
    """
    query = """
        MATCH (c:ChangeSet)
        WHERE c.embedding IS NOT NULL AND c.occurredAt IS NOT NULL
          __PROJECT_FILTER__
          AND NOT EXISTS {
            MATCH (c)-[tb:TRIGGERED_BY]->(:Issue)
            WHERE tb.source = 'text'
          }
        RETURN c.project_id AS project_id,
               c.hash AS changeset_id,
               c.message AS message,
               c.embedding AS embedding,
               c.occurredAt AS occurred_at
    """.replace("__PROJECT_FILTER__", "AND c.project_id = $project_id" if project_id else "")
    async with get_driver().session() as session:
        result = await session.run(query, project_id=project_id)
        rows = await result.data()
    return [
        {
            "project_id":   r["project_id"],
            "changeset_id": r["changeset_id"],
            "message":      r["message"] or "",
            "embedding":    list(r["embedding"]),
            "occurred_at":  r["occurred_at"].to_native(),
        }
        for r in rows
    ]


async def _fetch_unembedded_issues(project_id: str | None = None, force: bool = False) -> list[dict]:
    query = """
        MATCH (i:Issue)
        WHERE (i.title IS NOT NULL OR i.body IS NOT NULL) __EMBEDDING_FILTER__
        __PROJECT_FILTER__
        RETURN i.project_id AS project_id,
               i.jira_key AS id,
               i.title AS title,
               i.body AS body
    """.replace(
        "__EMBEDDING_FILTER__", "" if force else "AND i.embedding IS NULL"
    ).replace("__PROJECT_FILTER__", "AND i.project_id = $project_id" if project_id else "")
    async with get_driver().session() as session:
        result = await session.run(query, project_id=project_id)
        rows = await result.data()
    return [
        {
            "project_id": r["project_id"],
            "id":         r["id"],
            "title":      r["title"] or "",
            "body":       r["body"] or "",
        }
        for r in rows
    ]


async def _save_issue_embedding(project_id: str, jira_key: str, embedding: list[float]) -> None:
    async with get_driver().session() as session:
        await session.run(
            """
            MATCH (i:Issue {project_id: $project_id, jira_key: $jira_key})
            SET i.embedding = $embedding
            """,
            project_id=project_id,
            jira_key=jira_key,
            embedding=embedding,
        )


async def _create_triggered_by_semantic_edge(
    project_id: str, changeset_id: str, jira_key: str, confidence: float
) -> None:
    """TRIGGERED_BY (semantic): 임베딩/LLM 검증으로 발견된 연결.

    source='text' 인 엣지는 이미 더 신뢰성 높은 텍스트 참조로 확정된 것이므로
    시맨틱 결과가 덮어쓰지 못하도록 가드한다. 그 외(신규 엣지, 기존 semantic 엣지)는 갱신.
    """
    async with get_driver().session() as session:
        await session.run(
            """
            MATCH (c:ChangeSet {project_id: $project_id, hash: $changeset_id})
            MATCH (i:Issue {project_id: $project_id, jira_key: $jira_key})
            MERGE (c)-[r:TRIGGERED_BY]->(i)
            WITH r
            WHERE coalesce(r.source, '') <> 'text'
            SET r.source = 'semantic', r.confidence = $confidence
            """,
            project_id=project_id,
            changeset_id=changeset_id,
            jira_key=jira_key,
            confidence=confidence,
        )


async def _create_discussed_in_semantic_edge(
    project_id: str, jira_key: str, comm_url: str, confidence: float
) -> None:
    """DISCUSSED_IN (semantic): 임베딩/LLM 검증으로 발견된 연결.

    source='text' 인 엣지(메시지가 jira_key를 직접 언급)는 이미 확정된 참조이므로
    시맨틱 결과가 덮어쓰지 못하게 가드한다 — 덮어쓰면 confidence가 붙어 시맨틱으로 오인되고
    clear_semantic_discussed_in에 삭제된다(텍스트 엣지는 수집 시점에만 생겨 복구 불가).
    """
    async with get_driver().session() as session:
        await session.run(
            """
            MATCH (i:Issue {project_id: $project_id, jira_key: $jira_key})
            MATCH (comm:Communication {project_id: $project_id, url: $comm_url})
            MERGE (i)-[r:DISCUSSED_IN]->(comm)
            WITH r
            WHERE coalesce(r.source, '') <> 'text'
            SET r.source = 'semantic', r.confidence = $confidence
            """,
            project_id=project_id,
            jira_key=jira_key,
            comm_url=comm_url,
            confidence=confidence,
        )


def make_neo4j_issue_link_store(project_id: str | None = None):
    """Neo4j 기반 IssueLinkStore 인스턴스를 반환한다.

    project_id를 주면 fetch가 그 프로젝트 노드만 조회한다(per-project 빌드).
    None이면 전체 프로젝트를 조회한다(운영 일괄 트리거 — 기존 동작).

    fetch_modified_embeddings는 issue-linking 전용 함수를 사용해
    text TRIGGERED_BY가 이미 있는 ChangeSet은 후보에서 제외한다.
    (REFERENCE 엣지용 store는 여전히 _fetch_modified_embeddings를 사용)
    """
    from graph.issue_linker import IssueLinkStore
    return IssueLinkStore(
        fetch_issue_embeddings=lambda: _fetch_issue_embeddings(project_id),
        fetch_modified_embeddings=lambda: _fetch_modified_embeddings_for_issue_linking(project_id),
        fetch_communication_embeddings=lambda: _fetch_communication_embeddings(project_id),
        create_triggered_by_edge=_create_triggered_by_semantic_edge,
        create_discussed_in_edge=_create_discussed_in_semantic_edge,
        fetch_changeset_message_embeddings=lambda: _fetch_changeset_message_embeddings_for_issue_linking(project_id),
        fetch_unembedded_issues=lambda force: _fetch_unembedded_issues(project_id, force),
        save_issue_embedding=_save_issue_embedding,
    )
