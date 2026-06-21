"""이슈/에픽 컨텍스트 조회 — 이슈 상세, 타임라인."""

from tools.queries._common import (
    _MIN_CONFIDENCE,
    _group_communications_by_thread,
    get_driver,
)


# CHILD_OF 재귀 깊이 상한. Jira epic 구조 깊이는 보통 1~2 단계.
_CHILD_DEPTH = 5

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
