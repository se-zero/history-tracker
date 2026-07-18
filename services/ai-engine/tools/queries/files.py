"""파일 변경 이력 조회 — strict 매칭 + fuzzy path fallback + 관련도 기반 2계층 반환."""

import os

from tools.queries._common import (
    _MIN_CONFIDENCE,
    _detail_count_for_budget as _budget_count,
    _priority_order,
    get_driver,
)


_FUZZY_CANDIDATE_LIMIT = 5      # candidates 리스트에 노출할 최대 후보 수
_DIFF_SUMMARY_MAX_CHARS = 300   # detail 행당 diffSummary 상한 — executor 결과 상한(8000자)에 이력 행이 잘려나가는 것 방지
_DETAIL_MESSAGE_MAX_CHARS = 400 # detail 행당 커밋 메시지 상한 — 8행이 상한을 넘겨 string-cut(JSON 파손)으로 떨어지는 것 방지
_STUB_TITLE_MAX_CHARS = 100     # context stub의 요약(커밋 메시지 첫 줄) 상한

# 반환 정책 노브 (eval 스윕용 env 외부화 — TOOLS_MIN_CONFIDENCE 선례와 동일).
#   detail : 본문 포함 인용 대상 — 관련도 순으로 "예산이 되는 만큼" 채운다
#   context: 나머지 이력 — hash·요약만(개요·드릴다운용, 본문 없어 대량 인용 불가)
# detail 개수를 고정하지 않고 **바이트 예산**으로 채운다: 다 담아도 예산에 맞는 파일은
# 전량 detail(구 동작 = 전량 인용 가능 유지 → 나열형 질문 recall 보존), 예산을 넘는
# 파일만 관련도 상위를 detail로 올리고 나머지를 stub으로 내린다. 고정 detail_k는
# "다 담기는 파일"의 인용 폭까지 좁혀 나열형 recall을 깎았다(case-11).
_DETAIL_BUDGET = int(os.environ.get("FILE_HISTORY_DETAIL_BUDGET", "6500"))  # detail 행 직렬화 합 상한(자) — 나머지는 metadata·stub 여유
_DETAIL_K_MAX = int(os.environ.get("FILE_HISTORY_DETAIL_MAX", "30"))        # detail 행 수 하드 상한(예산과 무관한 안전 캡)
_CONTEXT_CAP = int(os.environ.get("FILE_HISTORY_CONTEXT_CAP", "40"))
_MAX_COMMITS = int(os.environ.get("FILE_HISTORY_MAX_COMMITS", "200"))       # 관련도 산정 대상 상한(최신순)

# 인용 앵커 문구 — 2계층 전환 풀 런(2026-07-17)에서 모델이 행의 이슈 키를 따라
# get_issue_context로 피벗해 커밋 대신 이슈만 인용하는 회귀(case-10)가 확인됐다.
# 파일 변경의 직접 증거는 커밋이므로, 결과 자체에 앵커 규칙을 명시한다.
_TIER_NOTE = (
    "detail=질문과 관련도가 높은 커밋(본문·diffSummary 포함, 인용 대상). "
    "context=나머지 이력의 시간순 개요(hash·요약만). context 커밋을 근거로 인용하려면 "
    "get_changeset_context로 본문을 조회한 뒤 인용하세요. "
    "이 결과의 인용 앵커는 파일을 실제 변경한 커밋입니다 — 각 행의 issues는 그 커밋에 "
    "연결된 이슈이므로, 이슈·작업 배경을 서술할 때도 근거에서 해당 커밋을 빼지 마세요 "
    "(이슈만 인용하면 파일 변경의 직접 증거가 없는 답이 됩니다)."
)


async def get_file_history(
    project_id: str,
    path: str,
    question_embedding: list[float] | None = None,
    limit: int | None = None,
) -> dict | list[dict]:
    """파일 경로의 변경 이력을 2계층(detail/context)으로 반환한다.

    question_embedding이 주어지면 각 커밋을 질문과의 임베딩 관련도로 재랭킹해,
    가장 관련 있는 커밋 _DETAIL_K건을 detail(본문 포함)로 승격한다 — 최신순 컷에서
    관련 있는 옛 커밋이 떨어지던 문제를 구조적으로 막는다. 임베딩이 없으면(None) 최신순
    폴백. 나머지 커밋은 context stub(개요)으로 내려 전체 이력 흐름은 보존하되 대량 인용은
    막는다.

    strict path match가 비면 다음 순서로 fuzzy fallback:
      1) basename ENDS WITH 매칭 (확장자 그대로, 다른 디렉토리 허용)
      2) stem 매칭 (확장자 무관)

    단일 매칭 시: 그 파일의 이력을 반환하고 결과에 `_resolved_via` / `_resolved_path`를
                  부여 (LLM이 evidence에 실제 경로를 사용하도록).
    다중 매칭 시: `[{"message": ..., "candidates": [...]}]` 반환 — LLM이 재호출하도록.
    """
    fetch_cap = limit if limit else _MAX_COMMITS
    async with get_driver().session() as session:
        # 1단계: strict
        rows = await _fetch_file_history(session, project_id, path, question_embedding, fetch_cap)
        if rows:
            return _tier_result(path, rows, question_embedding)

        basename = path.rsplit("/", 1)[-1]

        # 2단계: basename ENDS WITH (동일 파일명, 다른 디렉토리 가능)
        if basename:
            candidates = await _find_files_ending_with(session, project_id, basename, _FUZZY_CANDIDATE_LIMIT)
            if len(candidates) == 1:
                return await _resolved_history(
                    session, project_id, candidates[0], question_embedding, fetch_cap, "basename_match",
                )
            if len(candidates) > 1:
                return [{
                    "message": (
                        f"'{basename}' basename으로 {len(candidates)}개 파일 매칭됨. "
                        f"candidates 중 가장 적절한 경로로 재호출하세요."
                    ),
                    "candidates": candidates,
                }]

        # 3단계: stem 매칭 (확장자 무관 — '.py'로 호출했는데 실제 '.java'인 케이스 대응)
        stem = basename.rpartition(".")[0] or basename
        if stem:
            candidates = await _find_files_by_stem(session, project_id, stem, _FUZZY_CANDIDATE_LIMIT)
            if len(candidates) == 1:
                return await _resolved_history(
                    session, project_id, candidates[0], question_embedding, fetch_cap, "stem_match",
                )
            if len(candidates) > 1:
                return [{
                    "message": (
                        f"'{stem}' 파일명(stem)으로 {len(candidates)}개 파일 매칭됨(확장자 무관). "
                        f"candidates 중 가장 적절한 경로로 재호출하세요."
                    ),
                    "candidates": candidates,
                }]

        return [{"message": f"해당 파일 또는 비슷한 이름의 파일을 찾을 수 없습니다: {path}"}]


# ─── 2계층 분할 (순수 함수 — Neo4j 없이 단위 테스트 가능) ────────────────────────


def _detail_count_for_budget(rows: list[dict], ranked: bool, budget_chars: int, k_max: int) -> int:
    """공용 예산 채움(_common)의 file_history 렌더러 바인딩 — 단위 테스트가 이 이름을 쓴다."""
    return _budget_count(rows, ranked, budget_chars, k_max, _detail_row)


def _split_tiers(
    rows: list[dict], ranked: bool, detail_k: int, context_cap: int,
) -> tuple[list[dict], list[dict], int]:
    """rows(최신순 desc)를 detail/context 두 계층으로 나눈다.

    우선순위(ranked면 relevance, 아니면 최신순) 상위 detail_k건을 detail로 승격하고,
    detail은 다시 시간순(desc)으로 정렬해 읽기 흐름을 보존한다. 나머지는 최신순 stub으로
    context_cap까지 담는다.

    Returns:
        (detail_rows, context_stubs, overflow)
        overflow = context_cap을 넘어 생략된 stub 수.
    """
    detail_src = _priority_order(rows, ranked)[:detail_k]
    detail_hashes = {r["hash"] for r in detail_src}

    # context: detail에 안 든 나머지 — rows가 이미 최신순이라 순서 유지
    context_src = [r for r in rows if r["hash"] not in detail_hashes]

    detail = [
        _detail_row(r, ranked)
        for r in sorted(detail_src, key=lambda r: r.get("occurredAt") or "", reverse=True)
    ]
    context = [_stub_row(r) for r in context_src[:context_cap]]
    overflow = len(context_src) - len(context)
    return detail, context, overflow


def _detail_row(r: dict, ranked: bool) -> dict:
    """인용 대상 행 — 본문(message)·diffSummary·연결 이슈/PR 포함."""
    message = r.get("message")
    if isinstance(message, str) and len(message) > _DETAIL_MESSAGE_MAX_CHARS:
        message = message[:_DETAIL_MESSAGE_MAX_CHARS] + " …(생략)"
    row = {
        "hash": r["hash"],
        "message": message,
        "occurredAt": r.get("occurredAt"),
        "author": r.get("author"),
        "diff_summary": r.get("diff_summary"),
        "issues": r.get("issues") or [],
        "prs": r.get("prs") or [],
    }
    if ranked and r.get("relevance") is not None:
        row["relevance"] = round(r["relevance"], 3)
    return row


def _stub_row(r: dict) -> dict:
    """개요 stub — hash·시각·요약 첫 줄·연결 이슈키만. 본문(diffSummary) 없음."""
    message = (r.get("message") or "").strip()
    first_line = message.splitlines()[0][:_STUB_TITLE_MAX_CHARS] if message else ""
    return {
        "hash": r["hash"],
        "occurredAt": r.get("occurredAt"),
        "title": first_line,
        "issues": [i["jira_key"] for i in (r.get("issues") or []) if i.get("jira_key")],
    }


def _tier_result(
    path: str,
    rows: list[dict],
    question_embedding: list[float] | None,
    resolved_via: str | None = None,
    resolved_path: str | None = None,
) -> dict:
    ranked = bool(question_embedding)
    detail_k = _detail_count_for_budget(rows, ranked, _DETAIL_BUDGET, _DETAIL_K_MAX)
    detail, context, overflow = _split_tiers(rows, ranked, detail_k, _CONTEXT_CAP)
    result: dict = {
        "path": resolved_path or path,
        "total_commits": len(rows),
        "ranked_by": "relevance" if ranked else "recency",
        "detail": detail,
        "context": context,
        "_note": _TIER_NOTE,
    }
    if overflow > 0:
        result["context_truncated"] = (
            f"context 개요 {len(rows) - len(detail)}건 중 앞 {len(context)}건만 표시 "
            f"(오래된 {overflow}건 생략) — 필요하면 get_changeset_context로 개별 조회."
        )
    if resolved_via:
        result["_resolved_via"] = resolved_via
        result["_resolved_path"] = resolved_path or path
    return result


# ─── Neo4j 조회 ────────────────────────────────────────────────────────────────


async def _fetch_file_history(
    session, project_id: str, path: str, question_embedding: list[float] | None, fetch_cap: int,
) -> list[dict]:
    """strict path match로 변경 이력 조회. 결과 0건이면 빈 리스트 반환.

    question_embedding이 있으면 각 행(= 그 커밋의 해당 파일 MODIFIED 엣지 임베딩)과의
    코사인 관련도를 relevance로 함께 반환한다. 임베딩이 없거나(None) 엣지에 embedding이
    없으면 relevance는 null. 정렬 기준은 항상 최신순(관련도 재랭킹은 Python 단에서 수행).
    """
    result = await session.run(
        """
        MATCH (f:File {project_id: $project_id, path: $path})<-[m:MODIFIED]-(cs:ChangeSet)
        OPTIONAL MATCH (a:Actor)-[:AUTHORED]->(cs)
        OPTIONAL MATCH (cs)-[tb:TRIGGERED_BY]->(i:Issue)
            WHERE coalesce(tb.confidence, 1.0) >= $min_conf
        OPTIONAL MATCH (pr:PullRequest)-[:CONTAINS]->(cs)
        // 커밋당 1행으로 집계 — 이슈 링크·PR이 여러 개면 행이 곱으로 불어나
        // (같은 diffSummary 반복 + executor 상한에 오래된 커밋이 밀려남: case-27)
        WITH cs, m, a,
             collect(DISTINCT CASE WHEN i IS NOT NULL THEN {
                 jira_key: i.jira_key, title: i.title,
                 confidence: tb.confidence, source: tb.source
             } END) AS issue_links,
             collect(DISTINCT CASE WHEN pr IS NOT NULL THEN {
                 pr_number: pr.pr_number, url: pr.url
             } END) AS pr_links
        RETURN cs.hash AS hash,
               cs.message AS message,
               toString(cs.occurredAt) AS occurredAt,
               a.name AS author,
               m.diffSummary AS diff_summary,
               [x IN issue_links WHERE x IS NOT NULL] AS issues,
               [x IN pr_links WHERE x IS NOT NULL] AS prs,
               CASE WHEN $q_embedding IS NULL OR m.embedding IS NULL THEN null
                    ELSE vector.similarity.cosine(m.embedding, $q_embedding) END AS relevance
        ORDER BY cs.occurredAt DESC
        LIMIT $fetch_cap
        """,
        project_id=project_id,
        path=path,
        q_embedding=question_embedding,
        fetch_cap=fetch_cap,
        min_conf=_MIN_CONFIDENCE,
    )
    rows = await result.data()
    # diffSummary가 행당 수천 자면 오래된 행이 executor 상한에 통째로 밀려난다 —
    # 이력 질문의 핵심은 커밋 나열·방향이므로 요약은 앞부분만 남긴다
    for row in rows:
        ds = row.get("diff_summary")
        if isinstance(ds, str) and len(ds) > _DIFF_SUMMARY_MAX_CHARS:
            row["diff_summary"] = ds[:_DIFF_SUMMARY_MAX_CHARS] + " …(생략)"
    return rows


async def _resolved_history(
    session, project_id: str, resolved_path: str,
    question_embedding: list[float] | None, fetch_cap: int, resolved_via: str,
) -> dict:
    """resolved_path로 이력 조회 후 fuzzy resolution 메타를 부여한 2계층 결과 반환.

    이력이 비어있어도 resolved 정보를 전달해 LLM이 다음 호출에서 정확한 경로를 쓰게 한다.
    """
    rows = await _fetch_file_history(session, project_id, resolved_path, question_embedding, fetch_cap)
    if not rows:
        return {
            "path": resolved_path,
            "total_commits": 0,
            "detail": [],
            "context": [],
            "message": f"파일 매칭됐으나 변경 이력 없음: {resolved_path}",
            "_resolved_via": resolved_via,
            "_resolved_path": resolved_path,
        }
    return _tier_result(
        resolved_path, rows, question_embedding,
        resolved_via=resolved_via, resolved_path=resolved_path,
    )


async def _find_files_ending_with(session, project_id: str, basename: str, max_candidates: int) -> list[str]:
    """f.path ENDS WITH '/' + basename 또는 정확히 basename — 동일 basename 검색.

    경로 짧은 순서로 정렬 (root에 가까운 파일 우선 — 일반적으로 사용자가 찾는 파일이 더 위에 있음).
    """
    result = await session.run(
        """
        MATCH (f:File {project_id: $project_id})
        WHERE f.path ENDS WITH ('/' + $basename) OR f.path = $basename
        RETURN DISTINCT f.path AS path
        ORDER BY size(path) ASC, path ASC
        LIMIT $max
        """,
        project_id=project_id,
        basename=basename,
        max=max_candidates,
    )
    return [r["path"] for r in await result.data()]


async def _find_files_by_stem(session, project_id: str, stem: str, max_candidates: int) -> list[str]:
    """파일명 stem이 정확히 일치 (확장자 무관).

    LIMIT이 정제 전 후보에 걸리면 실제 후보가 누락될 수 있으므로, Cypher에서
    basename의 stem을 먼저 계산하고 정확히 일치하는 path만 제한한다.
    """
    if not stem:
        return []
    result = await session.run(
        """
        MATCH (f:File {project_id: $project_id})
        WITH f.path AS path, last(split(f.path, '/')) AS basename
        WITH path,
             CASE
               WHEN basename CONTAINS '.' AND NOT (basename STARTS WITH '.')
               THEN substring(basename, 0, size(basename) - size(last(split(basename, '.'))) - 1)
               ELSE basename
             END AS file_stem
        WHERE file_stem = $stem
        RETURN DISTINCT path
        ORDER BY size(path) ASC, path ASC
        LIMIT $max
        """,
        project_id=project_id,
        stem=stem,
        max=max_candidates,
    )
    return [r["path"] for r in await result.data()]
