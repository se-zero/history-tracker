"""clear_bulk_document_issue_links 소급 정리 회귀 테스트 (오프라인).

graph/event_handler.py의 런타임 가드(DOCUMENT_ISSUE_REF_LIMIT)는 앞으로 들어오는 이벤트만
막는다. 이미 상한을 넘겨 만들어진 text DESCRIBED_IN(Issue→Document) 엣지는 MERGE 전용이라
삭제 의미론이 없고 Notion은 재발행 트리거(웹훅)가 없어, 소급 정리 없이는 오염이 남는다.

여기서는 실제 Neo4j 의미론을 검증하지 않는다 — 실행된 Cypher 문자열·파라미터가 의도한
스코프(text만, 상한 초과만, project_id 필터, stub 수거)를 갖는지만 오프라인으로
확인한다(패턴은 test_reference_provenance.py 미러).
"""

import asyncio
import unittest
from unittest.mock import patch

from graph.document_policy import DOCUMENT_ISSUE_REF_LIMIT
from graph.maintenance import clear_bulk_document_issue_links


class _FakeCounters:
    def __init__(self, nodes_deleted: int = 0, relationships_deleted: int = 0):
        self.nodes_deleted = nodes_deleted
        self.relationships_deleted = relationships_deleted


class _FakeSummary:
    def __init__(self, nodes_deleted: int = 0, relationships_deleted: int = 0):
        self.counters = _FakeCounters(nodes_deleted, relationships_deleted)


class _FakeResult:
    """single()(엣지 삭제 RETURN count)과 consume()(stub DETACH DELETE 요약) 둘 다 지원한다."""

    def __init__(self, *, single_record=None, nodes_deleted: int = 0):
        self._single_record = single_record
        self._nodes_deleted = nodes_deleted

    async def single(self):
        return self._single_record

    async def consume(self):
        return _FakeSummary(nodes_deleted=self._nodes_deleted)


class _FakeSession:
    """실행된 (query, params)를 순서대로 기록하고, 미리 정해둔 결과를 문 순서대로 반환한다."""

    def __init__(self, results):
        self.calls: list[tuple[str, dict]] = []
        self._results = list(results)

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return None

    async def run(self, query, **params):
        self.calls.append((query, params))
        return self._results.pop(0)


class _FakeDriver:
    def __init__(self, session):
        self._session = session

    def session(self):
        return self._session


class ClearBulkDocumentIssueLinksTest(unittest.TestCase):
    def _run(self, project_id=None, edges_deleted=3, stubs_collected=1):
        session = _FakeSession([
            _FakeResult(single_record={"deleted": edges_deleted}),
            _FakeResult(nodes_deleted=stubs_collected),
        ])
        with patch("graph.maintenance.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(clear_bulk_document_issue_links(project_id))
        return result, session

    def test_edges_query_scoped_to_text_source(self):
        # semantic DESCRIBED_IN을 보존하려면 이 조건이 반드시 있어야 한다 —
        # 없으면(예: 실수로 지워지면) 이 assertion이 깨진다.
        result, session = self._run()

        edges_query, _params = session.calls[0]
        self.assertIn("r.source = 'text'", edges_query)

    def test_edges_query_targets_only_over_limit_documents(self):
        # '>'을 '>='로 바꾸면 상한과 정확히 같은 문서(경계값)까지 지워져 이 assertion이 깨진다.
        result, session = self._run()

        edges_query, params = session.calls[0]
        self.assertIn("size(rels) > $limit", edges_query)
        self.assertEqual(params["limit"], DOCUMENT_ISSUE_REF_LIMIT)

    def test_limit_comes_from_document_policy_module(self):
        # event_handler(수집 가드)와 maintenance(소급 정리)가 값이 갈라지면 안 된다는
        # 계약 — document_policy가 아닌 다른 값(하드코딩 등)을 쓰면 이 assertion이 깨진다.
        _result, session = self._run()

        _edges_query, params = session.calls[0]
        self.assertEqual(params["limit"], DOCUMENT_ISSUE_REF_LIMIT)

    def test_project_id_scopes_edges_and_stub_queries(self):
        result, session = self._run(project_id="project-1")

        edges_query, edges_params = session.calls[0]
        stub_query, stub_params = session.calls[1]
        self.assertIn("AND i.project_id = $project_id", edges_query)
        self.assertEqual(edges_params["project_id"], "project-1")
        self.assertIn("AND s.project_id = $project_id", stub_query)
        self.assertEqual(stub_params["project_id"], "project-1")

    def test_no_project_id_leaves_queries_unscoped(self):
        result, session = self._run(project_id=None)

        edges_query, edges_params = session.calls[0]
        stub_query, stub_params = session.calls[1]
        self.assertNotIn("i.project_id", edges_query)
        self.assertIsNone(edges_params["project_id"])
        self.assertNotIn("s.project_id", stub_query)
        self.assertIsNone(stub_params["project_id"])

    def test_stub_collection_follows_edge_deletion(self):
        result, session = self._run(edges_deleted=5, stubs_collected=2)

        self.assertEqual(len(session.calls), 2)
        stub_query, _params = session.calls[1]
        self.assertIn("s:Issue {source: '__stub__'}", stub_query)
        self.assertIn("NOT (s)--()", stub_query)
        self.assertEqual(result, {"edges_deleted": 5, "stubs_collected": 2})

    def test_returns_zero_edges_when_no_document_over_limit(self):
        result, _session = self._run(edges_deleted=0, stubs_collected=0)

        self.assertEqual(result, {"edges_deleted": 0, "stubs_collected": 0})


if __name__ == "__main__":
    unittest.main()
