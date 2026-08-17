"""
프로젝트 그래프 조회 (read-only).

두 가지 읽기 페이로드를 만든다 — 모두 `{nodes, edges}`(서브그래프는 `seeds` 추가):
  - get_project_overview: 그래프 탐색 화면용 최근 활동 개요.
  - get_evidence_subgraph: 대화 답변 evidence가 가리키는 관련 서브그래프(그래프 패널용).
모든 Cypher는 project_id로 스코프되어 다른 프로젝트의 노드/엣지를 절대 반환하지 않는다.

주의:
  - embedding 속성(1536차원 벡터)은 응답에 넣지 않는다 — 스칼라 필드만 명시적으로 select.
  - 노드 id는 elementId(n)를 그대로 쓴다. 라벨 간 자연키 충돌 걱정 없이 엣지 양 끝을 맞출 수 있다.
"""

import logging
import os
import re

from graph.builder import get_driver

logger = logging.getLogger(__name__)

# overview는 "최근 활동" 개요다 — 전체 그래프를 다 내리지 않는다.
DEFAULT_LIMIT = 200
MAX_LIMIT = 500

# snippet 본문 컷 (프론트 카드 미리보기용)
_SNIPPET_LEN = 240

# 프론트 type → content 노드 선택 술어.
# 프론트의 type은 제품명이 아니라 **렌더링 분류**다 — "jira"는 이슈 트래커 전체(Linear·Asana…),
# "slack"은 대화 전체(Discord·Teams·Google Chat…)를 가리킨다. 실제 출처는 노드의 source가 싣는다.
# Communication은 GitHub 이슈(issue)와 대화(slack)가 같은 라벨이라 source로 가른다. 이 분기는
# _to_graph_node의 `is_github`와 **같은 기준이어야 한다** — 어긋나면 대화가 "slack"으로 그려지는데
# "slack" 필터에서는 빠져 화면에서 사라진다(옛 `source = 'SLACK'`이 그랬다).
# 값은 고정 Cypher 조각이고 사용자 입력은 키 매칭에만 쓰므로 인젝션 위험 없음.
_CONTENT_TYPE_PREDICATES = {
    "commit": "n:ChangeSet",
    "pr":     "n:PullRequest",
    "jira":   "n:Issue",
    "issue":  "(n:Communication AND n.source = 'GITHUB')",
    # coalesce — source가 빈 레거시 노드도 대화로 본다(_to_graph_node가 그렇게 렌더한다)
    "slack":  "(n:Communication AND coalesce(n.source, '') <> 'GITHUB')",
    "doc":    "n:Document",
}
# DocumentSection은 의도적으로 뺐다 — 검색 내부 단위지 사용자가 볼 개체가 아니다
# (docs/notion-integration.md §6-5). content/확장 술어 어디에도 넣지 않는다.
_ALL_CONTENT_PRED = "n:ChangeSet OR n:PullRequest OR n:Issue OR n:Communication OR n:Document"

# 내용이 빈 Document pre-node를 응답에서 감춘다.
# writes.link_document_to_parent가 부모 page를 실키 pre-node로 MERGE하는데, Notion에서는
# 하위 페이지만 연동에 공유하고 상위는 공유하지 않는 사용이 흔하다 — 그러면 부모의 본
# 이벤트가 영영 오지 않아 external_id만 있는 빈 노드로 남고, 노드가 적은 초기 그래프에서
# "(문서)" 카드로 그려진다. 부모가 나중에 공유되면 upsert_document가 같은 MERGE 키로 채우므로
# 이 가드는 저절로 풀린다(__stub__ Issue가 흡수되는 것과 같은 성질).
#
# 판별을 title이 아니라 occurredAt으로 하는 이유: 실제 문서도 normalizer가 title을 JSON null로
# 보내면 title이 NULL이 될 수 있어(_handle_document의 url 주석과 같은 함정) 진짜 문서를 감출
# 위험이 있다. occurredAt은 checkpoint 전진 기준이라 실제 문서에는 항상 있다(없으면 수집 자체가
# 실패한다).
_EMPTY_DOCUMENT_PRED = "(n:Document AND n.occurredAt IS NULL)"

# 확장(이웃) 타입 — content 노드에 매달린 Actor/File만.
_EXPANSION_LABELS = {"actor": "nb:Actor", "code": "nb:File"}
_ALL_EXPANSION_PRED = "nb:Actor OR nb:File"

# _to_graph_node 변환에 필요한 스칼라 필드 — overview/subgraph 쿼리가 공유한다.
# embedding은 절대 포함하지 않는다(스칼라만 명시).
_NODE_RETURN_FIELDS = """elementId(n)        AS id,
       labels(n)[0]        AS label,
       coalesce(n.source, '') AS source,
       n.hash              AS hash,
       n.message           AS message,
       n.pr_number         AS pr_number,
       n.title             AS title,
       n.body              AS body,
       n.issue_key          AS issue_key,
       n.status            AS status,
       n.url               AS url,
       n.channel           AS channel,
       n.conversation_id   AS conversation_id,
       n.name              AS name,
       n.aliases           AS aliases,
       n.path              AS path,
       n.external_id       AS external_id,
       toString(n.occurredAt) AS occurred_at"""


def _node_query(content_pred: str, neighbor_pred: str) -> str:
    """content/이웃 선택 술어를 끼운 노드 조회 Cypher를 만든다.

    술어는 고정 조각만 들어가고(사용자 입력 미삽입), 항상 project_id로 스코프된다.
    embedding은 절대 select하지 않는다 — 스칼라 필드만 명시.
    """
    return f"""
MATCH (n)
WHERE n.project_id = $project_id
  AND ({content_pred})
  AND NOT (n:Issue AND n.source = '__stub__')
  AND NOT {_EMPTY_DOCUMENT_PRED}
WITH n ORDER BY n.occurredAt DESC LIMIT $limit
WITH collect(n) AS content
CALL (content) {{
    UNWIND content AS c
    OPTIONAL MATCH (c)--(nb)
    WHERE ({neighbor_pred}) AND nb.project_id = $project_id
    RETURN collect(DISTINCT nb) AS neighbors
}}
WITH content + neighbors AS nodes
UNWIND nodes AS n
WITH DISTINCT n
RETURN {_NODE_RETURN_FIELDS}
"""


def _resolve_filters(types: list[str] | None) -> tuple[str, str]:
    """프론트 type 화이트리스트를 (content 술어, 이웃 술어)로 변환한다.

    - types가 비어있으면 전체 content + 전체 확장.
    - content 타입을 하나도 안 고르면 content 술어가 빈 문자열 → 호출부가 빈 결과로 처리.
    - 확장 타입(actor/code)을 안 고르면 이웃 술어는 'false' (이웃 미확장).
    """
    if not types:
        return _ALL_CONTENT_PRED, _ALL_EXPANSION_PRED

    requested = {t.strip().lower() for t in types if t and t.strip()}
    content_preds = [pred for key, pred in _CONTENT_TYPE_PREDICATES.items() if key in requested]
    neighbor_preds = [pred for key, pred in _EXPANSION_LABELS.items() if key in requested]

    content_pred = " OR ".join(content_preds)
    neighbor_pred = " OR ".join(neighbor_preds) if neighbor_preds else "false"
    return content_pred, neighbor_pred


# 선택된 노드 집합(id) 안에서만 엣지를 수집한다 — 한쪽 끝이 잘려나간 dangling 엣지는 제외.
_EDGE_QUERY = """
MATCH (a)-[r]->(b)
WHERE elementId(a) IN $ids AND elementId(b) IN $ids
RETURN DISTINCT elementId(a) AS source, elementId(b) AS target
"""


def _truncate(text: str | None) -> str:
    if not text:
        return ""
    text = text.strip()
    return text[:_SNIPPET_LEN] + "…" if len(text) > _SNIPPET_LEN else text


def _first_line(text: str | None) -> str:
    if not text:
        return ""
    return text.strip().splitlines()[0]


def _basename(path: str | None) -> str:
    if not path:
        return ""
    return os.path.basename(path.rstrip("/")) or path


def _date_part(occurred_at: str | None) -> str:
    # ISO-8601 문자열에서 날짜(YYYY-MM-DD)만. None/"None" 방어.
    if not occurred_at or occurred_at == "None":
        return ""
    return occurred_at[:10]


def _node_ref(node_type: str, node_id: str | None) -> dict | None:
    # focus 질의용 도메인 키 — 질의 도구가 요구하는 정확한 키를 노드에 실어 보낸다(없으면 None).
    return {"type": node_type, "id": node_id} if node_id else None


# alias(예: "GITHUB:se-zero")의 소스 접두사 → 표시 라벨.
# 등록되지 않은 소스는 _source_label이 대문자 snake에서 유도하므로(LINEAR → "Linear",
# GOOGLE_CHAT → "Google Chat") 커넥터를 추가할 때 이 맵을 고칠 필요는 없다.
# **유도로 표기가 틀어지는 이름만** 여기에 넣는다 (GitHub·ClickUp처럼 중간에 대문자가 오는 경우).
_SOURCE_PREFIX_LABELS = {"GITHUB": "GitHub", "CLICKUP": "ClickUp", "MONDAY": "monday.com"}


def _source_label(prefix: str) -> str:
    """소스 접두사를 표시 라벨로. 등록된 예외를 먼저 보고, 없으면 대문자 snake에서 유도한다."""
    if prefix in _SOURCE_PREFIX_LABELS:
        return _SOURCE_PREFIX_LABELS[prefix]
    return " ".join(word.capitalize() for word in prefix.split("_") if word) or prefix


def _actor_source_summary(aliases: list[str]) -> str:
    """Actor aliases를 계정ID 없이 소스 라벨 요약으로 축약한다 (예: "GitHub · Jira").

    actor_admin.list_actors와 같은 원칙 — 그래프 뷰 응답도 계정ID(source_id) 원문을 깔지 않는다.
    중복 제거·순서는 aliases 등장 순으로 안정적으로 유지한다.
    """
    labels: list[str] = []
    seen: set[str] = set()
    for alias in aliases:
        prefix = alias.split(":", 1)[0].upper() if alias else ""
        if not prefix or prefix in seen:
            continue
        seen.add(prefix)
        labels.append(_source_label(prefix))
    return " · ".join(labels)


def _to_graph_node(row: dict) -> dict:
    """Neo4j 행을 프론트 GraphNode({id, type, title, meta, source, snippet})로 변환한다."""
    label = row["label"]
    src = (row.get("source") or "").upper()

    if label == "ChangeSet":
        return {
            "id": row["id"],
            "type": "commit",
            "title": _first_line(row.get("message")) or "(no message)",
            "meta": (row.get("hash") or "")[:7],
            "source": "github",
            "snippet": _truncate(row.get("message")),
            # ref엔 전체 해시 — get_changeset_context는 정확 매칭이라 [:7] 프리픽스로는 조회 실패.
            "ref": _node_ref("commit", row.get("hash")),
        }

    if label == "PullRequest":
        pr_number = row.get("pr_number")
        return {
            "id": row["id"],
            "type": "pr",
            "title": row.get("title") or "(no title)",
            "meta": f"PR #{pr_number}" if pr_number is not None else "PR",
            "source": "github",
            "snippet": _truncate(row.get("body")),
            "ref": _node_ref("pull_request", str(pr_number) if pr_number is not None else None),
        }

    if label == "Issue":
        return {
            "id": row["id"],
            # type은 프론트의 렌더링 분류(색·범례)라 이슈 트래커 공용 값을 쓴다.
            # 실제 출처는 source가 싣는다 — Linear 이슈가 "jira"로 보이지 않게.
            "type": "jira",
            "title": row.get("title") or row.get("issue_key") or "(issue)",
            "meta": row.get("issue_key") or "",
            "source": src.lower() or "jira",
            "snippet": _truncate(row.get("body")),
            # ref는 issue_key 기준 — 키 없는(issue_key nullable) 소스가 도입되면 재검토 필요.
            "ref": _node_ref("issue", row.get("issue_key")),
        }

    if label == "Communication":
        # GitHub Issue와 Slack 메시지 둘 다 Communication 노드 — source로 구분.
        is_github = src == "GITHUB"
        channel = row.get("channel") or ""
        date = _date_part(row.get("occurred_at"))
        if is_github:
            return {
                "id": row["id"],
                "type": "issue",
                "title": _first_line(row.get("body")) or "(issue)",
                "meta": f"Issue #{row['conversation_id']}" if row.get("conversation_id") else "issue",
                "source": "github",
                "snippet": _truncate(row.get("body")),
                # GitHub Issue·Slack 모두 message 도구(get_thread_context) 대상 — conversation_id로 조회.
                "ref": _node_ref("message", row.get("conversation_id")),
            }
        # GitHub이 아닌 Communication은 모두 대화 소스(Slack·Discord·Teams·Google Chat).
        # 채널 이름이 없을 때 제목을 "Slack 메시지"로 굳히면 Discord 대화가 Slack으로 보인다.
        return {
            "id": row["id"],
            # type은 프론트의 렌더링 분류(색·범례)라 대화 공용 값을 쓴다. 실제 출처는 source가 싣는다.
            "type": "slack",
            "title": f"#{channel}" if channel else (f"{_source_label(src)} 메시지" if src else "메시지"),
            "meta": " · ".join(p for p in (channel, date) if p),
            "source": src.lower() or "slack",
            "snippet": _truncate(row.get("body")),
            # meta엔 conversation_id가 없어 텍스트로는 못 가리키므로 ref로 표면화한다.
            "ref": _node_ref("message", row.get("conversation_id")),
        }

    if label == "Document":
        # meta는 날짜만 쓴다(부모 페이지 경로는 추가 조인이 필요해 카드 서브타이틀
        # 용도로는 과하다 — 부모 관계 자체는 CHILD_OF 엣지로 이미 그려진다).
        return {
            "id": row["id"],
            "type": "doc",
            "title": row.get("title") or "(문서)",
            "meta": _date_part(row.get("occurred_at")),
            "source": src.lower() or "notion",
            "snippet": _truncate(row.get("body")),
            "ref": _node_ref("document", row.get("external_id")),
        }

    if label == "Actor":
        aliases = row.get("aliases") or []
        return {
            "id": row["id"],
            "type": "actor",
            "title": row.get("name") or "(unknown)",
            "meta": _actor_source_summary(aliases),
            "source": "people",
            "snippet": "",
            "ref": None,
        }

    if label == "File":
        path = row.get("path") or ""
        return {
            "id": row["id"],
            "type": "code",
            "title": _basename(path) or "(file)",
            "meta": path,
            "source": "github",
            "snippet": path,
            "ref": None,
        }

    # 알 수 없는 라벨 — 방어적으로 최소 정보만.
    logger.warning("overview: 알 수 없는 노드 라벨 %s", label)
    return {
        "id": row["id"],
        "type": "code",
        "title": row.get("title") or label,
        "meta": "",
        "source": (row.get("source") or "").lower(),
        "snippet": "",
        "ref": None,
    }


async def get_project_overview(
    project_id: str,
    limit: int = DEFAULT_LIMIT,
    types: list[str] | None = None,
) -> dict:
    """프로젝트 그래프 개요를 {nodes, edges} 형태로 반환한다.

    content 노드(ChangeSet/PullRequest/Issue/Communication)를 occurredAt 최신순 limit개
    선택하고, 그에 연결된 Actor/File을 이웃으로 확장한 뒤, 선택된 노드 집합 내부의
    엣지만 모은다. 전부 project_id 스코프.

    types: 프론트 type 화이트리스트(commit/pr/jira/issue/slack/actor/code). None/빈 값이면 전체.
           content 타입만 노드 선택에 영향을 주고(필터 후 top-N), actor/code는 이웃 확장 토글.
           content 타입을 하나도 안 고르면 빈 결과 (확장 노드는 content에 매달려야 등장).
    """
    if not project_id:
        return {"nodes": [], "edges": []}
    limit = max(1, min(limit, MAX_LIMIT))

    content_pred, neighbor_pred = _resolve_filters(types)
    if not content_pred:
        # 선택된 content 타입이 없음 — 앵커가 없어 확장할 대상도 없다.
        return {"nodes": [], "edges": []}
    node_query = _node_query(content_pred, neighbor_pred)

    async with get_driver().session() as session:
        node_result = await session.run(node_query, project_id=project_id, limit=limit)
        node_rows = await node_result.data()

        if not node_rows:
            return {"nodes": [], "edges": []}

        ids = [r["id"] for r in node_rows]
        edge_result = await session.run(_EDGE_QUERY, ids=ids)
        edge_rows = await edge_result.data()

    nodes = [_to_graph_node(r) for r in node_rows]
    edges = [[r["source"], r["target"]] for r in edge_rows]

    logger.info(
        "overview project=%s nodes=%d edges=%d (limit=%d, types=%s)",
        project_id, len(nodes), len(edges), limit, types or "all",
    )
    return {"nodes": nodes, "edges": edges}


# ── 성좌 뷰 조회 ──────────────────────────────────────────────────────────────
# overview("최근 활동 top-N")와 계약이 다르다.
#
# 성좌 뷰는 작업 단위를 큰 노드(별성)로 두고 그에 딸린 것들을 주위에 배치한다.
# 별성은 그림의 골격이라 하나라도 빠지면 화면 자체가 틀린 그림이 된다 —
# 실제로 타입 구분 없이 최신 200개를 자르면 PR 56개 중 19개만 남았다.
# 반면 위성(commit/file/논의)은 최근 것만 있어도 충분하다.
#
# 그래서 "완전성이 필요한 것"과 "밀도가 필요한 것"을 분리해 조회한다:
#   작업 단위는 전량(상한 WORK_UNIT_MAX) · 위성은 최신 limit개 → 합집합.
# 작업 단위 수는 보통 수십 개라 전량으로 가져와도 비용이 거의 없다.

CONSTELLATION_DEFAULT_LIMIT = 400
CONSTELLATION_MAX_LIMIT = 800
WORK_UNIT_MAX = 300

# 작업 단위 후보 라벨 — 앞선 라벨이 0건이면 다음으로 넘어간다.
# PR은 base 브랜치 기준으로 수집되므로(pipeline-worker) 연동 브랜치가 feature 브랜치면
# PullRequest가 0건일 수 있다. 그 경우 별성이 하나도 없어 화면이 무너지므로 Issue로 폴백한다.
_WORK_UNIT_LABELS = ("PullRequest", "Issue", "ChangeSet")

# 최신 content 노드 — overview와 같은 기준(occurredAt 역순)이다.
_RECENT_CONTENT_QUERY = f"""
MATCH (n)
WHERE n.project_id = $project_id
  AND ({_ALL_CONTENT_PRED})
  AND NOT (n:Issue AND n.source = '__stub__')
  AND NOT {_EMPTY_DOCUMENT_PRED}
WITH n ORDER BY n.occurredAt DESC LIMIT $limit
RETURN {_NODE_RETURN_FIELDS}
"""


def _work_unit_query(label: str) -> str:
    """작업 단위 전량 조회. label은 _WORK_UNIT_LABELS의 고정 값이라 인젝션 위험 없음."""
    return f"""
MATCH (n:{label})
WHERE n.project_id = $project_id
  AND NOT (n:Issue AND n.source = '__stub__')
WITH n ORDER BY n.occurredAt DESC LIMIT $work_limit
RETURN {_NODE_RETURN_FIELDS}
"""


# content 노드에 매달린 Actor/File만 확장한다 (overview의 이웃 확장과 동일 기준).
_NEIGHBOR_BY_IDS_QUERY = f"""
MATCH (c) WHERE elementId(c) IN $ids
OPTIONAL MATCH (c)--(nb)
WHERE ({_ALL_EXPANSION_PRED}) AND nb.project_id = $project_id
WITH DISTINCT nb AS n
WHERE n IS NOT NULL
RETURN {_NODE_RETURN_FIELDS}
"""


async def get_constellation_view(
    project_id: str,
    limit: int = CONSTELLATION_DEFAULT_LIMIT,
) -> dict:
    """성좌 뷰용 그래프를 {nodes, edges, work_unit_ids}로 반환한다.

    work_unit_ids는 별성으로 그릴 노드 id 목록이다. 어떤 라벨이 작업 단위인지는
    서버가 정해서 알려준다 — 프론트가 노드 타입을 하드코딩하면 PR이 0건인 프로젝트에서
    별성이 사라지고, 나중에 작업 단위 정의가 바뀔 때도 양쪽을 같이 고쳐야 한다.
    """
    if not project_id:
        return {"nodes": [], "edges": [], "work_unit_ids": []}
    limit = max(1, min(limit, CONSTELLATION_MAX_LIMIT))

    async with get_driver().session() as session:
        # 작업 단위 — 첫 번째로 0건이 아닌 라벨을 쓴다.
        work_rows: list[dict] = []
        work_label = ""
        for label in _WORK_UNIT_LABELS:
            result = await session.run(
                _work_unit_query(label), project_id=project_id, work_limit=WORK_UNIT_MAX
            )
            work_rows = await result.data()
            if work_rows:
                work_label = label
                break

        recent_result = await session.run(
            _RECENT_CONTENT_QUERY, project_id=project_id, limit=limit
        )
        recent_rows = await recent_result.data()

        # 합집합 — 작업 단위를 앞에 두고, 이미 담긴 id는 건너뛴다.
        content_rows = list(work_rows)
        seen = {r["id"] for r in content_rows}
        for row in recent_rows:
            if row["id"] not in seen:
                seen.add(row["id"])
                content_rows.append(row)

        if not content_rows:
            return {"nodes": [], "edges": [], "work_unit_ids": []}

        content_ids = [r["id"] for r in content_rows]
        neighbor_result = await session.run(
            _NEIGHBOR_BY_IDS_QUERY, ids=content_ids, project_id=project_id
        )
        neighbor_rows = await neighbor_result.data()

        node_rows = list(content_rows)
        for row in neighbor_rows:
            if row["id"] not in seen:
                seen.add(row["id"])
                node_rows.append(row)

        edge_result = await session.run(_EDGE_QUERY, ids=[r["id"] for r in node_rows])
        edge_rows = await edge_result.data()

    nodes = [_to_graph_node(r) for r in node_rows]
    edges = [[r["source"], r["target"]] for r in edge_rows]
    work_unit_ids = [r["id"] for r in work_rows]

    logger.info(
        "constellation project=%s nodes=%d edges=%d works=%d(%s) (limit=%d)",
        project_id, len(nodes), len(edges), len(work_unit_ids), work_label or "none", limit,
    )
    return {"nodes": nodes, "edges": edges, "work_unit_ids": work_unit_ids}


# ── 작업 단위 이웃 조회 (성좌 드릴인) ─────────────────────────────────────────
# 성좌 뷰는 위성을 최신 limit개로 자르므로, 오래된 작업은 별성만 있고 위성이 없다.
# 그 성좌를 열 때 이 조회로 해당 작업의 이웃만 따로 채운다.
#
# 관계 타입을 명시해 확장한다 — 무타입 가변길이(*1..3)로 뻗으면 파일 같은 허브를 거쳐
# 다른 작업의 커밋까지 딸려와 결과가 폭주한다.
_WORK_UNIT_NODE_LIMIT = 400

_WORK_UNIT_NEIGHBORHOOD_QUERY = f"""
MATCH (w) WHERE elementId(w) = $node_id AND w.project_id = $project_id
CALL (w) {{
    // 1홉 — 작업 단위에 직접 매달린 content (PR→커밋, 이슈→커밋·대화)
    MATCH (w)-[:CONTAINS|TRIGGERED_BY|DISCUSSED_IN|REFERENCE|CHILD_OF]-(a)
    WHERE a.project_id = $project_id
    RETURN collect(DISTINCT a) AS h1
}}
CALL (h1) {{
    // 2홉 — 커밋이 건드린 파일, 커밋이 가리키는 이슈·대화
    UNWIND h1 AS n
    MATCH (n)-[:MODIFIED|TRIGGERED_BY|DISCUSSED_IN|REFERENCE]-(b)
    WHERE b.project_id = $project_id
    RETURN collect(DISTINCT b) AS h2
}}
CALL (h2) {{
    // 3홉 — 이슈를 거쳐야 닿는 대화 (DISCUSSED_IN)
    UNWIND h2 AS n
    MATCH (n)-[:DISCUSSED_IN]-(c)
    WHERE c.project_id = $project_id
    RETURN collect(DISTINCT c) AS h3
}}
WITH [w] + h1 + h2 + h3 AS found
UNWIND found AS n
WITH DISTINCT n
WHERE NOT n:Actor AND NOT (n:Issue AND n.source = '__stub__')
  AND NOT {_EMPTY_DOCUMENT_PRED}
WITH n LIMIT $node_limit
RETURN {_NODE_RETURN_FIELDS}
"""


async def get_work_unit_neighborhood(project_id: str, node_id: str) -> dict:
    """작업 단위 하나의 이웃을 {nodes, edges}로 반환한다 (성좌 드릴인용).

    반환 집합에는 공유 노드(주로 파일)도 포함되므로, 클라이언트가 기존 그래프에 합치면
    이미 로드된 다른 성좌와의 다리도 그 공유 노드를 통해 자연스럽게 이어진다.
    """
    if not project_id or not node_id:
        return {"nodes": [], "edges": []}

    async with get_driver().session() as session:
        node_result = await session.run(
            _WORK_UNIT_NEIGHBORHOOD_QUERY,
            node_id=node_id,
            project_id=project_id,
            node_limit=_WORK_UNIT_NODE_LIMIT,
        )
        node_rows = await node_result.data()
        if not node_rows:
            return {"nodes": [], "edges": []}

        edge_result = await session.run(_EDGE_QUERY, ids=[r["id"] for r in node_rows])
        edge_rows = await edge_result.data()

    nodes = [_to_graph_node(r) for r in node_rows]
    edges = [[r["source"], r["target"]] for r in edge_rows]
    logger.info(
        "work-unit project=%s node=%s nodes=%d edges=%d",
        project_id, node_id, len(nodes), len(edges),
    )
    return {"nodes": nodes, "edges": edges}


# ── 답변 evidence → 관련 서브그래프 ────────────────────────────────────────────
# 채팅 답변의 evidence는 도메인 키로 노드를 가리킨다(commit→hash 앞 7자, pull_request→
# "#번호", issue→issue_key, document→external_id, message→conversation_id). 그래프 노드는
# elementId로 식별되므로 둘을 잇는 공통 키가 없다 — 여기서 도메인 키로 노드를 resolve해
# 서브그래프를 만든다.

# 시드 노드(evidence가 가리키는 노드) + 1홉 이웃을 모으고, 그 집합 내부 엣지만 수집한다.
# 빈 파라미터 리스트는 각 술어를 false로 만들어 안전하다(any(... IN [])=false, x IN []=false).
_SUBGRAPH_QUERY = f"""
MATCH (n)
WHERE n.project_id = $project_id
  AND (
    (n:ChangeSet AND any(p IN $commit_prefixes WHERE n.hash STARTS WITH p))
    OR (n:PullRequest AND n.pr_number IN $pr_numbers)
    OR (n:Issue AND n.issue_key IN $issue_keys AND n.source <> '__stub__')
    OR (n:Document AND n.external_id IN $document_external_ids)
    OR (n:Communication AND (
        replace(n.conversation_id, '.', '') IN $conv_ids
        OR split(coalesce(n.url, ''), '/p')[-1] IN $conv_ids
    ))
  )
WITH collect(n) AS seeds
CALL (seeds) {{
    UNWIND seeds AS s
    OPTIONAL MATCH (s)--(nb)
    WHERE nb.project_id = $project_id
    RETURN collect(DISTINCT nb) AS neighbors
}}
WITH seeds + neighbors AS nodes
UNWIND nodes AS n
WITH DISTINCT n
// 이웃 확장은 라벨을 가리지 않으므로, 문서 시드의 CHILD_OF 부모가 빈 pre-node일 수 있다.
WHERE NOT {_EMPTY_DOCUMENT_PRED}
RETURN {_NODE_RETURN_FIELDS}
"""


def _slack_ts_key(raw: str) -> str:
    """Slack 메시지 식별자를 비교용 정규형(숫자만)으로 환원한다.

    LLM이 conversation_id 대신 퍼머링크(.../p<숫자>)나 점 없는 ts를 evidence.id에 넣는
    경우가 많아, 형식을 흡수해 저장된 conversation_id(점 제거형)와 맞춘다. 퍼머링크면
    마지막 '/p' 뒤를, 그 외에는 점 제거 후 앞쪽 숫자열만 취한다(숫자 없으면 "").
    """
    s = raw.strip()
    if "/p" in s:
        s = s.rsplit("/p", 1)[-1]
    s = s.replace(".", "")
    m = re.match(r"\d+", s)
    return m.group(0) if m else ""


def _normalize_evidence(item: dict) -> tuple[str, str] | None:
    """evidence 1건을 (type, 조회 키)로 정규화한다 — group/resolve가 공유하는 단일 규칙.

    빈 id·미지 타입·숫자 아닌 PR 번호·숫자로 환원 안 되는 message는 무효로 None. 파싱 규칙을
    한 곳에 모아, 한쪽만 바뀌어 "group은 수집했는데 resolve는 못 찾는" drift를 구조적으로 막는다.
    pull_request는 "#42"/"42"를 "42"로, message는 ts/퍼머링크/점없는 ts를 숫자 정규형으로,
    나머지(document 포함, id=external_id)는 strip한 id를 그대로 키로 쓴다.
    """
    etype = (item.get("type") or "").strip()
    eid = (item.get("id") or "").strip()
    if not eid:
        return None
    if etype == "commit":
        return ("commit", eid)
    if etype == "pull_request":
        num = eid.lstrip("#").strip()
        return ("pull_request", num) if num.isdigit() else None
    if etype == "issue":
        return ("issue", eid)
    if etype == "document":
        return ("document", eid)
    if etype == "message":
        key = _slack_ts_key(eid)
        return ("message", key) if key else None
    return None


def _group_evidence_keys(evidence: list[dict]) -> dict:
    """evidence를 타입별 조회 키로 그룹핑한다 (PR은 int로 — Neo4j pr_number가 int)."""
    keys: dict = {
        "commit_prefixes": [],
        "pr_numbers": [],
        "issue_keys": [],
        "document_external_ids": [],
        "conv_ids": [],
    }
    for item in evidence:
        norm = _normalize_evidence(item)
        if not norm:
            continue
        etype, key = norm
        if etype == "commit":
            keys["commit_prefixes"].append(key)
        elif etype == "pull_request":
            keys["pr_numbers"].append(int(key))
        elif etype == "issue":
            keys["issue_keys"].append(key)
        elif etype == "document":
            keys["document_external_ids"].append(key)
        elif etype == "message":
            keys["conv_ids"].append(key)
    return keys


def _resolve_seed_ids(evidence: list[dict], node_rows: list[dict]) -> list[str | None]:
    """조회된 노드 행을 evidence 순서대로 elementId에 정렬한다(미해석은 None).

    인용 카드 #i ↔ 그래프 노드 매핑용. 이웃 노드 행은 라벨이 안 맞아 자연히 무시된다.
    """
    seeds: list[str | None] = []
    for item in evidence:
        norm = _normalize_evidence(item)
        match: str | None = None
        if norm:
            etype, key = norm
            if etype == "commit":
                match = next(
                    (r["id"] for r in node_rows
                     if r.get("label") == "ChangeSet" and (r.get("hash") or "").startswith(key)),
                    None,
                )
            elif etype == "pull_request":
                match = next(
                    (r["id"] for r in node_rows
                     if r.get("label") == "PullRequest" and str(r.get("pr_number")) == key),
                    None,
                )
            elif etype == "issue":
                # stub(source='__stub__')은 이웃 확장으로 딸려올 수 있어 행 자체에서도 걸러낸다.
                match = next(
                    (r["id"] for r in node_rows
                     if r.get("label") == "Issue" and r.get("issue_key") == key
                     and r.get("source") != "__stub__"),
                    None,
                )
            elif etype == "document":
                # 빈 부모 pre-node는 _SUBGRAPH_QUERY가 이미 걸러내므로 여기서 따로 안 뺀다.
                match = next(
                    (r["id"] for r in node_rows
                     if r.get("label") == "Document" and r.get("external_id") == key),
                    None,
                )
            elif etype == "message":
                # conversation_id(스레드 ts)뿐 아니라 url의 자기 ts(/p 뒤)와도 매칭한다 —
                # 답글은 conversation_id가 부모 ts라, LLM이 답글 자기 ts/퍼머링크를 인용하면
                # url로만 잡힌다.
                match = next(
                    (r["id"] for r in node_rows
                     if r.get("label") == "Communication"
                     and key in {
                         (r.get("conversation_id") or "").replace(".", ""),
                         _slack_ts_key(r.get("url") or ""),
                     }),
                    None,
                )
        seeds.append(match)
    return seeds


async def get_evidence_subgraph(project_id: str, evidence: list[dict]) -> dict:
    """답변 evidence가 가리키는 노드 + 1홉 이웃을 {nodes, edges, seeds}로 반환한다.

    seeds는 입력 evidence 순서에 정렬된 노드 elementId 배열(미해석은 None) — 인용 카드 ↔ 노드 매핑용.
    전부 project_id로 스코프된다. evidence가 없거나 해석 가능한 키가 하나도 없으면 빈 결과.
    """
    if not project_id or not evidence:
        return {"nodes": [], "edges": [], "seeds": [None] * len(evidence)}

    keys = _group_evidence_keys(evidence)
    if not any(keys.values()):
        return {"nodes": [], "edges": [], "seeds": [None] * len(evidence)}

    async with get_driver().session() as session:
        node_result = await session.run(_SUBGRAPH_QUERY, project_id=project_id, **keys)
        node_rows = await node_result.data()

        if not node_rows:
            return {"nodes": [], "edges": [], "seeds": [None] * len(evidence)}

        ids = [r["id"] for r in node_rows]
        edge_result = await session.run(_EDGE_QUERY, ids=ids)
        edge_rows = await edge_result.data()

    nodes = [_to_graph_node(r) for r in node_rows]
    edges = [[r["source"], r["target"]] for r in edge_rows]
    seeds = _resolve_seed_ids(evidence, node_rows)

    logger.info(
        "subgraph project=%s evidence=%d nodes=%d edges=%d",
        project_id, len(evidence), len(nodes), len(edges),
    )
    return {"nodes": nodes, "edges": edges, "seeds": seeds}
