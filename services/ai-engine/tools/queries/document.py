"""문서(Document, Notion) 컨텍스트 조회 — 문서 상세, 시맨틱 검색.

DESCRIBED_IN(Issue→Document)·REFERENCE(ChangeSet→Document)는 항상 source가 명시적으로
SET돼 있다(Notion 도입 시점부터 존재한 관계라 레거시 source-less 엣지가 없다) — 다른 도구가
쓰는 coalesce(r.source, ...) 폴백이 여기선 필요 없다.
"""

from tools.queries._common import (
    _MIN_CONFIDENCE,
    _VECTOR_OVERFETCH,
    _VECTOR_OVERFETCH_CAP,
    _group_communications_by_thread,
    get_driver,
)


async def _resolve_document_root(
    session, project_id: str, external_id: str, source: str | None
) -> list[dict]:
    """external_id로 매칭되는 Document 후보를 소스별로 전수 조회한다.

    Document 자연키가 (project_id, source, external_id)라 소스가 둘 이상 붙으면(현재는
    Notion 하나뿐이지만 Confluence·Google Docs 등이 늘 수 있다 — docs/notion-integration.md
    §2-1) 같은 external_id가 우연히 겹칠 수 있다. get_issue_context._resolve_issue_root와
    동일한 후보-소독 패턴이다.
    """
    params = {"project_id": project_id, "external_id": external_id}
    query = "MATCH (d:Document {project_id: $project_id, external_id: $external_id})"
    if source:
        query += " WHERE d.source = $source"
        params["source"] = source.upper()
    query += " RETURN d.source AS source, d.title AS title"
    result = await session.run(query, **params)
    return await result.data()


async def get_document_context(project_id: str, external_id: str, source: str | None = None) -> dict:
    """문서 단일 external_id로 본문·작성자·편집자·연결된 이슈/커밋/대화를 반환.

    반환 구조:
      {
        title, body, url, createdAt, occurredAt, parent_type, parent_external_id,
        author, editors: [...],
        issues:        [{issue_key, title, source, confidence, section}, ...],
        changesets:    [{hash, message, occurredAt, author, source, confidence, section}, ...],
        discussions:   [{body, channel, source, occurredAt, conversation_id, author}, ...] (스레드 그룹핑)
      }

    필터 정책:
      - semantic 엣지는 confidence >= _MIN_CONFIDENCE만 통과(텍스트 참조는 confidence=1.0로 항상 통과)
      - source('text'|'semantic')·section(semantic만)을 그대로 노출해 LLM이 명시 참조와
        추론을 구분하게 한다(§2-5) — text를 "문서에 명시됨", semantic을 "관련 문서로 추론됨"으로
        단정하지 않고 답변에 반영해야 한다
    """
    async with get_driver().session() as session:
        candidates = await _resolve_document_root(session, project_id, external_id, source)
        if not candidates:
            return {"message": f"문서를 찾을 수 없습니다: {external_id}"}
        if len(candidates) > 1:
            return {
                "candidates": candidates,
                "message": "이 id에 해당하는 문서가 여러 소스에 있습니다. source를 지정해 다시 호출하세요.",
            }
        resolved_source = candidates[0]["source"]

        result = await session.run(
            """
            MATCH (d:Document {project_id: $project_id, source: $source, external_id: $external_id})
            OPTIONAL MATCH (author:Actor)-[:WROTE]->(d)
            OPTIONAL MATCH (editor:Actor)-[:EDITED]->(d)
            RETURN d.title AS title, d.body AS body, d.url AS url,
                   toString(d.createdAt) AS createdAt, toString(d.occurredAt) AS occurredAt,
                   d.parent_type AS parent_type, d.parent_external_id AS parent_external_id,
                   author.name AS author,
                   collect(DISTINCT editor.name) AS editors
            """,
            project_id=project_id, source=resolved_source, external_id=external_id,
        )
        row = await result.single()
        if not row:
            return {"message": f"문서를 찾을 수 없습니다: {external_id}"}
        base = dict(row)
        base["editors"] = [e for e in base["editors"] if e is not None]

        # 연결된 이슈 — DESCRIBED_IN 유입(text+semantic)
        result = await session.run(
            """
            MATCH (d:Document {project_id: $project_id, source: $source, external_id: $external_id})
            OPTIONAL MATCH (i:Issue)-[r:DESCRIBED_IN]->(d)
            WHERE i.source <> '__stub__' AND (r.source = 'text' OR r.confidence >= $min_conf)
            RETURN collect(DISTINCT {
                issue_key: i.issue_key, title: i.title,
                source: r.source, confidence: r.confidence, section: r.section
            }) AS issues
            """,
            project_id=project_id, source=resolved_source, external_id=external_id,
            min_conf=_MIN_CONFIDENCE,
        )
        issues_row = await result.single()
        base["issues"] = [it for it in issues_row["issues"] if it.get("issue_key") is not None]

        # 연결된 커밋 — REFERENCE 유입(text+semantic)
        result = await session.run(
            """
            MATCH (d:Document {project_id: $project_id, source: $source, external_id: $external_id})
            OPTIONAL MATCH (cs:ChangeSet)-[r:REFERENCE]->(d)
            WHERE r.source = 'text' OR r.confidence >= $min_conf
            OPTIONAL MATCH (cs_author:Actor)-[:AUTHORED]->(cs)
            RETURN collect(DISTINCT {
                hash: cs.hash, message: cs.message, occurredAt: toString(cs.occurredAt),
                author: cs_author.name, source: r.source, confidence: r.confidence, section: r.section
            }) AS changesets
            """,
            project_id=project_id, source=resolved_source, external_id=external_id,
            min_conf=_MIN_CONFIDENCE,
        )
        changesets_row = await result.single()
        base["changesets"] = [cs for cs in changesets_row["changesets"] if cs.get("hash") is not None]

        # 연결된 대화 — DISCUSSED_IN 유입(text만, 이 관계엔 semantic 변형이 없다 — §2-5)
        result = await session.run(
            """
            MATCH (d:Document {project_id: $project_id, source: $source, external_id: $external_id})
            OPTIONAL MATCH (d)-[:DISCUSSED_IN]->(c:Communication)
            OPTIONAL MATCH (c_author:Actor)-[:WROTE]->(c)
            RETURN collect(DISTINCT {
                body: c.body, channel: c.channel, source: c.source,
                occurredAt: toString(c.occurredAt), conversation_id: c.conversation_id,
                author: c_author.name
            }) AS discussions
            """,
            project_id=project_id, source=resolved_source, external_id=external_id,
        )
        discussions_row = await result.single()
        discussions = [d for d in discussions_row["discussions"] if d.get("body") is not None]
        base["discussions"] = _group_communications_by_thread(discussions)

        return base


async def search_documents(
    project_id: str, embedding: list[float], top_k: int = 5, threshold: float = 0.30
) -> list[dict]:
    """DocumentSection 벡터 검색 — 매칭은 섹션 단위, 반환은 문서 + 최고점 매칭 섹션 발췌.

    문서 전체가 아니라 매칭 섹션의 발췌만 반환한다 — 문서가 수만 자일 수 있어 통째로
    돌려주면 그것만으로 컨텍스트를 다 잡아먹는다(docs/notion-integration.md §6-4). 문서 본문
    전체가 필요하면 get_document_context를 별도로 호출한다.

    같은 문서의 여러 섹션이 매칭되면 최고점 섹션 하나만 대표로 남긴다(discovery.
    search_by_keyword가 같은 스레드의 여러 메시지를 dedupe하는 것과 같은 목적).
    """
    fetch_k = min(top_k * _VECTOR_OVERFETCH, _VECTOR_OVERFETCH_CAP)
    async with get_driver().session() as session:
        result = await session.run(
            """
            CALL db.index.vector.queryNodes('doc_section_embedding', $fetch_k, $embedding)
            YIELD node AS s, score
            WHERE score >= $threshold AND s.project_id = $project_id
            MATCH (s)-[:PART_OF]->(d:Document)
            RETURN d.source AS source, d.external_id AS external_id, d.title AS title,
                   d.url AS url, s.heading_path AS section, left(s.text, 500) AS excerpt,
                   score
            ORDER BY score DESC
            """,
            project_id=project_id,
            embedding=embedding,
            fetch_k=fetch_k,
            threshold=threshold,
        )
        rows = await result.data()

        # 쿼리가 이미 score DESC로 정렬해 주지만, 여기서도 max로 뽑아 정렬 순서에
        # 기대지 않는다 — 쿼리가 나중에 바뀌어도(예: ORDER BY 제거) 조용히 낮은 점수
        # 섹션이 대표로 남는 사고를 막는다.
        best: dict[tuple[str, str], dict] = {}
        for r in rows:
            key = (r["source"], r["external_id"])
            if key not in best or r["score"] > best[key]["score"]:
                best[key] = r
        deduped = sorted(best.values(), key=lambda r: r["score"], reverse=True)[:top_k]

        if not deduped:
            return [{"message": "유사한 문서를 찾지 못했습니다. threshold를 낮추거나 다른 질의를 시도하세요."}]
        return deduped
