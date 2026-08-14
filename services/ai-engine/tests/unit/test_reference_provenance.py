"""REFERENCE의 text/semantic provenance 보존 회귀 테스트 (오프라인).

Notion URL 같은 명시 참조는 원본 이벤트를 다시 수집해야만 복구된다. 정밀 재구축이
시맨틱 후보를 다시 만들면서 text 엣지를 지우거나 덮어쓰면 안 된다.
"""

import asyncio
import unittest
from unittest.mock import patch

from graph.maintenance import clear_reference
from graph.reference_store import _create_reference_edge


class _FakeCounters:
    def __init__(self, relationships_deleted: int):
        self.relationships_deleted = relationships_deleted


class _FakeSummary:
    def __init__(self, relationships_deleted: int):
        self.counters = _FakeCounters(relationships_deleted)


class _FakeResult:
    def __init__(self, relationships_deleted: int = 0):
        self._relationships_deleted = relationships_deleted

    async def consume(self):
        return _FakeSummary(self._relationships_deleted)


class _FakeSession:
    def __init__(self, relationships_deleted: int = 0):
        self.calls: list[tuple[str, dict]] = []
        self._relationships_deleted = relationships_deleted

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return None

    async def run(self, query, **params):
        self.calls.append((query, params))
        return _FakeResult(self._relationships_deleted)


class _FakeDriver:
    def __init__(self, session):
        self._session = session

    def session(self):
        return self._session


class ReferenceProvenanceTest(unittest.TestCase):
    def test_clear_reference_deletes_semantic_and_legacy_but_preserves_text(self):
        session = _FakeSession(relationships_deleted=2)

        with patch("graph.maintenance.get_driver", return_value=_FakeDriver(session)):
            deleted = asyncio.run(clear_reference("project-1"))

        self.assertEqual(deleted, 2)
        query, params = session.calls[0]
        self.assertIn("coalesce(r.source, 'semantic') = 'semantic'", query)
        self.assertNotIn("DELETE r\n        WHERE", query)
        self.assertEqual(params, {"project_id": "project-1"})

    def test_semantic_builder_never_overwrites_a_text_reference(self):
        session = _FakeSession()

        with patch("graph.reference_store.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(_create_reference_edge("project-1", "abc123", "slack://message/1", 0.73))

        query, params = session.calls[0]
        self.assertIn("WHERE NOT (c)-[:REFERENCE {source: 'text'}]->(comm)", query)
        self.assertIn("SET r.source = 'semantic', r.confidence = $confidence", query)
        self.assertEqual(
            params,
            {
                "project_id": "project-1",
                "changeset_id": "abc123",
                "communication_id": "slack://message/1",
                "confidence": 0.73,
            },
        )


if __name__ == "__main__":
    unittest.main()
