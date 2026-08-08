"""이슈/에픽 컨텍스트 조회 — 이슈 상세, 타임라인."""

import json
import os

from tools.queries._common import (
    _MIN_CONFIDENCE,
    _group_communications_by_thread,
    expand_events,
    get_driver,
    normalize_time,
    sort_events,
)
from tools.queries.files import (
    _FUZZY_CANDIDATE_LIMIT,
    _find_files_by_stem,
    _find_files_ending_with,
)


# CHILD_OF 재귀 깊이 상한. Jira epic 구조 깊이는 보통 1~2 단계.
_CHILD_DEPTH = 5

# 타임라인 반환 이벤트 상한 — executor의 바이트 컷(_MAX_RESULT_CHARS)에 닿기 전에
# 쿼리 단에서 먼저 자르고 잘린 사실을 구조적으로 알린다. eval 스윕용 env 노브.
_TIMELINE_MAX_EVENTS = int(os.environ.get("TIMELINE_MAX_EVENTS", "80"))
# 타임라인 직렬화 예산(자) — executor의 _MAX_RESULT_CHARS(8000) 아래로 잡아, 쿼리 단에서
# 예산에 맞춰 양끝을 남기고 자른다. 이렇게 해야 executor의 뒤쪽-컷이 뒤늦게 끼어들어
# duration 질문의 끝점(issue_closed 등)을 다시 떨어뜨리는 이중 잘림을 막는다.
_TIMELINE_BUDGET = int(os.environ.get("TIMELINE_BUDGET_CHARS", "7000"))
# 타임라인은 '순서 뼈대'라 본문·제목·메시지를 짧게 싣는다(인용은 상세 도구로).
# 커밋 메시지·PR/이슈 제목은 첫 줄만, 슬랙 본문은 앞부분만.
_BODY_CHARS = int(os.environ.get("TIMELINE_BODY_CHARS", "200"))
_TITLE_CHARS = int(os.environ.get("TIMELINE_TITLE_CHARS", "120"))


def _first_line(text: str | None, cap: int) -> str | None:
    """첫 줄만 cap자까지 — 타임라인 이벤트를 뼈대 크기로 유지(예산 절약)."""
    if not text:
        return None
    return text.splitlines()[0][:cap] or None

async def get_issue_context(project_id: str, issue_key: str) -> dict:
    """이슈 단일 키로 직속 작업 + 자식 이슈 작업까지 모두 집계해서 반환.

    진입 Issue를 project_id로 스코프한다 — issue_key는 프로젝트 간 충돌하므로 필수.
    여기서 도달하는 ChangeSet/PR/Communication/자식 Issue는 프로젝트 내부 엣지로만
    연결되므로(Phase A 보장) 추가 스코프 불필요.

    반환 구조:
      {
        issue_key, title, body, status, ..., creator, assignee,
        changesets:    [...],   # root 이슈에 직접 연결된 커밋
        pull_requests: [...],
        discussions:   [...],
        descendants: [
          {issue_key, title, status, changesets, pull_requests, discussions},
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
            MATCH (i:Issue {project_id: $project_id, issue_key: $issue_key})
            WHERE i.source <> '__stub__'
            OPTIONAL MATCH (creator:Actor)-[:CREATED]->(i)
            OPTIONAL MATCH (assignee:Actor)<-[:ASSIGNED_TO]-(i)
            RETURN i.issue_key AS issue_key, i.title AS title, i.body AS body,
                   i.status AS status, i.issue_type AS issue_type,
                   i.priority AS priority, toString(i.occurredAt) AS occurredAt,
                   creator.name AS creator, assignee.name AS assignee
            """,
            project_id=project_id,
            issue_key=issue_key,
        )
        row = await result.single()
        if not row:
            return {"message": f"이슈를 찾을 수 없습니다: {issue_key}"}
        base = dict(row)

        # 2단계: 스코프 결정 — root + 자식 이슈 메타데이터 (root 자체는 항상 첫 항목)
        result = await session.run(
            f"""
            MATCH (root:Issue {{project_id: $project_id, issue_key: $issue_key}})
            WHERE root.source <> '__stub__'
            OPTIONAL MATCH (desc:Issue)-[:CHILD_OF*1..{_CHILD_DEPTH}]->(root)
            WITH root, collect(DISTINCT desc) AS descs
            UNWIND ([root] + descs) AS i
            WITH i WHERE i IS NOT NULL
            RETURN i.issue_key AS issue_key, i.title AS title, i.status AS status
            """,
            project_id=project_id,
            issue_key=issue_key,
        )
        scope_issues = await result.data()  # 첫 항목이 root, 이후가 descendants

        # 3단계: 스코프 내 각 이슈의 커밋 + PR 일괄 조회 (issue_key 기준 grouping)
        result = await session.run(
            f"""
            MATCH (root:Issue {{project_id: $project_id, issue_key: $issue_key}})
            WHERE root.source <> '__stub__'
            OPTIONAL MATCH (desc:Issue)-[:CHILD_OF*1..{_CHILD_DEPTH}]->(root)
            WITH collect(DISTINCT root) + collect(DISTINCT desc) AS issues_raw
            UNWIND issues_raw AS i
            WITH i WHERE i IS NOT NULL
            OPTIONAL MATCH (cs:ChangeSet)-[tb:TRIGGERED_BY]->(i)
            WHERE coalesce(tb.confidence, 1.0) >= $min_conf
            OPTIONAL MATCH (cs_author:Actor)-[:AUTHORED]->(cs)
            OPTIONAL MATCH (pr:PullRequest)-[:CONTAINS]->(cs)
            RETURN i.issue_key AS issue_key,
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
            issue_key=issue_key,
            min_conf=_MIN_CONFIDENCE,
        )
        work_rows = {r["issue_key"]: r for r in await result.data()}

        # 4단계: 스코프 내 각 이슈의 논의 일괄 조회 (issue_key 기준 grouping)
        result = await session.run(
            f"""
            MATCH (root:Issue {{project_id: $project_id, issue_key: $issue_key}})
            WHERE root.source <> '__stub__'
            OPTIONAL MATCH (desc:Issue)-[:CHILD_OF*1..{_CHILD_DEPTH}]->(root)
            WITH collect(DISTINCT root) + collect(DISTINCT desc) AS issues_raw
            UNWIND issues_raw AS i
            WITH i WHERE i IS NOT NULL
            OPTIONAL MATCH (i)-[disc:DISCUSSED_IN]->(c:Communication)
            OPTIONAL MATCH (c_author:Actor)-[:WROTE]->(c)
            RETURN i.issue_key AS issue_key,
                   collect(DISTINCT {{
                       body: c.body, channel: c.channel, source: c.source,
                       occurredAt: toString(c.occurredAt),
                       conversation_id: c.conversation_id,
                       author: c_author.name,
                       confidence: disc.confidence
                   }}) AS discussions
            """,
            project_id=project_id,
            issue_key=issue_key,
        )
        disc_rows = {r["issue_key"]: r["discussions"] for r in await result.data()}

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
        base.update(_per_issue(issue_key))

        # descendants는 root 제외하고 issue_key 사전순 정렬
        base["descendants"] = [
            {
                "issue_key": i["issue_key"],
                "title":    i["title"],
                "status":   i["status"],
                **_per_issue(i["issue_key"]),
            }
            for i in sorted(scope_issues, key=lambda x: x["issue_key"])
            if i["issue_key"] != issue_key
        ]

        return base

_RANK_TOP_K_MAX = 20


async def rank_issues(project_id: str, by: str = "discussion", top_k: int = 5) -> dict:
    """이슈 전체를 지표로 정렬해 상위 top_k를 반환한다.

    "가장 오래/많이 논의된 티켓", "가장 오래 걸린 이슈"처럼 **전체를 비교해 1등을 뽑는**
    질문용이다. get_timeline은 스코프가 주어진 시간축 조회라 전수 비교를 못 한다 — 그걸로
    답하면 어쩌다 걸린 한 이슈를 최상위로 오인한다(HT-48을 "가장 길게 논의"로 오답).

    by:
      - "discussion" : DISCUSSED_IN 연결 수(논의량) 내림차순
      - "duration"   : createdAt→closedAt 경과일 내림차순 (종료된 이슈만)
    각 행에 두 지표를 함께 실어(discussion_count·duration_days) 모델이 맥락을 본다.
    """
    by = by if by in ("discussion", "duration") else "discussion"
    k = max(1, min(top_k, _RANK_TOP_K_MAX))
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (i:Issue {project_id: $project_id})
            WHERE i.title IS NOT NULL AND i.source <> '__stub__'
            OPTIONAL MATCH (i)-[:DISCUSSED_IN]->(c:Communication)
            WITH i, count(DISTINCT c) AS disc
            WITH i, disc,
                 CASE WHEN i.closedAt IS NOT NULL
                      THEN i.closedAt.epochMillis - i.createdAt.epochMillis
                      ELSE null END AS dur_ms
            RETURN i.issue_key AS issue_key, i.title AS title, i.status AS status,
                   toString(i.createdAt) AS created_at, toString(i.closedAt) AS closed_at,
                   disc AS discussion_count, dur_ms AS duration_ms
            """,
            project_id=project_id,
        )
        rows = await result.data()

    if not rows:
        return {"ranked_by": by, "issues": [], "message": "이슈가 없습니다."}

    # 경과일은 epoch 차이로 계산한다 — duration.between(...).days는 월로 정규화된 뒤
    # 남은 '일 성분'만 줘서(1개월 19일 → 19) 총 경과일을 크게 빗나간다.
    def duration_days(r):
        ms = r.get("duration_ms")
        return round(ms / 86_400_000, 1) if ms is not None else None

    ranked = [
        {
            "issue_key": r["issue_key"],
            "title": _first_line(r["title"], _TITLE_CHARS),
            "status": r["status"],
            "created_at": normalize_time(r["created_at"]),
            "closed_at": normalize_time(r["closed_at"]),
            "discussion_count": r["discussion_count"],
            "duration_days": duration_days(r),
        }
        for r in rows
    ]

    if by == "duration":
        pool = [x for x in ranked if x["duration_days"] is not None]
        pool.sort(key=lambda x: x["duration_days"], reverse=True)
    else:
        pool = sorted(ranked, key=lambda x: x["discussion_count"], reverse=True)

    return {
        "ranked_by": by,
        "total_issues": len(ranked),
        "issues": pool[:k],
    }


async def get_timeline(
    project_id: str,
    issue_key: str | None = None,
    path: str | None = None,
    actor: str | None = None,
    from_time: str | None = None,
    to_time: str | None = None,
) -> dict:
    """시간순 이벤트를 스코프별로 반환한다.

    스코프 우선순위: issue_key > path > actor > (전부 생략 시) 프로젝트 전체.
    from_time/to_time은 어느 스코프와도 조합 가능하며 **이벤트 단위**로 적용된다 —
    노드 단위로 자르면 창 이전에 생성돼 창 안에서 종료된 이슈의 종료 이벤트를 잃는다.

    각 이벤트에는 event_meaning 라벨이 붙어 LLM이 occurredAt만 보고 생성/완료/머지를
    추정하지 않는다 (매핑 단일 출처는 _common.EVENT_SPECS).

    반환:
      {
        scope:  {type, value, ...},              # type = issue|path|actor|project
        window: {requested_from, requested_to, covered_from, covered_to},
        total_events: int,                        # 창 필터 후 전체 (캡 적용 전)
        events: [{type, event_meaning, occurredAt, data}, ...],   # occurredAt 오름차순
        truncated: str,                           # 캡에 걸렸을 때만
      }
    """
    async with get_driver().session() as session:
        if issue_key:
            scope, events = await _issue_events(session, project_id, issue_key)
        elif path:
            scope, events = await _path_events(session, project_id, path)
        elif actor:
            scope, events = await _actor_events(session, project_id, actor)
        else:
            scope, events = {"type": "project"}, await _project_events(session, project_id)

        if events is None:  # 스코프 해석 실패 — scope에 message/candidates가 담겨 있다
            return scope

        return _timeline_result(scope, events, from_time, to_time)


def _in_window(at: str, from_time: str | None, to_time: str | None) -> bool:
    """경계 포함 창 판정. 경계값도 이벤트 시각과 같은 표기로 정규화해서 비교한다 —
    표기가 다르면 사전순 비교가 시간순과 어긋난다(_common._event_time 참고)."""
    if from_time and at < from_time:
        return False
    return not (to_time and at > to_time)


def _event_size(e: dict) -> int:
    return len(json.dumps(e, ensure_ascii=False, default=str))


def _truncate_span(ordered: list[dict]) -> tuple[list[dict], int]:
    """예산 초과 시 **양끝을 남기고 가운데를 자른다**. (표시 이벤트, 생략 건수) 반환.

    앞(가장 오래된 = 시작)과 뒤(가장 최신 = 끝)를 모두 보존한다 — '얼마 동안 진행됐어'
    류 질문은 시작·끝이 둘 다 필요한데, 뒤만 자르면 끝점(issue_closed 등)을 잃어 모델이
    잘린 창의 마지막을 진짜 끝으로 오인하고 기간을 틀리게 계산한다(HT-3 41일 오답).
    양끝을 남기면 covered_to가 실제 마지막 사건이 되어 기간이 맞다. 가운데 생략분은
    truncated로 고지하고, 필요하면 from_time/to_time으로 구간을 좁혀 다시 본다.
    """
    n = len(ordered)
    sizes = [_event_size(e) for e in ordered]
    if n <= _TIMELINE_MAX_EVENTS and sum(sizes) <= _TIMELINE_BUDGET:
        return ordered, 0

    # 뒤(최신)를 먼저 예산의 일부로 확보 — 끝점 보존이 목적이라 앞보다 우선.
    tail_budget = max(int(_TIMELINE_BUDGET * 0.3), 1)
    tail_max = max(_TIMELINE_MAX_EVENTS // 4, 1)
    tail_idx: list[int] = []
    used = 0
    for j in range(n - 1, -1, -1):
        if tail_idx and (used + sizes[j] > tail_budget or len(tail_idx) >= tail_max):
            break
        tail_idx.append(j)
        used += sizes[j]
    tail_start = min(tail_idx)

    # 앞(오래된)을 나머지 예산으로 채우되 tail 시작 전까지만.
    head_idx: list[int] = []
    for i in range(tail_start):
        if head_idx and (used + sizes[i] > _TIMELINE_BUDGET
                         or len(head_idx) + len(tail_idx) >= _TIMELINE_MAX_EVENTS):
            break
        head_idx.append(i)
        used += sizes[i]

    kept_idx = head_idx + sorted(tail_idx)
    kept = [ordered[i] for i in kept_idx]
    return kept, n - len(kept)


def _timeline_result(
    scope: dict, events: list[dict], from_time: str | None, to_time: str | None
) -> dict:
    """창 필터 → 정렬 → 예산 내 양끝 보존 잘림 후 반환 구조를 만든다."""
    lo, hi = normalize_time(from_time), normalize_time(to_time)
    ordered = sort_events([e for e in events if _in_window(e["occurredAt"], lo, hi)])
    total = len(ordered)

    kept, omitted = _truncate_span(ordered)
    result = {
        "scope": scope,
        "window": {
            "requested_from": from_time,
            "requested_to":   to_time,
            "covered_from":   kept[0]["occurredAt"] if kept else None,
            "covered_to":     kept[-1]["occurredAt"] if kept else None,
        },
        "total_events": total,
        "events": kept,
    }
    if omitted:
        result["truncated"] = (
            f"전체 {total}건 중 {len(kept)}건 표시 — 시작·끝 구간은 보존하고 가운데 "
            f"{omitted}건을 생략. covered_from~covered_to는 실제 처음·마지막 사건이라 "
            f"기간 판단에 그대로 쓸 수 있으나, 중간 사건이 필요하면 from_time/to_time으로 "
            f"구간을 좁혀 다시 호출하세요."
        )
    if not kept:
        result["message"] = "해당 스코프·기간에 이벤트가 없습니다."
    return result


def _cs_events(rows: list[dict]) -> list[dict]:
    """커밋 이벤트. issues 키가 있으면 연결 이슈 키를 data에 실어 준다 —
    파일 스코프에서 이슈를 별도 생명주기 이벤트로 만들지 않기 위한 경로다."""
    out = []
    for cs in rows:
        data = {"hash": cs.get("hash"), "message": _first_line(cs.get("message"), _TITLE_CHARS)}
        for k in ("confidence", "link_source"):
            if cs.get(k) is not None:
                data[k] = cs[k]
        if cs.get("issues"):
            data["issues"] = cs["issues"]
        out += expand_events("ChangeSet", cs, data)
    return out


def _pr_events(rows: list[dict]) -> list[dict]:
    return [
        e for pr in rows
        for e in expand_events("PullRequest", pr, {
            "pr_number": pr.get("pr_number"),
            "title": _first_line(pr.get("title"), _TITLE_CHARS), "url": pr.get("url"),
        })
    ]


def _comm_events(rows: list[dict]) -> list[dict]:
    return [
        e for c in rows
        for e in expand_events("Communication", c, {
            "body": (c.get("body") or "")[:_BODY_CHARS] or None,
            "channel": c.get("channel"), "source": c.get("source"),
            "conversation_id": c.get("conversation_id"),
        })
    ]


def _issue_lifecycle_events(rows: list[dict]) -> list[dict]:
    return [
        e for i in rows
        for e in expand_events("Issue", i, {
            "issue_key": i.get("issue_key"),
            "title": _first_line(i.get("title"), _TITLE_CHARS), "status": i.get("status"),
        })
    ]


async def _issue_events(session, project_id: str, issue_key: str) -> tuple[dict, list[dict] | None]:
    """이슈 스코프 — 이슈 생명주기 + 연결된 커밋·PR·논의.

    **CHILD_OF 자식 이슈까지 포함**한다 (get_issue_context와 같은 범위). epic 아래
    하위 작업이 달린 구조에서 root만 보면 "어떤 하위 작업들로 진행됐나"에 답할 수 없고,
    정보량이 더 적은 타임라인이 get_issue_context 드릴다운을 밀어내는 회귀가 생긴다
    (case-05: 자식 이슈 4건 조회가 타임라인 1회로 대체되며 recall 0.778 → 0.111).

    scope에 **root 이슈의 created_at/closed_at**을 실어 준다 — "이 이슈 얼마 동안 진행됐어"의
    권위 있는 답은 이벤트 목록(잘릴 수 있음)이 아니라 이슈 노드의 생애 속성이다. 잘림과
    무관하게 기간을 정확히 계산하게 한다(HT-3 41일 오답 방지).
    """
    result = await session.run(
        f"""
        MATCH (root:Issue {{project_id: $project_id, issue_key: $issue_key}})
        OPTIONAL MATCH (desc:Issue)-[:CHILD_OF*1..{_CHILD_DEPTH}]->(root)
        WITH root, collect(DISTINCT root) + collect(DISTINCT desc) AS issues_raw
        UNWIND issues_raw AS i
        WITH root, i WHERE i IS NOT NULL
        OPTIONAL MATCH (cs:ChangeSet)-[tb:TRIGGERED_BY]->(i)
            WHERE coalesce(tb.confidence, 1.0) >= $min_conf
        OPTIONAL MATCH (pr:PullRequest)-[:CONTAINS]->(cs)
        RETURN toString(root.createdAt) AS root_created,
               toString(root.closedAt) AS root_closed,
               root.status AS root_status,
               collect(DISTINCT {{
                   createdAt: toString(i.createdAt), closedAt: toString(i.closedAt),
                   issue_key: i.issue_key, title: i.title, status: i.status
               }}) AS issues,
               collect(DISTINCT {{
                   occurredAt: toString(cs.occurredAt),
                   hash: cs.hash, message: cs.message,
                   confidence: tb.confidence, link_source: tb.source
               }}) AS changesets,
               collect(DISTINCT {{
                   createdAt: toString(pr.createdAt),
                   occurredAt: toString(pr.occurredAt),
                   pr_number: pr.pr_number, title: pr.title, url: pr.url
               }}) AS pull_requests
        """,
        project_id=project_id, issue_key=issue_key, min_conf=_MIN_CONFIDENCE,
    )
    row = await result.single()
    if not row or not row["issues"] or not any(i.get("issue_key") for i in row["issues"]):
        return {"type": "issue", "value": issue_key}, None

    scope = {
        "type": "issue", "value": issue_key,
        "created_at": normalize_time(row["root_created"]),
        "closed_at":  normalize_time(row["root_closed"]),
        "status":     row["root_status"],
    }

    events = (
        _issue_lifecycle_events(row["issues"])
        + _cs_events(row["changesets"])
        + _pr_events(row["pull_requests"])
    )

    # 논의는 이슈와 독립적이므로 별도 쿼리 (자식 이슈의 논의까지 포함)
    result2 = await session.run(
        f"""
        MATCH (root:Issue {{project_id: $project_id, issue_key: $issue_key}})
        OPTIONAL MATCH (desc:Issue)-[:CHILD_OF*1..{_CHILD_DEPTH}]->(root)
        WITH collect(DISTINCT root) + collect(DISTINCT desc) AS issues_raw
        UNWIND issues_raw AS i
        WITH i WHERE i IS NOT NULL
        MATCH (i)-[:DISCUSSED_IN]->(c:Communication)
        RETURN collect(DISTINCT {{
            occurredAt: toString(c.occurredAt),
            body: c.body, channel: c.channel, source: c.source,
            conversation_id: c.conversation_id
        }}) AS communications
        """,
        project_id=project_id, issue_key=issue_key,
    )
    row2 = await result2.single()
    if row2:
        events += _comm_events(row2["communications"])
    return scope, events


async def _path_events(session, project_id: str, path: str) -> tuple[dict, list[dict] | None]:
    """파일 스코프 — 그 파일을 변경한 커밋 + 담은 PR + 연결된 이슈.

    strict 매칭이 0건이면 get_file_history와 같은 규칙으로 basename → stem 폴백한다.
    후보가 2개 이상이면 이벤트 대신 candidates를 돌려 재호출을 유도한다.
    """
    resolved, resolved_via, candidates = await _resolve_path(session, project_id, path)
    if candidates is not None:
        return {
            "type": "path", "value": path, "candidates": candidates,
            "message": "경로 후보가 여러 개입니다. 하나를 골라 다시 호출하세요.",
        }, None

    scope: dict = {"type": "path", "value": path}
    if resolved_via:
        scope["resolved_path"] = resolved
        scope["resolved_via"] = resolved_via

    # 연결 이슈는 **커밋 data에 키만** 싣는다. 이슈 생명주기를 별도 이벤트로 펼치면
    # 파일 하나에 연결된 이슈 수십 개의 생성/완료가 정작 그 파일을 바꾼 커밋을 덮는다
    # (orchestrator: 파일 이력 질문의 인용 앵커는 파일을 실제 변경한 커밋).
    # 이슈 자체의 시간축이 필요하면 그 issue_key로 이슈 스코프를 다시 호출한다.
    result = await session.run(
        """
        MATCH (cs:ChangeSet {project_id: $project_id})-[:MODIFIED]->(:File {path: $path})
        OPTIONAL MATCH (cs)-[tb:TRIGGERED_BY]->(i:Issue)
            WHERE coalesce(tb.confidence, 1.0) >= $min_conf
        WITH cs, collect(DISTINCT i.issue_key) AS issue_keys
        OPTIONAL MATCH (pr:PullRequest)-[:CONTAINS]->(cs)
        RETURN collect(DISTINCT {
                   occurredAt: toString(cs.occurredAt),
                   hash: cs.hash, message: cs.message, issues: issue_keys
               }) AS changesets,
               collect(DISTINCT {
                   createdAt: toString(pr.createdAt), occurredAt: toString(pr.occurredAt),
                   pr_number: pr.pr_number, title: pr.title, url: pr.url
               }) AS pull_requests
        """,
        project_id=project_id, path=resolved, min_conf=_MIN_CONFIDENCE,
    )
    row = await result.single()
    if not row:
        return scope, []
    return scope, _cs_events(row["changesets"]) + _pr_events(row["pull_requests"])


async def _resolve_path(session, project_id: str, path: str):
    """(해석된 경로, 해석 방식|None, 후보목록|None). 후보목록이 있으면 모호한 상태다."""
    exact = await session.run(
        "MATCH (f:File {project_id: $project_id, path: $path}) RETURN count(f) AS c",
        project_id=project_id, path=path,
    )
    if (await exact.single())["c"] > 0:
        return path, None, None

    basename = path.rsplit("/", 1)[-1]
    for finder, via in (
        (_find_files_ending_with, "basename_match"),
        (_find_files_by_stem, "stem_match"),
    ):
        key = basename if via == "basename_match" else basename.rsplit(".", 1)[0]
        found = await finder(session, project_id, key, _FUZZY_CANDIDATE_LIMIT)
        if len(found) == 1:
            return found[0], via, None
        if len(found) > 1:
            return path, None, found
    return path, None, None


async def _actor_events(session, project_id: str, actor: str) -> tuple[dict, list[dict] | None]:
    """사람 스코프 — 이름·alias·email 중 하나로 Actor를 찾아 그 사람의 활동 이벤트."""
    result = await session.run(
        """
        MATCH (a:Actor {project_id: $project_id})
        WHERE a.name = $actor
           OR $actor IN a.aliases
           OR EXISTS { MATCH (al:ActorAlias)-[:ALIAS_OF]->(a) WHERE al.pd_email = $actor OR al.pd_name = $actor }
        OPTIONAL MATCH (a)-[:AUTHORED]->(cs:ChangeSet)
        OPTIONAL MATCH (a)-[:AUTHORED]->(pr:PullRequest)
        OPTIONAL MATCH (a)-[:WROTE]->(c:Communication)
        OPTIONAL MATCH (a)-[:CREATED]->(i:Issue)
        RETURN a.name AS name,
               collect(DISTINCT {occurredAt: toString(cs.occurredAt),
                                 hash: cs.hash, message: cs.message}) AS changesets,
               collect(DISTINCT {createdAt: toString(pr.createdAt),
                                 occurredAt: toString(pr.occurredAt),
                                 pr_number: pr.pr_number, title: pr.title, url: pr.url}) AS pull_requests,
               collect(DISTINCT {occurredAt: toString(c.occurredAt), body: c.body,
                                 channel: c.channel, source: c.source,
                                 conversation_id: c.conversation_id}) AS communications,
               collect(DISTINCT {createdAt: toString(i.createdAt), closedAt: toString(i.closedAt),
                                 issue_key: i.issue_key, title: i.title, status: i.status}) AS issues
        """,
        project_id=project_id, actor=actor,
    )
    row = await result.single()
    if not row or not row["name"]:
        return {"type": "actor", "value": actor,
                "message": f"해당 사람을 찾지 못했습니다: {actor}"}, None
    return {"type": "actor", "value": actor, "resolved_name": row["name"]}, (
        _cs_events(row["changesets"])
        + _pr_events(row["pull_requests"])
        + _comm_events(row["communications"])
        + _issue_lifecycle_events(row["issues"])
    )


async def _project_events(session, project_id: str) -> list[dict]:
    """프로젝트 전체 스코프 — 전 노드 타입을 이벤트로 펼친다.

    레이블별로 나눠 조회한다 — `MATCH (n) WHERE n:A OR n:B`는 레이블 disjunction이라
    전 노드 스캔으로 떨어지고, 라벨별 필드도 달라 한 쿼리로 묶을 실익이 없다.
    """
    cs = await (await session.run(
        "MATCH (n:ChangeSet {project_id: $p}) "
        "RETURN collect({occurredAt: toString(n.occurredAt), hash: n.hash, message: n.message}) AS r",
        p=project_id)).single()
    pr = await (await session.run(
        "MATCH (n:PullRequest {project_id: $p}) "
        "RETURN collect({createdAt: toString(n.createdAt), occurredAt: toString(n.occurredAt), "
        "pr_number: n.pr_number, title: n.title, url: n.url}) AS r",
        p=project_id)).single()
    comm = await (await session.run(
        "MATCH (n:Communication {project_id: $p}) "
        "RETURN collect({occurredAt: toString(n.occurredAt), body: n.body, channel: n.channel, "
        "source: n.source, conversation_id: n.conversation_id}) AS r",
        p=project_id)).single()
    iss = await (await session.run(
        "MATCH (n:Issue {project_id: $p}) WHERE n.source <> '__stub__' "
        "RETURN collect({createdAt: toString(n.createdAt), closedAt: toString(n.closedAt), "
        "issue_key: n.issue_key, title: n.title, status: n.status}) AS r",
        p=project_id)).single()

    return (
        _cs_events(cs["r"]) + _pr_events(pr["r"])
        + _comm_events(comm["r"]) + _issue_lifecycle_events(iss["r"])
    )
