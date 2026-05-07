"""
Neo4j 그래프 빌더.

각 함수는 NormalizedEvent 하나에 대응하는 원자적 upsert 단위.
- Layer 1: AUTHORED / WROTE / CREATED 엣지 (actor + node MERGE)
- Layer 2: TRIGGERED_BY / CONTAINS / DISCUSSED_IN / CHILD_OF 엣지 (refs 기반, stub 허용)
- Layer 3: MODIFIED 엣지 (ChangeSet → File, diffSummary + embedding)
"""

import logging
import os
from typing import Optional

from neo4j import AsyncDriver, AsyncGraphDatabase

logger = logging.getLogger(__name__)

_driver: Optional[AsyncDriver] = None


def get_driver() -> AsyncDriver:
    global _driver
    if _driver is None:
        uri = os.environ.get("NEO4J_URI", "bolt://localhost:7687")
        user = os.environ.get("NEO4J_USER", "neo4j")
        password = os.environ.get("NEO4J_PASSWORD", "password1234")
        _driver = AsyncGraphDatabase.driver(uri, auth=(user, password))
        logger.info("Neo4j driver initialized: %s", uri)
    return _driver


async def close_driver() -> None:
    global _driver
    if _driver is not None:
        await _driver.close()
        _driver = None


# ── Layer 1 + Layer 3 upserts ─────────────────────────────────────────────


async def upsert_changeset(
    *,
    hash: str,
    message: str,
    occurred_at: str,
    source: str,
    actor_id: str,
    actor_name: str,
    actor_email: Optional[str],
) -> None:
    async with get_driver().session() as session:
        await session.run(
            """
            MERGE (a:Actor {id: $actor_id})
            SET a.name = $actor_name, a.email = $actor_email
            MERGE (c:ChangeSet {hash: $hash})
            SET c.message = $message,
                c.occurredAt = datetime($occurred_at),
                c.source = $source
            MERGE (a)-[:AUTHORED]->(c)
            """,
            actor_id=actor_id,
            actor_name=actor_name,
            actor_email=actor_email,
            hash=hash,
            message=message,
            occurred_at=occurred_at,
            source=source,
        )


async def upsert_file_with_modified_edge(
    *,
    changeset_hash: str,
    file_path: str,
    diff_summary: str,
    embedding: list[float],
) -> None:
    async with get_driver().session() as session:
        await session.run(
            """
            MERGE (f:File {path: $file_path})
            WITH f
            MATCH (c:ChangeSet {hash: $changeset_hash})
            MERGE (c)-[r:MODIFIED]->(f)
            SET r.diffSummary = $diff_summary,
                r.embedding = $embedding
            """,
            file_path=file_path,
            changeset_hash=changeset_hash,
            diff_summary=diff_summary,
            embedding=embedding,
        )


async def upsert_pull_request(
    *,
    pr_number: int,
    title: str,
    body: str,
    state: str,
    base_branch: str,
    url: str,
    occurred_at: Optional[str],
    created_at: Optional[str],
    source: str,
    actor_id: str,
    actor_name: str,
    actor_email: Optional[str],
) -> None:
    async with get_driver().session() as session:
        await session.run(
            """
            MERGE (a:Actor {id: $actor_id})
            SET a.name = $actor_name, a.email = $actor_email
            MERGE (pr:PullRequest {pr_number: $pr_number})
            SET pr.title = $title,
                pr.body = $body,
                pr.state = $state,
                pr.base_branch = $base_branch,
                pr.url = $url,
                pr.occurredAt = CASE WHEN $occurred_at IS NOT NULL THEN datetime($occurred_at) ELSE null END,
                pr.createdAt  = CASE WHEN $created_at  IS NOT NULL THEN datetime($created_at)  ELSE null END,
                pr.source = $source
            MERGE (a)-[:AUTHORED]->(pr)
            """,
            actor_id=actor_id,
            actor_name=actor_name,
            actor_email=actor_email,
            pr_number=pr_number,
            title=title,
            body=body,
            state=state,
            base_branch=base_branch,
            url=url,
            occurred_at=occurred_at,
            created_at=created_at,
            source=source,
        )


async def upsert_issue(
    *,
    jira_key: str,
    title: str,
    body: str,
    status: str,
    issue_type: str,
    priority: str,
    assignee: str,
    occurred_at: str,
    created_at: Optional[str],
    source: str,
    actor_id: str,
    actor_name: str,
    actor_email: Optional[str],
    embedding: list[float],
) -> None:
    async with get_driver().session() as session:
        await session.run(
            """
            MERGE (a:Actor {id: $actor_id})
            SET a.name = $actor_name, a.email = $actor_email
            MERGE (i:Issue {jira_key: $jira_key})
            SET i.title = $title,
                i.body = $body,
                i.status = $status,
                i.issue_type = $issue_type,
                i.priority = $priority,
                i.assignee = $assignee,
                i.occurredAt = datetime($occurred_at),
                i.createdAt  = CASE WHEN $created_at IS NOT NULL THEN datetime($created_at) ELSE null END,
                i.source = $source,
                i.embedding = $embedding
            MERGE (a)-[:CREATED]->(i)
            """,
            actor_id=actor_id,
            actor_name=actor_name,
            actor_email=actor_email,
            jira_key=jira_key,
            title=title,
            body=body,
            status=status,
            issue_type=issue_type,
            priority=priority,
            assignee=assignee,
            occurred_at=occurred_at,
            created_at=created_at,
            source=source,
            embedding=embedding,
        )


async def upsert_communication(
    *,
    url: str,
    body: str,
    channel: str,
    conversation_id: str,
    occurred_at: str,
    created_at: Optional[str],
    source: str,
    actor_id: str,
    actor_name: str,
    actor_email: Optional[str],
    embedding: list[float],
) -> None:
    async with get_driver().session() as session:
        await session.run(
            """
            MERGE (a:Actor {id: $actor_id})
            SET a.name = $actor_name, a.email = $actor_email
            MERGE (comm:Communication {url: $url})
            SET comm.body = $body,
                comm.channel = $channel,
                comm.conversation_id = $conversation_id,
                comm.occurredAt = datetime($occurred_at),
                comm.createdAt  = CASE WHEN $created_at IS NOT NULL THEN datetime($created_at) ELSE null END,
                comm.source = $source,
                comm.embedding = $embedding
            MERGE (a)-[:WROTE]->(comm)
            """,
            actor_id=actor_id,
            actor_name=actor_name,
            actor_email=actor_email,
            url=url,
            body=body,
            channel=channel,
            conversation_id=conversation_id,
            occurred_at=occurred_at,
            created_at=created_at,
            source=source,
            embedding=embedding,
        )


# ── Layer 2 ref 엣지 ──────────────────────────────────────────────────────
# 참조 대상 노드가 아직 없으면 MERGE로 stub 생성 후 실제 이벤트 도착 시 SET으로 채워짐


async def link_changeset_to_issue(changeset_hash: str, jira_key: str) -> None:
    """TRIGGERED_BY: ChangeSet refs.jiraKey 존재 시"""
    async with get_driver().session() as session:
        await session.run(
            """
            MERGE (i:Issue {jira_key: $jira_key})
            WITH i
            MATCH (c:ChangeSet {hash: $hash})
            MERGE (c)-[:TRIGGERED_BY]->(i)
            """,
            jira_key=jira_key,
            hash=changeset_hash,
        )


async def link_pr_to_changeset(pr_number: int, changeset_hash: str) -> None:
    """CONTAINS: ChangeSet refs.prNumber 존재 시. 머지된 PR 노드가 없으면 생성하지 않음."""
    async with get_driver().session() as session:
        await session.run(
            """
            MATCH (pr:PullRequest {pr_number: $pr_number})
            MATCH (c:ChangeSet {hash: $hash})
            MERGE (pr)-[:CONTAINS]->(c)
            """,
            hash=changeset_hash,
            pr_number=pr_number,
        )


async def link_issue_to_communication(jira_key: str, comm_url: str) -> None:
    """DISCUSSED_IN: Communication refs.jiraKey 존재 시"""
    async with get_driver().session() as session:
        await session.run(
            """
            MERGE (i:Issue {jira_key: $jira_key})
            WITH i
            MATCH (comm:Communication {url: $comm_url})
            MERGE (i)-[:DISCUSSED_IN]->(comm)
            """,
            jira_key=jira_key,
            comm_url=comm_url,
        )


async def link_issue_to_parent(child_key: str, parent_key: str) -> None:
    """CHILD_OF: Issue Jira parent 필드 존재 시"""
    async with get_driver().session() as session:
        await session.run(
            """
            MERGE (parent:Issue {jira_key: $parent_key})
            WITH parent
            MERGE (child:Issue {jira_key: $child_key})
            MERGE (child)-[:CHILD_OF]->(parent)
            """,
            parent_key=parent_key,
            child_key=child_key,
        )


async def propagate_thread_discussed_in() -> int:
    """방안 C — 스레드 전파: conversation_id로 묶인 스레드 내 하나의 Communication이
    DISCUSSED_IN을 가지면 같은 스레드의 나머지 Communication에도 전파."""
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (i:Issue)-[:DISCUSSED_IN]->(seed:Communication)
            WHERE seed.conversation_id IS NOT NULL AND seed.conversation_id <> ''
            WITH i, seed.conversation_id AS conv_id
            MATCH (other:Communication {conversation_id: conv_id})
            WHERE NOT (i)-[:DISCUSSED_IN]->(other)
            MERGE (i)-[:DISCUSSED_IN]->(other)
            RETURN count(*) AS created
            """
        )
        record = await result.single()
        return record["created"] if record else 0


async def link_issue_to_assignee(jira_key: str, assignee_id: str) -> None:
    """ASSIGNED_TO: Issue assignee 존재 시"""
    async with get_driver().session() as session:
        await session.run(
            """
            MERGE (a:Actor {id: $assignee_id})
            WITH a
            MATCH (i:Issue {jira_key: $jira_key})
            MERGE (i)-[:ASSIGNED_TO]->(a)
            """,
            jira_key=jira_key,
            assignee_id=assignee_id,
        )


# ── ReferenceStore Neo4j 구현체 ───────────────────────────────────────────


async def _fetch_modified_embeddings() -> list[dict]:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (c:ChangeSet)-[r:MODIFIED]->(f:File)
            WHERE r.embedding IS NOT NULL AND c.occurredAt IS NOT NULL
            RETURN c.hash AS changeset_id,
                   f.path AS file_path,
                   r.diffSummary AS diff_summary,
                   r.embedding AS embedding,
                   c.occurredAt AS occurred_at
            """
        )
        rows = await result.data()
    return [
        {
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
            RETURN comm.url AS id,
                   comm.body AS body,
                   comm.embedding AS embedding,
                   comm.occurredAt AS occurred_at
            """
        )
        rows = await result.data()
    return [
        {
            "id":          r["id"],
            "body":        r["body"],
            "embedding":   list(r["embedding"]),
            "occurred_at": r["occurred_at"].to_native(),
        }
        for r in rows
    ]


async def _create_reference_edge(changeset_id: str, communication_id: str, confidence: float) -> None:
    async with get_driver().session() as session:
        await session.run(
            """
            MATCH (c:ChangeSet {hash: $changeset_id})
            MATCH (comm:Communication {url: $communication_id})
            MERGE (c)-[r:REFERENCE]->(comm)
            SET r.confidence = $confidence
            """,
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
            RETURN comm.url AS id, comm.body AS body
            """
        )
        rows = await result.data()
    return [{"id": r["id"], "body": r["body"]} for r in rows]


async def _save_communication_embedding(communication_id: str, embedding: list[float]) -> None:
    async with get_driver().session() as session:
        await session.run(
            """
            MATCH (comm:Communication {url: $communication_id})
            SET comm.embedding = $embedding
            """,
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


# ── IssueLinkStore Neo4j 구현체 ───────────────────────────────────────────


async def _fetch_issue_embeddings() -> list[dict]:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (i:Issue)
            WHERE i.embedding IS NOT NULL AND i.occurredAt IS NOT NULL
            RETURN i.jira_key AS id,
                   i.embedding AS embedding,
                   i.occurredAt AS occurred_at
            """
        )
        rows = await result.data()
    return [
        {
            "id":          r["id"],
            "embedding":   list(r["embedding"]),
            "occurred_at": r["occurred_at"].to_native(),
        }
        for r in rows
    ]


async def _create_triggered_by_semantic_edge(
    changeset_id: str, jira_key: str, confidence: float
) -> None:
    async with get_driver().session() as session:
        await session.run(
            """
            MATCH (c:ChangeSet {hash: $changeset_id})
            MATCH (i:Issue {jira_key: $jira_key})
            MERGE (c)-[r:TRIGGERED_BY]->(i)
            SET r.confidence = $confidence
            """,
            changeset_id=changeset_id,
            jira_key=jira_key,
            confidence=confidence,
        )


async def _create_discussed_in_semantic_edge(
    jira_key: str, comm_url: str, confidence: float
) -> None:
    async with get_driver().session() as session:
        await session.run(
            """
            MATCH (i:Issue {jira_key: $jira_key})
            MATCH (comm:Communication {url: $comm_url})
            MERGE (i)-[r:DISCUSSED_IN]->(comm)
            SET r.confidence = $confidence
            """,
            jira_key=jira_key,
            comm_url=comm_url,
            confidence=confidence,
        )


def make_neo4j_issue_link_store():
    """Neo4j 기반 IssueLinkStore 인스턴스를 반환한다."""
    from graph.issue_linker import IssueLinkStore
    return IssueLinkStore(
        fetch_issue_embeddings=_fetch_issue_embeddings,
        fetch_modified_embeddings=_fetch_modified_embeddings,
        fetch_communication_embeddings=_fetch_communication_embeddings,
        create_triggered_by_edge=_create_triggered_by_semantic_edge,
        create_discussed_in_edge=_create_discussed_in_semantic_edge,
    )
