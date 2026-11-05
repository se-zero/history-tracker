"""임베딩 모델·차원 설정의 배선 테스트 (오프라인).

모델·차원은 env로 바꿀 수 있고, 그 값이 (1) 게이트웨이 호출 인자, (2) Neo4j 벡터 인덱스 DDL,
(3) /query/config 응답, (4) 기동 시 인덱스 차원 가드 네 곳에 같은 값으로 흘러야 한다.
어느 한 곳만 어긋나도 인덱스가 벡터를 거부하거나 신·구 벡터가 조용히 섞이기 때문이다.
"""

import asyncio
import contextlib
import importlib
import json
import os
import unittest
from types import SimpleNamespace
from unittest import mock

import main
from graph import embedder
from graph import schema
from routers import query as query_router

_ENV_NAMES = ("EMBEDDING_MODEL", "EMBEDDING_DIMENSIONS")


@contextlib.contextmanager
def _embedder_with_env(**env):
    """env를 준 채 embedder를 reload한다. 빠져나올 때 env를 되돌린 뒤 다시 reload해 상수가 새지 않게 한다."""
    try:
        with mock.patch.dict(os.environ):
            for name in _ENV_NAMES:
                os.environ.pop(name, None)
            os.environ.update(env)
            yield importlib.reload(embedder)
    finally:
        importlib.reload(embedder)


def _gateway_response():
    return SimpleNamespace(data=[SimpleNamespace(embedding=[0.1, 0.2])])


class EmbedderConstantsTest(unittest.TestCase):
    def test_defaults_when_env_is_unset(self):
        with _embedder_with_env() as module:
            self.assertEqual(module.EMBEDDING_MODEL, "text-embedding-3-large")
            self.assertEqual(module.EMBEDDING_DIMENSIONS, 1536)
            self.assertEqual(module._MODEL, "text-embedding-3-large")
            self.assertEqual(module._DIMENSIONS, 1536)

    def test_blank_env_is_treated_as_unset(self):
        # compose가 빈 값(${VAR:-})을 넘기므로 빈 문자열·공백은 기본값이다
        for blank in ("", "   "):
            with self.subTest(blank=blank), _embedder_with_env(
                EMBEDDING_MODEL=blank, EMBEDDING_DIMENSIONS=blank
            ) as module:
                self.assertEqual(module.EMBEDDING_MODEL, "text-embedding-3-large")
                self.assertEqual(module.EMBEDDING_DIMENSIONS, 1536)

    def test_env_overrides_model_and_dimensions(self):
        with _embedder_with_env(EMBEDDING_MODEL=" qwen3-embedding-0.6b ", EMBEDDING_DIMENSIONS="1024") as module:
            self.assertEqual(module.EMBEDDING_MODEL, "qwen3-embedding-0.6b")
            self.assertEqual(module.EMBEDDING_DIMENSIONS, 1024)
            self.assertEqual(module._MODEL, "qwen3-embedding-0.6b")
            self.assertEqual(module._DIMENSIONS, 1024)

    def test_env_values_reach_the_gateway_call(self):
        async def scenario(module):
            with mock.patch(
                "graph.embedder.embed", new=mock.AsyncMock(return_value=_gateway_response())
            ) as gateway:
                await module.embed_text("본문")
                single = gateway.await_args.kwargs
                await module.embed_batch(["a"])
                batch = gateway.await_args.kwargs
            return single, batch

        with _embedder_with_env(EMBEDDING_MODEL="qwen3-embedding-0.6b", EMBEDDING_DIMENSIONS="1024") as module:
            single, batch = asyncio.run(scenario(module))

        for kwargs in (single, batch):
            self.assertEqual(kwargs["model"], "qwen3-embedding-0.6b")
            self.assertEqual(kwargs["dimensions"], 1024)

    def test_invalid_dimensions_fail_at_import(self):
        # 조용히 기본값으로 돌아가면 인덱스와 어긋난 채로 도는 편이 더 위험하다
        for bad in ("abc", "1.5", "0", "-3"):
            with self.subTest(bad=bad):
                with self.assertRaises(ValueError) as caught:
                    with _embedder_with_env(EMBEDDING_DIMENSIONS=bad):
                        pass
                self.assertIn("EMBEDDING_DIMENSIONS", str(caught.exception))
                self.assertIn(bad, str(caught.exception))

    def test_constants_are_restored_after_failed_reload(self):
        with self.assertRaises(ValueError):
            with _embedder_with_env(EMBEDDING_DIMENSIONS="0"):
                pass

        self.assertEqual(embedder.EMBEDDING_DIMENSIONS, 1536)
        self.assertEqual(embedder._DIMENSIONS, 1536)


class _FakeResult:
    def __init__(self, rows):
        self._rows = rows

    async def data(self):
        return self._rows


class _FakeSession:
    """run()이 질의를 기록하고 rows를 data()로 돌려주는 가짜 세션."""

    def __init__(self, rows=None):
        self.queries = []
        self._rows = rows if rows is not None else []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return None

    async def run(self, query):
        self.queries.append(query)
        return _FakeResult(self._rows)


class _FakeDriver:
    def __init__(self, session):
        self._session = session

    def session(self):
        return self._session


def _index_rows(**dimensions):
    return [{"name": name, "dimensions": dim} for name, dim in dimensions.items()]


class VectorIndexDdlTest(unittest.TestCase):
    def _run_ddl(self):
        session = _FakeSession()
        with mock.patch("graph.schema.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(schema.ensure_vector_indexes())
        return session.queries

    def test_all_three_indexes_use_default_dimensions(self):
        queries = self._run_ddl()

        self.assertEqual(len(queries), 3)
        for query in queries:
            self.assertIn("`vector.dimensions`: 1536,", query)

    def test_all_three_indexes_follow_configured_dimensions(self):
        with mock.patch("graph.schema.EMBEDDING_DIMENSIONS", 1024):
            queries = self._run_ddl()

        self.assertEqual(len(queries), 3)
        for query in queries:
            self.assertIn("`vector.dimensions`: 1024,", query)
            self.assertNotIn("1536", query)


class VectorIndexDimensionGuardTest(unittest.TestCase):
    def _guard(self, rows, configured=1536):
        session = _FakeSession(rows)
        with mock.patch("graph.schema.get_driver", return_value=_FakeDriver(session)), \
             mock.patch("graph.schema.EMBEDDING_DIMENSIONS", configured):
            asyncio.run(schema.ensure_vector_index_dimensions())
        return session

    def test_passes_when_all_indexes_match(self):
        session = self._guard(
            _index_rows(comm_embedding=1536, issue_embedding=1536, doc_section_embedding=1536)
        )

        self.assertEqual(len(session.queries), 1)
        self.assertIn("SHOW VECTOR INDEXES", session.queries[0])
        self.assertIn("vector.dimensions", session.queries[0])

    def test_passes_when_configured_dimensions_match_non_default_indexes(self):
        self._guard(
            _index_rows(comm_embedding=1024, issue_embedding=1024, doc_section_embedding=1024),
            configured=1024,
        )

    def test_raises_when_any_index_differs(self):
        for mismatched in ("comm_embedding", "issue_embedding", "doc_section_embedding"):
            with self.subTest(mismatched=mismatched):
                rows = _index_rows(comm_embedding=1024, issue_embedding=1024, doc_section_embedding=1024)
                for row in rows:
                    if row["name"] == mismatched:
                        row["dimensions"] = 1536

                with self.assertRaises(RuntimeError) as caught:
                    self._guard(rows, configured=1024)

                self.assertIn(f"DROP INDEX {mismatched};", str(caught.exception))

    def test_raises_when_index_is_smaller_than_configured(self):
        # 설정을 1536으로 되돌리는 복구 단계가 이 방향이다 — 인덱스가 설정보다 작아도 막아야 한다
        rows = _index_rows(comm_embedding=1024, issue_embedding=1536, doc_section_embedding=1536)

        with self.assertRaises(RuntimeError) as caught:
            self._guard(rows, configured=1536)

        message = str(caught.exception)
        self.assertIn("DROP INDEX comm_embedding;", message)
        self.assertNotIn("DROP INDEX issue_embedding", message)
        self.assertIn("현재 1024", message)

    def test_message_lists_only_mismatched_indexes_with_dimensions_and_next_steps(self):
        rows = _index_rows(comm_embedding=1536, issue_embedding=1024, doc_section_embedding=1536)

        with self.assertRaises(RuntimeError) as caught:
            self._guard(rows, configured=1024)

        message = str(caught.exception)
        self.assertIn("DROP INDEX comm_embedding;", message)
        self.assertIn("DROP INDEX doc_section_embedding;", message)
        self.assertNotIn("DROP INDEX issue_embedding", message)  # 맞는 인덱스는 지우라고 안내하지 않는다
        self.assertIn("1536", message)  # 현재 차원
        self.assertIn("1024", message)  # 설정 차원
        self.assertIn("force=true", message)  # 지운 뒤 재임베딩 안내

    def test_skips_indexes_that_do_not_exist_yet(self):
        # 목록에 없는 인덱스(아직 만들어지지 않음)는 비교 대상이 아니다
        self._guard(_index_rows(comm_embedding=1536))
        self._guard([])

    def test_ignores_unrelated_vector_indexes(self):
        self._guard(
            _index_rows(
                comm_embedding=1536, issue_embedding=1536, doc_section_embedding=1536, someone_elses_index=384
            )
        )


class QueryConfigEmbeddingTest(unittest.TestCase):
    def test_config_reports_embedding_model_and_dimensions(self):
        body = asyncio.run(query_router.query_config())

        self.assertEqual(body["embedding_model"], embedder.EMBEDDING_MODEL)
        self.assertEqual(body["embedding_dimensions"], embedder.EMBEDDING_DIMENSIONS)

    def test_config_reads_the_embedder_constants_not_a_copy(self):
        with mock.patch("graph.embedder.EMBEDDING_MODEL", "qwen3-embedding-0.6b"), \
             mock.patch("graph.embedder.EMBEDDING_DIMENSIONS", 1024):
            body = asyncio.run(query_router.query_config())

        self.assertEqual(body["embedding_model"], "qwen3-embedding-0.6b")
        self.assertEqual(body["embedding_dimensions"], 1024)

    def test_config_does_not_expose_embedding_server_url(self):
        with mock.patch.dict(
            os.environ,
            {"EMBEDDING_BASE_URL": "http://secret-embedding-host:8100/v1", "EMBEDDING_API_KEY": "secret-key"},
        ):
            body = asyncio.run(query_router.query_config())

        serialized = json.dumps(body)
        self.assertNotIn("secret-embedding-host", serialized)
        self.assertNotIn("secret-key", serialized)


class LifespanVectorIndexGuardTest(unittest.TestCase):
    """기동 블록이 인덱스 생성 직후에 차원 가드를 부르고, 가드 실패가 기동을 막는지 본다."""

    def _patch_lifespan(self, stack, order, guard):
        async def noop(*_args, **_kwargs):
            return None

        def recorder(name):
            async def record():
                order.append(name)

            return record

        async def consumer():
            order.append("consumer")

        async def debounce():
            await asyncio.sleep(3600)

        async def no_wait(_seconds):
            return None

        stack.enter_context(mock.patch.object(main, "ensure_token_configured", lambda: None))
        stack.enter_context(mock.patch.object(main, "get_driver", lambda: None))
        for name in (
            "drop_legacy_issue_constraint", "ensure_constraints", "drop_node_search_index",
            "backfill_actor_aliases", "load_tombstones", "close_driver",
        ):
            stack.enter_context(mock.patch.object(main, name, noop))
        stack.enter_context(mock.patch.object(main, "ensure_vector_indexes", recorder("ensure_vector_indexes")))
        stack.enter_context(mock.patch.object(main, "ensure_vector_index_dimensions", guard))
        stack.enter_context(mock.patch.object(main, "start_consumer", consumer))
        stack.enter_context(mock.patch.object(main, "start_debounce_loop", debounce))
        stack.enter_context(mock.patch.object(main.asyncio, "sleep", no_wait))

    def test_guard_runs_right_after_index_creation(self):
        order = []

        async def guard():
            order.append("ensure_vector_index_dimensions")

        async def scenario():
            async with main.lifespan(main.app):
                pass

        with contextlib.ExitStack() as stack:
            self._patch_lifespan(stack, order, guard)
            asyncio.run(scenario())

        self.assertEqual(order[:2], ["ensure_vector_indexes", "ensure_vector_index_dimensions"])

    def test_guard_failure_retries_then_blocks_startup(self):
        order = []
        calls = []

        async def guard():
            calls.append(1)
            raise RuntimeError("벡터 인덱스 차원 불일치")

        async def scenario():
            async with main.lifespan(main.app):
                pass

        with contextlib.ExitStack() as stack:
            self._patch_lifespan(stack, order, guard)
            with self.assertRaises(RuntimeError):
                asyncio.run(scenario())

        self.assertEqual(len(calls), 30)  # 기존 초기화 블록의 재시도 횟수를 그대로 따른다
        self.assertNotIn("consumer", order)  # 불일치 상태로 수집이 시작되면 안 된다


if __name__ == "__main__":
    unittest.main()
