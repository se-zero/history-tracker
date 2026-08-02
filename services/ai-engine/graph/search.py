"""통합 검색 — 그래프 노드 키워드 검색 (read-only).

프론트 검색창(Cmd+K)용. overview가 최근 활동 top-N만 내리는 것과 달리,
full-text 인덱스(node_search)로 프로젝트 그래프 전체에서 노드를 찾는다.
반환 노드는 overview와 동일한 GraphNode shape({id, type, title, meta, source, snippet})
+ score라 프론트가 NodeDetail 등을 그대로 재사용할 수 있다.
"""

import logging
import re

from graph.driver import get_driver
from graph.overview import _NODE_RETURN_FIELDS, _to_graph_node

logger = logging.getLogger(__name__)

DEFAULT_LIMIT = 20
MAX_LIMIT = 50

# full-text 인덱스 over-fetch 배수 — 벡터 인덱스와 같은 이유(discovery.py 참고).
# db.index.fulltext.queryNodes도 전역 top-K만 반환하고 project_id 사전 필터가 불가능해,
# project_id 필터 후에도 충분한 후보가 남도록 넉넉히 가져온 뒤 잘라낸다.
_OVERFETCH = 20
_OVERFETCH_CAP = 500

# 스레드 dedupe로 걸러질 몫을 감안한 Cypher LIMIT 여유 배수.
_DEDUPE_PADDING = 3

# phrase(따옴표) 내부에서 escape가 필요한 문자 — 나머지 특수문자는 phrase 안에서 문법이 아니다.
_PHRASE_SPECIALS = re.compile(r'(["\\])')

# 유니코드 영숫자(한글 포함)가 하나라도 있는지 — 구두점만 남은 term 판별용.
_HAS_WORD_CHAR = re.compile(r"[^\W_]", re.UNICODE)

_SEARCH_QUERY = f"""
CALL db.index.fulltext.queryNodes('node_search', $lucene, {{limit: $fetch_k}})
YIELD node AS n, score
WHERE n.project_id = $project_id
// ActorAlias가 잡히면(Jira/Slack 이름 검색) 결과로는 alias가 아니라 그 소유 Actor를 낸다 —
// 표시 이름은 GitHub 기준으로 고정돼도 다른 소스 이름으로 검색은 되어야 한다.
OPTIONAL MATCH (n)-[:ALIAS_OF]->(owner:Actor)
WITH coalesce(owner, n) AS n, score
ORDER BY score DESC
// 같은 Actor가 자기 name과 alias 양쪽으로 잡히는 중복 제거 — 최고 score 1건만 남긴다.
WITH n, collect(score)[0] AS score
ORDER BY score DESC
LIMIT $fetch_limit
RETURN {_NODE_RETURN_FIELDS},
       score
"""


def build_lucene_query(q: str) -> str:
    """사용자 입력을 Lucene 쿼리 문자열로 변환한다 (모든 term AND 매치).

    - 순수 영숫자(ASCII) term은 prefix 와일드카드(term*)로 타이핑 중 부분 일치를 지원한다.
      와일드카드 term은 Lucene이 분석(소문자화)하지 않으므로 여기서 직접 소문자로 맞춘다.
    - 그 외 term(한글, 'HT-104' 같은 복합 키)은 phrase("...")로 감싼다 — cjk analyzer가
      bigram/토큰으로 분석하되 phrase가 토큰 인접을 강제해 substring처럼 정확히 매치된다.
      (escape만 하면 다중 토큰이 OR로 풀려 'HT-104'가 'HT'만 포함해도 잡힌다.
      와일드카드는 분석을 건너뛰어 bigram 인덱스와 아예 매치되지 않는다.)
      phrase 안에서는 따옴표·백슬래시만 escape하면 쿼리 문법 주입이 안 된다.
    - 구두점뿐인 term은 분석 후 토큰이 없어 AND 절 전체를 0건으로 만들므로 버린다.
    """
    terms: list[str] = []
    for raw in q.split():
        if not _HAS_WORD_CHAR.search(raw):
            continue
        if raw.isascii() and raw.isalnum():
            terms.append(raw.lower() + "*")
        else:
            terms.append('"' + _PHRASE_SPECIALS.sub(r"\\\1", raw) + '"')
    return " AND ".join(terms)


async def search_nodes(project_id: str, q: str, limit: int = DEFAULT_LIMIT) -> list[dict]:
    """project_id 스코프에서 키워드로 그래프 노드를 검색한다 (score 내림차순).

    빈/무효 질의면 빈 리스트. 같은 스레드(conversation_id)의 Communication이 여럿 잡히면
    대표(최고 score) 1건만 남긴다 — 결과가 한 스레드로 도배되는 것을 막는다
    (discovery.search_by_keyword와 동일한 규칙).
    """
    if not project_id:
        return []
    lucene_query = build_lucene_query(q or "")
    if not lucene_query:
        return []

    limit = max(1, min(limit, MAX_LIMIT))
    fetch_k = min(limit * _OVERFETCH, _OVERFETCH_CAP)

    async with get_driver().session() as session:
        # 파라미터 이름 'query'는 AsyncSession.run()의 첫 인자명과 충돌한다 — lucene으로 회피.
        result = await session.run(
            _SEARCH_QUERY,
            project_id=project_id,
            lucene=lucene_query,
            fetch_k=fetch_k,
            fetch_limit=limit * _DEDUPE_PADDING,
        )
        rows = await result.data()

    seen_threads: set[str] = set()
    nodes: list[dict] = []
    for row in rows:
        cid = row.get("conversation_id")
        if row.get("label") == "Communication" and cid:
            if cid in seen_threads:
                continue
            seen_threads.add(cid)
        node = _to_graph_node(row)
        node["score"] = row["score"]
        nodes.append(node)
        if len(nodes) >= limit:
            break

    logger.info("search project=%s q=%r nodes=%d", project_id, q, len(nodes))
    return nodes
