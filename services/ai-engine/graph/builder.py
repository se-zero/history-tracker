"""
Neo4j 그래프 빌더.

각 함수는 NormalizedEvent 하나에 대응하는 원자적 upsert 단위.
- Layer 1: AUTHORED / WROTE / CREATED 엣지 (actor + node MERGE)
- Layer 2: TRIGGERED_BY / CONTAINS / DISCUSSED_IN / CHILD_OF 엣지 (refs 기반, stub 허용)
- Layer 3: MODIFIED 엣지 (ChangeSet → File, diffSummary + embedding)
"""

import logging
import os
import re
import uuid
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


async def ensure_vector_indexes() -> None:
    """comm_embedding, issue_embedding 벡터 인덱스를 생성한다. 이미 존재하면 무시."""
    async with get_driver().session() as session:
        await session.run(
            """
            CREATE VECTOR INDEX comm_embedding IF NOT EXISTS
            FOR (c:Communication) ON (c.embedding)
            OPTIONS { indexConfig: {
                `vector.dimensions`: 1536,
                `vector.similarity_function`: 'cosine'
            }}
            """
        )
        await session.run(
            """
            CREATE VECTOR INDEX issue_embedding IF NOT EXISTS
            FOR (i:Issue) ON (i.embedding)
            OPTIONS { indexConfig: {
                `vector.dimensions`: 1536,
                `vector.similarity_function`: 'cosine'
            }}
            """
        )
    logger.info("벡터 인덱스 확인 완료 (comm_embedding, issue_embedding)")


# 프로젝트 격리의 핵심 — 모든 도메인 노드는 (project_id, 자연키) 복합 유니크.
# pr_number/path/jira_key 같은 자연키는 프로젝트(레포/워크스페이스)마다 충돌하므로
# project_id 없이 MERGE하면 서로 다른 프로젝트의 데이터가 같은 노드로 병합된다.
_UNIQUE_CONSTRAINTS: list[tuple[str, str, list[str]]] = [
    ("changeset_project_hash",      "ChangeSet",     ["project_id", "hash"]),
    ("pull_request_project_number", "PullRequest",   ["project_id", "pr_number"]),
    ("issue_project_jira_key",      "Issue",         ["project_id", "jira_key"]),
    ("communication_project_url",   "Communication", ["project_id", "url"]),
    ("file_project_path",           "File",          ["project_id", "path"]),
    ("actor_uuid",                  "Actor",         ["uuid"]),
]


async def ensure_constraints() -> None:
    """(project_id, 자연키) 복합 유니크 제약을 생성한다. 이미 존재하면 무시.

    제약 생성이 실패하는 환경(에디션/버전 차이)에서는 동일 키 조합의 range 인덱스로
    폴백한다 — MERGE 패턴 자체가 복합 키를 쓰므로 단일 컨슈머 환경에서는
    제약 없이도 중복이 생기지 않고, 인덱스만으로도 조회 성능은 확보된다.
    """
    async with get_driver().session() as session:
        for name, label, props in _UNIQUE_CONSTRAINTS:
            key = ", ".join(f"n.{p}" for p in props)
            try:
                await session.run(
                    f"CREATE CONSTRAINT {name} IF NOT EXISTS "
                    f"FOR (n:{label}) REQUIRE ({key}) IS UNIQUE"
                )
            except Exception:
                logger.warning("유니크 제약 생성 실패 — range 인덱스로 폴백: %s", name, exc_info=True)
                await session.run(
                    f"CREATE INDEX {name}_idx IF NOT EXISTS "
                    f"FOR (n:{label}) ON ({key})"
                )
    logger.info("프로젝트 스코프 유니크 제약 확인 완료 (%d개)", len(_UNIQUE_CONSTRAINTS))


# ── Layer 1 + Layer 3 upserts ─────────────────────────────────────────────


async def upsert_changeset(
    *,
    project_id: str,
    hash: str,
    message: str,
    occurred_at: str,
    source: str,
    actor_uuid: str,
) -> None:
    async with get_driver().session() as session:
        await session.run(
            """
            MATCH (a:Actor {uuid: $actor_uuid})
            MERGE (c:ChangeSet {project_id: $project_id, hash: $hash})
            SET c.message = $message,
                c.occurredAt = datetime($occurred_at),
                c.source = $source
            MERGE (a)-[:AUTHORED]->(c)
            """,
            actor_uuid=actor_uuid,
            project_id=project_id,
            hash=hash,
            message=message,
            occurred_at=occurred_at,
            source=source,
        )


async def upsert_file_with_modified_edge(
    *,
    project_id: str,
    changeset_hash: str,
    file_path: str,
    diff_summary: str,
    embedding: list[float],
) -> None:
    async with get_driver().session() as session:
        await session.run(
            """
            MERGE (f:File {project_id: $project_id, path: $file_path})
            WITH f
            MATCH (c:ChangeSet {project_id: $project_id, hash: $changeset_hash})
            MERGE (c)-[r:MODIFIED]->(f)
            SET r.diffSummary = $diff_summary,
                r.embedding = $embedding
            """,
            project_id=project_id,
            file_path=file_path,
            changeset_hash=changeset_hash,
            diff_summary=diff_summary,
            embedding=embedding,
        )


async def upsert_pull_request(
    *,
    project_id: str,
    pr_number: int,
    title: str,
    body: str,
    state: str,
    base_branch: str,
    url: str,
    occurred_at: Optional[str],
    created_at: Optional[str],
    source: str,
    actor_uuid: str,
    jira_keys: Optional[list[str]] = None,
) -> None:
    """PullRequest 노드 upsert.

    jira_keys:
      PR 제목/본문에서 추출한 다중 Jira 키. 그 PR이 머지한 모든 ChangeSet에 동일 키로
      text TRIGGERED_BY를 전파하는 데 사용된다 (link_pr_changesets_to_issues).
      None이면 기존 pr.jira_keys 값을 보존, 명시되면 갱신.
    """
    async with get_driver().session() as session:
        await session.run(
            """
            MATCH (a:Actor {uuid: $actor_uuid})
            MERGE (pr:PullRequest {project_id: $project_id, pr_number: $pr_number})
            SET pr.title = $title,
                pr.body = $body,
                pr.state = $state,
                pr.base_branch = $base_branch,
                pr.url = $url,
                pr.occurredAt = CASE WHEN $occurred_at IS NOT NULL THEN datetime($occurred_at) ELSE null END,
                pr.createdAt  = CASE WHEN $created_at  IS NOT NULL THEN datetime($created_at)  ELSE null END,
                pr.jira_keys  = CASE WHEN $jira_keys   IS NOT NULL THEN $jira_keys              ELSE pr.jira_keys END,
                pr.source = $source
            MERGE (a)-[:AUTHORED]->(pr)
            """,
            actor_uuid=actor_uuid,
            project_id=project_id,
            pr_number=pr_number,
            title=title,
            body=body,
            state=state,
            base_branch=base_branch,
            url=url,
            occurred_at=occurred_at,
            created_at=created_at,
            jira_keys=jira_keys,
            source=source,
        )


async def upsert_issue(
    *,
    project_id: str,
    jira_key: str,
    title: str,
    body: str,
    status: str,
    issue_type: str,
    priority: str,
    assignee: str,
    occurred_at: str,
    created_at: Optional[str],
    closed_at: Optional[str] = None,
    source: str,
    actor_uuid: str,
    embedding: list[float],
) -> None:
    """Issue 노드 upsert.

    closed_at 정책 (status-aware):
      - closed_at 값 있음                            → 그 값으로 덮어씀
      - closed_at 값 없음(None) + status가 TERMINAL → 기존 i.closedAt 보존
        (pipeline-worker가 아직 closed_at을 안 보내는 마이그레이션 단계 안전망)
      - closed_at 값 없음(None) + status가 non-TERMINAL → null 로 클리어
        (재오픈된 이슈가 비대칭 시간 윈도우 계산에서 오래된 종료 시각을 쓰지 않도록 함)

    createdAt 정책: 원래대로 — 값 있으면 SET, 없으면 null (이벤트 소스가 항상 보내는 게 정상).
    """
    async with get_driver().session() as session:
        await session.run(
            """
            MATCH (a:Actor {uuid: $actor_uuid})
            MERGE (i:Issue {project_id: $project_id, jira_key: $jira_key})
            SET i.title = $title,
                i.body = $body,
                i.status = $status,
                i.issue_type = $issue_type,
                i.priority = $priority,
                i.assignee = $assignee,
                i.occurredAt = datetime($occurred_at),
                i.createdAt  = CASE WHEN $created_at IS NOT NULL THEN datetime($created_at) ELSE null END,
                i.closedAt   = CASE
                                  WHEN $closed_at IS NOT NULL THEN datetime($closed_at)
                                  WHEN $status IN ['완료', 'Done', 'Closed', 'Resolved', '해결됨'] THEN i.closedAt
                                  ELSE null
                               END,
                i.source = $source,
                i.embedding = $embedding
            MERGE (a)-[:CREATED]->(i)
            """,
            actor_uuid=actor_uuid,
            project_id=project_id,
            jira_key=jira_key,
            title=title,
            body=body,
            status=status,
            issue_type=issue_type,
            priority=priority,
            assignee=assignee,
            occurred_at=occurred_at,
            created_at=created_at,
            closed_at=closed_at,
            source=source,
            embedding=embedding,
        )


async def upsert_communication(
    *,
    project_id: str,
    url: str,
    body: str,
    channel: str,
    conversation_id: str,
    occurred_at: str,
    created_at: Optional[str],
    source: str,
    actor_uuid: str,
    embedding: list[float],
    llm_filtered: bool = False,
) -> None:
    async with get_driver().session() as session:
        await session.run(
            """
            MATCH (a:Actor {uuid: $actor_uuid})
            MERGE (comm:Communication {project_id: $project_id, url: $url})
            SET comm.body = $body,
                comm.channel = $channel,
                comm.conversation_id = $conversation_id,
                comm.occurredAt = datetime($occurred_at),
                comm.createdAt  = CASE WHEN $created_at IS NOT NULL THEN datetime($created_at) ELSE null END,
                comm.source = $source,
                comm.embedding = $embedding,
                comm.llm_filtered = $llm_filtered
            MERGE (a)-[:WROTE]->(comm)
            """,
            actor_uuid=actor_uuid,
            project_id=project_id,
            url=url,
            body=body,
            channel=channel,
            conversation_id=conversation_id,
            occurred_at=occurred_at,
            created_at=created_at,
            source=source,
            embedding=embedding,
            llm_filtered=llm_filtered,
        )


# ── Layer 2 ref 엣지 ──────────────────────────────────────────────────────
# 참조 대상 노드가 아직 없으면 MERGE로 stub 생성 후 실제 이벤트 도착 시 SET으로 채워짐


async def link_changeset_to_issue(project_id: str, changeset_hash: str, jira_key: str) -> None:
    """TRIGGERED_BY (text): ChangeSet refs.jiraKey 존재 시.

    명시적 텍스트 참조이므로 source='text', confidence=1.0으로 고정한다.
    같은 (changeset, issue) 쌍에 시맨틱 엣지가 먼저 만들어져 있어도 텍스트가 우선이므로 덮어쓴다.
    """
    async with get_driver().session() as session:
        await session.run(
            """
            MERGE (i:Issue {project_id: $project_id, jira_key: $jira_key})
            WITH i
            MATCH (c:ChangeSet {project_id: $project_id, hash: $hash})
            MERGE (c)-[r:TRIGGERED_BY]->(i)
            SET r.source = 'text', r.confidence = 1.0
            """,
            project_id=project_id,
            jira_key=jira_key,
            hash=changeset_hash,
        )


async def link_pr_to_changeset(project_id: str, pr_number: int, changeset_hash: str) -> None:
    """CONTAINS: ChangeSet refs.prNumber 존재 시. 머지된 PR 노드가 없으면 생성하지 않음."""
    async with get_driver().session() as session:
        await session.run(
            """
            MATCH (pr:PullRequest {project_id: $project_id, pr_number: $pr_number})
            MATCH (c:ChangeSet {project_id: $project_id, hash: $hash})
            MERGE (pr)-[:CONTAINS]->(c)
            """,
            project_id=project_id,
            hash=changeset_hash,
            pr_number=pr_number,
        )


async def link_pr_changesets_to_issues(project_id: str, pr_number: int) -> int:
    """TRIGGERED_BY (text) 전파: PR.jira_keys에 등록된 각 Jira 키를 그 PR이 머지한
    모든 ChangeSet에 동일하게 연결한다.

    호출 시점:
      - PR 이벤트 처리 직후 (PR.jira_keys 갱신 직후 — 기존 CONTAINS 커밋에 전파)
      - ChangeSet 이벤트 처리 중 link_pr_to_changeset 직후 (PR이 먼저 도착했으면 새 커밋이 즉시 전파됨)

    PR.jira_keys가 비어있거나 CONTAINS 커밋이 없으면 noop. 모든 절은 MERGE/SET 기반이라 idempotent.

    Returns:
        새로 생성 또는 갱신된 TRIGGERED_BY 엣지 수.
    """
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (pr:PullRequest {project_id: $project_id, pr_number: $pr_number})
            WHERE pr.jira_keys IS NOT NULL AND size(pr.jira_keys) > 0
            UNWIND pr.jira_keys AS jira_key
            MERGE (i:Issue {project_id: $project_id, jira_key: jira_key})
            WITH pr, i
            MATCH (pr)-[:CONTAINS]->(c:ChangeSet)
            MERGE (c)-[r:TRIGGERED_BY]->(i)
            SET r.source = 'text', r.confidence = 1.0
            RETURN count(r) AS n
            """,
            project_id=project_id,
            pr_number=pr_number,
        )
        row = await result.single()
        return row["n"] if row else 0


async def link_issue_to_communication(project_id: str, jira_key: str, comm_url: str) -> None:
    """DISCUSSED_IN: Communication refs.jiraKey 존재 시"""
    async with get_driver().session() as session:
        await session.run(
            """
            MERGE (i:Issue {project_id: $project_id, jira_key: $jira_key})
            WITH i
            MATCH (comm:Communication {project_id: $project_id, url: $comm_url})
            MERGE (i)-[:DISCUSSED_IN]->(comm)
            """,
            project_id=project_id,
            jira_key=jira_key,
            comm_url=comm_url,
        )


async def link_issue_to_parent(project_id: str, child_key: str, parent_key: str) -> None:
    """CHILD_OF: Issue Jira parent 필드 존재 시"""
    async with get_driver().session() as session:
        await session.run(
            """
            MERGE (parent:Issue {project_id: $project_id, jira_key: $parent_key})
            WITH parent
            MERGE (child:Issue {project_id: $project_id, jira_key: $child_key})
            MERGE (child)-[:CHILD_OF]->(parent)
            """,
            project_id=project_id,
            parent_key=parent_key,
            child_key=child_key,
        )


async def propagate_thread_discussed_in() -> int:
    """방안 C — 스레드 전파: conversation_id로 묶인 스레드 내 하나의 Communication이
    DISCUSSED_IN을 가지면 같은 스레드의 나머지 Communication에도 전파.

    conversation_id(Slack ts 등)는 프로젝트 간 충돌 가능 — 같은 project_id 안에서만 전파한다.
    """
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (i:Issue)-[:DISCUSSED_IN]->(seed:Communication)
            WHERE seed.conversation_id IS NOT NULL AND seed.conversation_id <> ''
            WITH i, seed
            MATCH (other:Communication {project_id: seed.project_id, conversation_id: seed.conversation_id})
            WHERE NOT (i)-[:DISCUSSED_IN]->(other)
            MERGE (i)-[:DISCUSSED_IN]->(other)
            RETURN count(*) AS created
            """
        )
        record = await result.single()
        return record["created"] if record else 0


async def backfill_triggered_by_source() -> dict:
    """기존 TRIGGERED_BY 엣지에 source / confidence 속성을 채우는 일회성 마이그레이션.

    분류 기준:
      - confidence IS NULL          → 텍스트 경로로만 생성된 것 → source='text', confidence=1.0
      - confidence IS NOT NULL      → 시맨틱 경로 산물            → source='semantic'
      - 위 둘 다 끝난 뒤, commit.message에 jira_key 텍스트가 들어있는 시맨틱 엣지는
        실제로는 텍스트 참조 케이스로 봐야 하므로 'text'로 승격 (confidence=1.0)

    모든 절은 idempotent. 재실행해도 안전.
    반환: 단계별 갱신 카운트.
    """
    async with get_driver().session() as session:
        # 1) confidence가 없으면 텍스트 경로로만 생성된 것 → text/1.0
        result = await session.run(
            """
            MATCH ()-[r:TRIGGERED_BY]->()
            WHERE r.source IS NULL AND r.confidence IS NULL
            SET r.source = 'text', r.confidence = 1.0
            RETURN count(r) AS n
            """
        )
        text_backfilled = (await result.single())["n"]

        # 2) confidence 있으면 시맨틱 산물 → source='semantic'
        result = await session.run(
            """
            MATCH ()-[r:TRIGGERED_BY]->()
            WHERE r.source IS NULL AND r.confidence IS NOT NULL
            SET r.source = 'semantic'
            RETURN count(r) AS n
            """
        )
        semantic_backfilled = (await result.single())["n"]

        # 3) commit message에 jira_key가 직접 들어있는 시맨틱 엣지를 텍스트로 승격
        #    (pipeline-worker가 refs.jiraKey 추출에 실패했어도 후속 정정)
        result = await session.run(
            """
            MATCH (c:ChangeSet)-[r:TRIGGERED_BY]->(i:Issue)
            WHERE r.source = 'semantic'
              AND c.message IS NOT NULL
              AND c.message CONTAINS i.jira_key
            SET r.source = 'text', r.confidence = 1.0
            RETURN count(r) AS n
            """
        )
        promoted = (await result.single())["n"]

    logger.info(
        "TRIGGERED_BY source 백필 완료: text=%d, semantic=%d, promoted=%d",
        text_backfilled, semantic_backfilled, promoted,
    )
    return {
        "text_backfilled": text_backfilled,
        "semantic_backfilled": semantic_backfilled,
        "promoted_to_text": promoted,
    }


_JIRA_KEY_PATTERN = re.compile(r"\b([A-Z]{2,}-\d+)\b")


async def backfill_pr_jira_keys() -> dict:
    """기존 PR 노드의 title/body에서 jira_keys를 추출해 pr.jira_keys로 저장하고
    link_pr_changesets_to_issues 전파까지 수행한다.

    배경:
      Phase 2 이후 _handle_pull_request는 PR 이벤트가 들어올 때 refs.jiraKeys를 받아
      pr.jira_keys로 저장하지만, 그 변경 이전에 이미 그래프에 들어와 있던 PR은 속성이
      비어있다. 이 함수는 그런 기존 PR에 한정해 한 번에 후처리한다.

    동작:
      pr.jira_keys가 NULL이거나 빈 PR을 찾아 title + body 텍스트에서 Jira 키를 추출.
      매치가 있으면 pr.jira_keys 설정 후 link_pr_changesets_to_issues로 CONTAINS 커밋에
      텍스트 TRIGGERED_BY 전파.

    Idempotent: jira_keys가 이미 채워진 PR은 건너뜀.

    Returns:
        {"pr_scanned": N, "pr_backfilled": K, "edges_propagated": M}
    """
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (pr:PullRequest)
            WHERE (pr.jira_keys IS NULL OR size(pr.jira_keys) = 0)
              AND pr.project_id IS NOT NULL
            RETURN pr.project_id AS project_id,
                   pr.pr_number  AS pr_number,
                   pr.title      AS title,
                   pr.body       AS body
            """
        )
        prs = await result.data()

    backfilled = 0
    edges_propagated = 0
    for pr in prs:
        text = (pr["title"] or "") + " " + (pr["body"] or "")
        # 중복 제거 + 입력 순서 유지 — pipeline-worker RefsExtractor와 같은 정책
        keys = list(dict.fromkeys(_JIRA_KEY_PATTERN.findall(text)))
        if not keys:
            continue

        project_id = pr["project_id"]
        pr_number = pr["pr_number"]
        async with get_driver().session() as session:
            await session.run(
                """
                MATCH (pr:PullRequest {project_id: $project_id, pr_number: $pr_number})
                SET pr.jira_keys = $keys
                """,
                project_id=project_id,
                pr_number=pr_number,
                keys=keys,
            )
        propagated = await link_pr_changesets_to_issues(project_id, pr_number)
        edges_propagated += propagated
        backfilled += 1
        logger.debug("PR #%s 백필: jira_keys=%s → 전파 %d개", pr_number, keys, propagated)

    logger.info(
        "PR jira_keys 백필 완료: scanned=%d, backfilled=%d, propagated=%d",
        len(prs), backfilled, edges_propagated,
    )
    return {
        "pr_scanned":       len(prs),
        "pr_backfilled":    backfilled,
        "edges_propagated": edges_propagated,
    }


async def clear_semantic_triggered_by() -> int:
    """source='semantic'인 TRIGGERED_BY 엣지를 일괄 삭제한다.

    용도: 정책(threshold/window/top-1) 변경 후 시맨틱 결과를 깨끗하게 재구축하고 싶을 때.
    텍스트 매칭(source='text')은 보존되므로 명시 참조는 손상되지 않는다.

    선행 조건:
      backfill_triggered_by_source가 한 번이라도 실행되어 모든 엣지에 source가 라벨링되어 있어야 한다.
      (라벨이 없으면 이 함수가 그것을 시맨틱으로 간주하지 못해 정리 대상에서 누락된다.)

    Returns:
        삭제된 엣지 수.
    """
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH ()-[r:TRIGGERED_BY]->()
            WHERE r.source = 'semantic'
            DELETE r
            """
        )
        summary = await result.consume()
        deleted = summary.counters.relationships_deleted
    logger.info("시맨틱 TRIGGERED_BY 엣지 삭제 완료: %d개", deleted)
    return deleted


async def delete_project_graph(project_id: str, batch_size: int = 10_000) -> int:
    """해당 project_id의 모든 노드(Actor 포함)와 관계를 삭제한다.

    프로젝트 삭제 시 backend가 호출하는 cascade. 모든 도메인 노드뿐 아니라 Actor도
    project_id로 스코프되므로(상단 MERGE/CREATE 참고) 프로젝트 서브그래프 전체가 제거되고
    다른 프로젝트는 건드리지 않는다. 멱등 — 없는/빈 project_id면 0 반환.

    수개월 수집된 대형 프로젝트는 수만 노드·수십만 관계를 가질 수 있어, 단일 트랜잭션으로
    DETACH DELETE하면 tx timeout 또는 힙 부족이 발생한다. CALL { } IN TRANSACTIONS로
    배치 커밋해 메모리 상한을 피한다 — 중간 실패해도 멱등 재시도로 나머지를 마저 지운다.

    Returns:
        삭제된 노드 수.
    """
    if not project_id:
        return 0
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (n {project_id: $project_id})
            CALL (n) { DETACH DELETE n } IN TRANSACTIONS OF $batch_size ROWS
            """,
            project_id=project_id,
            batch_size=batch_size,
        )
        summary = await result.consume()
        deleted = summary.counters.nodes_deleted
    logger.info("프로젝트 그래프 삭제 완료: project=%s, nodes=%d", project_id, deleted)
    return deleted


async def link_issue_to_assignee(project_id: str, jira_key: str, assignee_id: str) -> None:
    """ASSIGNED_TO: Issue assignee 존재 시. JIRA source-scoped alias로 Actor 조회."""
    async with get_driver().session() as session:
        await session.run(
            """
            MATCH (a:Actor {project_id: $project_id})
            WHERE $scoped_alias IN a.aliases
            WITH a
            MATCH (i:Issue {project_id: $project_id, jira_key: $jira_key})
            MERGE (i)-[:ASSIGNED_TO]->(a)
            """,
            project_id=project_id,
            jira_key=jira_key,
            scoped_alias=f"JIRA:{assignee_id}",
        )


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


# ── IssueLinkStore Neo4j 구현체 ───────────────────────────────────────────


async def _fetch_issue_embeddings() -> list[dict]:
    """이슈 임베딩 + 비대칭 시간 윈도우 계산에 필요한 메타데이터 반환.

    closed_at은 NULL일 수 있다 (pipeline-worker가 아직 보내지 않으면).
    issue_linker._compute_issue_window가 status가 terminal일 때 occurred_at으로 fallback.
    """
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (i:Issue)
            WHERE i.embedding IS NOT NULL AND i.occurredAt IS NOT NULL
            RETURN i.project_id AS project_id,
                   i.jira_key AS id,
                   i.title AS title,
                   i.body AS body,
                   i.embedding AS embedding,
                   i.occurredAt AS occurred_at,
                   i.createdAt  AS created_at,
                   i.closedAt   AS closed_at,
                   i.status     AS status
            """
        )
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


async def _fetch_modified_embeddings_for_issue_linking() -> list[dict]:
    """이슈 시맨틱 연결용 — text TRIGGERED_BY가 이미 있는 ChangeSet은 제외.

    이미 텍스트 참조로 확정된 커밋은 시맨틱 매칭의 후보에서 빼서:
      1) 텍스트 매칭 결과가 시맨틱에 의해 다른 이슈로 덮어쓰이지 않게 보호 (semantic 가드 보완)
      2) Issue × ChangeSet 비교량 감소 → 처리 시간 단축
    """
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (c:ChangeSet)-[r:MODIFIED]->(f:File)
            WHERE r.embedding IS NOT NULL AND c.occurredAt IS NOT NULL
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
    async with get_driver().session() as session:
        await session.run(
            """
            MATCH (i:Issue {project_id: $project_id, jira_key: $jira_key})
            MATCH (comm:Communication {project_id: $project_id, url: $comm_url})
            MERGE (i)-[r:DISCUSSED_IN]->(comm)
            SET r.confidence = $confidence
            """,
            project_id=project_id,
            jira_key=jira_key,
            comm_url=comm_url,
            confidence=confidence,
        )


def make_neo4j_issue_link_store():
    """Neo4j 기반 IssueLinkStore 인스턴스를 반환한다.

    fetch_modified_embeddings는 issue-linking 전용 함수를 사용해
    text TRIGGERED_BY가 이미 있는 ChangeSet은 후보에서 제외한다.
    (REFERENCE 엣지용 store는 여전히 _fetch_modified_embeddings를 사용)
    """
    from graph.issue_linker import IssueLinkStore
    return IssueLinkStore(
        fetch_issue_embeddings=_fetch_issue_embeddings,
        fetch_modified_embeddings=_fetch_modified_embeddings_for_issue_linking,
        fetch_communication_embeddings=_fetch_communication_embeddings,
        create_triggered_by_edge=_create_triggered_by_semantic_edge,
        create_discussed_in_edge=_create_discussed_in_semantic_edge,
    )


# ── ActorStore Neo4j 구현체 ───────────────────────────────────────────────


async def _lookup_actor_by_alias(project_id: str, source_id: str) -> Optional[dict]:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (a:Actor {project_id: $project_id})
            WHERE $source_id IN a.aliases
            RETURN a.uuid AS uuid, a.name AS name,
                   a.aliases AS aliases, a.emails AS emails,
                   a.confidence AS confidence
            """,
            project_id=project_id,
            source_id=source_id,
        )
        record = await result.single()
    return dict(record) if record else None


async def _lookup_actor_by_email(project_id: str, email: str) -> Optional[dict]:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (a:Actor {project_id: $project_id})
            WHERE $email IN a.emails
            RETURN a.uuid AS uuid, a.name AS name,
                   a.aliases AS aliases, a.emails AS emails,
                   a.confidence AS confidence
            """,
            project_id=project_id,
            email=email,
        )
        record = await result.single()
    return dict(record) if record else None


async def _lookup_actor_by_name(project_id: str, normalized_name: str) -> list[dict]:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (a:Actor {project_id: $project_id})
            WHERE a.normalized_name = $normalized_name
            RETURN a.uuid AS uuid, a.name AS name,
                   a.aliases AS aliases, a.emails AS emails,
                   a.confidence AS confidence
            """,
            project_id=project_id,
            normalized_name=normalized_name,
        )
        rows = await result.data()
    return [dict(r) for r in rows]


async def _lookup_actor_activities(actor: dict) -> list[dict]:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (a:Actor {uuid: $actor_uuid})-[:AUTHORED|WROTE|CREATED]->(n)
            WHERE n.occurredAt IS NOT NULL
            RETURN labels(n)[0] AS nodeType,
                   n.source AS source,
                   n.message AS message,
                   n.title AS title,
                   n.body AS body,
                   n.channel AS channel,
                   n.occurredAt AS occurred_at
            ORDER BY n.occurredAt DESC
            LIMIT 10
            """,
            actor_uuid=actor.get("uuid"),
        )
        rows = await result.data()
    return [
        {
            **{k: r[k] for k in ("nodeType", "source", "message", "title", "body", "channel")},
            "occurred_at": r["occurred_at"].to_native() if r["occurred_at"] else None,
        }
        for r in rows
    ]


async def _merge_actor(
    actor: dict, new_alias: str, new_email: Optional[str], confidence: float
) -> None:
    async with get_driver().session() as session:
        await session.run(
            """
            MATCH (a:Actor {uuid: $actor_uuid})
            SET a.aliases = CASE WHEN $new_alias IN a.aliases
                                 THEN a.aliases
                                 ELSE a.aliases + $new_alias END,
                a.emails  = CASE WHEN $new_email IS NULL OR $new_email IN a.emails
                                 THEN a.emails
                                 ELSE a.emails + $new_email END,
                a.confidence = $confidence
            """,
            actor_uuid=actor.get("uuid"),
            new_alias=new_alias,
            new_email=new_email,
            confidence=confidence,
        )


async def _create_actor(
    project_id: str, name: str, aliases: list, emails: list, confidence: float
) -> dict:
    from graph.actor_resolver import normalize_name
    actor_uuid     = str(uuid.uuid4())
    normalized     = normalize_name(name)
    async with get_driver().session() as session:
        result = await session.run(
            """
            CREATE (a:Actor {
                uuid: $uuid,
                project_id: $project_id,
                name: $name,
                normalized_name: $normalized_name,
                aliases: $aliases,
                emails: $emails,
                confidence: $confidence
            })
            RETURN a.uuid AS uuid, a.name AS name,
                   a.aliases AS aliases, a.emails AS emails,
                   a.confidence AS confidence
            """,
            uuid=actor_uuid,
            project_id=project_id,
            name=name,
            normalized_name=normalized,
            aliases=aliases,
            emails=emails,
            confidence=confidence,
        )
        record = await result.single()
    return dict(record)


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


def make_neo4j_actor_store(project_id: str):
    """프로젝트 스코프 Neo4j ActorStore 인스턴스를 반환한다.

    Actor 동일인 판단(이름/이메일 매칭)이 프로젝트 경계를 넘지 않도록
    조회·생성 함수에 project_id를 바인딩한다 — 같은 사람이 두 프로젝트에
    등장하면 프로젝트마다 별도 Actor 노드가 생긴다.
    """
    from graph.actor_resolver import ActorStore
    return ActorStore(
        lookup_by_alias=lambda source_id: _lookup_actor_by_alias(project_id, source_id),
        lookup_by_email=lambda email: _lookup_actor_by_email(project_id, email),
        lookup_by_name=lambda name: _lookup_actor_by_name(project_id, name),
        lookup_activities=_lookup_actor_activities,
        merge_actor=_merge_actor,
        create_actor=lambda name, aliases, emails, confidence: _create_actor(
            project_id, name, aliases, emails, confidence
        ),
    )
