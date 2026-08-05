"""
NormalizedEvent 단위 그래프 쓰기 — upsert + 참조 엣지 (수집 쓰기 경로).

각 함수는 NormalizedEvent 하나에 대응하는 원자적 쓰기 단위.
- Layer 1: AUTHORED / WROTE / CREATED 엣지 (actor + node MERGE)
- Layer 2: TRIGGERED_BY / CONTAINS / DISCUSSED_IN / CHILD_OF / ASSIGNED_TO 엣지 (refs 기반, stub 허용)
- Layer 3: MODIFIED 엣지 (ChangeSet -> File, diffSummary + embedding)
"""

from typing import Optional

from graph.driver import get_driver


# ── Layer 1 + Layer 3 upserts ─────────────────────────────────────────────


async def upsert_changeset(
    *,
    project_id: str,
    hash: str,
    message: str,
    occurred_at: str,
    source: str,
    actor_uuid: str,
    embedding: list[float],
) -> None:
    """ChangeSet 노드 upsert.

    embedding은 커밋 메시지의 임베딩. 임베딩 실패 시 빈 리스트가 오는데,
    그때는 기존 c.embedding을 보존한다 — 재수집이 정상 임베딩을 지우지 않도록.
    (구멍은 backfill_changeset_message_embeddings가 채운다.)
    """
    async with get_driver().session() as session:
        await session.run(
            """
            MATCH (a:Actor {uuid: $actor_uuid})
            MERGE (c:ChangeSet {project_id: $project_id, hash: $hash})
            SET c.message = $message,
                c.occurredAt = datetime($occurred_at),
                c.source = $source,
                c.embedding = CASE WHEN size($embedding) > 0 THEN $embedding ELSE c.embedding END
            MERGE (a)-[:AUTHORED]->(c)
            """,
            actor_uuid=actor_uuid,
            project_id=project_id,
            hash=hash,
            message=message,
            occurred_at=occurred_at,
            source=source,
            embedding=embedding,
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


async def upsert_files_with_modified_edges(
    *,
    project_id: str,
    changeset_hash: str,
    files: list[dict],
) -> None:
    """한 ChangeSet의 파일 여러 개를 UNWIND로 한 번에 upsert한다 (#6 배치).

    files: [{"file_path": str, "diff_summary": str, "embedding": list[float]}, ...]

    파일별 단건 호출(세션 N번) 대비:
      - Neo4j 세션·왕복을 N→1로 줄인다.
      - 같은 ChangeSet 노드(c)에 대한 동시 MERGE 락 경합을 없앤다(한 트랜잭션 내 직렬).
    ChangeSet은 호출 전 upsert_changeset으로 이미 존재한다고 가정한다(없으면 no-op).
    빈 목록이면 no-op.
    """
    if not files:
        return
    async with get_driver().session() as session:
        await session.run(
            """
            MATCH (c:ChangeSet {project_id: $project_id, hash: $changeset_hash})
            UNWIND $files AS file
            MERGE (f:File {project_id: $project_id, path: file.file_path})
            MERGE (c)-[r:MODIFIED]->(f)
            SET r.diffSummary = file.diff_summary,
                r.embedding   = file.embedding
            """,
            project_id=project_id,
            changeset_hash=changeset_hash,
            files=files,
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
    issue_keys: Optional[list[str]] = None,
) -> None:
    """PullRequest 노드 upsert.

    issue_keys:
      PR 제목/본문에서 추출한 다중 이슈 키. 그 PR이 머지한 모든 ChangeSet에 동일 키로
      text TRIGGERED_BY를 전파하는 데 사용된다 (link_pr_changesets_to_issues).
      None이면 기존 pr.issue_keys 값을 보존, 명시되면 갱신.
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
                pr.issue_keys  = CASE WHEN $issue_keys   IS NOT NULL THEN $issue_keys              ELSE pr.issue_keys END,
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
            issue_keys=issue_keys,
            source=source,
        )


async def upsert_issue(
    *,
    project_id: str,
    issue_key: str,
    title: str,
    body: str,
    status: str,
    issue_type: str,
    priority: str,
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
            MERGE (i:Issue {project_id: $project_id, issue_key: $issue_key})
            SET i.title = $title,
                i.body = $body,
                i.status = $status,
                i.issue_type = $issue_type,
                i.priority = $priority,
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
            issue_key=issue_key,
            title=title,
            body=body,
            status=status,
            issue_type=issue_type,
            priority=priority,
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
            // llm_filtered는 노이즈 필터의 판정 결과다. 전체 재수집으로 같은 메시지가
            // 재-MERGE될 때 무조건 SET하면 keep(true) 판정을 false로 덮어 재필터·삭제될 수
            // 있으므로 생성 시에만 초기화하고, 기존 노드는 판정을 보존한다.
            ON CREATE SET comm.llm_filtered = $llm_filtered
            SET comm.body = $body,
                comm.channel = $channel,
                comm.conversation_id = $conversation_id,
                comm.occurredAt = datetime($occurred_at),
                comm.createdAt  = CASE WHEN $created_at IS NOT NULL THEN datetime($created_at) ELSE null END,
                comm.source = $source,
                comm.embedding = $embedding
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


async def link_changeset_to_issue(project_id: str, changeset_hash: str, issue_key: str) -> None:
    """TRIGGERED_BY (text): ChangeSet refs.issueKey 존재 시.

    명시적 텍스트 참조이므로 source='text', confidence=1.0으로 고정한다.
    같은 (changeset, issue) 쌍에 시맨틱 엣지가 먼저 만들어져 있어도 텍스트가 우선이므로 덮어쓴다.
    """
    async with get_driver().session() as session:
        await session.run(
            """
            MERGE (i:Issue {project_id: $project_id, issue_key: $issue_key})
            WITH i
            MATCH (c:ChangeSet {project_id: $project_id, hash: $hash})
            MERGE (c)-[r:TRIGGERED_BY]->(i)
            SET r.source = 'text', r.confidence = 1.0
            """,
            project_id=project_id,
            issue_key=issue_key,
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


async def link_changeset_to_pr_issues(project_id: str, pr_number: int, changeset_hash: str) -> int:
    """TRIGGERED_BY (text) 전파 — 단건: PR.issue_keys를 '이 커밋 하나'에만 연결한다.

    커밋이 올 때마다 PR 전체 커밋에 재전파하면(link_pr_changesets_to_issues) 커밋 k에서
    ~k개에 다시 걸려 1+2+…+N = O(N²)가 된다. 커밋 경로에서는 그 커밋 하나만 연결해
    O(N)으로 만든다. PR 전체 전파는 PR 도착 시(_handle_pull_request)에만 1회 수행한다.

    PR.issue_keys가 비었거나 (pr)-[:CONTAINS]->(이 커밋)이 아직 없으면 noop.
    모든 절은 MERGE/SET이라 idempotent.

    Returns:
        새로 생성 또는 갱신된 TRIGGERED_BY 엣지 수.
    """
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (pr:PullRequest {project_id: $project_id, pr_number: $pr_number})
            WHERE pr.issue_keys IS NOT NULL AND size(pr.issue_keys) > 0
            MATCH (pr)-[:CONTAINS]->(c:ChangeSet {project_id: $project_id, hash: $changeset_hash})
            UNWIND pr.issue_keys AS issue_key
            MERGE (i:Issue {project_id: $project_id, issue_key: issue_key})
            MERGE (c)-[r:TRIGGERED_BY]->(i)
            SET r.source = 'text', r.confidence = 1.0
            RETURN count(r) AS n
            """,
            project_id=project_id,
            pr_number=pr_number,
            changeset_hash=changeset_hash,
        )
        row = await result.single()
        return row["n"] if row else 0


async def link_pr_changesets_to_issues(project_id: str, pr_number: int) -> int:
    """TRIGGERED_BY (text) 전파: PR.issue_keys에 등록된 각 이슈 키를 그 PR이 머지한
    모든 ChangeSet에 동일하게 연결한다.

    호출 시점:
      - PR 이벤트 처리 직후 (PR.issue_keys 갱신 직후 — 기존 CONTAINS 커밋에 전파)
      - ChangeSet 이벤트 처리 중 link_pr_to_changeset 직후 (PR이 먼저 도착했으면 새 커밋이 즉시 전파됨)

    PR.issue_keys가 비어있거나 CONTAINS 커밋이 없으면 noop. 모든 절은 MERGE/SET 기반이라 idempotent.

    Returns:
        새로 생성 또는 갱신된 TRIGGERED_BY 엣지 수.
    """
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (pr:PullRequest {project_id: $project_id, pr_number: $pr_number})
            WHERE pr.issue_keys IS NOT NULL AND size(pr.issue_keys) > 0
            UNWIND pr.issue_keys AS issue_key
            MERGE (i:Issue {project_id: $project_id, issue_key: issue_key})
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


async def link_issue_to_communication(project_id: str, issue_key: str, comm_url: str) -> None:
    """DISCUSSED_IN: Communication refs.issueKey 존재 시.

    명시적 텍스트 참조이므로 source='text'로 고정한다 — 시맨틱 재구축(clear)이 이 엣지를
    지우지 않게 하는 표식이다. confidence는 부여하지 않는다: DISCUSSED_IN에서 confidence는
    시맨틱 엣지의 유사도 점수를 뜻하며, 채점(eval)이 그 유무로 시맨틱을 판별한다.

    같은 쌍에 시맨틱 엣지가 먼저 있었다면 텍스트가 우선이므로 덮어쓰되, 남아 있던 유사도
    점수는 REMOVE로 지운다 — 안 지우면 'text' 표식 때문에 clear·백필이 둘 다 건너뛰어
    잔존 confidence가 영구히 남고, 채점이 이 엣지를 시맨틱으로 오인한다.
    """
    async with get_driver().session() as session:
        await session.run(
            """
            MERGE (i:Issue {project_id: $project_id, issue_key: $issue_key})
            WITH i
            MATCH (comm:Communication {project_id: $project_id, url: $comm_url})
            MERGE (i)-[r:DISCUSSED_IN]->(comm)
            SET r.source = 'text'
            REMOVE r.confidence
            """,
            project_id=project_id,
            issue_key=issue_key,
            comm_url=comm_url,
        )


async def link_issue_to_parent(project_id: str, child_key: str, parent_key: str) -> None:
    """CHILD_OF: Issue Jira parent 필드 존재 시"""
    async with get_driver().session() as session:
        await session.run(
            """
            MERGE (parent:Issue {project_id: $project_id, issue_key: $parent_key})
            WITH parent
            MERGE (child:Issue {project_id: $project_id, issue_key: $child_key})
            MERGE (child)-[:CHILD_OF]->(parent)
            """,
            project_id=project_id,
            parent_key=parent_key,
            child_key=child_key,
        )

async def link_issue_to_assignee(project_id: str, issue_key: str, actor_uuid: str) -> None:
    """ASSIGNED_TO: Issue assignee 존재 시. event_handler가 resolve_actor로 확정한 Actor uuid를 받는다.

    Jira 이슈 이벤트는 그 이슈의 최신 스냅샷이라 항상 담당자 최대 1명을 가리켜야 한다.
    재배정(A→B) 시 새 엣지만 MERGE하면 이전 담당자 엣지가 남아 "현재 담당자" 조회·활동량
    집계가 과거 담당자까지 잡으므로, 새 담당자로 향하지 않는 기존 ASSIGNED_TO 엣지를 먼저 지운다.
    """
    async with get_driver().session() as session:
        await session.run(
            """
            MATCH (a:Actor {uuid: $actor_uuid, project_id: $project_id})
            WITH a
            MATCH (i:Issue {project_id: $project_id, issue_key: $issue_key})
            OPTIONAL MATCH (i)-[r:ASSIGNED_TO]->(other:Actor)
            WHERE other.uuid <> $actor_uuid
            DELETE r
            MERGE (i)-[:ASSIGNED_TO]->(a)
            """,
            project_id=project_id,
            issue_key=issue_key,
            actor_uuid=actor_uuid,
        )


async def unlink_issue_assignees(project_id: str, issue_key: str) -> None:
    """이슈 스냅샷 이벤트에 assigneeId가 없을 때(담당자 해제) 그 이슈의 기존 ASSIGNED_TO
    엣지를 전부 지운다. 이슈 이벤트가 아닌, 이슈를 참조만 하는 이벤트(코멘트 등)에서
    호출하면 안 된다 — 그 경우 assignee 정보 부재가 "해제"를 뜻하지 않는다.
    """
    async with get_driver().session() as session:
        await session.run(
            """
            MATCH (i:Issue {project_id: $project_id, issue_key: $issue_key})-[r:ASSIGNED_TO]->()
            DELETE r
            """,
            project_id=project_id,
            issue_key=issue_key,
        )
