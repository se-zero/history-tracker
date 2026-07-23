"""범용 그래프 조회의 안전 계층 테스트 (Neo4j·OpenAI 없이 동작).

`run_graph_query`는 LLM이 쓴 Cypher를 실행하므로, **격리가 뚫리는지는 e2e가 아니라
여기서 판정한다.** 거부돼야 할 쿼리와 재작성 결과를 표로 고정한다.

- 거부: 쓰기·부작용 구문, 화이트리스트 밖 라벨·관계, 스코프가 안 걸리는 형태
- 재작성: 모든 노드 패턴에 project_id 주입 + LIMIT 상한

새 거부 사유나 재작성 규칙을 추가하면 이 표에 케이스를 함께 추가한다.
"""

import re

import pytest

from tools.queries.explore import (
    _DEFAULT_LIMIT,
    _ROW_CAP,
    GraphQueryError,
    _belongs_to_project,
    _sanitize,
    compile_query,
)

# ─── 거부돼야 하는 쿼리 ───────────────────────────────────────────────────────

REJECTED = [
    # 쓰기·부작용
    ("MATCH (i:Issue) SET i.status = 'Done' RETURN i", "쓰기(SET)"),
    ("MATCH (i:Issue) DELETE i RETURN 1", "쓰기(DELETE)"),
    ("MATCH (i:Issue) DETACH DELETE i RETURN 1", "쓰기(DETACH DELETE)"),
    ("CREATE (i:Issue {jira_key: 'X'}) RETURN i", "쓰기(CREATE)"),
    ("MERGE (i:Issue {jira_key: 'X'}) RETURN i", "쓰기(MERGE)"),
    ("MATCH (i:Issue) REMOVE i.status RETURN i", "쓰기(REMOVE)"),
    ("MATCH (i:Issue) FOREACH (x IN [1] | SET i.a = 1) RETURN i", "쓰기(FOREACH)"),
    # 프로시저·외부 입력
    ("CALL db.labels() YIELD label RETURN label", "CALL"),
    ("MATCH (i:Issue) CALL apoc.help('x') RETURN i", "CALL"),
    ("LOAD CSV FROM 'http://x' AS row RETURN row", "LOAD CSV"),
    # 스코프를 벗어나는 구조
    ("MATCH (i:Issue) RETURN i UNION MATCH (c:ChangeSet) RETURN c", "UNION"),
    ("MATCH (i:Issue) UNWIND [1,2] AS x RETURN x", "UNWIND"),
    ("MATCH (i:Issue) WHERE EXISTS { MATCH (c:ChangeSet) } RETURN i", "EXISTS 서브쿼리"),
    ("MATCH (i:Issue) RETURN COUNT { MATCH (c:ChangeSet) } AS n", "COUNT 서브쿼리"),
    ("MATCH (i:Issue) RETURN i; MATCH (c:ChangeSet) RETURN c", "복수 문장"),
    # 라벨 없는 노드 = 무제한 스캔 + 내부 라벨 노출
    ("MATCH (n) RETURN n", "라벨 없음"),
    ("MATCH () RETURN 1", "라벨 없음"),
    ("MATCH (i:Issue)-[:DISCUSSED_IN]->(c) RETURN c", "라벨 없음(경로 끝)"),
    # 화이트리스트 밖
    ("MATCH (a:ActorAlias) RETURN a", "내부 라벨"),
    ("MATCH (d:ActorDecision) RETURN d", "내부 라벨"),
    ("MATCH (x:Secret) RETURN x", "없는 라벨"),
    ("MATCH (a:Actor)-[:ALIAS_OF]->(b:Actor) RETURN b", "화이트리스트 밖 관계"),
    # 무제한 순회
    ("MATCH (i:Issue)-[:CHILD_OF*]->(p:Issue) RETURN p", "무제한 홉"),
    ("MATCH (i:Issue)-[:CHILD_OF*1..]->(p:Issue) RETURN p", "상한 없는 홉"),
    ("MATCH (i:Issue)-[:CHILD_OF*1..50]->(p:Issue) RETURN p", "홉 상한 초과"),
    # MATCH 밖의 패턴 — 주입을 타지 않는 경로
    ("MATCH (i:Issue) RETURN [(i)-[:TRIGGERED_BY]->(c:ChangeSet) | c.hash] AS x", "패턴 컴프리헨션"),
    ("MATCH (i:Issue) WHERE (i)-->(:Communication) RETURN i", "WHERE 패턴 술어"),
    # WITH가 변수를 떨어뜨린 뒤 같은 이름을 다시 쓰면 그건 **새 미묶임 변수**다 = 전체 스캔
    ("MATCH (x:Issue) WITH count(x) AS n MATCH (x) RETURN x", "WITH 이후 묶임 소멸"),
    ("MATCH (i:Issue) WITH i AS issue MATCH (i) RETURN i", "WITH 별칭 이후 재사용"),
    # 형태 자체가 해석 불가
    ("MATCH (i:Issue WHERE i.status = 'Done') RETURN i", "패턴 인라인 WHERE"),
    ("MATCH (i:Issue) RETURN i LIMIT $n", "LIMIT 비정수"),
    ("RETURN 1", "MATCH 없음"),
    ("MATCH (i:Issue)", "RETURN 없음"),
    ("", "빈 쿼리"),
    ("   ", "빈 쿼리"),
]


@pytest.mark.parametrize("cypher,reason", REJECTED, ids=[r for _, r in REJECTED])
def test_rejected(cypher, reason):
    with pytest.raises(GraphQueryError):
        compile_query(cypher)


# ─── project_id 주입 ─────────────────────────────────────────────────────────

ACCEPTED = [
    "MATCH (i:Issue) RETURN i.jira_key",
    "MATCH (i:Issue {status: 'Done'}) RETURN i.jira_key",
    "MATCH (c:ChangeSet)-[:MODIFIED]->(f:File) RETURN f.path, count(*) AS n ORDER BY n DESC",
    "MATCH (a:Actor)-[:CREATED]->(i:Issue) WHERE i.status <> 'Done' RETURN a.name, i.jira_key",
    "MATCH (i:Issue) OPTIONAL MATCH (c:ChangeSet)-[r:TRIGGERED_BY]->(i) RETURN i.jira_key, c.hash",
    "MATCH (i:Issue)-[:CHILD_OF*1..5]->(p:Issue) RETURN p.jira_key",
    "MATCH p = (pr:PullRequest)-[:CONTAINS]->(c:ChangeSet) RETURN c.hash",
    "MATCH (i:Issue), (c:Communication) WHERE i.title = c.body RETURN i.jira_key",
    # 묶인 변수의 재참조 — 라벨 없이 써도 이미 스코프돼 있다
    "MATCH (i:Issue) WITH i MATCH (c:ChangeSet)-[:TRIGGERED_BY]->(i) RETURN c.hash",
    "MATCH (i:Issue) WITH * MATCH (c:ChangeSet)-[:TRIGGERED_BY]->(i) RETURN c.hash",
]

# 노드 패턴 = 라벨을 가진 여는 괄호. 주입 후에는 전부 project_id를 달고 있어야 한다.
_NODE_PATTERN_RE = re.compile(r"\((?:\s*\w+)?\s*:\s*\w+[^)]*\)")


@pytest.mark.parametrize("cypher", ACCEPTED)
def test_every_node_pattern_is_scoped(cypher):
    """주입 누락이 곧 격리 실패다 — 노드 패턴 하나라도 비면 실패."""
    compiled = compile_query(cypher)
    patterns = _NODE_PATTERN_RE.findall(compiled)
    assert patterns, "노드 패턴을 찾지 못했다 — 테스트 정규식 점검 필요"
    for pattern in patterns:
        assert "project_id: $project_id" in pattern, f"스코프 누락: {pattern}"


def test_injection_into_existing_property_map():
    compiled = compile_query("MATCH (i:Issue {status: 'Done'}) RETURN i.jira_key")
    assert "{project_id: $project_id, status: 'Done'}" in compiled


def test_injection_without_property_map():
    compiled = compile_query("MATCH (i:Issue) RETURN i.jira_key")
    assert "(i:Issue {project_id: $project_id})" in compiled


def test_string_literal_is_not_parsed_as_syntax():
    """문자열 안의 중괄호·키워드가 구문으로 오인되면 안 된다."""
    compiled = compile_query("MATCH (c:Communication) WHERE c.body = 'MATCH {x' RETURN c.url")
    assert compiled.count("project_id: $project_id") == 1
    assert "'MATCH {x'" in compiled


def test_comment_is_ignored():
    compiled = compile_query("MATCH (i:Issue) // DELETE 는 주석이라 무해\nRETURN i.jira_key")
    assert "project_id: $project_id" in compiled


# ─── LIMIT ───────────────────────────────────────────────────────────────────


def test_limit_is_injected_when_absent():
    compiled = compile_query("MATCH (i:Issue) RETURN i.jira_key")
    assert compiled.rstrip().endswith(f"LIMIT {_DEFAULT_LIMIT}")


def test_small_limit_is_preserved():
    compiled = compile_query("MATCH (i:Issue) RETURN i.jira_key LIMIT 3")
    assert compiled.rstrip().endswith("LIMIT 3")


def test_large_limit_is_capped():
    compiled = compile_query("MATCH (i:Issue) RETURN i.jira_key LIMIT 100000")
    assert compiled.rstrip().endswith(f"LIMIT {_ROW_CAP}")


# ─── 반환 위생 ────────────────────────────────────────────────────────────────


def test_embedding_is_stripped():
    row = {"i": {"jira_key": "HT-1", "embedding": [0.1] * 1536}, "xs": [{"embedding": [0.2]}]}
    assert _sanitize(row) == {"i": {"jira_key": "HT-1"}, "xs": [{}]}


def test_foreign_project_row_is_detected():
    assert _belongs_to_project({"i": {"project_id": "p1"}}, "p1")
    assert not _belongs_to_project({"i": {"project_id": "p2"}}, "p1")
    assert not _belongs_to_project({"xs": [{"project_id": "p2"}]}, "p1")
    assert _belongs_to_project({"count": 3}, "p1")
