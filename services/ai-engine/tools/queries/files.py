"""파일 변경 이력 조회 — strict 매칭 + fuzzy path fallback."""

from tools.queries._common import _MIN_CONFIDENCE, get_driver


_FUZZY_CANDIDATE_LIMIT = 5     # candidates 리스트에 노출할 최대 후보 수
_DIFF_SUMMARY_MAX_CHARS = 300  # 행당 diffSummary 상한 — executor 결과 상한(8000자)에 이력 행이 잘려나가는 것 방지


async def get_file_history(project_id: str, path: str, limit: int = 20) -> list[dict]:
    """파일 경로의 변경 이력을 최신순으로 반환한다.

    strict path match가 비면 다음 순서로 fuzzy fallback:
      1) basename ENDS WITH 매칭 (확장자 그대로, 다른 디렉토리 허용)
      2) stem 매칭 (확장자 무관)

    단일 매칭 시: 그 파일의 변경 이력을 반환하고 각 row에 `_resolved_via` /
                  `_resolved_path` 메타 필드를 인라인으로 부여 (LLM이 evidence에
                  실제 경로를 사용하도록).
    다중 매칭 시: `{"message": "...", "candidates": [...]}` 단건 반환 — LLM이
                  candidates 중 선택해 재호출하도록.
    """
    async with get_driver().session() as session:
        # 1단계: strict
        rows = await _fetch_file_history(session, project_id, path, limit)
        if rows:
            return rows

        basename = path.rsplit("/", 1)[-1]

        # 2단계: basename ENDS WITH (동일 파일명, 다른 디렉토리 가능)
        if basename:
            candidates = await _find_files_ending_with(session, project_id, basename, _FUZZY_CANDIDATE_LIMIT)
            if len(candidates) == 1:
                return await _fetch_with_resolution_meta(
                    session, project_id, candidates[0], limit, resolved_via="basename_match",
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
                return await _fetch_with_resolution_meta(
                    session, project_id, candidates[0], limit, resolved_via="stem_match",
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


async def _fetch_file_history(session, project_id: str, path: str, limit: int) -> list[dict]:
    """strict path match로 변경 이력 조회. 결과 0건이면 빈 리스트 반환."""
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
               [x IN pr_links WHERE x IS NOT NULL] AS prs
        ORDER BY cs.occurredAt DESC
        LIMIT $limit
        """,
        project_id=project_id,
        path=path,
        limit=limit,
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


async def _fetch_with_resolution_meta(
    session, project_id: str, resolved_path: str, limit: int, resolved_via: str,
) -> list[dict]:
    """resolved_path로 history 조회 후 row마다 fuzzy resolution 메타를 인라인 부여.

    이력이 비어있어도 resolved 정보를 전달해 LLM이 다음 호출에서 정확한 경로를 사용할 수 있게 한다.
    """
    rows = await _fetch_file_history(session, project_id, resolved_path, limit)
    if not rows:
        return [{
            "message": f"파일 매칭됐으나 변경 이력 없음: {resolved_path}",
            "_resolved_via":  resolved_via,
            "_resolved_path": resolved_path,
        }]
    for r in rows:
        r["_resolved_via"]  = resolved_via
        r["_resolved_path"] = resolved_path
    return rows


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
