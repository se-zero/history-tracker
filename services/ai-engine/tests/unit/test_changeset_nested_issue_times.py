"""get_changeset_context·get_conflict_context·get_pr_context 안에 중첩된 issues/issue_contexts에
created_at/closed_at이 실리고 UTC로 정규화되는지(B-3) 단위 테스트.

근거 스키마(agent/orchestrator.py의 _GROUNDED_ANSWER_SCHEMA)가 occurredAt을 null 불가
필수로 요구하는데, 커밋·PR 결과 안에 중첩된 이슈에는 원래 시각이 없어 모델이 인용을
포기하거나 시각을 지어냈다. Issue.createdAt/closedAt만 +09:00 오프셋으로 저장되므로
(_common._event_time docstring) 정규화 여부까지 함께 검증한다
(오프라인 — fake session/driver, 패턴은 test_changeset_pr_document_external_id.py와 동일).
"""

import asyncio
import unittest
from unittest.mock import patch

from tools.queries.changeset import get_changeset_context, get_conflict_context, get_pr_context

_FULL_HASH = "a" * 40
_KST_CREATED = "2026-05-16T19:47:00+09:00"   # = 2026-05-16T10:47:00Z
_KST_CLOSED = "2026-05-17T09:00:00+09:00"    # = 2026-05-17T00:00:00Z


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


class GetChangesetContextIssueTimesTest(unittest.TestCase):
    def test_issue_created_and_closed_at_normalized_to_utc(self):
        row = {
            "hash": _FULL_HASH, "commit_message": "m", "occurredAt": None, "author": "A",
            "issues": [{
                "issue_key": "HT-1", "title": "t", "body": "b", "status": "Done",
                "confidence": 1.0, "link_source": "text",
                "created_at": _KST_CREATED, "closed_at": _KST_CLOSED,
            }],
            "communications": [], "documents": [],
            "pull_request": {"pr_number": None, "title": None, "url": None},
            "file_changes": [],
        }
        session = _FakeSession(records=[row])
        with patch("tools.queries.changeset.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_changeset_context("p1", _FULL_HASH))

        query, _params = session.calls[0]
        self.assertIn("created_at: toString(i.createdAt)", query)
        self.assertIn("closed_at: toString(i.closedAt)", query)

        issue = result["issues"][0]
        self.assertEqual(issue["created_at"], "2026-05-16T10:47:00.000Z")
        self.assertEqual(issue["closed_at"], "2026-05-17T00:00:00.000Z")


class GetConflictContextIssueTimesTest(unittest.TestCase):
    def test_issue_context_created_and_closed_at_normalized_to_utc(self):
        row = {
            "hash": _FULL_HASH, "commit_message": "m", "occurredAt": None,
            "issue_contexts": [{
                "source": "JIRA", "id": "HT-1", "text": "t\nb",
                "confidence": 1.0, "link_source": "text",
                "created_at": _KST_CREATED, "closed_at": None,
            }],
            "comm_contexts": [], "pr_contexts": [], "doc_contexts": [],
            "file_changes": [],
        }
        session = _FakeSession(records=[row])
        with patch("tools.queries.changeset.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_conflict_context("p1", _FULL_HASH))

        query, _params = session.calls[0]
        self.assertIn("created_at: toString(i.createdAt)", query)
        self.assertIn("closed_at: toString(i.closedAt)", query)

        issue_ctx = result["issue_contexts"][0]
        self.assertEqual(issue_ctx["created_at"], "2026-05-16T10:47:00.000Z")
        self.assertIsNone(issue_ctx["closed_at"])


class GetPrContextIssueTimesTest(unittest.TestCase):
    def test_issue_created_and_closed_at_normalized_to_utc(self):
        row = {
            "pr_number": 18, "title": "t", "body": "b", "merged_at": None, "created_at": None,
            "url": "u", "author": "A",
            "changesets": [],
            "issues": [{
                "issue_key": "HT-1", "title": "t", "status": "Done",
                "confidence": 1.0, "link_source": "text",
                "created_at": _KST_CREATED, "closed_at": _KST_CLOSED,
            }],
            "discussions": [], "documents": [], "file_changes": [],
        }
        session = _FakeSession(records=[row])
        with patch("tools.queries.changeset.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_pr_context("p1", 18))

        query, _params = session.calls[0]
        self.assertIn("created_at: toString(i.createdAt)", query)
        self.assertIn("closed_at: toString(i.closedAt)", query)

        issue = result["issues"][0]
        self.assertEqual(issue["created_at"], "2026-05-16T10:47:00.000Z")
        self.assertEqual(issue["closed_at"], "2026-05-17T00:00:00.000Z")


if __name__ == "__main__":
    unittest.main()
