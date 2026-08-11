"""Issue 노드 키 일반화(project_id, source, external_id) 이후 텍스트 링크 stub 처리 단위 테스트.

Issue의 MERGE 키가 (project_id, issue_key)에서 (project_id, source, external_id)로 바뀌면서,
아직 실제 이슈 이벤트가 도착하지 않은 issue_key를 텍스트가 참조할 때는 __stub__ 센티널
Issue(project_id, source='__stub__', external_id=issue_key)를 만들어 엣지를 걸어둔다. 실제
이슈 이벤트가 도착하면 absorb_issue_stub이 그 엣지를 실노드로 이관하고 stub을 지운다.

이 파일은 오프라인 — 실제 Cypher 의미론(제약·트랜잭션)은 live Neo4j가 필요한 integration
영역이고, 여기서는 fake session/driver로 쿼리 문자열·문 구조·파라미터만 검증한다.
"""

import asyncio
import unittest
from unittest.mock import AsyncMock, patch

from graph.event_handler import handle
from graph.writes import (
    STATUS_CATEGORY_CLOSED,
    absorb_issue_stub,
    link_changeset_to_issue,
    link_changeset_to_pr_issues,
    link_issue_to_communication,
    link_pr_changesets_to_issues,
    upsert_issue,
)


class _FakeResult:
    def __init__(self, record):
        self._record = record

    async def single(self):
        return self._record


class _FakeSession:
    """실행된 (query, params)를 기록하고, 미리 정해둔 레코드를 문 순서대로 반환한다."""

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


class LinkChangesetToIssueStubTest(unittest.TestCase):
    def test_real_node_match_skips_stub_statement(self):
        session = _FakeSession(records=[{"matched": 1}])
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(link_changeset_to_issue("p1", "abc123", "HT-1"))

        self.assertEqual(len(session.calls), 1)
        query, params = session.calls[0]
        self.assertIn("i.source <> '__stub__'", query)
        self.assertEqual(params["issue_key"], "HT-1")
        self.assertEqual(params["hash"], "abc123")

    def test_no_match_falls_back_to_stub_creation(self):
        session = _FakeSession(records=[{"matched": 0}])
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(link_changeset_to_issue("p1", "abc123", "HT-1"))

        self.assertEqual(len(session.calls), 2)
        stub_query, stub_params = session.calls[1]
        self.assertIn("source: '__stub__'", stub_query)
        self.assertIn("ON CREATE SET i.issue_key = $issue_key", stub_query)
        self.assertEqual(stub_params["issue_key"], "HT-1")
        self.assertEqual(stub_params["hash"], "abc123")


class LinkIssueToCommunicationStubTest(unittest.TestCase):
    def test_real_node_match_skips_stub_statement(self):
        session = _FakeSession(records=[{"matched": 1}])
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(link_issue_to_communication("p1", "HT-1", "https://slack/x"))

        self.assertEqual(len(session.calls), 1)
        query, _params = session.calls[0]
        self.assertIn("i.source <> '__stub__'", query)
        self.assertIn("DISCUSSED_IN", query)

    def test_no_match_falls_back_to_stub_creation(self):
        session = _FakeSession(records=[{"matched": 0}])
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(link_issue_to_communication("p1", "HT-1", "https://slack/x"))

        self.assertEqual(len(session.calls), 2)
        stub_query, stub_params = session.calls[1]
        self.assertIn("source: '__stub__'", stub_query)
        self.assertIn("DISCUSSED_IN", stub_query)
        self.assertEqual(stub_params["issue_key"], "HT-1")


class LinkChangesetToPrIssuesUnwindTest(unittest.TestCase):
    def test_all_matched_skips_second_statement(self):
        session = _FakeSession(records=[{"matched": 2, "unmatched_raw": [None, None]}])
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            n = asyncio.run(link_changeset_to_pr_issues("p1", 7, "abc"))

        self.assertEqual(len(session.calls), 1)
        self.assertEqual(n, 2)

    def test_unmatched_keys_trigger_stub_statement(self):
        session = _FakeSession(records=[
            {"matched": 1, "unmatched_raw": ["HT-9", None]},
            {"created": 1},
        ])
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            n = asyncio.run(link_changeset_to_pr_issues("p1", 7, "abc"))

        self.assertEqual(len(session.calls), 2)
        stub_query, stub_params = session.calls[1]
        self.assertIn("source: '__stub__'", stub_query)
        self.assertEqual(stub_params["unmatched_keys"], ["HT-9"])
        self.assertEqual(n, 2)  # matched(1) + created(1)


class LinkPrChangesetsToIssuesUnwindTest(unittest.TestCase):
    def test_all_matched_skips_second_statement(self):
        session = _FakeSession(records=[{"matched": 3, "unmatched_raw": [None]}])
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            n = asyncio.run(link_pr_changesets_to_issues("p1", 7))

        self.assertEqual(len(session.calls), 1)
        self.assertEqual(n, 3)

    def test_unmatched_keys_trigger_stub_statement(self):
        session = _FakeSession(records=[
            {"matched": 0, "unmatched_raw": ["HT-9"]},
            {"created": 2},
        ])
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            n = asyncio.run(link_pr_changesets_to_issues("p1", 7))

        self.assertEqual(len(session.calls), 2)
        stub_query, stub_params = session.calls[1]
        self.assertIn("source: '__stub__'", stub_query)
        self.assertEqual(stub_params["unmatched_keys"], ["HT-9"])
        self.assertEqual(n, 2)  # matched(0) + created(2)


class AbsorbIssueStubTest(unittest.TestCase):
    def test_migrates_triggered_by_and_discussed_in_then_deletes_stub(self):
        session = _FakeSession()
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(absorb_issue_stub("p1", "JIRA", "JIRA-100", "HT-1"))

        self.assertEqual(len(session.calls), 1)
        query, params = session.calls[0]
        self.assertIn("TRIGGERED_BY", query)
        self.assertIn("DISCUSSED_IN", query)
        self.assertIn("DETACH DELETE s", query)
        self.assertEqual(params["project_id"], "p1")
        self.assertEqual(params["source"], "JIRA")
        self.assertEqual(params["external_id"], "JIRA-100")
        self.assertEqual(params["issue_key"], "HT-1")


class UpsertIssueMergeKeyTest(unittest.TestCase):
    def test_merge_key_is_project_source_external_id(self):
        session = _FakeSession()
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(upsert_issue(
                project_id="p1", source="JIRA", external_id="JIRA-100", issue_key="HT-1",
                title="t", body="b", status="진행중", status_category="in_progress",
                issue_type="Task", priority="Medium", occurred_at="2026-07-01T00:00:00Z",
                created_at=None, actor_uuid="author-uuid", embedding=[],
            ))

        self.assertEqual(len(session.calls), 1)
        query, params = session.calls[0]
        self.assertIn(
            "MERGE (i:Issue {project_id: $project_id, source: $source, external_id: $external_id})",
            query,
        )
        self.assertIn("i.issue_key = $issue_key", query)
        self.assertIn("$status_category = $closed_category", query)
        self.assertEqual(params["closed_category"], STATUS_CATEGORY_CLOSED)
        self.assertEqual(params["status_category"], "in_progress")
        self.assertEqual(params["external_id"], "JIRA-100")

    def test_closed_category_constant_is_closed(self):
        self.assertEqual(STATUS_CATEGORY_CLOSED, "closed")


def _issue_event(refs=None, issue_key="HT-1", external_id="JIRA-100"):
    props = {
        "title": "제목", "body": "본문", "status": "진행중",
        "status_category": "in_progress", "issue_type": "Task", "priority": "Medium",
    }
    if external_id is not None:
        props["external_id"] = external_id
    if issue_key is not None:
        props["issue_key"] = issue_key
    return {
        "nodeType": "Issue", "source": "JIRA", "projectId": "p1",
        "occurredAt": "2026-07-01T00:00:00Z",
        "actor": {"id": "reporter1", "name": "Reporter One", "email": "reporter1@example.com"},
        "properties": props, "refs": refs or {},
    }


class HandleIssueAbsorbTest(unittest.TestCase):
    def _run(self, event):
        absorb_mock = AsyncMock()
        with patch("graph.event_handler.builder.upsert_issue", AsyncMock()), \
             patch("graph.event_handler.builder.absorb_issue_stub", absorb_mock), \
             patch("graph.event_handler.builder.set_issue_assignees", AsyncMock()), \
             patch("graph.event_handler.resolve_actor", AsyncMock(return_value={"uuid": "author-uuid"})), \
             patch("graph.event_handler.make_neo4j_actor_store", return_value="STORE"), \
             patch("graph.event_handler.embed_text", AsyncMock(return_value=[])):
            asyncio.run(handle(event))
        return absorb_mock

    def test_absorb_called_when_issue_key_present(self):
        absorb_mock = self._run(_issue_event(issue_key="HT-1", external_id="JIRA-100"))
        absorb_mock.assert_awaited_once_with("p1", "JIRA", "JIRA-100", "HT-1")

    def test_absorb_not_called_when_issue_key_missing(self):
        absorb_mock = self._run(_issue_event(issue_key=None, external_id="JIRA-100"))
        absorb_mock.assert_not_awaited()


if __name__ == "__main__":
    unittest.main()
