"""연동 해제 cascade(소스 단위 그래프 삭제) 단위 테스트 (오프라인 — Neo4j driver fake 주입).

실제 Cypher 의미론(Actor가 정말 살아남는지 등)은 live Neo4j가 필요해 integration 영역이다.
여기서는 그 Cypher를 결정하는 **파라미터와 단계 구성**을 고정한다 — 소스 대문자 정규화,
alias 접두사(`SLACK:`), 그리고 네 단계(도메인 노드 → 고아 File → Actor → ActorDecision)가
모두 실행되는지. 이 셋 중 하나만 틀려도 다른 소스의 데이터를 지우거나 흔적을 남긴다.
"""

import asyncio
import unittest
from unittest.mock import patch

from graph.maintenance import delete_project_source_graph


class _FakeCounters:
    def __init__(self, nodes_deleted):
        self.nodes_deleted = nodes_deleted


class _FakeSummary:
    def __init__(self, nodes_deleted):
        self.counters = _FakeCounters(nodes_deleted)


class _FakeResult:
    def __init__(self, nodes_deleted):
        self._nodes_deleted = nodes_deleted

    async def consume(self):
        return _FakeSummary(self._nodes_deleted)


class _FakeSession:
    """실행된 (query, params)를 순서대로 기록하고, 정해진 삭제 수를 차례로 돌려준다."""

    def __init__(self, deleted_counts):
        self.calls = []
        self._deleted_counts = list(deleted_counts)

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return None

    async def run(self, query, **params):
        self.calls.append((query, params))
        count = self._deleted_counts.pop(0) if self._deleted_counts else 0
        return _FakeResult(count)


class _FakeDriver:
    def __init__(self, session):
        self._session = session

    def session(self):
        return self._session


def _run(project_id, source, deleted_counts=(0, 0, 0, 0, 0, 0)):
    session = _FakeSession(deleted_counts)
    with patch("graph.maintenance.get_driver", return_value=_FakeDriver(session)):
        result = asyncio.run(delete_project_source_graph(project_id, source))
    return result, session


class DeleteProjectSourceGraph(unittest.TestCase):
    def test_scopes_every_step_to_project_and_source(self):
        _, session = _run("p1", "SLACK")

        # 모든 쿼리가 project_id로 스코프된다 — 다른 프로젝트를 건드리면 안 된다
        for _query, params in session.calls:
            self.assertEqual(params["project_id"], "p1")

        # 도메인 노드 삭제만 source 속성을 쓰고, Actor 계열은 alias 접두사를 쓴다
        node_params = session.calls[0][1]
        self.assertEqual(node_params["source"], "SLACK")
        alias_params = [p for _q, p in session.calls if "alias_prefix" in p]
        self.assertTrue(alias_params)
        for params in alias_params:
            self.assertEqual(params["alias_prefix"], "SLACK:")

    def test_lowercase_source_is_normalized(self):
        # backend가 provider를 소문자(`slack`)로 넘겨도 저장된 노드(SLACK)와 맞아야 한다
        _, session = _run("p1", "slack")

        self.assertEqual(session.calls[0][1]["source"], "SLACK")
        self.assertEqual(
            [p["alias_prefix"] for _q, p in session.calls if "alias_prefix" in p],
            ["SLACK:", "SLACK:", "SLACK:", "SLACK:"],
        )

    def test_runs_all_four_cleanup_stages(self):
        _, session = _run("p1", "GITHUB")

        queries = [query for query, _params in session.calls]
        joined = "\n".join(queries)
        # 도메인 노드 / 고아 File / ActorAlias 인덱스 / Actor / ActorDecision
        self.assertIn("{project_id: $project_id, source: $source}", queries[0])
        self.assertIn(":File", joined)
        self.assertIn("NOT (f)<-[:MODIFIED]-()", joined)
        self.assertIn(":ActorAlias", joined)
        self.assertIn(":Actor {project_id: $project_id}", joined)
        self.assertIn(":ActorDecision", joined)

    def test_returns_counts_per_stage(self):
        # 삭제 수를 읽는 단계는 넷(노드·File·Actor·Decision) — 사이 두 쿼리는 수를 세지 않는다
        result, _session = _run("p1", "JIRA", deleted_counts=(12, 3, 0, 2, 0, 1))

        self.assertEqual(result, {"nodes": 12, "files": 3, "actors": 2, "decisions": 1})

    def test_blank_input_is_noop(self):
        # backend가 빈 값을 넘겨도 프로젝트 전체를 쓸어가는 쿼리가 나가면 안 된다
        for project_id, source in (("", "SLACK"), ("p1", ""), ("", "")):
            session = _FakeSession([])
            with patch("graph.maintenance.get_driver", return_value=_FakeDriver(session)):
                result = asyncio.run(delete_project_source_graph(project_id, source))
            self.assertEqual(result, {"nodes": 0, "files": 0, "actors": 0, "decisions": 0})
            self.assertEqual(session.calls, [])


if __name__ == "__main__":
    unittest.main()
