"""Document/DocumentSection Neo4j 스키마 부트스트랩 테스트."""

import asyncio
import unittest
from unittest.mock import patch

from graph.schema import _UNIQUE_CONSTRAINTS, ensure_vector_indexes


class _FakeSession:
    def __init__(self):
        self.queries = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return None

    async def run(self, query):
        self.queries.append(query)


class _FakeDriver:
    def __init__(self, session):
        self._session = session

    def session(self):
        return self._session


class DocumentSchemaTest(unittest.TestCase):
    def test_document_keys_are_project_source_scoped(self):
        self.assertIn(
            ("document_project_source_external", "Document", ["project_id", "source", "external_id"]),
            _UNIQUE_CONSTRAINTS,
        )
        self.assertIn(
            ("document_section_key", "DocumentSection", ["project_id", "source", "document_external_id", "ordinal"]),
            _UNIQUE_CONSTRAINTS,
        )

    def test_creates_section_vector_index(self):
        session = _FakeSession()
        with patch("graph.schema.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(ensure_vector_indexes())

        query = next(query for query in session.queries if "doc_section_embedding" in query)
        self.assertIn("FOR (s:DocumentSection) ON (s.embedding)", query)
        self.assertIn("`vector.dimensions`: 1536", query)


if __name__ == "__main__":
    unittest.main()
