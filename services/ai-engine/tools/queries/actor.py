"""사람(Actor) 컨텍스트 조회 — 전문가 추천, 활동 내역, 동일인 inspect."""

import os
import re

from tools.queries._common import _detail_count_for_budget, _priority_order, get_driver


# get_actor_activity 2계층 반환 노브 (file_history와 같은 정책 — env로 eval 스윕).
# 카테고리별 최신순 [0..20] 컷이 관련 있는 옛 활동을 떨어뜨리고, limit 확대는 rich 행
# 대량 인용(환각 폭주, 2026-07-14 롤백)을 부르던 것을 — detail(본문 포함, 바이트 예산)과
# context(stub 개요, 본문 없음) 분리로 해소한다. Actor 수동 병합으로 액터당 엣지가
# 늘어나면 컷 문제가 커지므로 병합 전에 반환 정책을 바꿔 둔다.
_ACTIVITY_FETCH_MAX = int(os.environ.get("ACTOR_ACTIVITY_MAX", "100"))          # 카테고리별 조회 상한(최신순)
_ACTIVITY_DETAIL_BUDGET = int(os.environ.get("ACTOR_ACTIVITY_DETAIL_BUDGET", "4000"))  # detail 직렬화 합 상한(자)
# context stub 상한 — 종류별(커밋·메시지·PR)로 독립 배정한다. 메시지가 가장 많고 최신이라
# 종류 구분 없는 총량 상한을 쓰면 메시지가 자리를 독식해 오래된 PR·커밋이 개요에서도
# 사라진다(모델이 드릴다운할 식별자를 잃는다).
_ACTIVITY_CONTEXT_CAP_PER_KIND = int(os.environ.get("ACTOR_ACTIVITY_CONTEXT_CAP_PER_KIND", "10"))
_ISSUES_CAP = int(os.environ.get("ACTOR_ACTIVITY_ISSUES_CAP", "20"))             # 생성/담당 이슈 리스트 상한
_ISSUE_TITLE_MAX_CHARS = 40
_ACTIVITY_DETAIL_K_MAX = 20        # 카테고리별 detail 행 수 하드 상한 (기존 limit 20과 동일한 천장)
_MESSAGE_MAX_CHARS = 400           # detail 커밋 메시지 상한 (file_history와 동일)
_BODY_MAX_CHARS = 200              # detail 메시지 본문 상한 (기존과 동일)
_STUB_TITLE_MAX_CHARS = 100

# detail 예산의 카테고리 배분 — 한 카테고리가 예산을 독식해 다른 카테고리가 전멸하는 것 방지.
_BUDGET_SPLIT = {"commit": 0.45, "message": 0.35, "pull_request": 0.20}

_TIER_NOTE = (
    "detail=본문 포함(인용 대상, 커밋은 최신순·메시지는 질문 관련도순), "
    "context=나머지 활동의 시간순 개요(본문 없음). context 항목을 근거로 쓰려면 "
    "commit→get_changeset_context, message→get_thread_context, "
    "pull_request→get_pr_context로 본문을 조회한 뒤 인용하세요."
)

# context_truncated 문구용 — kind 코드를 사람이 읽는 표기로.
_KIND_LABEL_KO = {"commit": "커밋", "message": "메시지", "pull_request": "PR"}


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

# ─── 2계층 행 렌더러 (순수 함수 — Neo4j 없이 단위 테스트 가능) ──────────────────


def _commit_detail(r: dict, ranked: bool) -> dict:
    message = r.get("message") or ""
    if len(message) > _MESSAGE_MAX_CHARS:
        message = message[:_MESSAGE_MAX_CHARS] + " …(생략)"
    return {"kind": "commit", "hash": r["hash"], "message": message, "occurredAt": r.get("occurredAt")}


def _pr_detail(r: dict, ranked: bool) -> dict:
    return {"kind": "pull_request", "pr_number": r["pr_number"], "title": r.get("title"),
            "occurredAt": r.get("occurredAt")}


def _comm_detail(r: dict, ranked: bool) -> dict:
    body = r.get("body") or ""
    row = {
        "kind": "message",
        "conversation_id": r.get("conversation_id"),
        "channel": r.get("channel"),
        "body": body[:_BODY_MAX_CHARS],
        "occurredAt": r.get("occurredAt"),
    }
    if ranked and r.get("relevance") is not None:
        row["relevance"] = round(r["relevance"], 3)
    return row


def _first_line(text: str | None) -> str:
    text = (text or "").strip()
    return text.splitlines()[0][:_STUB_TITLE_MAX_CHARS] if text else ""


def _issue_key_order(issue: dict) -> int:
    """issue_key 번호 내림차순 정렬용 — 번호가 클수록 최근 이슈 (이슈 쿼리엔 시각이 없다).

    끝자리 연속 숫자를 정규식으로 뽑는다 — "HT-45"뿐 아니라 GitHub 이슈 키("#142")도
    끝자리 숫자로 정렬된다. 끝자리에 숫자가 없으면 -1(가장 오래된 취급).
    """
    key = issue.get("issue_key") or ""
    m = re.search(r"(\d+)$", key)
    return int(m.group(1)) if m else -1


def _cap_issues(issues: list[dict], cap: int = _ISSUES_CAP) -> tuple[list[dict], int, str]:
    """생성/담당 이슈 리스트를 최근(번호 큰) 순으로 cap까지만 — 무상한 리스트가 응답 예산을
    독식해 detail·context가 밀려나던 것 방지 (2인 팀은 전체 이슈가 두 사람에게 귀속된다).

    잘린 이슈는 key만 컴팩트하게 이어붙여 반환한다 — 오래된(번호 작은) 이슈도 존재가
    보이고 인용·드릴다운(get_issue_context)이 가능해야 한다. 제목이 필요한 초기 이슈를
    통째로 숨기면 "담당 이슈 정리" 질문에서 초기 대표작이 증발한다.

    Returns: (capped_list, total, overflow_keys) — overflow_keys는 잘린 key들의 쉼표 문자열.
    """
    valid = [i for i in issues if i.get("issue_key")]
    valid.sort(key=_issue_key_order, reverse=True)
    capped = [
        {"issue_key": i["issue_key"],
         "title": (i.get("title") or "")[:_ISSUE_TITLE_MAX_CHARS]}
        for i in valid[:cap]
    ]
    overflow_keys = ", ".join(i["issue_key"] for i in valid[cap:])
    return capped, len(valid), overflow_keys


def _stub(kind: str, r: dict) -> dict:
    """개요 stub — 식별자·시각·제목만, 본문 없음(대량 인용 구조적 차단). 드릴다운 id 포함."""
    if kind == "commit":
        return {"kind": kind, "hash": r["hash"], "title": _first_line(r.get("message")),
                "occurredAt": r.get("occurredAt")}
    if kind == "pull_request":
        return {"kind": kind, "pr_number": r["pr_number"], "title": _first_line(r.get("title")),
                "occurredAt": r.get("occurredAt")}
    return {"kind": "message", "conversation_id": r.get("conversation_id"),
            "channel": r.get("channel"), "occurredAt": r.get("occurredAt")}


def _build_activity_tiers(
    commits: list[dict], prs: list[dict], comms: list[dict], comm_ranked: bool,
    budget: int = _ACTIVITY_DETAIL_BUDGET, context_cap_per_kind: int = _ACTIVITY_CONTEXT_CAP_PER_KIND,
) -> tuple[list[dict], list[dict], dict[str, int]]:
    """카테고리별 예산으로 detail을 뽑고 나머지를 stub으로 내려 시간순 병합한다.

    커밋·PR은 최신순(노드 임베딩 없음), 메시지는 comm_ranked=True면 관련도순으로 승격.
    카테고리별 예산 배분(_BUDGET_SPLIT)으로 한 카테고리의 독식을 막는다.

    leftover stub도 **종류별로 독립 상한**을 적용한 뒤 합쳐 시간순으로 병합한다 — 메시지가
    가장 많고 최신이라 종류 구분 없는 총량 상한을 쓰면 메시지가 자리를 독식해 오래된 PR·커밋이
    context에서도 사라진다(모델이 드릴다운할 식별자를 잃는다).

    Returns: (detail, context, overflow_by_kind) — overflow_by_kind는 종류별로 상한 초과해
    생략된 stub 수(초과가 없는 종류는 키 자체가 없다).
    """
    plan = [
        ("commit", commits, False, _commit_detail),
        ("message", comms, comm_ranked, _comm_detail),
        ("pull_request", prs, False, _pr_detail),
    ]
    detail: list[dict] = []
    leftovers_by_kind: dict[str, list[dict]] = {}
    for kind, rows, ranked, render in plan:
        k = _detail_count_for_budget(rows, ranked, int(budget * _BUDGET_SPLIT[kind]),
                                     _ACTIVITY_DETAIL_K_MAX, render)
        chosen = _priority_order(rows, ranked)[:k]
        chosen_ids = {id(r) for r in chosen}
        detail.extend(render(r, ranked) for r in chosen)
        leftovers_by_kind[kind] = [r for r in rows if id(r) not in chosen_ids]

    detail.sort(key=lambda r: r.get("occurredAt") or "", reverse=True)

    context: list[dict] = []
    overflow: dict[str, int] = {}
    for kind, rows in leftovers_by_kind.items():
        rows_sorted = sorted(rows, key=lambda r: r.get("occurredAt") or "", reverse=True)
        kept = rows_sorted[:context_cap_per_kind]
        context.extend(_stub(kind, r) for r in kept)
        if len(rows_sorted) > len(kept):
            overflow[kind] = len(rows_sorted) - len(kept)

    context.sort(key=lambda r: r.get("occurredAt") or "", reverse=True)
    return detail, context, overflow


# Actor 식별자(이름/alias/이메일) 정확 일치 WHERE 조각 — _resolve_actor 1차 시도에서만 쓴다.
# 개인정보(표시 이름·이메일)는 ActorAlias로 이전됐으므로 EXISTS 서브쿼리로 조회한다.
# al.pd_name 매칭은 의도된 확장 — 표시 이름이 GitHub 기준으로 바뀌어도 LLM이 Jira 실명("김영희")
# 으로 사람을 찾을 수 있어야 한다. ALIAS_OF는 항상 같은 project 안에서만 이어지므로 project_id
# 중복 필터는 불필요하다.
_ACTOR_MATCH_WHERE = """a.name = $identifier
   OR $identifier IN a.aliases
   OR EXISTS { MATCH (al:ActorAlias)-[:ALIAS_OF]->(a) WHERE al.pd_email = $identifier OR al.pd_name = $identifier }"""

# 후보 상한 — candidates로 되돌릴 목록이 무한정 늘어나지 않게 한다.
# 잘림을 알리려면 상한보다 1건 더 조회해야 한다(_actor_ambiguous_response가 고지).
_ACTOR_RESOLVE_LIMIT = 6


async def _resolve_actor(session, project_id: str, identifier: str) -> list[dict]:
    """식별자(이름/alias/이메일)로 매칭되는 Actor 후보를 조회한다.

    1차: 정확 일치(_ACTOR_MATCH_WHERE, 오늘의 동작과 동일) — 매칭이 있으면 그대로 반환하고
    2차를 실행하지 않는다(정확 일치 입력의 동작을 완전히 보존하기 위한 순서).
    2차(1차가 0건일 때만): 대소문자 무시 부분 일치 — "junsu"처럼 부분 이름·대소문자가
    다른 입력을 구제한다.

    호출부는 반환 건수로 없음(0)/바로 사용(1)/모호(2+, candidates)를 판단한다.
    """
    result = await session.run(
        """
        MATCH (a:Actor {project_id: $project_id})
        WHERE """ + _ACTOR_MATCH_WHERE + """
        RETURN a.uuid AS uuid, a.name AS name, a.aliases AS aliases
        LIMIT $resolve_limit
        """,
        project_id=project_id, identifier=identifier, resolve_limit=_ACTOR_RESOLVE_LIMIT + 1,
    )
    exact = await result.data()
    if exact:
        return exact

    result = await session.run(
        """
        MATCH (a:Actor {project_id: $project_id})
        WHERE toLower(a.name) CONTAINS toLower($identifier)
           OR any(x IN a.aliases WHERE toLower(x) CONTAINS toLower($identifier))
           OR EXISTS {
                MATCH (al:ActorAlias)-[:ALIAS_OF]->(a)
                WHERE toLower(al.pd_name) CONTAINS toLower($identifier)
                   OR toLower(al.pd_email) CONTAINS toLower($identifier)
              }
        RETURN a.uuid AS uuid, a.name AS name, a.aliases AS aliases
        LIMIT $resolve_limit
        """,
        project_id=project_id, identifier=identifier, resolve_limit=_ACTOR_RESOLVE_LIMIT + 1,
    )
    return await result.data()


def _actor_ambiguous_response(candidates: list[dict]) -> dict:
    """후보 목록 응답.

    **alias를 함께 안내하는 이유**: Actor 동일인 판단이 실패해 같은 사람이 여러 노드로 갈리면
    표시 이름까지 같을 수 있다. 그때 "이름으로 재호출"은 같은 모호함으로 돌아오는 지시가 된다 —
    alias는 소스별로 유일하므로(예: GITHUB:se-zero) 그쪽이 탈출로다.
    """
    message = (
        "이 이름에 해당하는 사람이 여러 명입니다. candidates 중 한 명의 이름 또는 alias로 다시 "
        "호출하세요 — 표시 이름이 서로 같으면 alias가 유일한 구분자입니다."
    )
    if len(candidates) > _ACTOR_RESOLVE_LIMIT:
        candidates = candidates[:_ACTOR_RESOLVE_LIMIT]
        message += f" 후보가 {_ACTOR_RESOLVE_LIMIT}명을 넘어 일부만 표시했습니다 — 이름을 더 길게 지정하세요."
    return {
        "message": message,
        "candidates": [{"name": c["name"], "aliases": c["aliases"]} for c in candidates],
    }


async def get_actor_activity(
    project_id: str,
    identifier: str,
    from_time: str | None = None,
    limit: int | None = None,
    question_embedding: list[float] | None = None,
) -> dict:
    """사람의 활동(커밋·PR·메시지·이슈)을 2계층(detail/context)으로 반환한다.

    detail은 본문 포함 인용 대상(카테고리별 바이트 예산), context는 나머지 활동의
    시간순 stub. 메시지는 question_embedding이 있으면 Communication 임베딩과의
    관련도로 승격한다(커밋·PR은 노드 임베딩이 없어 최신순).
    """
    fetch_cap = min(limit, _ACTIVITY_FETCH_MAX) if limit else _ACTIVITY_FETCH_MAX
    async with get_driver().session() as session:
        candidates = await _resolve_actor(session, project_id, identifier)
        if not candidates:
            return {"message": f"Actor를 찾을 수 없습니다: {identifier}"}
        if len(candidates) > 1:
            return _actor_ambiguous_response(candidates)
        actor_uuid = candidates[0]["uuid"]

        # Actor 메타(표시명·alias·이메일) — 해석된 uuid로 확정 스코프
        result = await session.run(
            """
            MATCH (a:Actor {project_id: $project_id, uuid: $actor_uuid})
            RETURN a.name AS name, a.aliases AS aliases,
                   [x IN [(al:ActorAlias)-[:ALIAS_OF]->(a) | al.pd_email] WHERE x IS NOT NULL] AS emails
            """,
            project_id=project_id, actor_uuid=actor_uuid,
        )
        actor_row = await result.single()
        if not actor_row:
            # resolve 직후라 정상 경로에선 반드시 있다. 연동 해제(delete_project_source_graph)가
            # 두 쿼리 사이에 끼어 노드가 사라진 경우에만 비며, 그때 dict(None) TypeError로
            # 터뜨리지 않고 안내로 떨어뜨린다.
            return {"message": f"Actor를 찾을 수 없습니다: {identifier}"}
        actor = dict(actor_row)

        # 커밋 (최신순)
        result = await session.run(
            """
            MATCH (a:Actor {project_id: $project_id, uuid: $actor_uuid})-[:AUTHORED]->(cs:ChangeSet)
            WHERE $from_time IS NULL OR cs.occurredAt >= datetime($from_time)
            WITH cs ORDER BY cs.occurredAt DESC LIMIT $fetch_cap
            RETURN cs.hash AS hash, cs.message AS message, toString(cs.occurredAt) AS occurredAt
            """,
            project_id=project_id, actor_uuid=actor_uuid, from_time=from_time, fetch_cap=fetch_cap,
        )
        commits = await result.data()

        # PR (최신순)
        result = await session.run(
            """
            MATCH (a:Actor {project_id: $project_id, uuid: $actor_uuid})-[:AUTHORED]->(pr:PullRequest)
            WHERE $from_time IS NULL OR pr.occurredAt >= datetime($from_time)
            WITH pr ORDER BY pr.occurredAt DESC LIMIT $fetch_cap
            RETURN pr.pr_number AS pr_number, pr.title AS title, toString(pr.occurredAt) AS occurredAt
            """,
            project_id=project_id, actor_uuid=actor_uuid, from_time=from_time, fetch_cap=fetch_cap,
        )
        prs = await result.data()

        # 메시지 (최신순 조회 + 질문 관련도 계산 — 승격은 Python 단에서)
        result = await session.run(
            """
            MATCH (a:Actor {project_id: $project_id, uuid: $actor_uuid})-[:WROTE]->(c:Communication)
            WHERE $from_time IS NULL OR c.occurredAt >= datetime($from_time)
            WITH c ORDER BY c.occurredAt DESC LIMIT $fetch_cap
            RETURN c.body AS body, c.channel AS channel,
                   c.conversation_id AS conversation_id,
                   toString(c.occurredAt) AS occurredAt,
                   CASE WHEN $q_embedding IS NULL OR c.embedding IS NULL THEN null
                        ELSE vector.similarity.cosine(c.embedding, $q_embedding) END AS relevance
            """,
            project_id=project_id, actor_uuid=actor_uuid, from_time=from_time,
            fetch_cap=fetch_cap, q_embedding=question_embedding,
        )
        comms = await result.data()

        comm_ranked = bool(question_embedding)
        detail, context, overflow = _build_activity_tiers(commits, prs, comms, comm_ranked)
        # 조회 상한에 걸린 카운트는 "N+" 문자열로 — 정수로 주면 모델이 "커밋 100건"처럼
        # 절대 수치로 단정한다(실측: case-01 환각). 값 자체가 하한임을 드러낸다.
        actor["totals"] = {
            key: f"{len(rows)}+ (최신 {fetch_cap}건 조회 상한 도달 — 실제는 더 많을 수 있음)"
                 if len(rows) >= fetch_cap else len(rows)
            for key, rows in (("changesets", commits), ("pull_requests", prs), ("communications", comms))
        }
        actor["ranked_by"] = {"message": "relevance" if comm_ranked else "recency",
                              "commit": "recency", "pull_request": "recency"}
        actor["detail"] = detail
        actor["context"] = context
        if overflow:
            parts = ", ".join(f"{_KIND_LABEL_KO[kind]} {n}건" for kind, n in overflow.items())
            actor["context_truncated"] = f"context 개요 생략 — {parts}."
        actor["_note"] = _TIER_NOTE

        # Jira 생성 / 담당
        result = await session.run(
            """
            MATCH (a:Actor {project_id: $project_id, uuid: $actor_uuid})
            OPTIONAL MATCH (a)-[:CREATED]->(i:Issue)
            OPTIONAL MATCH (assigned:Issue)-[:ASSIGNED_TO]->(a)
            RETURN collect(DISTINCT {issue_key: i.issue_key, title: i.title}) AS issues_created,
                   collect(DISTINCT {issue_key: assigned.issue_key, title: assigned.title}) AS issues_assigned
            """,
            project_id=project_id,
            actor_uuid=actor_uuid,
        )
        row = await result.single()
        if row:
            for key in ("issues_created", "issues_assigned"):
                capped, total, overflow_keys = _cap_issues(row[key])
                actor[key] = capped
                if overflow_keys:
                    actor[f"{key}_total"] = total
                    actor[f"{key}_older_keys"] = (
                        overflow_keys + " — 제목이 필요하면 get_issue_context로 조회"
                    )

        return actor

async def inspect_actor(project_id: str, identifier: str) -> dict:
    async with get_driver().session() as session:
        candidates = await _resolve_actor(session, project_id, identifier)
        if not candidates:
            return {"message": f"Actor를 찾을 수 없습니다: {identifier}"}
        if len(candidates) > 1:
            return _actor_ambiguous_response(candidates)
        actor_uuid = candidates[0]["uuid"]

        result = await session.run(
            """
            MATCH (a:Actor {project_id: $project_id, uuid: $actor_uuid})
            OPTIONAL MATCH (al:ActorAlias)-[:ALIAS_OF]->(a)
            WITH a, collect(DISTINCT al.pd_email) AS raw_emails
            RETURN a.uuid AS uuid,
                   a.name AS display_name,
                   a.aliases AS all_aliases,
                   [x IN raw_emails WHERE x IS NOT NULL] AS emails,
                   count { (a)-[:AUTHORED]->(:ChangeSet) } AS commit_count,
                   count { (a)-[:AUTHORED]->(:PullRequest) } AS pr_count,
                   count { (a)-[:WROTE]->(:Communication) } AS message_count,
                   count { (a)-[:CREATED]->(:Issue) } AS issue_created_count
            """,
            project_id=project_id,
            actor_uuid=actor_uuid,
        )
        row = await result.single()
        if not row:
            return {"message": f"Actor를 찾을 수 없습니다: {identifier}"}
        return dict(row)
