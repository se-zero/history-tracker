"""임베딩 실패가 기존 벡터를 빈 값으로 덮지 않는 Cypher 가드 단위 테스트.

upsert_changeset은 이미 CASE WHEN size($embedding) > 0 가드를 쓰고,
Issue·Communication·DocumentSection upsert가 같은 패턴을 따르는지 쿼리 문자열로 고정한다.
실제 Neo4j 의미론(빈 리스트 size, MERGE 후 ELSE 보존)은 live 영역이다.
"""

import asyncio
import unittest
from unittest.mock import patch

from graph.writes import (
    replace_document_sections,
    upsert_communication,
    upsert_issue,
)


class _FakeResult:
    async def single(self):
        return None


class _FakeSession:
    def __init__(self):
        self.calls = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return None

    async def run(self, query, **params):
        self.calls.append((query, params))
        return _FakeResult()


class _FakeDriver:
    def __init__(self, session):
        self._session = session

    def session(self):
        return self._session


def _issue_kwargs():
    return dict(
        project_id="p1", source="JIRA", external_id="JIRA-100", issue_key="HT-1",
        title="t", body="b", status="진행중", status_category="in_progress",
        issue_type="Task", priority="Medium", occurred_at="2026-07-01T00:00:00Z",
        created_at=None, actor_uuid="author-uuid", embedding=[],
    )


class EmbeddingPreserveCypherTest(unittest.TestCase):
    def test_upsert_issue_keeps_existing_embedding_when_empty(self):
        session = _FakeSession()
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(upsert_issue(**_issue_kwargs()))

        query = session.calls[0][0]
        self.assertIn(
            "CASE WHEN size($embedding) > 0 THEN $embedding ELSE i.embedding END",
            query,
        )

    def test_upsert_communication_keeps_existing_embedding_when_empty(self):
        session = _FakeSession()
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(upsert_communication(
                project_id="p1", url="https://slack/x", body="hello",
                channel="C1", conversation_id="T1", occurred_at="2026-07-01T00:00:00Z",
                created_at=None, source="SLACK", actor_uuid="actor-1", embedding=[],
            ))

        query = session.calls[0][0]
        self.assertIn(
            "CASE WHEN size($embedding) > 0 THEN $embedding ELSE comm.embedding END",
            query,
        )

    def test_replace_document_sections_keeps_existing_embedding_when_empty(self):
        session = _FakeSession()
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(replace_document_sections(
                project_id="p1", source="NOTION", document_external_id="page-1",
                sections=[{"ordinal": 0, "heading_path": "h", "text": "t", "embedding": []}],
            ))

        create_query = session.calls[1][0]
        self.assertIn(
            "CASE WHEN size(section.embedding) > 0 THEN section.embedding ELSE s.embedding END",
            create_query,
        )


if __name__ == "__main__":
    unittest.main()
