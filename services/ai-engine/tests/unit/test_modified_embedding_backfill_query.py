"""MODIFIED 엣지 임베딩 재생성 대상 쿼리 단위 테스트 (오프라인).

embed_batch 실패는 NULL이 아니라 빈 목록으로 저장되므로, 재생성이 NULL만 찾으면
빈 목록 엣지가 영원히 보정되지 않는다. force=True는 종전처럼 임베딩 조건이 없어야 한다.
"""

import asyncio
import unittest
from unittest.mock import patch

from graph.reference_store import _fetch_unembedded_modified_edges


class _FakeResult:
    async def data(self):
        return []


class _FakeSession:
    def __init__(self):
        self.calls = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return None

    async def run(self, query, **params):
        self.calls.append((query, params))
        return _FakeResult()


class _FakeDriver:
    def __init__(self, session):
        self._session = session

    def session(self):
        return self._session


def _query(force: bool) -> str:
    session = _FakeSession()
    with patch("graph.reference_store.get_driver", return_value=_FakeDriver(session)):
        asyncio.run(_fetch_unembedded_modified_edges("p1", force))
    return session.calls[0][0]


class UnembeddedModifiedEdgesQueryTest(unittest.TestCase):
    def test_non_force_also_targets_empty_embedding(self):
        query = _query(force=False)
        self.assertIn("(r.embedding IS NULL OR size(r.embedding) = 0)", query)

    def test_force_has_no_embedding_filter(self):
        query = _query(force=True)
        self.assertNotIn("r.embedding", query)
