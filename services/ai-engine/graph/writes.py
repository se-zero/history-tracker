"""
NormalizedEvent 단위 그래프 쓰기 — upsert + 참조 엣지 (수집 쓰기 경로).

각 함수는 NormalizedEvent 하나에 대응하는 원자적 쓰기 단위.
- Layer 1: AUTHORED / WROTE / CREATED 엣지 (actor + node MERGE)
- Layer 2: TRIGGERED_BY / CONTAINS / DISCUSSED_IN / CHILD_OF / ASSIGNED_TO 엣지 (refs 기반, stub 허용)
- Layer 3: MODIFIED 엣지 (ChangeSet -> File, diffSummary + embedding)
"""

from typing import Optional

from graph.driver import get_driver

# Jira status_category가 이 값이면 이슈가 종료 상태다. writes.py는 그래프 쓰기 경로의
# 최하층이라 여기 둔다 — issue_linker.py(상위 계층)가 이 정규 위치에서 import해 재사용할
# 예정(writes → issue_linker 방향 import는 없으므로 순환 위험이 없다).
STATUS_CATEGORY_CLOSED = "closed"


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
    issue_external_ids: Optional[list[str]] = None,
    document_external_ids: Optional[list[str]] = None,
) -> None:
    """PullRequest 노드 upsert.

    issue_keys:
      PR 제목/본문에서 추출한 다중 이슈 키. 그 PR이 머지한 모든 ChangeSet에 동일 키로
      text TRIGGERED_BY를 전파하는 데 사용된다 (link_pr_changesets_to_issues).
      None이면 기존 pr.issue_keys 값을 보존, 명시되면 갱신.

    issue_external_ids:
      이슈 키가 없는 소스(Asana 등)에서 URL로 추출한 참조. "SOURCE:external_id" 콜론
      결합 문자열 배열로 저장한다(Neo4j 속성은 맵 배열 불가 — 복합 id 관례, 예: "ASANA:123").
      issue_keys와 동일한 보존/갱신 규칙 — None이면 기존 pr.issue_external_ids 값을 보존,
      명시되면 갱신. link_pr_changesets_to_issue_externals가 전파에 사용한다.

    document_external_ids:
      PR 제목/본문의 Notion URL에서 추출한 참조. issue_external_ids와 같은 "SOURCE:external_id"
      인코딩·보존/갱신 규칙을 쓴다. link_pr_changesets_to_documents가 REFERENCE(text) 전파에
      사용한다(docs/notion-integration.md §2-5 — PR 본문이 커밋 메시지보다 흔한 Notion 링크
      유입로다).
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
                pr.issue_external_ids = CASE WHEN $issue_external_ids IS NOT NULL THEN $issue_external_ids ELSE pr.issue_external_ids END,
                pr.document_external_ids = CASE WHEN $document_external_ids IS NOT NULL THEN $document_external_ids ELSE pr.document_external_ids END,
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
            issue_external_ids=issue_external_ids,
            document_external_ids=document_external_ids,
            source=source,
        )


async def upsert_issue(
    *,
    project_id: str,
    source: str,
    external_id: str,
    issue_key: Optional[str],
    title: str,
    body: str,
    status: Optional[str],
    status_category: str,
    issue_type: str,
    priority: str,
    occurred_at: str,
    created_at: Optional[str],
    closed_at: Optional[str] = None,
    actor_uuid: str,
    embedding: list[float],
) -> None:
    """Issue 노드 upsert.

    MERGE 키는 (project_id, source, external_id) — Jira issue id(external_id)는 불변이라
    사람용 키(issue_key, 예: HT-7)가 바뀌어도 같은 노드를 계속 가리킨다. issue_key는
    표시·텍스트 링크 매칭용 속성으로만 SET한다.

    closed_at 정책 (status_category 기반):
      - closed_at 값 있음                                      → 그 값으로 덮어씀
      - closed_at 값 없음(None) + status_category == closed   → 기존 i.closedAt 보존
        (pipeline-worker가 아직 closed_at을 안 보내는 마이그레이션 단계 안전망)
      - closed_at 값 없음(None) + status_category != closed   → null 로 클리어
        (재오픈된 이슈가 비대칭 시간 윈도우 계산에서 오래된 종료 시각을 쓰지 않도록 함)

    createdAt 정책: 원래대로 — 값 있으면 SET, 없으면 null (이벤트 소스가 항상 보내는 게 정상).
    """
    async with get_driver().session() as session:
        await session.run(
            """
            MATCH (a:Actor {uuid: $actor_uuid})
            MERGE (i:Issue {project_id: $project_id, source: $source, external_id: $external_id})
            SET i.issue_key = $issue_key,
                i.title = $title,
                i.body = $body,
                i.status = $status,
                i.status_category = $status_category,
                i.issue_type = $issue_type,
                i.priority = $priority,
                i.occurredAt = datetime($occurred_at),
                i.createdAt  = CASE WHEN $created_at IS NOT NULL THEN datetime($created_at) ELSE null END,
                i.embedding  = $embedding,
                i.closedAt   = CASE
                                  WHEN $closed_at IS NOT NULL THEN datetime($closed_at)
                                  WHEN $status_category = $closed_category THEN i.closedAt
                                  ELSE null
                               END
            MERGE (a)-[:CREATED]->(i)
            """,
            actor_uuid=actor_uuid,
            project_id=project_id,
            source=source,
            external_id=external_id,
            issue_key=issue_key,
            title=title,
            body=body,
            status=status,
            status_category=status_category,
            issue_type=issue_type,
            priority=priority,
            occurred_at=occurred_at,
            created_at=created_at,
            closed_at=closed_at,
            closed_category=STATUS_CATEGORY_CLOSED,
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


async def upsert_document(
    *,
    project_id: str,
    source: str,
    external_id: str,
    title: str,
    body: str,
    url: str,
    occurred_at: str,
    created_at: Optional[str],
    parent_type: str,
    parent_external_id: Optional[str],
    actor_uuid: str,
) -> None:
    """Document 노드 upsert와 작성자 WROTE 연결.

    page id는 제목·URL과 달리 불변이므로 `(project_id, source, external_id)`를 MERGE 키로
    쓴다. Document 자체에는 embedding을 저장하지 않는다 — 긴 본문의 검색 벡터는
    DocumentSection에만 둔다.
    """
    async with get_driver().session() as session:
        await session.run(
            """
            MATCH (a:Actor {uuid: $actor_uuid})
            MERGE (d:Document {project_id: $project_id, source: $source, external_id: $external_id})
            SET d.title = $title,
                d.body = $body,
                d.url = $url,
                d.occurredAt = datetime($occurred_at),
                d.createdAt = CASE WHEN $created_at IS NOT NULL THEN datetime($created_at) ELSE null END,
                d.parent_type = $parent_type,
                d.parent_external_id = $parent_external_id
            MERGE (a)-[:WROTE]->(d)
            """,
            actor_uuid=actor_uuid,
            project_id=project_id,
            source=source,
            external_id=external_id,
            title=title,
            body=body,
            url=url,
            occurred_at=occurred_at,
            created_at=created_at,
            parent_type=parent_type,
            parent_external_id=parent_external_id,
        )


async def replace_document_sections(
    *,
    project_id: str,
    source: str,
    document_external_id: str,
    sections: list[dict],
) -> None:
    """문서의 DocumentSection을 전량 교체한다.

    본문 중간 편집은 이후 ordinal을 전부 밀 수 있어 부분 upsert가 안전하지 않다. 삭제 후
    생성 사이에 실패해도 이벤트 재시도는 멱등적으로 다시 채우므로, 두 문으로 분리해 Cypher의
    빈 UNWIND 함정을 피한다.
    """
    async with get_driver().session() as session:
        await session.run(
            """
            MATCH (s:DocumentSection {
                project_id: $project_id,
                source: $source,
                document_external_id: $document_external_id
            })
            DETACH DELETE s
            """,
            project_id=project_id,
            source=source,
            document_external_id=document_external_id,
        )
        if not sections:
            return
        await session.run(
            """
            MATCH (d:Document {project_id: $project_id, source: $source, external_id: $document_external_id})
            UNWIND $sections AS section
            MERGE (s:DocumentSection {
                project_id: $project_id,
                source: $source,
                document_external_id: $document_external_id,
                ordinal: section.ordinal
            })
            SET s.heading_path = section.heading_path,
                s.text = section.text,
                s.embedding = section.embedding
            MERGE (s)-[:PART_OF]->(d)
            """,
            project_id=project_id,
            source=source,
            document_external_id=document_external_id,
            sections=sections,
        )


# ── Layer 2 ref 엣지 ──────────────────────────────────────────────────────
# 참조 대상 노드가 아직 없으면 MERGE로 stub 생성 후 실제 이벤트 도착 시 SET으로 채워짐


async def link_changeset_to_issue(project_id: str, changeset_hash: str, issue_key: str) -> None:
    """TRIGGERED_BY (text): ChangeSet refs.issueKey 존재 시.

    실노드(같은 issue_key, source <> '__stub__') 우선 매칭 — 있으면 거기에 건다. 없으면
    __stub__ 센티널 Issue를 만들어 걸어 두고, 실제 이슈 이벤트가 나중에 도착하면
    absorb_issue_stub이 이 엣지를 실노드로 이관한다.

    명시적 텍스트 참조이므로 source='text', confidence=1.0으로 고정한다.
    같은 (changeset, issue) 쌍에 시맨틱 엣지가 먼저 만들어져 있어도 텍스트가 우선이므로 덮어쓴다.

    두 문(실노드 매칭 → stub 폴백)은 각각 MERGE/SET이라 멱등이다 — 문 사이에 컨슈머가
    죽어도 재시도가 나머지를 채운다.
    """
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (c:ChangeSet {project_id: $project_id, hash: $hash})
            MATCH (i:Issue {project_id: $project_id, issue_key: $issue_key})
            WHERE i.source <> '__stub__'
            MERGE (c)-[r:TRIGGERED_BY]->(i)
            SET r.source = 'text', r.confidence = 1.0
            RETURN count(i) AS matched
            """,
            project_id=project_id,
            issue_key=issue_key,
            hash=changeset_hash,
        )
        record = await result.single()
        if record and record["matched"] > 0:
            return

        await session.run(
            """
            MATCH (c:ChangeSet {project_id: $project_id, hash: $hash})
            MERGE (i:Issue {project_id: $project_id, source: '__stub__', external_id: $issue_key})
            ON CREATE SET i.issue_key = $issue_key
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

    실노드 우선 매칭 → 매칭 안 된 키만 __stub__ 센티널로 폴백한다. 같은 세션 2문:
    ①에서 UNWIND + OPTIONAL MATCH 실노드로 매칭·미매칭 키를 가르고, 매칭된 키는 그
    자리에서 엣지를 건다 → ②에서 미매칭 키만 UNWIND해 stub Issue를 만들고 엣지를 건다.
    두 문 모두 MERGE/SET이라 멱등 — 문 사이에 컨슈머가 죽어도 재시도가 나머지를 채운다.

    Returns:
        새로 생성 또는 갱신된 TRIGGERED_BY 엣지 수(실노드 매칭 + stub 생성의 합, 근사치).
    """
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (pr:PullRequest {project_id: $project_id, pr_number: $pr_number})
            WHERE pr.issue_keys IS NOT NULL AND size(pr.issue_keys) > 0
            MATCH (pr)-[:CONTAINS]->(c:ChangeSet {project_id: $project_id, hash: $changeset_hash})
            UNWIND pr.issue_keys AS issue_key
            OPTIONAL MATCH (i:Issue {project_id: $project_id, issue_key: issue_key})
            WHERE i.source <> '__stub__'
            FOREACH (_ IN CASE WHEN i IS NOT NULL THEN [1] ELSE [] END |
                MERGE (c)-[r:TRIGGERED_BY]->(i)
                SET r.source = 'text', r.confidence = 1.0
            )
            RETURN
                sum(CASE WHEN i IS NOT NULL THEN 1 ELSE 0 END) AS matched,
                collect(DISTINCT CASE WHEN i IS NULL THEN issue_key ELSE null END) AS unmatched_raw
            """,
            project_id=project_id,
            pr_number=pr_number,
            changeset_hash=changeset_hash,
        )
        row = await result.single()
        matched = row["matched"] if row and row["matched"] is not None else 0
        unmatched_keys = [k for k in (row["unmatched_raw"] if row else []) if k is not None]

        created = 0
        if unmatched_keys:
            result2 = await session.run(
                """
                MATCH (pr:PullRequest {project_id: $project_id, pr_number: $pr_number})
                MATCH (pr)-[:CONTAINS]->(c:ChangeSet {project_id: $project_id, hash: $changeset_hash})
                UNWIND $unmatched_keys AS issue_key
                MERGE (i:Issue {project_id: $project_id, source: '__stub__', external_id: issue_key})
                ON CREATE SET i.issue_key = issue_key
                MERGE (c)-[r:TRIGGERED_BY]->(i)
                SET r.source = 'text', r.confidence = 1.0
                RETURN count(r) AS created
                """,
                project_id=project_id,
                pr_number=pr_number,
                changeset_hash=changeset_hash,
                unmatched_keys=unmatched_keys,
            )
            row2 = await result2.single()
            created = row2["created"] if row2 else 0

        return matched + created


async def link_pr_changesets_to_issues(project_id: str, pr_number: int) -> int:
    """TRIGGERED_BY (text) 전파: PR.issue_keys에 등록된 각 이슈 키를 그 PR이 머지한
    모든 ChangeSet에 동일하게 연결한다.

    호출 시점:
      - PR 이벤트 처리 직후 (PR.issue_keys 갱신 직후 — 기존 CONTAINS 커밋에 전파)
      - ChangeSet 이벤트 처리 중 link_pr_to_changeset 직후 (PR이 먼저 도착했으면 새 커밋이 즉시 전파됨)

    PR.issue_keys가 비어있거나 CONTAINS 커밋이 없으면 noop.

    실노드 우선 매칭 → 매칭 안 된 키만 __stub__ 센티널로 폴백한다(같은 세션 2문 구조는
    link_changeset_to_pr_issues와 동일 — 차이는 커밋 하나가 아니라 PR의 모든 CONTAINS
    커밋에 전파한다는 것). 두 문 모두 MERGE/SET이라 멱등.

    Returns:
        새로 생성 또는 갱신된 TRIGGERED_BY 엣지 수(실노드 매칭 + stub 생성의 합, 근사치).
    """
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (pr:PullRequest {project_id: $project_id, pr_number: $pr_number})
            WHERE pr.issue_keys IS NOT NULL AND size(pr.issue_keys) > 0
            OPTIONAL MATCH (pr)-[:CONTAINS]->(cs:ChangeSet)
            WITH pr, [x IN collect(DISTINCT cs) WHERE x IS NOT NULL] AS changesets
            UNWIND pr.issue_keys AS issue_key
            OPTIONAL MATCH (i:Issue {project_id: $project_id, issue_key: issue_key})
            WHERE i.source <> '__stub__'
            FOREACH (c IN CASE WHEN i IS NOT NULL THEN changesets ELSE [] END |
                MERGE (c)-[r:TRIGGERED_BY]->(i)
                SET r.source = 'text', r.confidence = 1.0
            )
            RETURN
                sum(CASE WHEN i IS NOT NULL THEN size(changesets) ELSE 0 END) AS matched,
                collect(DISTINCT CASE WHEN i IS NULL THEN issue_key ELSE null END) AS unmatched_raw
            """,
            project_id=project_id,
            pr_number=pr_number,
        )
        row = await result.single()
        matched = row["matched"] if row and row["matched"] is not None else 0
        unmatched_keys = [k for k in (row["unmatched_raw"] if row else []) if k is not None]

        created = 0
        if unmatched_keys:
            result2 = await session.run(
                """
                MATCH (pr:PullRequest {project_id: $project_id, pr_number: $pr_number})
                UNWIND $unmatched_keys AS issue_key
                MERGE (i:Issue {project_id: $project_id, source: '__stub__', external_id: issue_key})
                ON CREATE SET i.issue_key = issue_key
                WITH pr, i
                MATCH (pr)-[:CONTAINS]->(c:ChangeSet)
                MERGE (c)-[r:TRIGGERED_BY]->(i)
                SET r.source = 'text', r.confidence = 1.0
                RETURN count(r) AS created
                """,
                project_id=project_id,
                pr_number=pr_number,
                unmatched_keys=unmatched_keys,
            )
            row2 = await result2.single()
            created = row2["created"] if row2 else 0

        return matched + created


async def link_issue_to_communication(project_id: str, issue_key: str, comm_url: str) -> None:
    """DISCUSSED_IN: Communication refs.issueKey 존재 시.

    실노드(같은 issue_key, source <> '__stub__') 우선 매칭 → 없으면 __stub__ 센티널
    Issue로 폴백한다(link_changeset_to_issue와 동일한 2문 구조). absorb_issue_stub이
    실제 이슈 도착 시 이 엣지를 실노드로 이관한다.

    명시적 텍스트 참조이므로 source='text'로 고정한다 — 시맨틱 재구축(clear)이 이 엣지를
    지우지 않게 하는 표식이다. confidence는 부여하지 않는다: DISCUSSED_IN에서 confidence는
    시맨틱 엣지의 유사도 점수를 뜻하며, 채점(eval)이 그 유무로 시맨틱을 판별한다.

    같은 쌍에 시맨틱 엣지가 먼저 있었다면 텍스트가 우선이므로 덮어쓰되, 남아 있던 유사도
    점수는 REMOVE로 지운다 — 안 지우면 'text' 표식 때문에 clear·백필이 둘 다 건너뛰어
    잔존 confidence가 영구히 남고, 채점이 이 엣지를 시맨틱으로 오인한다.
    """
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (comm:Communication {project_id: $project_id, url: $comm_url})
            MATCH (i:Issue {project_id: $project_id, issue_key: $issue_key})
            WHERE i.source <> '__stub__'
            MERGE (i)-[r:DISCUSSED_IN]->(comm)
            SET r.source = 'text'
            REMOVE r.confidence
            RETURN count(i) AS matched
            """,
            project_id=project_id,
            issue_key=issue_key,
            comm_url=comm_url,
        )
        record = await result.single()
        if record and record["matched"] > 0:
            return

        await session.run(
            """
            MATCH (comm:Communication {project_id: $project_id, url: $comm_url})
            MERGE (i:Issue {project_id: $project_id, source: '__stub__', external_id: $issue_key})
            ON CREATE SET i.issue_key = $issue_key
            MERGE (i)-[r:DISCUSSED_IN]->(comm)
            SET r.source = 'text'
            REMOVE r.confidence
            """,
            project_id=project_id,
            issue_key=issue_key,
            comm_url=comm_url,
        )


async def link_issue_to_parent(
    project_id: str, source: str, child_external_id: str,
    parent_external_id: str, parent_issue_key: Optional[str],
) -> None:
    """CHILD_OF: Issue Jira parent 필드 존재 시.

    parent는 __stub__ 센티널이 아니라 실키 (project_id, source, external_id) pre-node로
    미리 만든다 — 부모 이슈의 본 이벤트가 도착하면 upsert_issue가 같은 키로 MERGE해 채운다.
    child는 이 함수 호출 전에 upsert_issue로 이미 존재하므로 MATCH.
    """
    async with get_driver().session() as session:
        await session.run(
            """
            MERGE (parent:Issue {project_id: $project_id, source: $source, external_id: $parent_external_id})
            ON CREATE SET parent.issue_key = $parent_issue_key
            WITH parent
            MATCH (child:Issue {project_id: $project_id, source: $source, external_id: $child_external_id})
            MERGE (child)-[:CHILD_OF]->(parent)
            """,
            project_id=project_id,
            source=source,
            parent_external_id=parent_external_id,
            parent_issue_key=parent_issue_key,
            child_external_id=child_external_id,
        )


async def link_document_to_parent(
    project_id: str, source: str, child_external_id: str, parent_external_id: str,
) -> None:
    """CHILD_OF: Document의 부모 page를 실키 pre-node로 연결한다."""
    async with get_driver().session() as session:
        await session.run(
            """
            MERGE (parent:Document {
                project_id: $project_id, source: $source, external_id: $parent_external_id
            })
            WITH parent
            MATCH (child:Document {
                project_id: $project_id, source: $source, external_id: $child_external_id
            })
            MERGE (child)-[:CHILD_OF]->(parent)
            """,
            project_id=project_id,
            source=source,
            child_external_id=child_external_id,
            parent_external_id=parent_external_id,
        )


async def set_document_editors(
    project_id: str, source: str, external_id: str, actor_uuids: list[str],
) -> None:
    """EDITED: 마지막 편집자 스냅샷을 누적 이력으로 반영한다.

    Notion은 마지막 편집자만 주므로 Issue ASSIGNED_TO처럼 기존 엣지를 지우면 과거 편집자가
    사라진다. 따라서 새 actor만 MERGE하고 해제는 하지 않는다.
    """
    if not actor_uuids:
        return
    async with get_driver().session() as session:
        await session.run(
            """
            MATCH (d:Document {project_id: $project_id, source: $source, external_id: $external_id})
            UNWIND $actor_uuids AS uuid
            MATCH (a:Actor {uuid: uuid})
            MERGE (a)-[:EDITED]->(d)
            """,
            project_id=project_id,
            source=source,
            external_id=external_id,
            actor_uuids=list(dict.fromkeys(actor_uuids)),
        )


async def set_issue_assignees(
    project_id: str, source: str, external_id: str, actor_uuids: list[str],
) -> None:
    """ASSIGNED_TO: Issue assignees 스냅샷을 통째로 반영한다. event_handler가 resolve_actor로
    확정한 Actor uuid 목록을 받는다.

    Jira 이슈 이벤트는 그 이슈의 최신 스냅샷이라 담당자 목록 전체를 이 호출 하나로
    대체해야 한다 — 목록 밖으로 빠진 기존 ASSIGNED_TO 엣지를 먼저 지우고, 목록의 각
    uuid로 새로 MERGE한다. 빈 리스트를 주면 담당자 전원 해제.

    이슈 이벤트가 아닌, 이슈를 참조만 하는 이벤트(코멘트 등)에서 호출하면 안 된다 — 그
    경우 assignees 정보 부재가 "해제"를 뜻하지 않는다.

    삭제와 연결을 세션 2문으로 분리한다 — 한 문에서 빈 actor_uuids를 UNWIND하면 그 행이
    사라져(Cypher UNWIND 빈 리스트는 결과 행을 0개로 만든다) 뒤에 이어붙인 DELETE까지
    실행되지 않을 수 있다.
    """
    async with get_driver().session() as session:
        await session.run(
            """
            MATCH (i:Issue {project_id: $project_id, source: $source, external_id: $external_id})
                  -[r:ASSIGNED_TO]->(other:Actor)
            WHERE NOT other.uuid IN $actor_uuids
            DELETE r
            """,
            project_id=project_id,
            source=source,
            external_id=external_id,
            actor_uuids=actor_uuids,
        )
        if not actor_uuids:
            return
        await session.run(
            """
            MATCH (i:Issue {project_id: $project_id, source: $source, external_id: $external_id})
            UNWIND $actor_uuids AS uuid
            MATCH (a:Actor {uuid: uuid, project_id: $project_id})
            MERGE (i)-[:ASSIGNED_TO]->(a)
            """,
            project_id=project_id,
            source=source,
            external_id=external_id,
            actor_uuids=actor_uuids,
        )


async def absorb_issue_stub(project_id: str, source: str, external_id: str, issue_key: str) -> None:
    """실노드 upsert 직후 호출 — 같은 issue_key의 __stub__ Issue가 있으면 거기 붙은
    텍스트 링크 엣지를 실노드로 이관하고 stub을 지운다.

    stub에는 텍스트 링크 함수(link_changeset_to_issue, link_changeset_to_pr_issues,
    link_pr_changesets_to_issues, link_issue_to_communication, link_issue_to_document)가 만든
    3종 엣지만 붙는다 — 유입 TRIGGERED_BY(ChangeSet→stub), 유출 DISCUSSED_IN(대화),
    DESCRIBED_IN(문서). 센티널 stub은 이벤트가 없어 refs.parentExternalId를 가질 수 없으므로
    CHILD_OF가 stub에 붙는 경로 자체가 없다.

    MATCH s로 시작하므로 멱등이다 — 이미 이관돼 stub이 없으면 전체가 no-op.
    """
    async with get_driver().session() as session:
        await session.run(
            """
            MATCH (s:Issue {project_id: $project_id, source: '__stub__', external_id: $issue_key})
            MATCH (real:Issue {project_id: $project_id, source: $source, external_id: $external_id})
            WITH s, real
            OPTIONAL MATCH (c:ChangeSet)-[:TRIGGERED_BY]->(s)
            WITH s, real, [x IN collect(DISTINCT c) WHERE x IS NOT NULL] AS triggering_changesets
            FOREACH (c IN triggering_changesets |
                MERGE (c)-[r2:TRIGGERED_BY]->(real)
                SET r2.source = 'text', r2.confidence = 1.0
            )
            WITH s, real
            OPTIONAL MATCH (s)-[:DISCUSSED_IN]->(comm:Communication)
            WITH s, real, [x IN collect(DISTINCT comm) WHERE x IS NOT NULL] AS discussed_comms
            FOREACH (comm IN discussed_comms |
                MERGE (real)-[r4:DISCUSSED_IN]->(comm)
                SET r4.source = 'text'
                REMOVE r4.confidence
            )
            WITH s, real
            OPTIONAL MATCH (s)-[:DESCRIBED_IN]->(doc:Document)
            WITH s, real, [x IN collect(DISTINCT doc) WHERE x IS NOT NULL] AS described_docs
            FOREACH (doc IN described_docs |
                MERGE (real)-[r5:DESCRIBED_IN]->(doc)
                SET r5.source = 'text', r5.confidence = 1.0
                REMOVE r5.section
            )
            WITH s
            DETACH DELETE s
            """,
            project_id=project_id,
            source=source,
            external_id=external_id,
            issue_key=issue_key,
        )


# ── issueExternalRefs / documentExternalRefs (실키 pre-node) ────────────
# Asana처럼 사람용 이슈 키가 없는 소스는 커밋/PR/Slack 텍스트의 태스크 URL에서
# (source, external_id) 쌍을 직접 추출한다. Notion 링크(§2-5)도 같은 "SOURCE:external_id"
# 인코딩을 쓴다. 이미 소스와 불변 ID를 알고 있으므로 link_changeset_to_issue류의
# 실노드/__stub__ 폴백이 필요 없다 — link_issue_to_parent(538)와 같은 방식으로
# (project_id, source, external_id) 실키에 곧바로 pre-node MERGE한다.
#
# Issue(TRIGGERED_BY/DISCUSSED_IN)와 Document(REFERENCE/DISCUSSED_IN)는 대상 라벨·관계
# 타입만 다를 뿐 나머지 Cypher·세션 구조가 완전히 같아, 아래 두 헬퍼를 두 라벨이 공유한다.
# 병합 변수명은 라벨 첫 글자를 그대로 쓴다(Issue→i, Document→d). node_label·rel_type은 이
# 모듈 안의 리터럴 상수만 호출부에서 넘긴다(사용자 입력이 Cypher 라벨·관계 타입 위치에
# 도달하지 않는다) — Cypher는 라벨·관계 타입을 파라미터로 바인딩할 수 없어 f-string으로 심는다.


def _parse_prefixed_refs(values: Optional[list[str]]) -> list[dict]:
    """"SOURCE:external_id" 콜론 결합 문자열 배열을 (source, external_id) 맵 리스트로 파싱한다.

    Neo4j 노드 속성은 맵 배열을 지원하지 않아 PullRequest.issue_external_ids ·
    PullRequest.document_external_ids가 공통으로 쓰는 인코딩이다(복합 id 관례). 콜론 첫
    등장을 기준으로 분리한다 — external_id(gid 등) 자체에 콜론이 섞여 있을 가능성을 배제하지
    않기 위함. 콜론이 없거나 source/external_id 중 하나라도 비면 그 원소는 건너뛴다(잘못된
    형식 방어).
    """
    refs = []
    for value in values or []:
        if not value or ":" not in value:
            continue
        source, _, external_id = value.partition(":")
        if not source or not external_id:
            continue
        refs.append({"source": source, "external_id": external_id})
    return refs


async def _link_changeset_to_external_ref(
    project_id: str, changeset_hash: str, ref_source: str, ref_external_id: str, *,
    node_label: str, rel_type: str,
) -> None:
    """{rel_type} (text): ChangeSet refs.{issue,document}ExternalRefs 존재 시.

    link_changeset_to_issue(297)와 달리 URL에서 추출한 참조는 소스와 불변 external_id를
    이미 알고 있어 실노드/__stub__ 폴백 2문이 필요 없다 — (project_id, source, external_id)
    실키로 곧바로 pre-node MERGE한다(link_issue_to_parent, 538과 동일한 메커니즘). 본
    이벤트가 나중에 도착하면 해당 upsert가 같은 키로 MERGE해 채운다.

    MATCH c로 시작해 커밋이 아직 없으면 고아 pre-node를 만들지 않는다.
    """
    var = node_label[0].lower()
    async with get_driver().session() as session:
        await session.run(
            f"""
            MATCH (c:ChangeSet {{project_id: $project_id, hash: $hash}})
            MERGE ({var}:{node_label} {{project_id: $project_id, source: $source, external_id: $external_id}})
            MERGE (c)-[r:{rel_type}]->({var})
            SET r.source = 'text', r.confidence = 1.0
            """,
            project_id=project_id,
            hash=changeset_hash,
            source=ref_source,
            external_id=ref_external_id,
        )


async def link_changeset_to_issue_external(
    project_id: str, changeset_hash: str, source: str, external_id: str,
) -> None:
    """TRIGGERED_BY (text): ChangeSet refs.issueExternalRefs 존재 시. 코어는
    _link_changeset_to_external_ref 공유."""
    await _link_changeset_to_external_ref(
        project_id, changeset_hash, source, external_id,
        node_label="Issue", rel_type="TRIGGERED_BY",
    )


async def link_changeset_to_document(
    project_id: str, changeset_hash: str, document_source: str, document_external_id: str,
) -> None:
    """REFERENCE (text): ChangeSet refs.documentExternalRefs 존재 시 — 커밋 메시지의 Notion
    URL. REFERENCE는 이 커넥터 전까지 전부 시맨틱 산물이었다(N0가 source 필드만 준비해
    뒀다) — 이게 그 첫 text 작성자다. 코어는 _link_changeset_to_external_ref 공유."""
    await _link_changeset_to_external_ref(
        project_id, changeset_hash, document_source, document_external_id,
        node_label="Document", rel_type="REFERENCE",
    )


async def _link_external_ref_to_communication(
    project_id: str, ref_source: str, ref_external_id: str, comm_url: str, *, node_label: str,
) -> None:
    """DISCUSSED_IN (text): Communication refs.{issue,document}ExternalRefs 존재 시.

    _link_changeset_to_external_ref와 동일한 이유로 실노드/__stub__ 구분 없이 실키로
    pre-node MERGE한다. DISCUSSED_IN의 text 엣지는 confidence를 갖지 않는다는 기존 규약
    (link_issue_to_communication, 498)을 그대로 따른다 — confidence는 시맨틱 엣지의 유사도
    점수이고 채점(eval)이 그 유무로 시맨틱을 판별한다. 시맨틱 엣지를 덮어쓸 때 남아 있던
    값을 REMOVE로 지우는 이유도 같다 — 안 지우면 'text' 표식 때문에 clear·백필이 둘 다
    건너뛰어 영구 잔존하고, 채점이 이 엣지를 시맨틱으로 오인한다.
    """
    var = node_label[0].lower()
    async with get_driver().session() as session:
        await session.run(
            f"""
            MATCH (comm:Communication {{project_id: $project_id, url: $comm_url}})
            MERGE ({var}:{node_label} {{project_id: $project_id, source: $source, external_id: $external_id}})
            MERGE ({var})-[r:DISCUSSED_IN]->(comm)
            SET r.source = 'text'
            REMOVE r.confidence
            """,
            project_id=project_id,
            comm_url=comm_url,
            source=ref_source,
            external_id=ref_external_id,
        )


async def link_issue_external_to_communication(
    project_id: str, source: str, external_id: str, comm_url: str,
) -> None:
    """DISCUSSED_IN (text): Communication refs.issueExternalRefs 존재 시. 코어는
    _link_external_ref_to_communication 공유."""
    await _link_external_ref_to_communication(
        project_id, source, external_id, comm_url, node_label="Issue",
    )


async def link_document_to_communication(
    project_id: str, document_source: str, document_external_id: str, communication_url: str,
) -> None:
    """DISCUSSED_IN (text): Communication refs.documentExternalRefs 존재 시 — 대화 본문의
    Notion URL. 코어는 _link_external_ref_to_communication 공유."""
    await _link_external_ref_to_communication(
        project_id, document_source, document_external_id, communication_url, node_label="Document",
    )


async def link_issue_to_document(
    project_id: str, issue_key: str, document_source: str, document_external_id: str,
) -> None:
    """DESCRIBED_IN (text): Document refs.issueKeys 존재 시.

    issue_key로 실노드를 먼저 찾고, 아직 수집되지 않았으면 기존 Issue stub 규약을 따른다.
    text가 semantic보다 우선하므로 confidence와 section 근거를 명시 참조 규약으로 교체한다.
    """
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (d:Document {
                project_id: $project_id, source: $document_source, external_id: $document_external_id
            })
            MATCH (i:Issue {project_id: $project_id, issue_key: $issue_key})
            WHERE i.source <> '__stub__'
            MERGE (i)-[r:DESCRIBED_IN]->(d)
            SET r.source = 'text', r.confidence = 1.0
            REMOVE r.section
            RETURN count(i) AS matched
            """,
            project_id=project_id,
            issue_key=issue_key,
            document_source=document_source,
            document_external_id=document_external_id,
        )
        record = await result.single()
        if record and record["matched"] > 0:
            return

        await session.run(
            """
            MATCH (d:Document {
                project_id: $project_id, source: $document_source, external_id: $document_external_id
            })
            MERGE (i:Issue {project_id: $project_id, source: '__stub__', external_id: $issue_key})
            ON CREATE SET i.issue_key = $issue_key
            MERGE (i)-[r:DESCRIBED_IN]->(d)
            SET r.source = 'text', r.confidence = 1.0
            REMOVE r.section
            """,
            project_id=project_id,
            issue_key=issue_key,
            document_source=document_source,
            document_external_id=document_external_id,
        )


async def link_issue_external_to_document(
    project_id: str,
    issue_source: str,
    issue_external_id: str,
    document_source: str,
    document_external_id: str,
) -> None:
    """DESCRIBED_IN (text): URL 기반 이슈 참조를 실키 pre-node로 연결한다."""
    async with get_driver().session() as session:
        await session.run(
            """
            MATCH (d:Document {
                project_id: $project_id, source: $document_source, external_id: $document_external_id
            })
            MERGE (i:Issue {
                project_id: $project_id, source: $issue_source, external_id: $issue_external_id
            })
            MERGE (i)-[r:DESCRIBED_IN]->(d)
            SET r.source = 'text', r.confidence = 1.0
            REMOVE r.section
            """,
            project_id=project_id,
            issue_source=issue_source,
            issue_external_id=issue_external_id,
            document_source=document_source,
            document_external_id=document_external_id,
        )


async def _link_changeset_to_pr_refs(
    project_id: str, pr_number: int, changeset_hash: str, *,
    pr_field: str, node_label: str, rel_type: str,
) -> int:
    """{rel_type} (text, 실키) 전파 — 단건: PR.{pr_field}를 '이 커밋 하나'에만 연결한다.

    link_changeset_to_pr_issues(357)와 대칭인 O(N) 단건 버전 — 커밋마다 PR 전체에
    재전파하면 O(N²)가 되므로, 이 함수는 그 커밋 하나만 연결한다. PR 전체 전파는 PR
    도착 시(_link_pr_changesets_to_refs)에만 수행한다.

    실키 pre-node라 실노드/stub 구분이 필요 없어(모듈 상단 절 설명 참고) issue_keys류와
    세션 2문의 목적이 다르다: ①에서 PR.{pr_field}를 조회하면서
    (pr)-[:CONTAINS]->(이 커밋) 관계도 함께 확인해 둘 중 하나라도 없으면 noop으로 끝낸다 →
    파싱은 Python에서(_parse_prefixed_refs) → ②에서 UNWIND한 각 참조를 pre-node
    MERGE하고 이 커밋에 관계를 건다.

    Returns:
        새로 생성 또는 갱신된 엣지 수.
    """
    var = node_label[0].lower()
    async with get_driver().session() as session:
        result = await session.run(
            f"""
            MATCH (pr:PullRequest {{project_id: $project_id, pr_number: $pr_number}})
            WHERE pr.{pr_field} IS NOT NULL AND size(pr.{pr_field}) > 0
            MATCH (pr)-[:CONTAINS]->(c:ChangeSet {{project_id: $project_id, hash: $changeset_hash}})
            RETURN pr.{pr_field} AS raw_refs
            """,
            project_id=project_id,
            pr_number=pr_number,
            changeset_hash=changeset_hash,
        )
        row = await result.single()
        if not row:
            return 0

        refs = _parse_prefixed_refs(row["raw_refs"])
        if not refs:
            return 0

        result2 = await session.run(
            f"""
            MATCH (c:ChangeSet {{project_id: $project_id, hash: $changeset_hash}})
            UNWIND $refs AS ref
            MERGE ({var}:{node_label} {{project_id: $project_id, source: ref.source, external_id: ref.external_id}})
            MERGE (c)-[r:{rel_type}]->({var})
            SET r.source = 'text', r.confidence = 1.0
            RETURN count(r) AS created
            """,
            project_id=project_id,
            changeset_hash=changeset_hash,
            refs=refs,
        )
        row2 = await result2.single()
        return row2["created"] if row2 else 0


async def link_changeset_to_pr_issue_externals(project_id: str, pr_number: int, changeset_hash: str) -> int:
    """TRIGGERED_BY (text, 실키) 전파 — 단건. 코어는 _link_changeset_to_pr_refs 공유."""
    return await _link_changeset_to_pr_refs(
        project_id, pr_number, changeset_hash,
        pr_field="issue_external_ids", node_label="Issue", rel_type="TRIGGERED_BY",
    )


async def link_changeset_to_pr_documents(project_id: str, pr_number: int, changeset_hash: str) -> int:
    """REFERENCE (text, 실키) 전파 — 단건. link_changeset_to_pr_issue_externals와 완전히
    대칭 — 코어는 _link_changeset_to_pr_refs 공유."""
    return await _link_changeset_to_pr_refs(
        project_id, pr_number, changeset_hash,
        pr_field="document_external_ids", node_label="Document", rel_type="REFERENCE",
    )


async def _link_pr_changesets_to_refs(
    project_id: str, pr_number: int, *, pr_field: str, node_label: str, rel_type: str,
) -> int:
    """{rel_type} (text, 실키) 전파: PR.{pr_field}에 등록된 각 참조를 그 PR이 머지한 모든
    ChangeSet에 동일하게 연결한다.

    실키 pre-node라 실노드/stub 구분이 없다는 차이만 있고, 세션 2문 구조(① 조회+Python
    파싱 → ② UNWIND 반영)는 _link_changeset_to_pr_refs와 동일하다.

    ①에서 CONTAINS 커밋 존재까지 확인한다 — PR 이벤트가 자기 커밋들보다 먼저 소비되는
    정상 순서(수집기가 PR을 먼저 발행)에서는 이 시점에 연결할 커밋이 없으므로, 건너뛰어야
    엣지 없는 pre-node가 먼저 생기지 않는다. 커밋이 도착하면 단건 전파
    (_link_changeset_to_pr_refs)가 pre-node 생성과 연결을 함께 수행하므로 잃는 링크는 없다.

    Returns:
        새로 생성 또는 갱신된 엣지 수(근사치 — 참조 수 × CONTAINS 커밋 수).
    """
    var = node_label[0].lower()
    async with get_driver().session() as session:
        result = await session.run(
            f"""
            MATCH (pr:PullRequest {{project_id: $project_id, pr_number: $pr_number}})
            WHERE pr.{pr_field} IS NOT NULL AND size(pr.{pr_field}) > 0
              AND EXISTS {{ (pr)-[:CONTAINS]->(:ChangeSet) }}
            RETURN pr.{pr_field} AS raw_refs
            """,
            project_id=project_id,
            pr_number=pr_number,
        )
        row = await result.single()
        if not row:
            return 0

        refs = _parse_prefixed_refs(row["raw_refs"])
        if not refs:
            return 0

        result2 = await session.run(
            f"""
            MATCH (pr:PullRequest {{project_id: $project_id, pr_number: $pr_number}})
            OPTIONAL MATCH (pr)-[:CONTAINS]->(cs:ChangeSet)
            WITH [x IN collect(DISTINCT cs) WHERE x IS NOT NULL] AS changesets
            UNWIND $refs AS ref
            MERGE ({var}:{node_label} {{project_id: $project_id, source: ref.source, external_id: ref.external_id}})
            FOREACH (c IN changesets |
                MERGE (c)-[r:{rel_type}]->({var})
                SET r.source = 'text', r.confidence = 1.0
            )
            RETURN sum(size(changesets)) AS created
            """,
            project_id=project_id,
            pr_number=pr_number,
            refs=refs,
        )
        row2 = await result2.single()
        return row2["created"] if row2 else 0


async def link_pr_changesets_to_issue_externals(project_id: str, pr_number: int) -> int:
    """TRIGGERED_BY (text, 실키) 전파: PR.issue_external_ids 전체를 CONTAINS 커밋에 전파.
    코어는 _link_pr_changesets_to_refs 공유."""
    return await _link_pr_changesets_to_refs(
        project_id, pr_number,
        pr_field="issue_external_ids", node_label="Issue", rel_type="TRIGGERED_BY",
    )


async def link_pr_changesets_to_documents(project_id: str, pr_number: int) -> int:
    """REFERENCE (text, 실키) 전파: PR.document_external_ids 전체를 CONTAINS 커밋에 전파 —
    link_pr_changesets_to_issue_externals와 완전히 대칭이다. PR 본문에 박힌 Notion 링크가
    커밋 메시지보다 흔하므로(§2-5) 이 전파가 Document REFERENCE의 주 유입로다. 코어는
    _link_pr_changesets_to_refs 공유."""
    return await _link_pr_changesets_to_refs(
        project_id, pr_number,
        pr_field="document_external_ids", node_label="Document", rel_type="REFERENCE",
    )
