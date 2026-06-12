"""
프로젝트 그래프 조회 (read-only overview).

프론트엔드 그래프 탐색 화면이 쓰는 `{nodes, edges}` 페이로드를 만든다.
모든 Cypher는 project_id로 스코프되어 다른 프로젝트의 노드/엣지를 절대 반환하지 않는다.

주의:
  - embedding 속성(1536차원 벡터)은 응답에 넣지 않는다 — 스칼라 필드만 명시적으로 select.
  - 노드 id는 elementId(n)를 그대로 쓴다. 라벨 간 자연키 충돌 걱정 없이 엣지 양 끝을 맞출 수 있다.
"""

import logging
import os

from graph.builder import get_driver

logger = logging.getLogger(__name__)

# overview는 "최근 활동" 개요다 — 전체 그래프를 다 내리지 않는다.
DEFAULT_LIMIT = 200
MAX_LIMIT = 500

# snippet 본문 컷 (프론트 카드 미리보기용)
_SNIPPET_LEN = 240

# 프론트 type → content 노드 선택 술어.
# Communication은 source로 분기 — GitHub 이슈(issue)와 Slack 메시지(slack)가 같은 라벨이라.
# 값은 고정 Cypher 조각이고 사용자 입력은 키 매칭에만 쓰므로 인젝션 위험 없음.
_CONTENT_TYPE_PREDICATES = {
    "commit": "n:ChangeSet",
    "pr":     "n:PullRequest",
    "jira":   "n:Issue",
    "issue":  "(n:Communication AND n.source = 'GITHUB')",
    "slack":  "(n:Communication AND n.source = 'SLACK')",
}
_ALL_CONTENT_PRED = "n:ChangeSet OR n:PullRequest OR n:Issue OR n:Communication"

# 확장(이웃) 타입 — content 노드에 매달린 Actor/File만.
_EXPANSION_LABELS = {"actor": "nb:Actor", "code": "nb:File"}
_ALL_EXPANSION_PRED = "nb:Actor OR nb:File"


def _node_query(content_pred: str, neighbor_pred: str) -> str:
    """content/이웃 선택 술어를 끼운 노드 조회 Cypher를 만든다.

    술어는 고정 조각만 들어가고(사용자 입력 미삽입), 항상 project_id로 스코프된다.
    embedding은 절대 select하지 않는다 — 스칼라 필드만 명시.
    """
    return f"""
MATCH (n)
WHERE n.project_id = $project_id
  AND ({content_pred})
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
RETURN elementId(n)        AS id,
       labels(n)[0]        AS label,
       coalesce(n.source, '') AS source,
       n.hash              AS hash,
       n.message           AS message,
       n.pr_number         AS pr_number,
       n.title             AS title,
       n.body              AS body,
       n.jira_key          AS jira_key,
       n.status            AS status,
       n.url               AS url,
       n.channel           AS channel,
       n.conversation_id   AS conversation_id,
       n.name              AS name,
       n.aliases           AS aliases,
       n.path              AS path,
       toString(n.occurredAt) AS occurred_at
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
        }

    if label == "PullRequest":
        return {
            "id": row["id"],
            "type": "pr",
            "title": row.get("title") or "(no title)",
            "meta": f"PR #{row.get('pr_number')}" if row.get("pr_number") is not None else "PR",
            "source": "github",
            "snippet": _truncate(row.get("body")),
        }

    if label == "Issue":
        return {
            "id": row["id"],
            "type": "jira",
            "title": row.get("title") or row.get("jira_key") or "(issue)",
            "meta": row.get("jira_key") or "",
            "source": "jira",
            "snippet": _truncate(row.get("body")),
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
            }
        return {
            "id": row["id"],
            "type": "slack",
            "title": f"#{channel}" if channel else "Slack 메시지",
            "meta": " · ".join(p for p in (channel, date) if p),
            "source": "slack",
            "snippet": _truncate(row.get("body")),
        }

    if label == "Actor":
        aliases = row.get("aliases") or []
        return {
            "id": row["id"],
            "type": "actor",
            "title": row.get("name") or "(unknown)",
            "meta": aliases[0] if aliases else "",
            "source": "people",
            "snippet": _truncate(", ".join(aliases)),
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
