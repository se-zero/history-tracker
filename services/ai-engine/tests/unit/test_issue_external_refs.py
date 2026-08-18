"""issueExternalRefs(이슈 키 없는 소스의 URL 참조) 소비 경로 단위 테스트.

Asana처럼 사람용 이슈 키가 없는 소스는 커밋/PR/Slack 텍스트의 태스크 URL에서
(source, externalId) 쌍을 직접 추출해 refs.issueExternalRefs로 발행한다. 이미 소스와
불변 external_id를 알고 있으므로 link_changeset_to_issue류의 실노드/__stub__ 폴백이
필요 없다 — link_issue_to_parent와 동일하게 (project_id, source, external_id) 실키에
곧바로 pre-node MERGE한다.

이 파일은 오프라인 — 실제 Cypher 의미론(제약·트랜잭션)은 live Neo4j가 필요한 integration
영역이고, 여기서는 fake session/driver로 쿼리 문자열·문 구조·파라미터만 검증한다
(test_issue_stub.py의 패턴을 그대로 미러).
"""

import asyncio
import unittest
from unittest.mock import AsyncMock, patch

from graph.event_handler import handle
from graph.writes import (
    _parse_prefixed_refs,
    link_changeset_to_issue_external,
    link_changeset_to_pr_issue_externals,
    link_issue_external_to_communication,
    link_pr_changesets_to_issue_externals,
    upsert_pull_request,
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


class ParseIssueExternalRefsTest(unittest.TestCase):
    def test_splits_on_first_colon(self):
        self.assertEqual(
            _parse_prefixed_refs(["ASANA:123"]),
            [{"source": "ASANA", "external_id": "123"}],
        )

    def test_external_id_may_contain_colon(self):
        # external_id(gid 등) 자체에 콜론이 섞여 있어도 첫 콜론만 분리 기준으로 삼는다.
        self.assertEqual(
            _parse_prefixed_refs(["ASANA:abc:def"]),
            [{"source": "ASANA", "external_id": "abc:def"}],
        )

    def test_malformed_entries_are_skipped(self):
        self.assertEqual(
            _parse_prefixed_refs(["no-colon", ":missing-source", "ASANA:", None, ""]),
            [],
        )

    def test_none_input_yields_empty_list(self):
        self.assertEqual(_parse_prefixed_refs(None), [])


class LinkChangesetToIssueExternalTest(unittest.TestCase):
    def test_merges_real_key_without_stub(self):
        session = _FakeSession()
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(link_changeset_to_issue_external("p1", "abc123", "ASANA", "999"))

        self.assertEqual(len(session.calls), 1)
        query, params = session.calls[0]
        self.assertIn(
            "MERGE (i:Issue {project_id: $project_id, source: $source, external_id: $external_id})",
            query,
        )
        self.assertNotIn("__stub__", query)
        self.assertEqual(params["source"], "ASANA")
        self.assertEqual(params["external_id"], "999")
        self.assertEqual(params["hash"], "abc123")


class LinkIssueExternalToCommunicationTest(unittest.TestCase):
    def test_removes_confidence_without_stub(self):
        session = _FakeSession()
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(link_issue_external_to_communication("p1", "ASANA", "999", "https://slack/x"))

        self.assertEqual(len(session.calls), 1)
        query, params = session.calls[0]
        self.assertIn("DISCUSSED_IN", query)
        self.assertIn("REMOVE r.confidence", query)
        self.assertNotIn("__stub__", query)
        self.assertEqual(params["comm_url"], "https://slack/x")


class LinkChangesetToPrIssueExternalsTest(unittest.TestCase):
    def test_no_contains_relationship_skips_second_statement(self):
        session = _FakeSession(records=[None])
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            n = asyncio.run(link_changeset_to_pr_issue_externals("p1", 7, "abc"))

        self.assertEqual(len(session.calls), 1)
        self.assertEqual(n, 0)

    def test_all_malformed_refs_skip_second_statement(self):
        session = _FakeSession(records=[{"raw_refs": ["no-colon", ":missing-source", "ASANA:"]}])
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            n = asyncio.run(link_changeset_to_pr_issue_externals("p1", 7, "abc"))

        self.assertEqual(len(session.calls), 1)
        self.assertEqual(n, 0)

    def test_parses_refs_and_links_single_changeset(self):
        session = _FakeSession(records=[
            {"raw_refs": ["ASANA:123", "bad-format"]},
            {"created": 1},
        ])
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            n = asyncio.run(link_changeset_to_pr_issue_externals("p1", 7, "abc"))

        self.assertEqual(len(session.calls), 2)
        first_query, _first_params = session.calls[0]
        self.assertIn("CONTAINS", first_query)
        second_query, second_params = session.calls[1]
        self.assertNotIn("__stub__", second_query)
        self.assertEqual(second_params["refs"], [{"source": "ASANA", "external_id": "123"}])
        self.assertEqual(n, 1)


class LinkPrChangesetsToIssueExternalsTest(unittest.TestCase):
    def test_no_row_skips_second_statement(self):
        session = _FakeSession(records=[None])
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            n = asyncio.run(link_pr_changesets_to_issue_externals("p1", 7))

        self.assertEqual(len(session.calls), 1)
        self.assertEqual(n, 0)

    def test_first_statement_requires_contains_changeset(self):
        # CONTAINS 커밋이 없는 PR(정상 흐름의 PR 선도착 순간)은 ①에서 걸러져 엣지 없는
        # pre-node를 만들지 않는다 — 커밋 도착 시 단건 전파가 생성과 연결을 함께 수행한다.
        session = _FakeSession(records=[None])
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            n = asyncio.run(link_pr_changesets_to_issue_externals("p1", 7))

        first_query, _params = session.calls[0]
        self.assertIn("EXISTS { (pr)-[:CONTAINS]->(:ChangeSet) }", first_query)
        self.assertEqual(len(session.calls), 1)
        self.assertEqual(n, 0)

    def test_parses_refs_and_propagates_to_all_contains(self):
        session = _FakeSession(records=[
            {"raw_refs": ["ASANA:123"]},
            {"created": 4},
        ])
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            n = asyncio.run(link_pr_changesets_to_issue_externals("p1", 7))

        self.assertEqual(len(session.calls), 2)
        second_query, second_params = session.calls[1]
        self.assertNotIn("__stub__", second_query)
        self.assertIn("OPTIONAL MATCH", second_query)
        self.assertEqual(second_params["refs"], [{"source": "ASANA", "external_id": "123"}])
        self.assertEqual(n, 4)


class UpsertPullRequestIssueExternalIdsTest(unittest.TestCase):
    def _upsert(self, session, issue_external_ids=None):
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(upsert_pull_request(
                project_id="p1", pr_number=7, title="t", body="b", state="merged",
                base_branch="main", url="https://x", occurred_at=None, created_at=None,
                source="GITHUB", actor_uuid="author-uuid", issue_external_ids=issue_external_ids,
            ))

    def test_none_preserves_existing_value_via_case(self):
        session = _FakeSession()
        self._upsert(session, issue_external_ids=None)

        query, params = session.calls[0]
        self.assertIn(
            "pr.issue_external_ids = CASE WHEN $issue_external_ids IS NOT NULL "
            "THEN $issue_external_ids ELSE pr.issue_external_ids END",
            query,
        )
        self.assertIsNone(params["issue_external_ids"])

    def test_explicit_value_is_set(self):
        session = _FakeSession()
        self._upsert(session, issue_external_ids=["ASANA:123"])

        _query, params = session.calls[0]
        self.assertEqual(params["issue_external_ids"], ["ASANA:123"])


def _changeset_event(refs=None):
    return {
        "nodeType": "ChangeSet", "source": "GITHUB", "projectId": "p1",
        "occurredAt": "2026-07-01T00:00:00Z",
        "actor": {"id": "author1", "name": "Author One", "email": "author1@example.com"},
        "properties": {"hash": "abc123", "message": "fix: 버그 수정"},
        "refs": refs or {},
    }


class HandleChangesetIssueExternalRefsTest(unittest.TestCase):
    def _run(self, event):
        link_external_mock = AsyncMock()
        link_pr_external_mock = AsyncMock()
        with patch("graph.event_handler.builder.upsert_changeset", AsyncMock()), \
             patch("graph.event_handler.builder.link_pr_to_changeset", AsyncMock()), \
             patch("graph.event_handler.builder.link_changeset_to_pr_issues", AsyncMock()), \
             patch("graph.event_handler.builder.link_changeset_to_issue_external", link_external_mock), \
             patch("graph.event_handler.builder.link_changeset_to_pr_issue_externals", link_pr_external_mock), \
             patch("graph.event_handler.builder.link_changeset_to_document", AsyncMock()), \
             patch("graph.event_handler.builder.link_changeset_to_pr_documents", AsyncMock()), \
             patch("graph.event_handler.resolve_actor", AsyncMock(return_value={"uuid": "author-uuid"})), \
             patch("graph.event_handler.make_neo4j_actor_store", return_value="STORE"), \
             patch("graph.event_handler.embed_text_batched", AsyncMock(return_value=[])):
            asyncio.run(handle(event))
        return link_external_mock, link_pr_external_mock

    def test_called_when_issue_external_refs_present(self):
        event = _changeset_event(refs={
            "issueExternalRefs": [{"source": "ASANA", "externalId": "123"}],
        })
        link_external_mock, _link_pr_external_mock = self._run(event)
        link_external_mock.assert_awaited_once_with("p1", "abc123", "ASANA", "123")

    def test_not_called_when_issue_external_refs_missing(self):
        link_external_mock, _link_pr_external_mock = self._run(_changeset_event(refs={}))
        link_external_mock.assert_not_awaited()

    def test_entry_missing_external_id_is_skipped(self):
        event = _changeset_event(refs={"issueExternalRefs": [{"source": "ASANA"}]})
        link_external_mock, _link_pr_external_mock = self._run(event)
        link_external_mock.assert_not_awaited()

    def test_pr_number_propagates_to_external_refs_too(self):
        _link_external_mock, link_pr_external_mock = self._run(_changeset_event(refs={"prNumber": "7"}))
        link_pr_external_mock.assert_awaited_once_with("p1", 7, "abc123")


def _pr_event(refs=None):
    return {
        "nodeType": "PullRequest", "source": "GITHUB", "projectId": "p1",
        "occurredAt": "2026-07-01T00:00:00Z",
        "actor": {"id": "author1", "name": "Author One", "email": "author1@example.com"},
        "properties": {
            "pr_number": 7, "title": "t", "body": "b", "state": "merged",
            "base_branch": "main", "url": "https://github.com/x/y/pull/7",
            "created_at": "2026-06-30T00:00:00Z",
        },
        "refs": refs or {},
    }


class HandlePullRequestIssueExternalRefsTest(unittest.TestCase):
    def _run(self, event, propagate_return=0):
        upsert_pr_mock = AsyncMock()
        propagate_mock = AsyncMock(return_value=propagate_return)
        with patch("graph.event_handler.builder.upsert_pull_request", upsert_pr_mock), \
             patch("graph.event_handler.builder.link_pr_changesets_to_issues", AsyncMock(return_value=0)), \
             patch("graph.event_handler.builder.link_pr_changesets_to_issue_externals", propagate_mock), \
             patch("graph.event_handler.resolve_actor", AsyncMock(return_value={"uuid": "author-uuid"})), \
             patch("graph.event_handler.make_neo4j_actor_store", return_value="STORE"):
            asyncio.run(handle(event))
        return upsert_pr_mock, propagate_mock

    def test_encodes_valid_refs_and_propagates(self):
        event = _pr_event(refs={
            "issueExternalRefs": [
                {"source": "ASANA", "externalId": "123"},
                {"source": "ASANA"},  # externalId 없음 — 건너뜀
            ],
        })
        upsert_pr_mock, propagate_mock = self._run(event, propagate_return=2)

        self.assertEqual(upsert_pr_mock.await_args.kwargs["issue_external_ids"], ["ASANA:123"])
        propagate_mock.assert_awaited_once_with("p1", 7)

    def test_absent_key_passes_none_to_preserve_existing(self):
        upsert_pr_mock, propagate_mock = self._run(_pr_event(refs={}))

        self.assertIsNone(upsert_pr_mock.await_args.kwargs["issue_external_ids"])
        propagate_mock.assert_not_awaited()


def _comm_event(refs=None):
    return {
        "nodeType": "Communication", "source": "SLACK", "projectId": "p1",
        "occurredAt": "2026-07-01T00:00:00Z",
        "actor": {"id": "author1", "name": "Author One", "email": "author1@example.com"},
        "properties": {
            "body": "이 작업 관련 링크입니다", "url": "https://slack.com/archives/C1/p1",
            "channel": "general", "conversation_id": "C1",
        },
        "refs": refs or {},
    }


class HandleCommunicationIssueExternalRefsTest(unittest.TestCase):
    def _run(self, event):
        link_external_mock = AsyncMock()
        with patch("graph.event_handler.builder.upsert_communication", AsyncMock()), \
             patch("graph.event_handler.builder.link_issue_to_communication", AsyncMock()), \
             patch("graph.event_handler.builder.link_issue_external_to_communication", link_external_mock), \
             patch("graph.event_handler.resolve_actor", AsyncMock(return_value={"uuid": "author-uuid"})), \
             patch("graph.event_handler.make_neo4j_actor_store", return_value="STORE"), \
             patch("graph.event_handler.embed_text", AsyncMock(return_value=[])), \
             patch("graph.event_handler.should_skip_slack", return_value=False):
            asyncio.run(handle(event))
        return link_external_mock

    def test_called_when_issue_external_refs_present(self):
        event = _comm_event(refs={"issueExternalRefs": [{"source": "ASANA", "externalId": "123"}]})
        link_external_mock = self._run(event)
        link_external_mock.assert_awaited_once_with(
            "p1", "ASANA", "123", "https://slack.com/archives/C1/p1",
        )

    def test_not_called_when_issue_external_refs_missing(self):
        link_external_mock = self._run(_comm_event(refs={}))
        link_external_mock.assert_not_awaited()


if __name__ == "__main__":
    unittest.main()
