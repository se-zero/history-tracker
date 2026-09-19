"""get_changeset_context·get_conflict_context·get_pr_context의 대화 항목에 link_source(C-2)가
실리는지 단위 테스트.

커밋→이슈(TRIGGERED_BY) 항목은 이미 link_source(=tb.source)를 실어 명시 참조('text')와
시맨틱 추론('semantic')을 구분하는데, 같은 파일의 커밋↔대화(REFERENCE) 항목에는 그 구분이
없었다 — 신뢰도 필터가 적용되지 않는 연결이라(docs/evidence-retrieval-fixes.md §5 C-2) 모델이
낮은 confidence를 스스로 판단하려면 link_source가 필요하다.
_group_communications_by_thread의 GROUP_KEYS({conversation_id, source, channel})와 이름이
겹치지 않는지도 함께 확인한다 — link_source가 그룹 키에 흡수되지 않고 메시지 dict에 남아야 한다.
(오프라인 — fake session/driver, 패턴은 test_changeset_nested_issue_times.py와 동일)
"""

import asyncio
import unittest
from unittest.mock import patch

from tools.queries.changeset import get_changeset_context, get_conflict_context, get_pr_context

_FULL_HASH = "a" * 40


class _FakeResult:
    def __init__(self, record):
        self._record = record

    async def single(self):
        return self._record

    async def data(self):
        return self._record


class _FakeSession:
    def __init__(self, records=None):
        self.calls = []
        self._records = list(records or [])

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return None

    async def run(self, query, **params):
        self.calls.append((query, params))
        record = self._records.pop(0) if self._records else None
        return _FakeResult(record)


class _FakeDriver:
    def __init__(self, session):
        self._session = session

    def session(self):
        return self._session


class GetChangesetContextCommunicationLinkSourceTest(unittest.TestCase):
    def test_query_selects_link_source_and_survives_grouping(self):
        row = {
            "hash": _FULL_HASH, "commit_message": "m", "occurredAt": None, "author": "A",
            "issues": [],
            "communications": [{
                "body": "hi", "channel": "general", "source": "SLACK",
                "occurredAt": "2026-05-16T10:47:00.000Z", "conversation_id": "c1",
                "author": "B", "confidence": 0.4, "link_source": "semantic",
            }],
            "documents": [],
            "pull_request": {"pr_number": None, "title": None, "url": None},
            "file_changes": [],
        }
        session = _FakeSession(records=[row])
        with patch("tools.queries.changeset.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_changeset_context("p1", _FULL_HASH))

        query, _params = session.calls[0]
        self.assertIn("link_source: ref.source", query)

        thread = result["communications"][0]
        self.assertEqual("semantic", thread["messages"][0]["link_source"])


class GetConflictContextCommunicationLinkSourceTest(unittest.TestCase):
    def test_query_selects_link_source_and_survives_grouping(self):
        row = {
            "hash": _FULL_HASH, "commit_message": "m", "occurredAt": None,
            "issue_contexts": [],
            "comm_contexts": [{
                "source": "SLACK", "channel": "general", "conversation_id": "c1",
                "body": "hi", "author": "B", "occurredAt": "2026-05-16T10:47:00.000Z",
                "confidence": None, "link_source": "text",
            }],
            "pr_contexts": [], "doc_contexts": [], "file_changes": [],
        }
        session = _FakeSession(records=[row])
        with patch("tools.queries.changeset.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_conflict_context("p1", _FULL_HASH))

        query, _params = session.calls[0]
        self.assertIn("link_source: ref.source", query)

        thread = result["comm_contexts"][0]
        self.assertEqual("text", thread["messages"][0]["link_source"])


class GetPrContextCommunicationLinkSourceTest(unittest.TestCase):
    def test_query_selects_link_source_and_survives_grouping(self):
        row = {
            "pr_number": 18, "title": "t", "body": "b", "merged_at": None, "created_at": None,
            "url": "u", "author": "A", "changesets": [], "issues": [],
            "discussions": [{
                "body": "hi", "channel": "general", "source": "SLACK",
                "occurredAt": "2026-05-16T10:47:00.000Z", "conversation_id": "c1",
                "author": "B", "confidence": 0.6, "link_source": "semantic",
            }],
            "documents": [], "file_changes": [],
        }
        session = _FakeSession(records=[row])
        with patch("tools.queries.changeset.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_pr_context("p1", 18))

        query, _params = session.calls[0]
        self.assertIn("link_source: ref.source", query)

        thread = result["discussions"][0]
        self.assertEqual("semantic", thread["messages"][0]["link_source"])


if __name__ == "__main__":
    unittest.main()
