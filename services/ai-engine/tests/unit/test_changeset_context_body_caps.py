"""get_changeset_context·get_conflict_context·get_pr_context의 본문 캡(B-1) 단위 테스트.

diffSummary/diff_summary·changesets[*].message는 executor 8,000자 상한에서 결과가 문자열
중간 절단되지 않도록 조회 후 Python에서 캡을 씌운다. commit_message·PR body·
issue_contexts[*].text는 근거 인용의 원문이라 캡 대상이 아니다 — 함께 확인한다
(오프라인 — fake session/driver, 패턴은 test_changeset_pr_document_external_id.py와 동일).
"""

import asyncio
import unittest
from unittest.mock import patch

from tools.queries._common import _DETAIL_MESSAGE_MAX_CHARS, _DIFF_SUMMARY_MAX_CHARS
from tools.queries.changeset import get_changeset_context, get_conflict_context, get_pr_context

_ELLIPSIS = " …(생략)"
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


class GetChangesetContextCapsTest(unittest.TestCase):
    def test_diff_summary_capped_but_commit_message_untouched(self):
        long_diff = "D" * (_DIFF_SUMMARY_MAX_CHARS + 50)
        long_message = "M" * 5000
        row = {
            "hash": _FULL_HASH, "commit_message": long_message, "occurredAt": None, "author": "A",
            "issues": [], "communications": [], "documents": [],
            "pull_request": {"pr_number": None, "title": None, "url": None},
            "file_changes": [{"path": "a.py", "diffSummary": long_diff}],
        }
        session = _FakeSession(records=[row])
        with patch("tools.queries.changeset.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_changeset_context("p1", _FULL_HASH))

        capped = result["file_changes"][0]["diffSummary"]
        self.assertTrue(capped.endswith(_ELLIPSIS))
        self.assertEqual(len(capped), _DIFF_SUMMARY_MAX_CHARS + len(_ELLIPSIS))
        self.assertEqual(result["commit_message"], long_message)  # 인용 원문 — 캡 대상 아님


class GetConflictContextCapsTest(unittest.TestCase):
    def test_diff_summary_capped_but_issue_text_untouched(self):
        long_diff = "D" * (_DIFF_SUMMARY_MAX_CHARS + 50)
        long_text = "T" * 5000
        row = {
            "hash": _FULL_HASH, "commit_message": "m", "occurredAt": None,
            "issue_contexts": [{
                "source": "JIRA", "id": "HT-1", "text": long_text,
                "confidence": 1.0, "link_source": "text",
                "created_at": None, "closed_at": None,
            }],
            "comm_contexts": [], "pr_contexts": [], "doc_contexts": [],
            "file_changes": [{"path": "a.py", "diff_summary": long_diff}],
        }
        session = _FakeSession(records=[row])
        with patch("tools.queries.changeset.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_conflict_context("p1", _FULL_HASH))

        capped = result["file_changes"][0]["diff_summary"]
        self.assertTrue(capped.endswith(_ELLIPSIS))
        self.assertEqual(result["issue_contexts"][0]["text"], long_text)  # 인용 원문 유지


class GetPrContextCapsTest(unittest.TestCase):
    def test_diff_summary_and_changeset_message_capped_but_body_untouched(self):
        long_diff = "D" * (_DIFF_SUMMARY_MAX_CHARS + 50)
        long_message = "M" * (_DETAIL_MESSAGE_MAX_CHARS + 50)
        long_body = "B" * 5000
        row = {
            "pr_number": 18, "title": "t", "body": long_body, "merged_at": None, "created_at": None,
            "url": "u", "author": "A",
            "changesets": [{"hash": "abc1234", "message": long_message, "occurredAt": None, "author": "A"}],
            "issues": [], "discussions": [], "documents": [],
            "file_changes": [{"path": "a.py", "diff_summary": long_diff}],
        }
        session = _FakeSession(records=[row])
        with patch("tools.queries.changeset.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_pr_context("p1", 18))

        self.assertTrue(result["file_changes"][0]["diff_summary"].endswith(_ELLIPSIS))
        capped_message = result["changesets"][0]["message"]
        self.assertTrue(capped_message.endswith(_ELLIPSIS))
        self.assertEqual(len(capped_message), _DETAIL_MESSAGE_MAX_CHARS + len(_ELLIPSIS))
        self.assertEqual(result["body"], long_body)  # 인용 원문 — 캡 대상 아님


if __name__ == "__main__":
    unittest.main()
