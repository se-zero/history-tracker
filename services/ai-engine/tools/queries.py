from graph.builder import get_driver


# TRIGGERED_BY 엣지 노이즈 컷오프.
# 텍스트 매칭은 항상 1.0이므로 항상 통과. 시맨틱은 0.5 미만이면 응답에서 제외.
# (issue_linker 자체 생성 임계값은 0.40이라 0.40~0.49 구간이 응답 단에서 마저 차단됨)
_MIN_CONFIDENCE = 0.5

# CHILD_OF 재귀 깊이 상한. Jira epic 구조 깊이는 보통 1~2 단계.
_CHILD_DEPTH = 5

# 벡터 인덱스 over-fetch 배수.
# db.index.vector.queryNodes는 전역 top-K만 반환하고 project_id 사전 필터가 불가능하다.
# 단일 Neo4j에 여러 프로젝트가 섞여 있으므로, project_id 필터 후에도 충분한 후보가
# 남도록 top_k의 배수만큼 넉넉히 가져온 뒤 후처리로 잘라낸다.
_VECTOR_OVERFETCH = 20
_VECTOR_OVERFETCH_CAP = 500


def _group_communications_by_thread(comms: list[dict]) -> list[dict]:
    """flat Communication 리스트를 conversation_id 기준으로 그룹핑한다.

    Slack 응답에서 LLM이 서로 다른 스레드를 한 대화로 합치거나 화자(author)를
    swap하지 않도록, 도구 응답 단계에서 스레드 경계를 명시적으로 구조화한다.

    입력: 각 dict는 최소 {body, conversation_id} 를 가지며 그 외 author, occurredAt,
          source, channel, confidence 등 임의 메타가 포함될 수 있다.

    반환:
        [
            {
                "conversation_id": str,
                "source":          str | None,  # SLACK / GITHUB
                "channel":         str | None,
                "messages": [
                    {author, body, occurredAt, confidence, ...},  # 그룹 메타 제외
                    ...
                ]  # occurredAt 오름차순 정렬
            },
            ...
        ]
        그룹들 자체는 첫 메시지 occurredAt 오름차순 정렬.

    예외 처리:
        - conversation_id가 None이거나 빈 문자열이면 별도 "(orphan)" 그룹으로 모음
        - 본문/저자/시각이 모두 None인 더미 dict는 제외 (OPTIONAL MATCH 미매치 잔재)
    """
    GROUP_KEYS = {"conversation_id", "source", "channel"}

    valid = [
        c for c in comms
        if any(c.get(k) is not None for k in c if k not in GROUP_KEYS)
    ]

    groups: dict[str, dict] = {}
    for c in valid:
        cid = c.get("conversation_id") or "(orphan)"
        if cid not in groups:
            groups[cid] = {
                "conversation_id": cid,
                "source":          c.get("source"),
                "channel":         c.get("channel"),
                "messages":        [],
            }
        msg = {k: v for k, v in c.items() if k not in GROUP_KEYS}
        groups[cid]["messages"].append(msg)

    for g in groups.values():
        g["messages"].sort(key=lambda m: m.get("occurredAt") or "")

    return sorted(
        groups.values(),
        key=lambda g: (g["messages"][0].get("occurredAt") or "") if g["messages"] else "",
    )


async def get_issue_context(project_id: str, jira_key: str) -> dict:
    """이슈 단일 키로 직속 작업 + 자식 이슈 작업까지 모두 집계해서 반환.

    진입 Issue를 project_id로 스코프한다 — jira_key는 프로젝트 간 충돌하므로 필수.
    여기서 도달하는 ChangeSet/PR/Communication/자식 Issue는 프로젝트 내부 엣지로만
    연결되므로(Phase A 보장) 추가 스코프 불필요.

    반환 구조:
      {
        jira_key, title, body, status, ..., creator, assignee,
        changesets:    [...],   # root 이슈에 직접 연결된 커밋
        pull_requests: [...],
        discussions:   [...],
        descendants: [
          {jira_key, title, status, changesets, pull_requests, discussions},
          ...
        ]
      }

    필터 정책:
      - TRIGGERED_BY 엣지는 confidence >= _MIN_CONFIDENCE 만 통과 (텍스트 매칭은 항상 1.0)
      - 각 changeset 항목에 source('text' | 'semantic') 노출하여 LLM이 신뢰도 구분 가능
      - CHILD_OF 재귀 깊이 상한 _CHILD_DEPTH
    """
    async with get_driver().session() as session:
        # 1단계: 이슈 + creator + assignee
        result = await session.run(
            """
            MATCH (i:Issue {project_id: $project_id, jira_key: $jira_key})
            OPTIONAL MATCH (creator:Actor)-[:CREATED]->(i)
            OPTIONAL MATCH (assignee:Actor)<-[:ASSIGNED_TO]-(i)
            RETURN i.jira_key AS jira_key, i.title AS title, i.body AS body,
                   i.status AS status, i.issue_type AS issue_type,
                   i.priority AS priority, toString(i.occurredAt) AS occurredAt,
                   creator.name AS creator, assignee.name AS assignee
            """,
            project_id=project_id,
            jira_key=jira_key,
        )
        row = await result.single()
        if not row:
            return {"message": f"이슈를 찾을 수 없습니다: {jira_key}"}
        base = dict(row)

        # 2단계: 스코프 결정 — root + 자식 이슈 메타데이터 (root 자체는 항상 첫 항목)
        result = await session.run(
            f"""
            MATCH (root:Issue {{project_id: $project_id, jira_key: $jira_key}})
            OPTIONAL MATCH (desc:Issue)-[:CHILD_OF*1..{_CHILD_DEPTH}]->(root)
            WITH root, collect(DISTINCT desc) AS descs
            UNWIND ([root] + descs) AS i
            WITH i WHERE i IS NOT NULL
            RETURN i.jira_key AS jira_key, i.title AS title, i.status AS status
            """,
            project_id=project_id,
            jira_key=jira_key,
        )
        scope_issues = await result.data()  # 첫 항목이 root, 이후가 descendants

        # 3단계: 스코프 내 각 이슈의 커밋 + PR 일괄 조회 (jira_key 기준 grouping)
        result = await session.run(
            f"""
            MATCH (root:Issue {{project_id: $project_id, jira_key: $jira_key}})
            OPTIONAL MATCH (desc:Issue)-[:CHILD_OF*1..{_CHILD_DEPTH}]->(root)
            WITH collect(DISTINCT root) + collect(DISTINCT desc) AS issues_raw
            UNWIND issues_raw AS i
            WITH i WHERE i IS NOT NULL
            OPTIONAL MATCH (cs:ChangeSet)-[tb:TRIGGERED_BY]->(i)
            WHERE coalesce(tb.confidence, 1.0) >= $min_conf
            OPTIONAL MATCH (cs_author:Actor)-[:AUTHORED]->(cs)
            OPTIONAL MATCH (pr:PullRequest)-[:CONTAINS]->(cs)
            RETURN i.jira_key AS jira_key,
                   collect(DISTINCT {{
                       hash: cs.hash, message: cs.message,
                       occurredAt: toString(cs.occurredAt),
                       author: cs_author.name,
                       confidence: tb.confidence,
                       link_source: tb.source
                   }}) AS changesets,
                   collect(DISTINCT {{
                       pr_number: pr.pr_number, title: pr.title, url: pr.url,
                       occurredAt: toString(pr.occurredAt)
                   }}) AS pull_requests
            """,
            project_id=project_id,
            jira_key=jira_key,
            min_conf=_MIN_CONFIDENCE,
        )
        work_rows = {r["jira_key"]: r for r in await result.data()}

        # 4단계: 스코프 내 각 이슈의 논의 일괄 조회 (jira_key 기준 grouping)
        result = await session.run(
            f"""
            MATCH (root:Issue {{project_id: $project_id, jira_key: $jira_key}})
            OPTIONAL MATCH (desc:Issue)-[:CHILD_OF*1..{_CHILD_DEPTH}]->(root)
            WITH collect(DISTINCT root) + collect(DISTINCT desc) AS issues_raw
            UNWIND issues_raw AS i
            WITH i WHERE i IS NOT NULL
            OPTIONAL MATCH (i)-[disc:DISCUSSED_IN]->(c:Communication)
            OPTIONAL MATCH (c_author:Actor)-[:WROTE]->(c)
            RETURN i.jira_key AS jira_key,
                   collect(DISTINCT {{
                       body: c.body, channel: c.channel, source: c.source,
                       occurredAt: toString(c.occurredAt),
                       conversation_id: c.conversation_id,
                       author: c_author.name,
                       confidence: disc.confidence
                   }}) AS discussions
            """,
            project_id=project_id,
            jira_key=jira_key,
        )
        disc_rows = {r["jira_key"]: r["discussions"] for r in await result.data()}

        def _filter_empty(items: list[dict]) -> list[dict]:
            """collect로 OPTIONAL MATCH가 비었을 때 들어오는 전 필드 None 더미 제거."""
            return [it for it in items if any(v is not None for v in it.values())]

        def _per_issue(key: str) -> dict:
            w = work_rows.get(key, {})
            # discussions는 thread별 그룹핑된 구조로 반환 — LLM이 스레드 경계 명확히 인지하도록.
            return {
                "changesets":    _filter_empty(w.get("changesets", [])),
                "pull_requests": _filter_empty(w.get("pull_requests", [])),
                "discussions":   _group_communications_by_thread(disc_rows.get(key, [])),
            }

        # root 자체의 작업은 top-level에 그대로 배치 (하위 호환)
        base.update(_per_issue(jira_key))

        # descendants는 root 제외하고 jira_key 사전순 정렬
        base["descendants"] = [
            {
                "jira_key": i["jira_key"],
                "title":    i["title"],
                "status":   i["status"],
                **_per_issue(i["jira_key"]),
            }
            for i in sorted(scope_issues, key=lambda x: x["jira_key"])
            if i["jira_key"] != jira_key
        ]

        return base


async def get_changeset_context(project_id: str, hash: str) -> dict:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (cs:ChangeSet {project_id: $project_id, hash: $hash})
            MATCH (a:Actor)-[:AUTHORED]->(cs)
            OPTIONAL MATCH (cs)-[tb:TRIGGERED_BY]->(i:Issue)
                WHERE coalesce(tb.confidence, 1.0) >= $min_conf
            OPTIONAL MATCH (cs)-[ref:REFERENCE]->(c:Communication)
            OPTIONAL MATCH (c_author:Actor)-[:WROTE]->(c)
            OPTIONAL MATCH (pr:PullRequest)-[:CONTAINS]->(cs)
            OPTIONAL MATCH (cs)-[m:MODIFIED]->(f:File)
            RETURN cs.hash AS hash,
                   cs.message AS commit_message,
                   toString(cs.occurredAt) AS occurredAt,
                   a.name AS author,
                   collect(DISTINCT {
                       jira_key: i.jira_key, title: i.title,
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
                   {pr_number: pr.pr_number, title: pr.title, url: pr.url} AS pull_request,
                   collect(DISTINCT {path: f.path, diffSummary: m.diffSummary}) AS file_changes
            """,
            project_id=project_id,
            hash=hash,
            min_conf=_MIN_CONFIDENCE,
        )
        row = await result.single()
        if not row:
            return {"message": f"커밋을 찾을 수 없습니다: {hash}"}
        out = dict(row)
        # Slack 스레드 경계 보존 — communications를 conversation_id별로 그룹핑.
        out["communications"] = _group_communications_by_thread(out.get("communications") or [])
        return out


async def find_expert(project_id: str, path_prefix: str) -> list[dict]:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (a:Actor)-[:AUTHORED]->(cs:ChangeSet)-[:MODIFIED]->(f:File)
            WHERE f.path STARTS WITH $path_prefix AND cs.project_id = $project_id
            WITH a, cs,
                 CASE WHEN cs.occurredAt >= datetime() - duration('P180D')
                      THEN 2 ELSE 1 END AS weight
            WITH a,
                 count(cs) AS commit_count,
                 sum(weight) AS weighted_score,
                 max(cs.occurredAt) AS last_commit
            RETURN a.name AS author,
                   a.uuid AS actor_uuid,
                   commit_count,
                   weighted_score,
                   toString(last_commit) AS last_commit
            ORDER BY weighted_score DESC
            LIMIT 5
            """,
            project_id=project_id,
            path_prefix=path_prefix,
        )
        rows = await result.data()
        if not rows:
            return [{"message": f"해당 경로에 대한 커밋 이력이 없습니다: {path_prefix}"}]
        return rows


async def get_timeline(project_id: str, jira_key: str) -> list[dict]:
    """이슈 생명주기 이벤트를 시간순으로 반환.

    각 이벤트에 명시적 event_meaning 라벨을 붙여 LLM이 occurredAt만 보고 생성/완료/머지를
    추정하지 않도록 한다. Issue 생명주기는 createdAt → 'issue_created',
    closedAt(존재 시) → 'issue_closed' 두 이벤트로 분리; PR도 createdAt → 'pr_opened',
    occurredAt(merged_at) → 'pr_merged' 분리.

    반환 항목 공통 구조: {type, event_meaning, occurredAt, data: {...}}
    """
    async with get_driver().session() as session:
        # 이슈 자체 + 연결된 커밋·PR
        result = await session.run(
            """
            MATCH (i:Issue {project_id: $project_id, jira_key: $jira_key})
            OPTIONAL MATCH (cs:ChangeSet)-[tb:TRIGGERED_BY]->(i)
                WHERE coalesce(tb.confidence, 1.0) >= $min_conf
            OPTIONAL MATCH (pr:PullRequest)-[:CONTAINS]->(cs)
            WITH i,
                 collect(DISTINCT {
                     type: 'ChangeSet',
                     event_meaning: 'commit_authored',
                     occurredAt: toString(cs.occurredAt),
                     data: {hash: cs.hash, message: cs.message,
                            confidence: tb.confidence,
                            link_source: tb.source}
                 }) AS cs_events,
                 collect(DISTINCT {
                     type: 'PullRequest',
                     event_meaning: 'pr_opened',
                     occurredAt: toString(pr.createdAt),
                     data: {pr_number: pr.pr_number, title: pr.title, url: pr.url}
                 }) AS pr_open_events,
                 collect(DISTINCT {
                     type: 'PullRequest',
                     event_meaning: 'pr_merged',
                     occurredAt: toString(pr.occurredAt),
                     data: {pr_number: pr.pr_number, title: pr.title, url: pr.url}
                 }) AS pr_merge_events
            RETURN i, cs_events, pr_open_events, pr_merge_events
            """,
            project_id=project_id,
            jira_key=jira_key,
            min_conf=_MIN_CONFIDENCE,
        )
        row = await result.single()
        if not row:
            return [{"message": f"이슈를 찾을 수 없습니다: {jira_key}"}]

        i = row["i"]
        issue_data = {
            "jira_key": i.get("jira_key"),
            "title":    i.get("title"),
            "status":   i.get("status"),
        }
        # Issue 생명주기 이벤트 — createdAt / closedAt 각각 별도 이벤트로 emit.
        # occurredAt(최종 업데이트)은 의미가 모호하므로 별도 이벤트로 만들지 않는다.
        issue_events: list[dict] = []
        if i.get("createdAt"):
            issue_events.append({
                "type": "Issue",
                "event_meaning": "issue_created",
                "occurredAt": str(i.get("createdAt")),
                "data": issue_data,
            })
        if i.get("closedAt"):
            issue_events.append({
                "type": "Issue",
                "event_meaning": "issue_closed",
                "occurredAt": str(i.get("closedAt")),
                "data": issue_data,
            })

        all_events = issue_events + row["cs_events"] + row["pr_open_events"] + row["pr_merge_events"]

        # 논의 수집 (이슈와 독립적이므로 별도 쿼리)
        result2 = await session.run(
            """
            MATCH (i:Issue {project_id: $project_id, jira_key: $jira_key})-[:DISCUSSED_IN]->(c:Communication)
            RETURN collect(DISTINCT {
                type: 'Communication',
                event_meaning: 'message_posted',
                occurredAt: toString(c.occurredAt),
                data: {body: c.body, channel: c.channel, source: c.source,
                       conversation_id: c.conversation_id}
            }) AS comm_events
            """,
            project_id=project_id,
            jira_key=jira_key,
        )
        row2 = await result2.single()
        if row2:
            all_events += row2["comm_events"]

        # null occurredAt 제거 후 정렬
        valid = [e for e in all_events if e.get("occurredAt") and e["occurredAt"] != "None"]
        return sorted(valid, key=lambda e: e["occurredAt"])


async def search_by_keyword(project_id: str, embedding: list[float], top_k: int = 5, threshold: float = 0.30) -> list[dict]:
    fetch_k = min(top_k * _VECTOR_OVERFETCH, _VECTOR_OVERFETCH_CAP)
    async with get_driver().session() as session:
        # Communication 인덱스 검색 — 전역 fetch_k 후보를 project_id로 필터하고 top_k로 자른다.
        result = await session.run(
            """
            CALL db.index.vector.queryNodes('comm_embedding', $fetch_k, $embedding)
            YIELD node AS c, score
            WHERE score >= $threshold AND c.project_id = $project_id
            OPTIONAL MATCH (cs:ChangeSet)-[:REFERENCE]->(c)
            OPTIONAL MATCH (i:Issue)-[:DISCUSSED_IN]->(c)
            RETURN 'Communication' AS type,
                   left(c.body, 300) AS text,
                   c.channel AS channel,
                   c.source AS source,
                   c.conversation_id AS conversation_id,
                   toString(c.occurredAt) AS occurredAt,
                   score,
                   collect(DISTINCT cs.hash) AS related_changesets,
                   collect(DISTINCT i.jira_key) AS related_issues
            ORDER BY score DESC
            LIMIT $top_k
            """,
            project_id=project_id,
            embedding=embedding,
            fetch_k=fetch_k,
            top_k=top_k,
            threshold=threshold,
        )
        comm_rows = await result.data()

        # 같은 스레드의 여러 메시지가 검색에 잡혔을 때 dedupe — 대표 메시지(최고 score) 한 건만 유지.
        # 후속으로 LLM이 conversation_id를 가지고 get_thread_context를 호출해 전체 스레드 맥락을 얻도록.
        seen_threads: set[str] = set()
        deduped_comm_rows: list[dict] = []
        for r in comm_rows:
            cid = r.get("conversation_id")
            if cid and cid in seen_threads:
                continue
            if cid:
                seen_threads.add(cid)
            deduped_comm_rows.append(r)
        comm_rows = deduped_comm_rows

        # Issue 인덱스 검색 — 동일하게 over-fetch 후 project_id 필터 + top_k.
        result = await session.run(
            """
            CALL db.index.vector.queryNodes('issue_embedding', $fetch_k, $embedding)
            YIELD node AS i, score
            WHERE score >= $threshold AND i.project_id = $project_id
            OPTIONAL MATCH (cs:ChangeSet)-[tb:TRIGGERED_BY]->(i)
                WHERE coalesce(tb.confidence, 1.0) >= $min_conf
            RETURN 'Issue' AS type,
                   (i.title + ': ' + coalesce(i.body, '')) AS text,
                   null AS channel,
                   'JIRA' AS source,
                   toString(i.occurredAt) AS occurredAt,
                   score,
                   collect(DISTINCT cs.hash) AS related_changesets,
                   collect(DISTINCT i.jira_key) AS related_issues
            ORDER BY score DESC
            LIMIT $top_k
            """,
            project_id=project_id,
            embedding=embedding,
            fetch_k=fetch_k,
            top_k=top_k,
            threshold=threshold,
            min_conf=_MIN_CONFIDENCE,
        )
        issue_rows = await result.data()

        combined = comm_rows + issue_rows
        if not combined:
            return [{"message": "유사한 컨텍스트를 찾지 못했습니다. threshold를 낮추거나 다른 키워드를 시도하세요."}]
        return sorted(combined, key=lambda r: r["score"], reverse=True)


async def get_actor_activity(
    project_id: str,
    identifier: str,
    from_time: str | None = None,
    limit: int = 20,
) -> dict:
    async with get_driver().session() as session:
        # Actor 확인
        result = await session.run(
            """
            MATCH (a:Actor {project_id: $project_id})
            WHERE a.name = $identifier
               OR $identifier IN a.aliases
               OR $identifier IN a.emails
            RETURN a.name AS name, a.aliases AS aliases, a.emails AS emails
            LIMIT 1
            """,
            project_id=project_id,
            identifier=identifier,
        )
        actor_row = await result.single()
        if not actor_row:
            return {"message": f"Actor를 찾을 수 없습니다: {identifier}"}
        actor = dict(actor_row)

        # 커밋 (최신순)
        result = await session.run(
            """
            MATCH (a:Actor {project_id: $project_id})-[:AUTHORED]->(cs:ChangeSet)
            WHERE (a.name = $identifier OR $identifier IN a.aliases OR $identifier IN a.emails)
              AND ($from_time IS NULL OR cs.occurredAt >= datetime($from_time))
            WITH cs ORDER BY cs.occurredAt DESC
            RETURN collect(cs)[0..$limit] AS changesets_raw
            """,
            project_id=project_id,
            identifier=identifier,
            from_time=from_time,
            limit=limit,
        )
        row = await result.single()
        actor["changesets"] = [
            {"hash": c["hash"], "message": c["message"], "occurredAt": str(c.get("occurredAt", ""))}
            for c in (row["changesets_raw"] if row else [])
        ]

        # PR (최신순)
        result = await session.run(
            """
            MATCH (a:Actor {project_id: $project_id})-[:AUTHORED]->(pr:PullRequest)
            WHERE (a.name = $identifier OR $identifier IN a.aliases OR $identifier IN a.emails)
              AND ($from_time IS NULL OR pr.occurredAt >= datetime($from_time))
            WITH pr ORDER BY pr.occurredAt DESC
            RETURN collect(pr)[0..$limit] AS prs_raw
            """,
            project_id=project_id,
            identifier=identifier,
            from_time=from_time,
            limit=limit,
        )
        row = await result.single()
        actor["pull_requests"] = [
            {"pr_number": p["pr_number"], "title": p["title"], "occurredAt": str(p.get("occurredAt", ""))}
            for p in (row["prs_raw"] if row else [])
        ]

        # 메시지 (최신순)
        result = await session.run(
            """
            MATCH (a:Actor {project_id: $project_id})-[:WROTE]->(c:Communication)
            WHERE (a.name = $identifier OR $identifier IN a.aliases OR $identifier IN a.emails)
              AND ($from_time IS NULL OR c.occurredAt >= datetime($from_time))
            WITH c ORDER BY c.occurredAt DESC
            RETURN collect(c)[0..$limit] AS comms_raw
            """,
            project_id=project_id,
            identifier=identifier,
            from_time=from_time,
            limit=limit,
        )
        row = await result.single()
        actor["communications"] = [
            {"body": c["body"][:200] if c.get("body") else "", "channel": c.get("channel"), "occurredAt": str(c.get("occurredAt", ""))}
            for c in (row["comms_raw"] if row else [])
        ]

        # Jira 생성 / 담당
        result = await session.run(
            """
            MATCH (a:Actor {project_id: $project_id})
            WHERE a.name = $identifier OR $identifier IN a.aliases OR $identifier IN a.emails
            OPTIONAL MATCH (a)-[:CREATED]->(i:Issue)
            OPTIONAL MATCH (assigned:Issue)-[:ASSIGNED_TO]->(a)
            RETURN collect(DISTINCT {jira_key: i.jira_key, title: i.title}) AS issues_created,
                   collect(DISTINCT {jira_key: assigned.jira_key, title: assigned.title}) AS issues_assigned
            """,
            project_id=project_id,
            identifier=identifier,
        )
        row = await result.single()
        if row:
            actor["issues_created"] = row["issues_created"]
            actor["issues_assigned"] = row["issues_assigned"]

        return actor


_FUZZY_CANDIDATE_LIMIT = 5     # candidates 리스트에 노출할 최대 후보 수


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
        MATCH (a:Actor)-[:AUTHORED]->(cs)
        OPTIONAL MATCH (cs)-[tb:TRIGGERED_BY]->(i:Issue)
            WHERE coalesce(tb.confidence, 1.0) >= $min_conf
        OPTIONAL MATCH (pr:PullRequest)-[:CONTAINS]->(cs)
        RETURN cs.hash AS hash,
               cs.message AS message,
               toString(cs.occurredAt) AS occurredAt,
               a.name AS author,
               m.diffSummary AS diff_summary,
               i.jira_key AS jira_key,
               i.title AS issue_title,
               tb.confidence AS issue_link_confidence,
               tb.source     AS issue_link_source,
               pr.pr_number AS pr_number,
               pr.url AS pr_url
        ORDER BY cs.occurredAt DESC
        LIMIT $limit
        """,
        project_id=project_id,
        path=path,
        limit=limit,
        min_conf=_MIN_CONFIDENCE,
    )
    return await result.data()


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


async def inspect_actor(project_id: str, identifier: str) -> dict:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (a:Actor {project_id: $project_id})
            WHERE a.name = $identifier
               OR $identifier IN a.aliases
               OR $identifier IN a.emails
            RETURN a.uuid AS uuid,
                   a.name AS display_name,
                   a.normalized_name AS normalized_name,
                   a.aliases AS all_aliases,
                   a.emails AS emails,
                   a.confidence AS merge_confidence,
                   count { (a)-[:AUTHORED]->(:ChangeSet) } AS commit_count,
                   count { (a)-[:AUTHORED]->(:PullRequest) } AS pr_count,
                   count { (a)-[:WROTE]->(:Communication) } AS message_count,
                   count { (a)-[:CREATED]->(:Issue) } AS issue_created_count
            """,
            project_id=project_id,
            identifier=identifier,
        )
        row = await result.single()
        if not row:
            return {"message": f"Actor를 찾을 수 없습니다: {identifier}"}
        return dict(row)


async def get_conflict_context(project_id: str, hash: str) -> dict:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (cs:ChangeSet {project_id: $project_id, hash: $hash})
            OPTIONAL MATCH (cs)-[tb:TRIGGERED_BY]->(i:Issue)
                WHERE coalesce(tb.confidence, 1.0) >= $min_conf
            OPTIONAL MATCH (cs)-[ref:REFERENCE]->(c:Communication)
            OPTIONAL MATCH (c_author:Actor)-[:WROTE]->(c)
            OPTIONAL MATCH (pr:PullRequest)-[:CONTAINS]->(cs)
            OPTIONAL MATCH (cs)-[m:MODIFIED]->(f:File)
            RETURN cs.hash AS hash,
                   cs.message AS commit_message,
                   toString(cs.occurredAt) AS occurredAt,
                   collect(DISTINCT {
                       source: 'Jira',
                       id: i.jira_key,
                       text: i.title + '\n' + coalesce(i.body, ''),
                       confidence: tb.confidence,
                       link_source: tb.source
                   }) AS jira_contexts,
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
                   collect(DISTINCT {path: f.path, diff_summary: m.diffSummary}) AS file_changes
            """,
            project_id=project_id,
            hash=hash,
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


async def get_recent_activity(
    project_id: str,
    from_time: str,
    to_time: str | None = None,
    limit: int = 30,
) -> list[dict]:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (n)
            WHERE (n:ChangeSet OR n:PullRequest OR n:Communication OR n:Issue)
              AND n.project_id = $project_id
              AND n.occurredAt >= datetime($from_time)
              AND ($to_time IS NULL OR n.occurredAt <= datetime($to_time))
            WITH n, labels(n)[0] AS node_type
            OPTIONAL MATCH (a:Actor)-[:AUTHORED|WROTE|CREATED]->(n)
            RETURN node_type AS type,
                   toString(n.occurredAt) AS occurredAt,
                   a.name AS actor,
                   CASE node_type
                     WHEN 'ChangeSet'     THEN n.hash
                     WHEN 'PullRequest'   THEN toString(n.pr_number)
                     WHEN 'Communication' THEN n.url
                     WHEN 'Issue'         THEN n.jira_key
                   END AS id,
                   CASE node_type
                     WHEN 'ChangeSet'     THEN n.message
                     WHEN 'PullRequest'   THEN n.title
                     WHEN 'Communication' THEN left(n.body, 200)
                     WHEN 'Issue'         THEN n.title
                   END AS summary
            ORDER BY occurredAt DESC
            LIMIT $limit
            """,
            project_id=project_id,
            from_time=from_time,
            to_time=to_time,
            limit=limit,
        )
        rows = await result.data()
        if not rows:
            return [{"message": "해당 기간에 활동이 없습니다."}]
        return rows


async def get_pr_context(project_id: str, pr_number: int) -> dict:
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (pr:PullRequest {project_id: $project_id, pr_number: $pr_number})
            OPTIONAL MATCH (author:Actor)-[:AUTHORED]->(pr)
            OPTIONAL MATCH (pr)-[:CONTAINS]->(cs:ChangeSet)
            OPTIONAL MATCH (cs_author:Actor)-[:AUTHORED]->(cs)
            OPTIONAL MATCH (cs)-[tb:TRIGGERED_BY]->(i:Issue)
                WHERE coalesce(tb.confidence, 1.0) >= $min_conf
            OPTIONAL MATCH (cs)-[ref:REFERENCE]->(c:Communication)
            OPTIONAL MATCH (c_author:Actor)-[:WROTE]->(c)
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
                       jira_key: i.jira_key, title: i.title,
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


async def get_thread_context(project_id: str, conversation_id: str) -> list[dict]:
    async with get_driver().session() as session:
        # collect(DISTINCT ...) 때문에 RETURN이 aggregation으로 처리됨 →
        # ORDER BY는 RETURN의 projected alias만 참조 가능 (c.occurredAt 직접 참조 시 SyntaxError).
        # occurredAt이 ISO 문자열이라 lexicographic = chronological 정렬.
        result = await session.run(
            """
            MATCH (c:Communication {project_id: $project_id, conversation_id: $conversation_id})
            OPTIONAL MATCH (a:Actor)-[:WROTE]->(c)
            OPTIONAL MATCH (i:Issue)-[:DISCUSSED_IN]->(c)
            RETURN c.body AS body,
                   toString(c.occurredAt) AS occurredAt,
                   c.source AS source,
                   c.url AS url,
                   a.name AS author,
                   collect(DISTINCT {jira_key: i.jira_key, title: i.title}) AS related_issues
            ORDER BY occurredAt ASC
            """,
            project_id=project_id,
            conversation_id=conversation_id,
        )
        rows = await result.data()
        if not rows:
            return [{"message": f"해당 conversation_id의 메시지가 없습니다: {conversation_id}"}]
        return rows
