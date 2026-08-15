"""시맨틱 Document 링크(REFERENCE ChangeSet→Document / DESCRIBED_IN Issue→Document)용
Neo4j DocumentLinkStore 어댑터.

graph.document_linker.DocumentLinkStore에 주입할 Neo4j 조회/생성 콜백을 제공한다.
ChangeSet·Issue 임베딩 조회는 reference_store·issue_link_store의 전체(필터 없음) 버전을
재사용한다 — text 배제는 이 스토어의 fetch 단계가 아니라 write 시점에 쌍 단위로 한다.
"""

from graph.driver import get_driver
from graph.issue_link_store import _fetch_issue_embeddings
from graph.reference_store import _fetch_modified_embeddings


# ── DocumentLinkStore Neo4j 구현체 ────────────────────────────────────────


async def _fetch_documents(project_id: str | None = None) -> list[dict]:
    """Document 메타(시간 윈도우 계산용) 반환.

    id는 (project_id, source, external_id) 복합 MERGE 키를 "{SOURCE}:{external_id}" 형태의
    불투명 문자열로 인코딩한 것 — Issue의 관례와 동일. createdAt이 없으면(구버전 이벤트) 재수집
    직후에도 링크를 시도할 수 있게 occurredAt으로 폴백한다.
    """
    query = """
        MATCH (d:Document)
        WHERE d.occurredAt IS NOT NULL
        __PROJECT_FILTER__
        RETURN d.project_id AS project_id,
               d.source + ':' + d.external_id AS id,
               coalesce(d.createdAt, d.occurredAt) AS created_at
    """.replace("__PROJECT_FILTER__", "AND d.project_id = $project_id" if project_id else "")
    async with get_driver().session() as session:
        result = await session.run(query, project_id=project_id)
        rows = await result.data()
    return [
        {
            "project_id": r["project_id"],
            "id":         r["id"],
            "created_at": r["created_at"].to_native(),
        }
        for r in rows
    ]


async def _fetch_document_sections(project_id: str | None = None) -> list[dict]:
    """embedding이 있는 모든 DocumentSection 반환.

    document_id는 소속 Document와 같은 복합 식별자로 인코딩해 _select_document_pairs가
    Document 메타(윈도우)와 조인할 수 있게 한다.
    """
    query = """
        MATCH (s:DocumentSection)
        WHERE s.embedding IS NOT NULL
        __PROJECT_FILTER__
        RETURN s.project_id AS project_id,
               s.source + ':' + s.document_external_id AS document_id,
               s.heading_path AS heading_path,
               s.embedding AS embedding
    """.replace("__PROJECT_FILTER__", "AND s.project_id = $project_id" if project_id else "")
    async with get_driver().session() as session:
        result = await session.run(query, project_id=project_id)
        rows = await result.data()
    return [
        {
            "project_id":   r["project_id"],
            "document_id":  r["document_id"],
            "heading_path": r["heading_path"] or "",
            "embedding":    list(r["embedding"]),
        }
        for r in rows
    ]


async def _create_document_reference_edge(
    project_id: str, changeset_id: str, document_id: str, confidence: float, section: str
) -> None:
    """REFERENCE (ChangeSet→Document, semantic): 임베딩 매칭으로 발견된 연결.

    source='text' 인 쌍은 이미 명시 URL 참조로 확정된 것이므로 시맨틱 결과가 덮어쓰지 못하도록
    쌍 단위로 배제한다(reference_store._create_reference_edge와 동일 패턴) — TRIGGERED_BY처럼
    ChangeSet 전체를 후보에서 빼지 않는다. 같은 커밋이 다른 문서엔 여전히 semantic으로 붙을 수
    있어야 하기 때문이다.
    """
    source, external_id = document_id.split(":", 1)
    async with get_driver().session() as session:
        await session.run(
            """
            MATCH (c:ChangeSet {project_id: $project_id, hash: $changeset_id})
            MATCH (d:Document {project_id: $project_id, source: $source, external_id: $external_id})
            WHERE NOT (c)-[:REFERENCE {source: 'text'}]->(d)
            MERGE (c)-[r:REFERENCE]->(d)
            SET r.source = 'semantic', r.confidence = $confidence, r.section = $section
            """,
            project_id=project_id,
            changeset_id=changeset_id,
            source=source,
            external_id=external_id,
            confidence=confidence,
            section=section,
        )


async def _create_described_in_document_edge(
    project_id: str, issue_id: str, document_id: str, confidence: float, section: str
) -> None:
    """DESCRIBED_IN (Issue→Document, semantic): 임베딩 매칭으로 발견된 연결.

    _create_document_reference_edge와 동일한 이유로 쌍 단위 text 배제를 쓴다.
    """
    issue_source, issue_external_id = issue_id.split(":", 1)
    doc_source, doc_external_id = document_id.split(":", 1)
    async with get_driver().session() as session:
        await session.run(
            """
            MATCH (i:Issue {project_id: $project_id, source: $issue_source, external_id: $issue_external_id})
            MATCH (d:Document {project_id: $project_id, source: $doc_source, external_id: $doc_external_id})
            WHERE NOT (i)-[:DESCRIBED_IN {source: 'text'}]->(d)
            MERGE (i)-[r:DESCRIBED_IN]->(d)
            SET r.source = 'semantic', r.confidence = $confidence, r.section = $section
            """,
            project_id=project_id,
            issue_source=issue_source,
            issue_external_id=issue_external_id,
            doc_source=doc_source,
            doc_external_id=doc_external_id,
            confidence=confidence,
            section=section,
        )


def make_neo4j_document_link_store(project_id: str | None = None):
    """Neo4j 기반 DocumentLinkStore 인스턴스를 반환한다.

    project_id를 주면 fetch가 그 프로젝트 노드만 조회한다(per-project 빌드).
    None이면 전체 프로젝트를 조회한다(운영 일괄 트리거).
    """
    from graph.document_linker import DocumentLinkStore
    return DocumentLinkStore(
        fetch_documents=lambda: _fetch_documents(project_id),
        fetch_document_sections=lambda: _fetch_document_sections(project_id),
        fetch_modified_embeddings=lambda: _fetch_modified_embeddings(project_id),
        fetch_issue_embeddings=lambda: _fetch_issue_embeddings(project_id),
        create_reference_edge=_create_document_reference_edge,
        create_described_in_edge=_create_described_in_document_edge,
    )
