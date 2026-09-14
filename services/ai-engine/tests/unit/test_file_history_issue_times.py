"""get_file_history의 issue_links(_fetch_file_history)에 연결 이슈 created_at/closed_at이
실리고 UTC로 정규화되는지(B-3) 단위 테스트 — Neo4j 없이 fake session/driver로 검증.

Issue.createdAt/closedAt만 +09:00 오프셋으로 저장돼 있어(_common._event_time docstring)
정규화하지 않으면 파일 이력 안의 이슈 시각이 다른 노드와 표기가 섞인다.
"""

import asyncio
import unittest
from unittest.mock import patch

from tools.queries.files import get_file_history

_KST_CREATED = "2026-05-16T19:47:00+09:00"  # = 2026-05-16T10:47:00Z


class _FakeResult:
    def __init__(self, record):
        self._record = record

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
        record = self._records.pop(0) if self._records else []
        return _FakeResult(record)


class _FakeDriver:
    def __init__(self, session):
        self._session = session

    def session(self):
        return self._session


class FileHistoryIssueTimesTest(unittest.TestCase):
    def test_issue_created_and_closed_at_normalized_to_utc(self):
        rows = [{
            "hash": "abc1234", "message": "m", "occurredAt": "2026-05-16T11:07:48Z", "author": "a",
            "diff_summary": "d",
            "issues": [{
                "issue_key": "HT-1", "title": "t", "confidence": 1.0, "source": "text",
                "created_at": _KST_CREATED, "closed_at": None,
            }],
            "prs": [], "relevance": None,
        }]
        session = _FakeSession(records=[rows])
        with patch("tools.queries.files.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_file_history("p1", "src/x.py"))

        query, _params = session.calls[0]
        self.assertIn("created_at: toString(i.createdAt)", query)
        self.assertIn("closed_at: toString(i.closedAt)", query)

        issue = result["detail"][0]["issues"][0]
        self.assertEqual(issue["created_at"], "2026-05-16T10:47:00.000Z")
        self.assertIsNone(issue["closed_at"])


if __name__ == "__main__":
    unittest.main()
