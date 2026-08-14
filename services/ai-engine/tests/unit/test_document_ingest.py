"""Document 이벤트 소비와 Neo4j 쓰기 경로 단위 테스트 (오프라인)."""

import asyncio
import unittest
from unittest.mock import AsyncMock, patch

from graph.document_chunker import DocumentSection
from graph.event_handler import handle
from graph.writes import (
    link_changeset_to_document,
    link_changeset_to_pr_documents,
    link_document_to_communication,
    link_issue_to_document,
    link_pr_changesets_to_documents,
    replace_document_sections,
    set_document_editors,
    upsert_document,
    upsert_pull_request,
)


class _FakeResult:
    def __init__(self, record=None):
        self._record = record

    async def single(self):
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
        return _FakeResult(self._records.pop(0) if self._records else None)


class _FakeDriver:
    def __init__(self, session):
        self._session = session

    def session(self):
        return self._session


def _document_event(external_id="page-1"):
    return {
        "nodeType": "Document",
        "source": "NOTION",
        "projectId": "project-1",
        "occurredAt": "2026-08-14T09:00:00Z",
        "actor": {"id": "creator", "name": "Creator", "email": "creator@example.com"},
        "properties": {
            "external_id": external_id,
            "title": "인증 설계",
            "body": "# 토큰\n갱신 정책",
            "url": "https://www.notion.so/page-1",
            "created_at": "2026-08-01T09:00:00Z",
            "parent_type": "page_id",
            "parent_external_id": "parent-1",
        },
        "refs": {
            "editors": [{"id": "editor", "name": "Editor", "email": "editor@example.com"}],
            "issueKeys": ["HT-1", "HT-1"],
            "issueExternalRefs": [{"source": "ASANA", "externalId": "task-1"}],
        },
    }


class HandleDocumentTest(unittest.TestCase):
    def test_consumes_document_sections_and_all_layer_two_refs(self):
        upsert = AsyncMock()
        replace = AsyncMock()
        editors = AsyncMock()
        parent = AsyncMock()
        issue_link = AsyncMock()
        external_issue_link = AsyncMock()
        with patch("graph.event_handler.builder.upsert_document", upsert), \
             patch("graph.event_handler.builder.replace_document_sections", replace), \
             patch("graph.event_handler.builder.set_document_editors", editors), \
             patch("graph.event_handler.builder.link_document_to_parent", parent), \
             patch("graph.event_handler.builder.link_issue_to_document", issue_link), \
             patch("graph.event_handler.builder.link_issue_external_to_document", external_issue_link), \
             patch("graph.event_handler.resolve_actor", AsyncMock(side_effect=[{"uuid": "creator-uuid"}, {"uuid": "editor-uuid"}])), \
             patch("graph.event_handler.make_neo4j_actor_store", return_value="STORE"), \
             patch("graph.event_handler.chunk_document", return_value=[DocumentSection("토큰", "갱신 정책")]), \
             patch("graph.event_handler.embed_batch", AsyncMock(return_value=[[0.1, 0.2]])):
            asyncio.run(handle(_document_event()))

        self.assertEqual(upsert.await_args.kwargs["external_id"], "page-1")
        self.assertEqual(upsert.await_args.kwargs["actor_uuid"], "creator-uuid")
        self.assertEqual(
            replace.await_args.kwargs["sections"],
            [{"ordinal": 0, "heading_path": "토큰", "text": "갱신 정책", "embedding": [0.1, 0.2]}],
        )
        editors.assert_awaited_once_with("project-1", "NOTION", "page-1", ["editor-uuid"])
        parent.assert_awaited_once_with("project-1", "NOTION", "page-1", "parent-1")
        issue_link.assert_awaited_once_with("project-1", "HT-1", "NOTION", "page-1")
        external_issue_link.assert_awaited_once_with("project-1", "ASANA", "task-1", "NOTION", "page-1")

    def test_missing_external_id_drops_event_before_any_write(self):
        upsert = AsyncMock()
        with patch("graph.event_handler.builder.upsert_document", upsert), \
             patch("graph.event_handler.resolve_actor", AsyncMock()) as resolve:
            asyncio.run(handle(_document_event(external_id=None)))

        resolve.assert_not_awaited()
        upsert.assert_not_awaited()


class DocumentWritesTest(unittest.TestCase):
    def test_upsert_uses_immutable_document_key_and_wrote(self):
        session = _FakeSession()
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(upsert_document(
                project_id="p1", source="NOTION", external_id="page-1", title="제목", body="본문",
                url="https://notion.so/page-1", occurred_at="2026-08-14T00:00:00Z",
                created_at="2026-08-01T00:00:00Z", parent_type="page_id", parent_external_id="parent-1",
                actor_uuid="actor-1",
            ))

        query, params = session.calls[0]
        self.assertIn("MERGE (d:Document {project_id: $project_id, source: $source, external_id: $external_id})", query)
        self.assertIn("MERGE (a)-[:WROTE]->(d)", query)
        self.assertEqual(params["external_id"], "page-1")

    def test_replace_deletes_old_sections_then_creates_new_part_of_edges(self):
        session = _FakeSession()
        rows = [{"ordinal": 0, "heading_path": "인증", "text": "본문", "embedding": [0.1]}]
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(replace_document_sections(
                project_id="p1", source="NOTION", document_external_id="page-1", sections=rows,
            ))

        self.assertEqual(len(session.calls), 2)
        self.assertIn("DETACH DELETE s", session.calls[0][0])
        self.assertIn("MERGE (s)-[:PART_OF]->(d)", session.calls[1][0])
        self.assertEqual(session.calls[1][1]["sections"], rows)

    def test_empty_replacement_only_deletes_existing_sections(self):
        session = _FakeSession()
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(replace_document_sections(
                project_id="p1", source="NOTION", document_external_id="page-1", sections=[],
            ))

        self.assertEqual(len(session.calls), 1)
        self.assertIn("DETACH DELETE s", session.calls[0][0])

    def test_text_described_in_overwrites_semantic_metadata(self):
        session = _FakeSession(records=[{"matched": 1}])
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(link_issue_to_document("p1", "HT-1", "NOTION", "page-1"))

        self.assertEqual(len(session.calls), 1)
        query, params = session.calls[0]
        self.assertIn("SET r.source = 'text', r.confidence = 1.0", query)
        self.assertIn("REMOVE r.section", query)
        self.assertEqual(params["issue_key"], "HT-1")

    def test_editors_are_cumulative_without_delete(self):
        session = _FakeSession()
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(set_document_editors("p1", "NOTION", "page-1", ["editor-1", "editor-1"]))

        query, params = session.calls[0]
        self.assertIn("MERGE (a)-[:EDITED]->(d)", query)
        self.assertNotIn("DELETE", query)
        self.assertEqual(params["actor_uuids"], ["editor-1"])


class DocumentTextReferenceTest(unittest.TestCase):
    """documentExternalRefs 기반 text REFERENCE/DISCUSSED_IN — REFERENCE의 첫 text 작성자(§2-7)."""

    def test_changeset_reference_is_marked_text_with_confidence_one(self):
        session = _FakeSession()
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(link_changeset_to_document("p1", "abc123", "NOTION", "page-1"))

        self.assertEqual(len(session.calls), 1)
        query, params = session.calls[0]
        self.assertIn("MERGE (c)-[r:REFERENCE]->(d)", query)
        self.assertIn("SET r.source = 'text', r.confidence = 1.0", query)
        self.assertEqual(params["hash"], "abc123")
        self.assertEqual(params["document_external_id"], "page-1")

    def test_document_discussed_in_communication_removes_confidence(self):
        session = _FakeSession()
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(link_document_to_communication("p1", "NOTION", "page-1", "https://slack/x"))

        self.assertEqual(len(session.calls), 1)
        query, params = session.calls[0]
        self.assertIn("MERGE (d)-[r:DISCUSSED_IN]->(comm)", query)
        self.assertIn("REMOVE r.confidence", query)
        self.assertEqual(params["comm_url"], "https://slack/x")


class PrDocumentPropagationTest(unittest.TestCase):
    """link_pr_changesets_to_documents / link_changeset_to_pr_documents —
    link_pr_changesets_to_issue_externals와 완전히 대칭인 전파 경로."""

    def test_single_commit_propagation_requires_contains(self):
        session = _FakeSession(records=[None])
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            n = asyncio.run(link_changeset_to_pr_documents("p1", 7, "abc"))

        self.assertEqual(len(session.calls), 1)
        self.assertEqual(n, 0)

    def test_single_commit_propagation_links_document(self):
        session = _FakeSession(records=[
            {"raw_refs": ["NOTION:page-1"]},
            {"created": 1},
        ])
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            n = asyncio.run(link_changeset_to_pr_documents("p1", 7, "abc"))

        self.assertEqual(len(session.calls), 2)
        second_query, second_params = session.calls[1]
        self.assertIn("MERGE (c)-[r:REFERENCE]->(d)", second_query)
        self.assertEqual(second_params["refs"], [{"source": "NOTION", "external_id": "page-1"}])
        self.assertEqual(n, 1)

    def test_full_propagation_requires_contains_changeset(self):
        session = _FakeSession(records=[None])
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            n = asyncio.run(link_pr_changesets_to_documents("p1", 7))

        first_query, _params = session.calls[0]
        self.assertIn("EXISTS { (pr)-[:CONTAINS]->(:ChangeSet) }", first_query)
        self.assertEqual(len(session.calls), 1)
        self.assertEqual(n, 0)

    def test_full_propagation_links_all_contains_changesets(self):
        session = _FakeSession(records=[
            {"raw_refs": ["NOTION:page-1"]},
            {"created": 3},
        ])
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            n = asyncio.run(link_pr_changesets_to_documents("p1", 7))

        self.assertEqual(len(session.calls), 2)
        second_query, second_params = session.calls[1]
        self.assertIn("FOREACH (c IN changesets", second_query)
        self.assertEqual(second_params["refs"], [{"source": "NOTION", "external_id": "page-1"}])
        self.assertEqual(n, 3)


class UpsertPullRequestDocumentExternalIdsTest(unittest.TestCase):
    def test_none_preserves_existing_value_via_case(self):
        session = _FakeSession()
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(upsert_pull_request(
                project_id="p1", pr_number=7, title="t", body="b", state="merged",
                base_branch="main", url="https://x", occurred_at=None, created_at=None,
                source="GITHUB", actor_uuid="author-uuid", document_external_ids=None,
            ))

        query, params = session.calls[0]
        self.assertIn(
            "pr.document_external_ids = CASE WHEN $document_external_ids IS NOT NULL "
            "THEN $document_external_ids ELSE pr.document_external_ids END",
            query,
        )
        self.assertIsNone(params["document_external_ids"])

    def test_explicit_value_is_set(self):
        session = _FakeSession()
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(upsert_pull_request(
                project_id="p1", pr_number=7, title="t", body="b", state="merged",
                base_branch="main", url="https://x", occurred_at=None, created_at=None,
                source="GITHUB", actor_uuid="author-uuid", document_external_ids=["NOTION:page-1"],
            ))

        _query, params = session.calls[0]
        self.assertEqual(params["document_external_ids"], ["NOTION:page-1"])


def _changeset_event_with_refs(refs):
    return {
        "nodeType": "ChangeSet", "source": "GITHUB", "projectId": "p1",
        "occurredAt": "2026-07-01T00:00:00Z",
        "actor": {"id": "author1", "name": "Author One", "email": "author1@example.com"},
        "properties": {"hash": "abc123", "message": "fix: 버그 수정"},
        "refs": refs,
    }


class HandleChangesetDocumentExternalRefsTest(unittest.TestCase):
    def _run(self, event):
        link_document_mock = AsyncMock()
        with patch("graph.event_handler.builder.upsert_changeset", AsyncMock()), \
             patch("graph.event_handler.builder.link_changeset_to_document", link_document_mock), \
             patch("graph.event_handler.resolve_actor", AsyncMock(return_value={"uuid": "author-uuid"})), \
             patch("graph.event_handler.make_neo4j_actor_store", return_value="STORE"), \
             patch("graph.event_handler.embed_text", AsyncMock(return_value=[])):
            asyncio.run(handle(event))
        return link_document_mock

    def test_called_when_document_external_refs_present(self):
        event = _changeset_event_with_refs({
            "documentExternalRefs": [{"source": "NOTION", "externalId": "page-1"}],
        })
        link_document_mock = self._run(event)
        link_document_mock.assert_awaited_once_with("p1", "abc123", "NOTION", "page-1")

    def test_not_called_when_document_external_refs_missing(self):
        link_document_mock = self._run(_changeset_event_with_refs({}))
        link_document_mock.assert_not_awaited()


def _pr_event_with_refs(refs):
    return {
        "nodeType": "PullRequest", "source": "GITHUB", "projectId": "p1",
        "occurredAt": "2026-07-01T00:00:00Z",
        "actor": {"id": "author1", "name": "Author One", "email": "author1@example.com"},
        "properties": {
            "pr_number": 7, "title": "t", "body": "본문에 https://www.notion.so/page-1 링크",
            "state": "merged", "base_branch": "main", "url": "https://github.com/x/y/pull/7",
            "created_at": "2026-06-30T00:00:00Z",
        },
        "refs": refs,
    }


class HandlePullRequestDocumentExternalRefsTest(unittest.TestCase):
    def _run(self, event, propagate_return=0):
        upsert_pr_mock = AsyncMock()
        propagate_mock = AsyncMock(return_value=propagate_return)
        with patch("graph.event_handler.builder.upsert_pull_request", upsert_pr_mock), \
             patch("graph.event_handler.builder.link_pr_changesets_to_issues", AsyncMock(return_value=0)), \
             patch("graph.event_handler.builder.link_pr_changesets_to_documents", propagate_mock), \
             patch("graph.event_handler.resolve_actor", AsyncMock(return_value={"uuid": "author-uuid"})), \
             patch("graph.event_handler.make_neo4j_actor_store", return_value="STORE"):
            asyncio.run(handle(event))
        return upsert_pr_mock, propagate_mock

    def test_encodes_valid_refs_and_propagates(self):
        event = _pr_event_with_refs({
            "documentExternalRefs": [
                {"source": "NOTION", "externalId": "page-1"},
                {"source": "NOTION"},  # externalId 없음 — 건너뜀
            ],
        })
        upsert_pr_mock, propagate_mock = self._run(event, propagate_return=2)

        self.assertEqual(upsert_pr_mock.await_args.kwargs["document_external_ids"], ["NOTION:page-1"])
        propagate_mock.assert_awaited_once_with("p1", 7)

    def test_absent_key_passes_none_to_preserve_existing(self):
        upsert_pr_mock, propagate_mock = self._run(_pr_event_with_refs({}))

        self.assertIsNone(upsert_pr_mock.await_args.kwargs["document_external_ids"])
        propagate_mock.assert_not_awaited()


def _comm_event_with_refs(refs):
    return {
        "nodeType": "Communication", "source": "SLACK", "projectId": "p1",
        "occurredAt": "2026-07-01T00:00:00Z",
        "actor": {"id": "author1", "name": "Author One", "email": "author1@example.com"},
        "properties": {
            "body": "이 문서를 참고하세요", "url": "https://slack.com/archives/C1/p1",
            "channel": "general", "conversation_id": "C1",
        },
        "refs": refs,
    }


class HandleCommunicationDocumentExternalRefsTest(unittest.TestCase):
    def _run(self, event):
        link_document_mock = AsyncMock()
        with patch("graph.event_handler.builder.upsert_communication", AsyncMock()), \
             patch("graph.event_handler.builder.link_document_to_communication", link_document_mock), \
             patch("graph.event_handler.resolve_actor", AsyncMock(return_value={"uuid": "author-uuid"})), \
             patch("graph.event_handler.make_neo4j_actor_store", return_value="STORE"), \
             patch("graph.event_handler.embed_text", AsyncMock(return_value=[])), \
             patch("graph.event_handler.should_skip_slack", return_value=False):
            asyncio.run(handle(event))
        return link_document_mock

    def test_called_when_document_external_refs_present(self):
        event = _comm_event_with_refs({
            "documentExternalRefs": [{"source": "NOTION", "externalId": "page-1"}],
        })
        link_document_mock = self._run(event)
        link_document_mock.assert_awaited_once_with(
            "p1", "NOTION", "page-1", "https://slack.com/archives/C1/p1",
        )

    def test_not_called_when_document_external_refs_missing(self):
        link_document_mock = self._run(_comm_event_with_refs({}))
        link_document_mock.assert_not_awaited()


if __name__ == "__main__":
    unittest.main()
