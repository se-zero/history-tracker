"""ChangeSet 프리페치 준비/쓰기 분리(prepare_changeset / handle prepared) 단위 테스트 (오프라인).

graph.event_handler에 직접 patch해 Neo4j·OpenAI 없이 검증한다(tests/unit/test_issue_external_refs.py 관행).
prepare_changeset은 순수 함수(Neo4j 무관)이므로 builder 계열 mock 없이 그대로 asyncio.run으로 호출한다.
"""

import asyncio
import unittest
from unittest.mock import AsyncMock, patch

from graph.event_handler import (
    ChangesetPrepared,
    handle,
    is_prefetchable_changeset,
    prepare_changeset,
)


def _changeset_event(files=None, message="fix: 버그 수정", actor=None, project_id="p1", source="GITHUB"):
    return {
        "nodeType": "ChangeSet", "source": source, "projectId": project_id,
        "occurredAt": "2026-07-01T00:00:00Z",
        "actor": actor or {"id": "author1", "name": "Author One", "email": "author1@example.com"},
        "properties": {"hash": "abc123", "message": message, "files": files or []},
        "refs": {},
    }


# --- prepare_changeset --------------------------------------------------------

class PrepareChangesetTest(unittest.TestCase):
    def test_builds_message_embedding_and_file_rows(self):
        event = _changeset_event(files=[
            {"path": "a.py", "diff": "diff-a", "additions": 1, "deletions": 0},
            {"path": "b.py", "diff": "diff-b", "additions": 2, "deletions": 1},
        ])
        summarize_mock = AsyncMock(side_effect=["summary-a", "summary-b"])
        with patch("graph.event_handler.embed_text_batched", AsyncMock(return_value=[0.1, 0.2])), \
             patch("graph.event_handler.summarize_diff", summarize_mock), \
             patch("graph.event_handler.embed_batch", AsyncMock(return_value=[[1.0], [2.0]])):
            result = asyncio.run(prepare_changeset(event))

        self.assertEqual(result.message_embedding, [0.1, 0.2])
        self.assertEqual(result.file_rows, [
            {"file_path": "a.py", "diff_summary": "summary-a", "embedding": [1.0]},
            {"file_path": "b.py", "diff_summary": "summary-b", "embedding": [2.0]},
        ])

    def test_file_summary_failure_is_skipped_per_file(self):
        event = _changeset_event(files=[
            {"path": "a.py", "diff": "diff-a"},
            {"path": "b.py", "diff": "diff-b"},
        ])

        async def summarize_side_effect(path, diff, additions, deletions, message):
            if path == "a.py":
                raise RuntimeError("llm down")
            return "summary-b"

        with patch("graph.event_handler.embed_text_batched", AsyncMock(return_value=[])), \
             patch("graph.event_handler.summarize_diff", AsyncMock(side_effect=summarize_side_effect)), \
             patch("graph.event_handler.embed_batch", AsyncMock(return_value=[[9.0]])):
            result = asyncio.run(prepare_changeset(event))

        self.assertEqual(result.file_rows, [
            {"file_path": "b.py", "diff_summary": "summary-b", "embedding": [9.0]},
        ])

    def test_no_files_returns_empty_file_rows_without_embed_batch_call(self):
        event = _changeset_event(files=[])
        with patch("graph.event_handler.embed_text_batched", AsyncMock(return_value=[0.5])), \
             patch("graph.event_handler.summarize_diff", AsyncMock()) as summarize_mock, \
             patch("graph.event_handler.embed_batch", AsyncMock()) as embed_batch_mock:
            result = asyncio.run(prepare_changeset(event))

        self.assertEqual(result.message_embedding, [0.5])
        self.assertEqual(result.file_rows, [])
        summarize_mock.assert_not_awaited()
        embed_batch_mock.assert_not_awaited()

    def test_embed_batch_failure_does_not_raise_and_fills_empty_embeddings(self):
        event = _changeset_event(files=[{"path": "a.py", "diff": "diff-a"}])
        with patch("graph.event_handler.embed_text_batched", AsyncMock(return_value=[])), \
             patch("graph.event_handler.summarize_diff", AsyncMock(return_value="summary-a")), \
             patch("graph.event_handler.embed_batch", AsyncMock(side_effect=RuntimeError("boom"))):
            result = asyncio.run(prepare_changeset(event))

        self.assertEqual(result.file_rows, [
            {"file_path": "a.py", "diff_summary": "summary-a", "embedding": []},
        ])


# --- handle(prepared=...) 배선 ------------------------------------------------

class HandleChangesetPreparedTest(unittest.TestCase):
    def test_prepared_value_used_without_recomputation(self):
        event = _changeset_event(files=[{"path": "a.py", "diff": "diff-a"}])
        prepared = ChangesetPrepared(
            message_embedding=[0.9],
            file_rows=[{"file_path": "a.py", "diff_summary": "s", "embedding": [1.0]}],
        )
        upsert_changeset_mock = AsyncMock()
        upsert_files_mock = AsyncMock()
        summarize_mock = AsyncMock()
        embed_text_batched_mock = AsyncMock()

        with patch("graph.event_handler.builder.upsert_changeset", upsert_changeset_mock), \
             patch("graph.event_handler.builder.upsert_files_with_modified_edges", upsert_files_mock), \
             patch("graph.event_handler.resolve_actor", AsyncMock(return_value={"uuid": "author-uuid"})), \
             patch("graph.event_handler.make_neo4j_actor_store", return_value="STORE"), \
             patch("graph.event_handler.summarize_diff", summarize_mock), \
             patch("graph.event_handler.embed_text_batched", embed_text_batched_mock):
            asyncio.run(handle(event, prepared=prepared))

        summarize_mock.assert_not_awaited()
        embed_text_batched_mock.assert_not_awaited()
        upsert_changeset_mock.assert_awaited_once()
        self.assertEqual(upsert_changeset_mock.await_args.kwargs["embedding"], [0.9])
        upsert_files_mock.assert_awaited_once()
        self.assertEqual(upsert_files_mock.await_args.kwargs["files"], prepared.file_rows)

    def test_handle_without_prepared_computes_inline(self):
        """admin의 /test/ingest 등 prepared를 모르는 기존 호출부(handle(event) 단독)와의 호환성."""
        event = _changeset_event(files=[{"path": "a.py", "diff": "diff-a"}])
        upsert_changeset_mock = AsyncMock()
        upsert_files_mock = AsyncMock()

        with patch("graph.event_handler.builder.upsert_changeset", upsert_changeset_mock), \
             patch("graph.event_handler.builder.upsert_files_with_modified_edges", upsert_files_mock), \
             patch("graph.event_handler.resolve_actor", AsyncMock(return_value={"uuid": "author-uuid"})), \
             patch("graph.event_handler.make_neo4j_actor_store", return_value="STORE"), \
             patch("graph.event_handler.summarize_diff", AsyncMock(return_value="summary-a")), \
             patch("graph.event_handler.embed_text_batched", AsyncMock(return_value=[0.7])), \
             patch("graph.event_handler.embed_batch", AsyncMock(return_value=[[1.0]])):
            asyncio.run(handle(event))

        upsert_changeset_mock.assert_awaited_once()
        self.assertEqual(upsert_changeset_mock.await_args.kwargs["embedding"], [0.7])
        upsert_files_mock.assert_awaited_once()
        self.assertEqual(upsert_files_mock.await_args.kwargs["files"], [
            {"file_path": "a.py", "diff_summary": "summary-a", "embedding": [1.0]},
        ])


# --- is_prefetchable_changeset -------------------------------------------------

class IsPrefetchableChangesetTest(unittest.TestCase):
    def test_true_for_normal_changeset(self):
        self.assertTrue(is_prefetchable_changeset(_changeset_event()))

    def test_false_for_non_changeset_node_type(self):
        event = _changeset_event()
        event["nodeType"] = "PullRequest"
        self.assertFalse(is_prefetchable_changeset(event))

    def test_false_without_project_id(self):
        event = _changeset_event(project_id="")
        self.assertFalse(is_prefetchable_changeset(event))

    def test_false_for_bot_actor(self):
        event = _changeset_event(actor={"id": "dependabot[bot]"})
        self.assertFalse(is_prefetchable_changeset(event))


if __name__ == "__main__":
    unittest.main()
