"""get_issue_context 루트·descendants의 created_at/closed_at(B-3)과 changesets[*].message
캡(B-1) 단위 테스트.

근거 스키마가 occurredAt을 null 불가 필수로 요구하는데 루트/자식 이슈 메타데이터에는
원래 시각이 없어 모델이 인용을 포기하거나 시각을 지어냈다. Issue.createdAt/closedAt만
+09:00 오프셋으로 저장되므로(_common._event_time docstring) 정규화 여부까지 검증한다
(오프라인 — fake session/driver, 패턴은 test_issue_query_ambiguity.py와 동일).
"""

import asyncio
import unittest
from unittest.mock import patch

from tools.queries._common import _DETAIL_MESSAGE_MAX_CHARS
from tools.queries.issue import get_issue_context

_KST_CREATED = "2026-05-18T01:00:00+09:00"  # = 2026-05-17T16:00:00Z
_KST_CLOSED = "2026-05-19T10:00:00+09:00"   # = 2026-05-19T01:00:00Z
_ELLIPSIS = " …(생략)"


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


class GetIssueContextNestedTimesTest(unittest.TestCase):
    def test_root_and_descendant_created_closed_at_normalized(self):
        candidates = [{"source": "JIRA", "issue_key": "ENG-1", "title": "t", "status": "open"}]
        base_row = {
            "issue_key": "ENG-1", "title": "t", "body": "b", "status": "open",
            "issue_type": "Task", "priority": "Medium", "occurredAt": None,
            "created_at": _KST_CREATED, "closed_at": None,
            "creator": None, "assignee": None,
        }
        scope_issues = [
            {"issue_key": "ENG-1", "title": "t", "status": "open",
             "created_at": _KST_CREATED, "closed_at": None},
            {"issue_key": "ENG-2", "title": "child", "status": "done",
             "created_at": _KST_CREATED, "closed_at": _KST_CLOSED},
        ]
        work_rows = [
            {"issue_key": "ENG-1", "changesets": [], "pull_requests": []},
            {"issue_key": "ENG-2", "changesets": [], "pull_requests": []},
        ]
        disc_rows = [{"issue_key": "ENG-1", "discussions": []}, {"issue_key": "ENG-2", "discussions": []}]
        doc_rows = [{"issue_key": "ENG-1", "documents": []}, {"issue_key": "ENG-2", "documents": []}]
        session = _FakeSession(
            records=[candidates, base_row, scope_issues, work_rows, disc_rows, doc_rows]
        )

        with patch("tools.queries.issue.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_issue_context("p1", "ENG-1"))

        base_query, _ = session.calls[1]
        self.assertIn("toString(i.createdAt) AS created_at", base_query)
        self.assertIn("toString(i.closedAt) AS closed_at", base_query)
        scope_query, _ = session.calls[2]
        self.assertIn("toString(i.createdAt) AS created_at", scope_query)
        self.assertIn("toString(i.closedAt) AS closed_at", scope_query)

        self.assertEqual(result["created_at"], "2026-05-17T16:00:00.000Z")
        self.assertIsNone(result["closed_at"])

        child = next(d for d in result["descendants"] if d["issue_key"] == "ENG-2")
        self.assertEqual(child["created_at"], "2026-05-17T16:00:00.000Z")
        self.assertEqual(child["closed_at"], "2026-05-19T01:00:00.000Z")

    def test_root_and_descendant_changeset_message_capped(self):
        candidates = [{"source": "JIRA", "issue_key": "ENG-1", "title": "t", "status": "open"}]
        base_row = {
            "issue_key": "ENG-1", "title": "t", "body": "b", "status": "open",
            "issue_type": "Task", "priority": "Medium", "occurredAt": None,
            "created_at": None, "closed_at": None,
            "creator": None, "assignee": None,
        }
        scope_issues = [
            {"issue_key": "ENG-1", "title": "t", "status": "open",
             "created_at": None, "closed_at": None},
            {"issue_key": "ENG-2", "title": "child", "status": "done",
             "created_at": None, "closed_at": None},
        ]
        long_message = "M" * (_DETAIL_MESSAGE_MAX_CHARS + 50)
        work_rows = [
            {"issue_key": "ENG-1",
             "changesets": [{"hash": "h1", "message": long_message, "occurredAt": None,
                             "author": "a", "confidence": 1.0, "link_source": "text"}],
             "pull_requests": []},
            {"issue_key": "ENG-2",
             "changesets": [{"hash": "h2", "message": long_message, "occurredAt": None,
                             "author": "a", "confidence": 1.0, "link_source": "text"}],
             "pull_requests": []},
        ]
        disc_rows = [{"issue_key": "ENG-1", "discussions": []}, {"issue_key": "ENG-2", "discussions": []}]
        doc_rows = [{"issue_key": "ENG-1", "documents": []}, {"issue_key": "ENG-2", "documents": []}]
        session = _FakeSession(
            records=[candidates, base_row, scope_issues, work_rows, disc_rows, doc_rows]
        )

        with patch("tools.queries.issue.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_issue_context("p1", "ENG-1"))

        root_message = result["changesets"][0]["message"]
        self.assertTrue(root_message.endswith(_ELLIPSIS))
        self.assertEqual(len(root_message), _DETAIL_MESSAGE_MAX_CHARS + len(_ELLIPSIS))

        child = next(d for d in result["descendants"] if d["issue_key"] == "ENG-2")
        child_message = child["changesets"][0]["message"]
        self.assertTrue(child_message.endswith(_ELLIPSIS))


class GetIssueContextDiscussionLinkSourceTest(unittest.TestCase):
    def test_discussion_link_source_selected_and_survives_grouping(self):
        # 스레드 전파 엣지는 source='propagated'만 쓰고 confidence를 옮기지 않는다
        # (graph/maintenance.py). 노출하지 않으면 모델에게 명시 참조와 전파가 똑같이
        # "confidence 없음"으로 보여, 시맨틱 추정에서 퍼진 연결을 확정으로 읽는다.
        candidates = [{"source": "JIRA", "issue_key": "ENG-1", "title": "t", "status": "open"}]
        base_row = {
            "issue_key": "ENG-1", "title": "t", "body": "b", "status": "open",
            "issue_type": "Task", "priority": "Medium", "occurredAt": None,
            "created_at": None, "closed_at": None, "creator": None, "assignee": None,
        }
        scope_issues = [{"issue_key": "ENG-1", "title": "t", "status": "open",
                         "created_at": None, "closed_at": None}]
        work_rows = [{"issue_key": "ENG-1", "changesets": [], "pull_requests": []}]
        disc_rows = [{"issue_key": "ENG-1", "discussions": [
            {"body": "b", "channel": "ch", "source": "SLACK", "occurredAt": "2026-05-18T00:00:00Z",
             "conversation_id": "t1", "author": "a", "confidence": None, "link_source": "propagated"},
        ]}]
        doc_rows = [{"issue_key": "ENG-1", "documents": []}]
        session = _FakeSession(
            records=[candidates, base_row, scope_issues, work_rows, disc_rows, doc_rows]
        )

        with patch("tools.queries.issue.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_issue_context("p1", "ENG-1"))

        disc_query, _ = session.calls[4]
        self.assertIn("link_source: disc.source", disc_query)
        message = result["discussions"][0]["messages"][0]
        self.assertEqual("propagated", message["link_source"])  # 그룹 키(source)와 충돌 없이 남는다


if __name__ == "__main__":
    unittest.main()
