"""작업 단위 뷰 조회 단위 테스트 (오프라인 — Neo4j driver fake 주입).

작업 단위 뷰의 계약은 overview와 다르다: 작업 단위(작업 단위 노드)는 전량, 구성 노드만 최신 N개다.
작업 단위 노드가 하나라도 빠지면 화면이 틀린 그림이 되므로, 전량 조회·폴백·합집합 규칙을 고정한다.
"""

import asyncio
import unittest
from unittest.mock import patch

from graph.overview import (
    WORK_UNITS_MAX_LIMIT,
    WORK_UNIT_MAX,
    _collapse_edges,
    get_work_unit_neighborhood,
    get_work_units_view,
)


def _row(node_id: str, label: str, **extra) -> dict:
    """_to_graph_node가 읽는 필드를 모두 채운 Neo4j 행 하나."""
    row = {
        "id": node_id,
        "label": label,
        "source": extra.pop("source", ""),
        "hash": None,
        "message": None,
        "pr_number": None,
        "title": None,
        "body": None,
        "issue_key": None,
        "status": None,
        "url": None,
        "channel": None,
        "conversation_id": None,
        "name": None,
        "aliases": None,
        "path": None,
        "occurred_at": "2026-07-01T00:00:00Z",
    }
    row.update(extra)
    return row


def _edge_row(source: str, target: str, **extra) -> dict:
    """_EDGE_QUERY가 반환하는 6키를 모두 채운 엣지 행 하나. 기본은 구조 관계(CONTAINS)."""
    row = {
        "source": source,
        "target": target,
        "kind": "CONTAINS",
        "method": None,
        "confidence": None,
        "section": None,
    }
    row.update(extra)
    return row


class _FakeResult:
    def __init__(self, rows):
        self._rows = rows

    async def data(self):
        return self._rows


class _FakeSession:
    """쿼리 문자열로 어떤 조회인지 판별해 미리 정한 행을 돌려준다."""

    def __init__(self, responder):
        self._responder = responder
        self.calls = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return None

    async def run(self, query, **params):
        self.calls.append((query, params))
        return _FakeResult(self._responder(query, params))


class _FakeDriver:
    def __init__(self, responder):
        self.session_obj = _FakeSession(responder)

    def session(self):
        return self.session_obj


def _kind(query: str) -> str:
    """쿼리 종류 판별 — 호출 순서에 의존하지 않도록 문자열로 구분한다."""
    if "MATCH (n:PullRequest)" in query:
        return "work:PullRequest"
    if "MATCH (n:Issue)" in query:
        return "work:Issue"
    if "MATCH (n:ChangeSet)" in query:
        return "work:ChangeSet"
    if "OPTIONAL MATCH (c)--(nb)" in query:
        return "neighbors"
    if "MATCH (a)-[r]->(b)" in query:
        return "edges"
    if "ORDER BY n.occurredAt DESC LIMIT $limit" in query:
        return "recent"
    return "unknown"


def _run_work_units(responses, limit=400):
    """responses: {쿼리종류: 행 목록}. 반환은 (결과, 세션)."""
    driver = _FakeDriver(lambda q, p: responses.get(_kind(q), []))
    with patch("graph.overview.get_driver", return_value=driver):
        result = asyncio.run(get_work_units_view("p1", limit))
    return result, driver.session_obj


class WorkUnitsView(unittest.TestCase):
    def test_work_units_are_returned_in_full_and_merged_with_recent(self):
        # 작업 단위 2건 중 1건은 최신 창에도 들어 있다 — 합집합이 중복을 만들면 안 된다.
        result, _ = _run_work_units({
            "work:PullRequest": [_row("pr1", "PullRequest"), _row("pr2", "PullRequest")],
            "recent": [_row("pr2", "PullRequest"), _row("c1", "ChangeSet")],
            "neighbors": [_row("f1", "File", path="src/a.ts")],
            "edges": [_edge_row("pr1", "c1")],
        })

        self.assertEqual(result["work_unit_ids"], ["pr1", "pr2"])
        ids = [n["id"] for n in result["nodes"]]
        self.assertEqual(ids, ["pr1", "pr2", "c1", "f1"])
        self.assertEqual(len(ids), len(set(ids)), "합집합에 중복 노드가 있으면 안 된다")
        self.assertEqual(
            result["edges"],
            [{"source": "pr1", "target": "c1", "kind": "CONTAINS",
              "method": None, "confidence": None, "section": None}],
        )

    def test_recent_window_does_not_drop_work_units(self):
        # 최신 창에 전혀 없는 오래된 작업 단위도 반드시 살아남아야 한다 —
        # 이 뷰에서 작업 단위 노드가 빠지면 그림 자체가 틀린다.
        result, _ = _run_work_units({
            "work:PullRequest": [_row("old_pr", "PullRequest")],
            "recent": [_row("c1", "ChangeSet"), _row("c2", "ChangeSet")],
        })

        self.assertEqual(result["work_unit_ids"], ["old_pr"])
        self.assertIn("old_pr", [n["id"] for n in result["nodes"]])

    def test_falls_back_to_issue_when_no_pull_requests(self):
        # PR은 base 브랜치 기준으로 수집되므로 연동 브랜치에 따라 0건일 수 있다.
        # 그때 작업 단위 노드가 사라지면 화면이 미소속 노드 고리만 남으므로 Issue로 폴백한다.
        result, session = _run_work_units({
            "work:PullRequest": [],
            "work:Issue": [_row("i1", "Issue", issue_key="HT-1")],
            "recent": [_row("c1", "ChangeSet")],
        })

        self.assertEqual(result["work_unit_ids"], ["i1"])
        kinds = [_kind(q) for q, _ in session.calls]
        self.assertIn("work:PullRequest", kinds)
        self.assertIn("work:Issue", kinds)
        self.assertNotIn("work:ChangeSet", kinds, "Issue가 있으면 더 내려가지 않는다")

    def test_falls_back_to_changeset_when_no_pull_requests_and_no_issues(self):
        result, session = _run_work_units({
            "work:PullRequest": [],
            "work:Issue": [],
            "work:ChangeSet": [_row("c1", "ChangeSet")],
        })

        self.assertEqual(result["work_unit_ids"], ["c1"])
        self.assertIn("work:ChangeSet", [_kind(q) for q, _ in session.calls])

    def test_returns_empty_when_no_content_at_all(self):
        result, _ = _run_work_units({})

        self.assertEqual(result["nodes"], [])
        self.assertEqual(result["edges"], [])
        self.assertEqual(result["work_unit_ids"], [])

    def test_blank_project_id_skips_neo4j(self):
        driver = _FakeDriver(lambda q, p: [])
        with patch("graph.overview.get_driver", return_value=driver):
            result = asyncio.run(get_work_units_view("", 400))

        self.assertEqual(result, {"nodes": [], "edges": [], "work_unit_ids": []})
        self.assertEqual(driver.session_obj.calls, [], "빈 project_id면 조회하지 않는다")

    def test_limit_is_clamped_and_work_limit_is_fixed(self):
        _, session = _run_work_units(
            {"work:PullRequest": [_row("pr1", "PullRequest")]},
            limit=WORK_UNITS_MAX_LIMIT + 5000,
        )

        params = {_kind(q): p for q, p in session.calls}
        self.assertEqual(params["recent"]["limit"], WORK_UNITS_MAX_LIMIT)
        # 구성 노드 상한과 달리 작업 단위 상한은 호출자가 바꿀 수 없다.
        self.assertEqual(params["work:PullRequest"]["work_limit"], WORK_UNIT_MAX)

    def test_lower_bound_limit_is_at_least_one(self):
        _, session = _run_work_units(
            {"work:PullRequest": [_row("pr1", "PullRequest")]}, limit=0
        )

        params = {_kind(q): p for q, p in session.calls}
        self.assertEqual(params["recent"]["limit"], 1)


class WorkUnitNeighborhood(unittest.TestCase):
    def test_returns_nodes_and_edges_for_one_work_unit(self):
        def responder(query, _params):
            if "MATCH (a)-[r]->(b)" in query:
                return [_edge_row("pr1", "c1")]
            return [_row("pr1", "PullRequest"), _row("c1", "ChangeSet")]

        driver = _FakeDriver(responder)
        with patch("graph.overview.get_driver", return_value=driver):
            result = asyncio.run(get_work_unit_neighborhood("p1", "pr1"))

        self.assertEqual([n["id"] for n in result["nodes"]], ["pr1", "c1"])
        self.assertEqual(
            result["edges"],
            [{"source": "pr1", "target": "c1", "kind": "CONTAINS",
              "method": None, "confidence": None, "section": None}],
        )

    def test_missing_node_returns_empty_without_edge_query(self):
        driver = _FakeDriver(lambda q, p: [])
        with patch("graph.overview.get_driver", return_value=driver):
            result = asyncio.run(get_work_unit_neighborhood("p1", "ghost"))

        self.assertEqual(result, {"nodes": [], "edges": []})
        kinds = [_kind(q) for q, _ in driver.session_obj.calls]
        self.assertNotIn("edges", kinds, "노드가 없으면 엣지 조회로 넘어가지 않는다")

    def test_blank_arguments_skip_neo4j(self):
        driver = _FakeDriver(lambda q, p: [])
        with patch("graph.overview.get_driver", return_value=driver):
            self.assertEqual(
                asyncio.run(get_work_unit_neighborhood("", "pr1")),
                {"nodes": [], "edges": []},
            )
            self.assertEqual(
                asyncio.run(get_work_unit_neighborhood("p1", "")),
                {"nodes": [], "edges": []},
            )

        self.assertEqual(driver.session_obj.calls, [])


class EdgeCollapsing(unittest.TestCase):
    """_collapse_edges 단위 테스트 — 순수 함수라 fake 드라이버 없이 직접 호출한다.

    확정 연결(명시 참조)과 추측 연결(임베딩 유사도)을 프론트가 구분할 수 있도록 엣지에
    관계 메타데이터(kind/method/confidence/section)를 싣는 계약과, 같은 노드쌍의 관계가
    여럿이어도 대표 1개로 접히는 규칙을 검증한다.
    """

    def test_edge_is_returned_as_six_key_dict(self):
        rows = [_edge_row("a", "b", kind="REFERENCE", method="text", confidence=0.9, section="intro")]

        self.assertEqual(
            _collapse_edges(rows),
            [{"source": "a", "target": "b", "kind": "REFERENCE",
              "method": "text", "confidence": 0.9, "section": "intro"}],
        )

    def test_duplicate_relations_between_same_pair_collapse_to_one(self):
        # Actor→Document의 WROTE+EDITED처럼 같은 두 노드 사이에 관계가 둘이어도
        # "연결 N" 카운트·레이아웃이 부풀지 않도록 대표 1개로 접는다.
        rows = [_edge_row("a", "b", kind="WROTE"), _edge_row("a", "b", kind="EDITED")]

        self.assertEqual(len(_collapse_edges(rows)), 1)

    def test_representative_edge_follows_rank_order(self):
        # 구조 관계(rank 1)가 추측 연결(rank 2, semantic)보다 우선한다.
        structural = _edge_row("a", "b", kind="CONTAINS")
        semantic = _edge_row("a", "b", kind="REFERENCE", method="semantic", confidence=0.7)
        self.assertEqual(_collapse_edges([semantic, structural])[0]["kind"], "CONTAINS")

        # 명시 참조(rank 0, method=text)가 추측 연결(rank 2, method=semantic)보다 우선한다.
        text_ref = _edge_row("c", "d", kind="REFERENCE", method="text")
        semantic_ref = _edge_row("c", "d", kind="REFERENCE", method="semantic", confidence=0.99)
        self.assertEqual(_collapse_edges([semantic_ref, text_ref])[0]["method"], "text")

        # method가 없는 구 데이터는 semantic과 같은 칸이다(coalesce(r.source,'semantic') 규약) —
        # 최후순위로 밀지 않으므로 같은 rank 안에서 confidence로만 갈린다.
        legacy = _edge_row("e", "f", kind="REFERENCE", method=None, confidence=0.9)
        semantic_legacy = _edge_row("e", "f", kind="REFERENCE", method="semantic", confidence=0.5)
        self.assertIsNone(_collapse_edges([semantic_legacy, legacy])[0]["method"])

    def test_edge_without_properties_has_none_metadata(self):
        # CONTAINS 같은 구조 관계는 method/confidence/section 속성 자체가 없다.
        rows = [_edge_row("a", "b", kind="CONTAINS")]

        collapsed = _collapse_edges(rows)[0]
        self.assertIsNone(collapsed["method"])
        self.assertIsNone(collapsed["confidence"])
        self.assertIsNone(collapsed["section"])


class ActorPrivacyInGraphNode(unittest.TestCase):
    """Actor 노드의 meta/snippet에 계정ID(aliases) 원문이 노출되면 안 된다.

    액터 관리 UI(actor_admin.list_actors)와 같은 원칙 — 목록에 계정ID를 깔지 않는다.
    """

    def test_actor_meta_is_source_label_summary_without_raw_alias(self):
        result, _ = _run_work_units({
            "work:PullRequest": [_row("pr1", "PullRequest")],
            "neighbors": [
                _row("a1", "Actor", name="Kim", aliases=["GITHUB:se-zero", "JIRA:5b10a2"])
            ],
        })

        actor_nodes = [n for n in result["nodes"] if n["type"] == "actor"]
        self.assertEqual(len(actor_nodes), 1)
        actor = actor_nodes[0]

        self.assertNotIn("se-zero", actor["meta"])
        self.assertNotIn("5b10a2", actor["meta"])
        self.assertNotIn("se-zero", actor["snippet"])
        self.assertNotIn("5b10a2", actor["snippet"])
        self.assertEqual(actor["meta"], "GitHub · Jira")
        self.assertEqual(actor["snippet"], "")


if __name__ == "__main__":
    unittest.main()
