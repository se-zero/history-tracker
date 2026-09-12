"""코드 변경 컨텍스트 조회 — 커밋(ChangeSet)·PR·충돌·맥락 누락 커밋."""

from tools.queries._common import (
    _MIN_CONFIDENCE,
    _group_communications_by_thread,
    get_driver,
)


# 짧은 해시 접두어 거부 최소 길이 — 이보다 짧으면 후보가 폭주해 candidates가 무의미해진다.
_MIN_HASH_PREFIX_LEN = 7
# Git SHA-1 전체 길이. 이 길이로 들어오면 접두어 해석이 필요 없다.
_FULL_HASH_LEN = 40
# candidates로 되돌릴 후보 상한. 잘림을 알리려면 상한보다 1건 더 조회해야 한다.
_HASH_CANDIDATE_LIMIT = 6


def _first_line(text: str | None) -> str | None:
    """candidates 목록에 커밋 본문 전체가 실리지 않도록 첫 줄만 남긴다."""
    if not text:
        return None
    return text.splitlines()[0]


async def _resolve_changeset_hash(session, project_id: str, hash: str) -> list[dict]:
    """짧은 해시(접두어)로 매칭되는 ChangeSet 후보를 조회한다.

    사용자·시스템 프롬프트가 커밋을 앞 7자 해시로 지칭하는데 본 쿼리(get_changeset_context·
    get_conflict_context)는 정확 일치라 "커밋을 찾을 수 없습니다"로 떨어지던 문제 — STARTS WITH로
    접두어를 허용하고, 후보가 여러 개면 호출부가 candidates 중 전체 hash로 재호출하게 한다.
    """
    result = await session.run(
        """
        MATCH (cs:ChangeSet {project_id: $project_id})
        WHERE cs.hash STARTS WITH $hash
        RETURN cs.hash AS hash, cs.message AS message, toString(cs.occurredAt) AS occurredAt
        LIMIT $limit
        """,
        project_id=project_id,
        hash=hash,
        limit=_HASH_CANDIDATE_LIMIT + 1,
    )
    return await result.data()


def _hash_candidates_response(candidates: list[dict]) -> dict:
    """후보 목록 응답. 상한을 넘겼으면 잘렸다는 사실을 함께 알린다 —
    고지가 없으면 모델이 목록을 전부로 믿고 진짜 대상이 빠진 채 고른다."""
    message = "앞자리가 겹치는 커밋이 여러 개입니다. candidates 중 하나의 전체 hash로 다시 호출하세요."
    if len(candidates) > _HASH_CANDIDATE_LIMIT:
        candidates = candidates[:_HASH_CANDIDATE_LIMIT]
        message += f" 후보가 {_HASH_CANDIDATE_LIMIT}건을 넘어 일부만 표시했습니다 — 해시를 더 길게 지정하세요."
    return {
        "message": message,
        "candidates": [
            {"hash": c["hash"], "message": _first_line(c["message"]), "occurredAt": c["occurredAt"]}
            for c in candidates
        ],
    }


async def _resolve_single_hash(session, project_id: str, hash: str) -> tuple[str | None, dict | None]:
    """해시를 단일 커밋으로 해석한다. 반환은 (전체 hash, 조기 반환 응답) — 둘 중 하나만 non-None.

    전체 해시(40자)면 조회 없이 그대로 쓴다 — 타임라인·파일 이력이 넘기는 값이 이 형태라 가장
    흔한 경로이고, 왕복 한 번과 STARTS WITH 스캔을 아낀다(복합 인덱스가 접두어 조건을 못 타면
    프로젝트 전체 ChangeSet 스캔이 된다). 없는 전체 해시는 본 쿼리가 빈 결과로 걸러
    기존 "찾을 수 없습니다" 문구로 떨어진다.
    """
    if len(hash) == _FULL_HASH_LEN:
        return hash, None
    candidates = await _resolve_changeset_hash(session, project_id, hash)
    if not candidates:
        return None, {"message": f"커밋을 찾을 수 없습니다: {hash}"}
    if len(candidates) > 1:
        return None, _hash_candidates_response(candidates)
    return candidates[0]["hash"], None


async def get_changeset_context(project_id: str, hash: str) -> dict:
    if len(hash) < _MIN_HASH_PREFIX_LEN:
        return {"message": f"커밋 해시는 앞 7자 이상으로 지정하세요: {hash}"}
    async with get_driver().session() as session:
        resolved_hash, early_return = await _resolve_single_hash(session, project_id, hash)
        if early_return is not None:
            return early_return

        result = await session.run(
            """
            MATCH (cs:ChangeSet {project_id: $project_id, hash: $hash})
            MATCH (a:Actor)-[:AUTHORED]->(cs)
            OPTIONAL MATCH (cs)-[tb:TRIGGERED_BY]->(i:Issue)
                WHERE coalesce(tb.confidence, 1.0) >= $min_conf AND i.source <> '__stub__'
            OPTIONAL MATCH (cs)-[ref:REFERENCE]->(c:Communication)
            OPTIONAL MATCH (c_author:Actor)-[:WROTE]->(c)
            OPTIONAL MATCH (cs)-[docref:REFERENCE]->(d:Document)
            OPTIONAL MATCH (pr:PullRequest)-[:CONTAINS]->(cs)
            OPTIONAL MATCH (cs)-[m:MODIFIED]->(f:File)
            RETURN cs.hash AS hash,
                   cs.message AS commit_message,
                   toString(cs.occurredAt) AS occurredAt,
                   a.name AS author,
                   collect(DISTINCT {
                       issue_key: i.issue_key, title: i.title,
                       body: i.body, status: i.status,
                       confidence: tb.confidence,
                       link_source: tb.source
                   }) AS issues,
                   collect(DISTINCT {
                       body: c.body, channel: c.channel, source: c.source,
                       occurredAt: toString(c.occurredAt),
                       conversation_id: c.conversation_id,
                       author: c_author.name,
                       confidence: ref.confidence
                   }) AS communications,
                   collect(DISTINCT {
                       external_id: d.external_id, title: d.title, url: d.url, source: d.source,
                       confidence: docref.confidence
                   }) AS documents,
                   {pr_number: pr.pr_number, title: pr.title, url: pr.url} AS pull_request,
                   collect(DISTINCT {path: f.path, diffSummary: m.diffSummary}) AS file_changes
            """,
            project_id=project_id,
            hash=resolved_hash,
            min_conf=_MIN_CONFIDENCE,
        )
        row = await result.single()
        if not row:
            return {"message": f"커밋을 찾을 수 없습니다: {hash}"}
        out = dict(row)
        # Slack 스레드 경계 보존 — communications를 conversation_id별로 그룹핑.
        out["communications"] = _group_communications_by_thread(out.get("communications") or [])
        return out

async def check_missing_context(
    project_id: str,
    from_time: str | None = None,
    to_time: str | None = None,
    limit: int = 50,
) -> list[dict]:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (cs:ChangeSet {project_id: $project_id})
            WHERE NOT EXISTS {
                MATCH (cs)-[tb:TRIGGERED_BY]->(:Issue)
                WHERE coalesce(tb.confidence, 1.0) >= $min_conf
              }
              AND NOT (cs)-[:REFERENCE]->(:Communication)
              AND NOT (cs)-[:REFERENCE]->(:Document)
              AND ($from_time IS NULL OR cs.occurredAt >= datetime($from_time))
              AND ($to_time IS NULL OR cs.occurredAt <= datetime($to_time))
            MATCH (a:Actor)-[:AUTHORED]->(cs)
            OPTIONAL MATCH (cs)-[:MODIFIED]->(f:File)
            RETURN cs.hash AS hash,
                   cs.message AS message,
                   toString(cs.occurredAt) AS occurredAt,
                   a.name AS author,
                   collect(f.path) AS files
            ORDER BY cs.occurredAt DESC
            LIMIT $limit
            """,
            project_id=project_id,
            from_time=from_time,
            to_time=to_time,
            limit=limit,
            min_conf=_MIN_CONFIDENCE,
        )
        rows = await result.data()
        if not rows:
            return [{"message": "컨텍스트 없는 커밋이 없습니다."}]
        return rows

async def get_conflict_context(project_id: str, hash: str) -> dict:
    if len(hash) < _MIN_HASH_PREFIX_LEN:
        return {"message": f"커밋 해시는 앞 7자 이상으로 지정하세요: {hash}"}
    async with get_driver().session() as session:
        resolved_hash, early_return = await _resolve_single_hash(session, project_id, hash)
        if early_return is not None:
            return early_return

        result = await session.run(
            """
            MATCH (cs:ChangeSet {project_id: $project_id, hash: $hash})
            OPTIONAL MATCH (cs)-[tb:TRIGGERED_BY]->(i:Issue)
                WHERE coalesce(tb.confidence, 1.0) >= $min_conf AND i.source <> '__stub__'
            OPTIONAL MATCH (cs)-[ref:REFERENCE]->(c:Communication)
            OPTIONAL MATCH (c_author:Actor)-[:WROTE]->(c)
            OPTIONAL MATCH (cs)-[docref:REFERENCE]->(d:Document)
            OPTIONAL MATCH (pr:PullRequest)-[:CONTAINS]->(cs)
            OPTIONAL MATCH (cs)-[m:MODIFIED]->(f:File)
            RETURN cs.hash AS hash,
                   cs.message AS commit_message,
                   toString(cs.occurredAt) AS occurredAt,
                   collect(DISTINCT {
                       source: i.source,
                       id: i.issue_key,
                       text: i.title + '\n' + coalesce(i.body, ''),
                       confidence: tb.confidence,
                       link_source: tb.source
                   }) AS issue_contexts,
                   collect(DISTINCT {
                       source: c.source,
                       channel: c.channel,
                       conversation_id: c.conversation_id,
                       body: c.body,
                       author: c_author.name,
                       occurredAt: toString(c.occurredAt),
                       confidence: ref.confidence
                   }) AS comm_contexts,
                   collect(DISTINCT {
                       source: 'GitHub PR',
                       id: toString(pr.pr_number),
                       text: pr.title + '\n' + coalesce(pr.body, ''),
                       confidence: 1.0
                   }) AS pr_contexts,
                   collect(DISTINCT {
                       source: d.source,
                       id: d.external_id,
                       text: d.title + '\n' + left(coalesce(d.body, ''), 500),
                       confidence: docref.confidence
                   }) AS doc_contexts,
                   collect(DISTINCT {path: f.path, diff_summary: m.diffSummary}) AS file_changes
            """,
            project_id=project_id,
            hash=resolved_hash,
            min_conf=_MIN_CONFIDENCE,
        )
        row = await result.single()
        if not row:
            return {"message": f"커밋을 찾을 수 없습니다: {hash}"}
        out = dict(row)
        # Slack 스레드 경계 보존. (기존 comm_contexts는 text 키로 본문을 노출했지만,
        # 그룹핑 결과에서는 messages[*].body로 정규화 — _group_communications_by_thread가
        # GROUP_KEYS 외 모든 필드를 메시지 dict에 그대로 넘김.)
        out["comm_contexts"] = _group_communications_by_thread(out.get("comm_contexts") or [])
        return out

async def get_pr_context(project_id: str, pr_number: int) -> dict:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (pr:PullRequest {project_id: $project_id, pr_number: $pr_number})
            OPTIONAL MATCH (author:Actor)-[:AUTHORED]->(pr)
            OPTIONAL MATCH (pr)-[:CONTAINS]->(cs:ChangeSet)
            OPTIONAL MATCH (cs_author:Actor)-[:AUTHORED]->(cs)
            OPTIONAL MATCH (cs)-[tb:TRIGGERED_BY]->(i:Issue)
                WHERE coalesce(tb.confidence, 1.0) >= $min_conf AND i.source <> '__stub__'
            OPTIONAL MATCH (cs)-[ref:REFERENCE]->(c:Communication)
            OPTIONAL MATCH (c_author:Actor)-[:WROTE]->(c)
            OPTIONAL MATCH (cs)-[docref:REFERENCE]->(d:Document)
            OPTIONAL MATCH (cs)-[m:MODIFIED]->(f:File)
            RETURN pr.pr_number AS pr_number,
                   pr.title AS title,
                   pr.body AS body,
                   toString(pr.occurredAt) AS merged_at,
                   toString(pr.createdAt) AS created_at,
                   pr.url AS url,
                   author.name AS author,
                   collect(DISTINCT {
                       hash: cs.hash, message: cs.message,
                       occurredAt: toString(cs.occurredAt),
                       author: cs_author.name
                   }) AS changesets,
                   collect(DISTINCT {
                       issue_key: i.issue_key, title: i.title,
                       status: i.status,
                       confidence: tb.confidence,
                       link_source: tb.source
                   }) AS issues,
                   collect(DISTINCT {
                       body: c.body, channel: c.channel, source: c.source,
                       occurredAt: toString(c.occurredAt),
                       conversation_id: c.conversation_id,
                       author: c_author.name, confidence: ref.confidence
                   }) AS discussions,
                   collect(DISTINCT {
                       external_id: d.external_id, title: d.title, url: d.url, source: d.source,
                       confidence: docref.confidence
                   }) AS documents,
                   collect(DISTINCT {path: f.path, diff_summary: m.diffSummary}) AS file_changes
            """,
            project_id=project_id,
            pr_number=pr_number,
            min_conf=_MIN_CONFIDENCE,
        )
        row = await result.single()
        if not row:
            return {"message": f"PR을 찾을 수 없습니다: #{pr_number}"}
        out = dict(row)
        out["discussions"] = _group_communications_by_thread(out.get("discussions") or [])
        return out
