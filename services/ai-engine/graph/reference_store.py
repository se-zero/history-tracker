"""REFERENCE 엣지(ChangeSet <-> Communication)용 Neo4j ReferenceStore 어댑터.

graph.reference_builder.ReferenceStore에 주입할 Neo4j 조회/생성 콜백을 제공한다.
"""

from graph.driver import get_driver


# ── ReferenceStore Neo4j 구현체 ───────────────────────────────────────────


async def _fetch_modified_embeddings() -> list[dict]:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (c:ChangeSet)-[r:MODIFIED]->(f:File)
            WHERE r.embedding IS NOT NULL AND c.occurredAt IS NOT NULL
            RETURN c.project_id AS project_id,
                   c.hash AS changeset_id,
                   f.path AS file_path,
                   r.diffSummary AS diff_summary,
                   r.embedding AS embedding,
                   c.occurredAt AS occurred_at
            """
        )
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


async def _fetch_communication_embeddings() -> list[dict]:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (comm:Communication)
            WHERE comm.embedding IS NOT NULL AND comm.occurredAt IS NOT NULL
            RETURN comm.project_id AS project_id,
                   comm.url AS id,
                   comm.body AS body,
                   comm.embedding AS embedding,
                   comm.occurredAt AS occurred_at
            """
        )
        rows = await result.data()
    return [
        {
            "project_id":  r["project_id"],
            "id":          r["id"],
            "body":        r["body"],
            "embedding":   list(r["embedding"]),
            "occurred_at": r["occurred_at"].to_native(),
        }
        for r in rows
    ]


async def _create_reference_edge(project_id: str, changeset_id: str, communication_id: str, confidence: float) -> None:
    async with get_driver().session() as session:
        await session.run(
            """
            MATCH (c:ChangeSet {project_id: $project_id, hash: $changeset_id})
            MATCH (comm:Communication {project_id: $project_id, url: $communication_id})
            MERGE (c)-[r:REFERENCE]->(comm)
            SET r.confidence = $confidence
            """,
            project_id=project_id,
            changeset_id=changeset_id,
            communication_id=communication_id,
            confidence=confidence,
        )


async def _fetch_unembedded_communications() -> list[dict]:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (comm:Communication)
            WHERE comm.embedding IS NULL
            RETURN comm.project_id AS project_id, comm.url AS id, comm.body AS body
            """
        )
        rows = await result.data()
    return [{"project_id": r["project_id"], "id": r["id"], "body": r["body"]} for r in rows]


async def _save_communication_embedding(project_id: str, communication_id: str, embedding: list[float]) -> None:
    async with get_driver().session() as session:
        await session.run(
            """
            MATCH (comm:Communication {project_id: $project_id, url: $communication_id})
            SET comm.embedding = $embedding
            """,
            project_id=project_id,
            communication_id=communication_id,
            embedding=embedding,
        )


def make_neo4j_reference_store():
    """Neo4j 기반 ReferenceStore 인스턴스를 반환한다."""
    from graph.reference_builder import ReferenceStore
    return ReferenceStore(
        fetch_modified_embeddings=_fetch_modified_embeddings,
        fetch_communication_embeddings=_fetch_communication_embeddings,
        create_reference_edge=_create_reference_edge,
        fetch_unembedded_communications=_fetch_unembedded_communications,
        save_communication_embedding=_save_communication_embedding,
    )
