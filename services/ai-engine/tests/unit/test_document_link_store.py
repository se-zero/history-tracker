"""document_link_store.py의 Cypher 문 구조·파라미터 검증 (오프라인 — fake session/driver).

test_document_ingest.py의 _FakeSession 패턴을 그대로 미러한다. 여기서는 실제 Cypher 의미론이
아니라 문 구조·바인딩 파라미터만 확인한다.
"""

import asyncio
import unittest
from unittest.mock import patch

from graph.document_link_store import (
    _create_described_in_document_edge,
    _create_document_reference_edge,
    _fetch_document_sections,
    _fetch_documents,
)


class _FakeResult:
    def __init__(self, rows=None):
        self._rows = rows or []

    async def data(self):
        return self._rows


class _FakeSession:
    def __init__(self, rows=None):
        self.calls = []
        self._rows = rows or []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return None

    async def run(self, query, **params):
        self.calls.append((query, params))
        return _FakeResult(self._rows)


class _FakeDriver:
    def __init__(self, session):
        self._session = session

    def session(self):
        return self._session


class FetchDocumentsTest(unittest.TestCase):
    def test_scopes_by_project_and_falls_back_created_at_to_occurred_at(self):
        session = _FakeSession()
        with patch("graph.document_link_store.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(_fetch_documents("p1"))

        query, params = session.calls[0]
        self.assertIn("MATCH (d:Document)", query)
        self.assertIn("coalesce(d.createdAt, d.occurredAt)", query)
        self.assertIn("AND d.project_id = $project_id", query)
        self.assertEqual(params["project_id"], "p1")

    def test_none_project_id_omits_filter(self):
        session = _FakeSession()
        with patch("graph.document_link_store.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(_fetch_documents(None))

        query, _params = session.calls[0]
        self.assertNotIn("d.project_id = $project_id", query)


class FetchDocumentSectionsTest(unittest.TestCase):
    def test_encodes_document_id_as_composite_key(self):
        session = _FakeSession()
        with patch("graph.document_link_store.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(_fetch_document_sections("p1"))

        query, params = session.calls[0]
        self.assertIn("MATCH (s:DocumentSection)", query)
        self.assertIn("s.source + ':' + s.document_external_id AS document_id", query)
        self.assertIn("WHERE s.embedding IS NOT NULL", query)
        self.assertEqual(params["project_id"], "p1")


class CreateDocumentReferenceEdgeTest(unittest.TestCase):
    def test_guards_against_overwriting_text_edge_and_splits_composite_id(self):
        session = _FakeSession()
        with patch("graph.document_link_store.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(_create_document_reference_edge("p1", "abc123", "NOTION:page-1", 0.77, "토큰 갱신"))

        query, params = session.calls[0]
        self.assertIn("WHERE NOT (c)-[:REFERENCE {source: 'text'}]->(d)", query)
        self.assertIn("MERGE (c)-[r:REFERENCE]->(d)", query)
        self.assertIn("SET r.source = 'semantic', r.confidence = $confidence, r.section = $section", query)
        self.assertEqual(params["source"], "NOTION")
        self.assertEqual(params["external_id"], "page-1")
        self.assertEqual(params["confidence"], 0.77)
        self.assertEqual(params["section"], "토큰 갱신")


class CreateDescribedInDocumentEdgeTest(unittest.TestCase):
    def test_guards_against_overwriting_text_edge_and_splits_both_composite_ids(self):
        session = _FakeSession()
        with patch("graph.document_link_store.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(_create_described_in_document_edge("p1", "JIRA:HT-1", "NOTION:page-1", 0.6, "배경"))

        query, params = session.calls[0]
        self.assertIn("WHERE NOT (i)-[:DESCRIBED_IN {source: 'text'}]->(d)", query)
        self.assertIn("MERGE (i)-[r:DESCRIBED_IN]->(d)", query)
        self.assertIn("SET r.source = 'semantic', r.confidence = $confidence, r.section = $section", query)
        self.assertEqual(params["issue_source"], "JIRA")
        self.assertEqual(params["issue_external_id"], "HT-1")
        self.assertEqual(params["doc_source"], "NOTION")
        self.assertEqual(params["doc_external_id"], "page-1")


if __name__ == "__main__":
    unittest.main()
