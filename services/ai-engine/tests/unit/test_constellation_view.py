"""성좌 뷰 조회 단위 테스트 (오프라인 — Neo4j driver fake 주입).

성좌 뷰의 계약은 overview와 다르다: 작업 단위(별성)는 전량, 위성만 최신 N개다.
별성이 하나라도 빠지면 화면이 틀린 그림이 되므로, 전량 조회·폴백·합집합 규칙을 고정한다.
"""

import asyncio
import unittest
from unittest.mock import patch

from graph.overview import (
    CONSTELLATION_MAX_LIMIT,
    WORK_UNIT_MAX,
    get_constellation_view,
    get_work_unit_neighborhood,
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


def _run_constellation(responses, limit=400):
    """responses: {쿼리종류: 행 목록}. 반환은 (결과, 세션)."""
    driver = _FakeDriver(lambda q, p: responses.get(_kind(q), []))
    with patch("graph.overview.get_driver", return_value=driver):
        result = asyncio.run(get_constellation_view("p1", limit))
    return result, driver.session_obj


class ConstellationView(unittest.TestCase):
    def test_work_units_are_returned_in_full_and_merged_with_recent(self):
        # 작업 단위 2건 중 1건은 최신 창에도 들어 있다 — 합집합이 중복을 만들면 안 된다.
        result, _ = _run_constellation({
            "work:PullRequest": [_row("pr1", "PullRequest"), _row("pr2", "PullRequest")],
            "recent": [_row("pr2", "PullRequest"), _row("c1", "ChangeSet")],
            "neighbors": [_row("f1", "File", path="src/a.ts")],
            "edges": [{"source": "pr1", "target": "c1"}],
        })

        self.assertEqual(result["work_unit_ids"], ["pr1", "pr2"])
        ids = [n["id"] for n in result["nodes"]]
        self.assertEqual(ids, ["pr1", "pr2", "c1", "f1"])
        self.assertEqual(len(ids), len(set(ids)), "합집합에 중복 노드가 있으면 안 된다")
        self.assertEqual(result["edges"], [["pr1", "c1"]])

    def test_recent_window_does_not_drop_work_units(self):
        # 최신 창에 전혀 없는 오래된 작업 단위도 반드시 살아남아야 한다 —
        # 이 뷰에서 별성이 빠지면 그림 자체가 틀린다.
        result, _ = _run_constellation({
            "work:PullRequest": [_row("old_pr", "PullRequest")],
            "recent": [_row("c1", "ChangeSet"), _row("c2", "ChangeSet")],
        })

        self.assertEqual(result["work_unit_ids"], ["old_pr"])
        self.assertIn("old_pr", [n["id"] for n in result["nodes"]])

    def test_falls_back_to_issue_when_no_pull_requests(self):
        # PR은 base 브랜치 기준으로 수집되므로 연동 브랜치에 따라 0건일 수 있다.
        # 그때 별성이 사라지면 화면이 먼지 고리만 남으므로 Issue로 폴백한다.
        result, session = _run_constellation({
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
        result, session = _run_constellation({
            "work:PullRequest": [],
            "work:Issue": [],
            "work:ChangeSet": [_row("c1", "ChangeSet")],
        })

        self.assertEqual(result["work_unit_ids"], ["c1"])
        self.assertIn("work:ChangeSet", [_kind(q) for q, _ in session.calls])

    def test_returns_empty_when_no_content_at_all(self):
        result, _ = _run_constellation({})

        self.assertEqual(result["nodes"], [])
        self.assertEqual(result["edges"], [])
        self.assertEqual(result["work_unit_ids"], [])

    def test_blank_project_id_skips_neo4j(self):
        driver = _FakeDriver(lambda q, p: [])
        with patch("graph.overview.get_driver", return_value=driver):
            result = asyncio.run(get_constellation_view("", 400))

        self.assertEqual(result, {"nodes": [], "edges": [], "work_unit_ids": []})
        self.assertEqual(driver.session_obj.calls, [], "빈 project_id면 조회하지 않는다")

    def test_limit_is_clamped_and_work_limit_is_fixed(self):
        _, session = _run_constellation(
            {"work:PullRequest": [_row("pr1", "PullRequest")]},
            limit=CONSTELLATION_MAX_LIMIT + 5000,
        )

        params = {_kind(q): p for q, p in session.calls}
        self.assertEqual(params["recent"]["limit"], CONSTELLATION_MAX_LIMIT)
        # 위성 상한과 달리 작업 단위 상한은 호출자가 바꿀 수 없다.
        self.assertEqual(params["work:PullRequest"]["work_limit"], WORK_UNIT_MAX)

    def test_lower_bound_limit_is_at_least_one(self):
        _, session = _run_constellation(
            {"work:PullRequest": [_row("pr1", "PullRequest")]}, limit=0
        )

        params = {_kind(q): p for q, p in session.calls}
        self.assertEqual(params["recent"]["limit"], 1)


class WorkUnitNeighborhood(unittest.TestCase):
    def test_returns_nodes_and_edges_for_one_work_unit(self):
        def responder(query, _params):
            if "MATCH (a)-[r]->(b)" in query:
                return [{"source": "pr1", "target": "c1"}]
            return [_row("pr1", "PullRequest"), _row("c1", "ChangeSet")]

        driver = _FakeDriver(responder)
        with patch("graph.overview.get_driver", return_value=driver):
            result = asyncio.run(get_work_unit_neighborhood("p1", "pr1"))

        self.assertEqual([n["id"] for n in result["nodes"]], ["pr1", "c1"])
        self.assertEqual(result["edges"], [["pr1", "c1"]])

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


class ActorPrivacyInGraphNode(unittest.TestCase):
    """Actor 노드의 meta/snippet에 계정ID(aliases) 원문이 노출되면 안 된다.

    액터 관리 UI(actor_admin.list_actors)와 같은 원칙 — 목록에 계정ID를 깔지 않는다.
    """

    def test_actor_meta_is_source_label_summary_without_raw_alias(self):
        result, _ = _run_constellation({
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
