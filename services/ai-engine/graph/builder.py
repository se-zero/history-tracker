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
) -> None:
    async with get_driver().session() as session:
        await session.run(
            """
            MERGE (a:Actor {id: $actor_id})
            SET a.name = $actor_name
            MERGE (c:ChangeSet {hash: $hash})
            SET c.message = $message,
                c.occurredAt = datetime($occurred_at),
                c.source = $source
            MERGE (a)-[:AUTHORED]->(c)
            """,
            actor_id=actor_id,
            actor_name=actor_name,
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
    merged_at: Optional[str],
    url: str,
    occurred_at: Optional[str],
    created_at: Optional[str],
    source: str,
    actor_id: str,
    actor_name: str,
) -> None:
    async with get_driver().session() as session:
        await session.run(
            """
            MERGE (a:Actor {id: $actor_id})
            SET a.name = $actor_name
            MERGE (pr:PullRequest {pr_number: $pr_number})
            SET pr.title = $title,
                pr.body = $body,
                pr.state = $state,
                pr.base_branch = $base_branch,
                pr.merged_at = $merged_at,
                pr.url = $url,
                pr.occurredAt = CASE WHEN $occurred_at IS NOT NULL THEN datetime($occurred_at) ELSE null END,
                pr.createdAt  = CASE WHEN $created_at  IS NOT NULL THEN datetime($created_at)  ELSE null END,
                pr.source = $source
            MERGE (a)-[:AUTHORED]->(pr)
            """,
            actor_id=actor_id,
            actor_name=actor_name,
            pr_number=pr_number,
            title=title,
            body=body,
            state=state,
            base_branch=base_branch,
            merged_at=merged_at,
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
    embedding: list[float],
) -> None:
    async with get_driver().session() as session:
        await session.run(
            """
            MERGE (a:Actor {id: $actor_id})
            SET a.name = $actor_name
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
    embedding: list[float],
) -> None:
    async with get_driver().session() as session:
        await session.run(
            """
            MERGE (a:Actor {id: $actor_id})
            SET a.name = $actor_name
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
    """CONTAINS: ChangeSet refs.prNumber 존재 시"""
    async with get_driver().session() as session:
        await session.run(
            """
            MERGE (c:ChangeSet {hash: $hash})
            WITH c
            MERGE (pr:PullRequest {pr_number: $pr_number})
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
