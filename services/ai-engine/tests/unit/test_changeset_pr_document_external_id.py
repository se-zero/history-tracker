"""get_changeset_context/get_pr_context의 documents 필드에 external_id가 실리는지 회귀 테스트.

두 도구 모두 커밋/PR에 REFERENCE로 연결된 Document를 {title, url, source, confidence}만
반환해, 발견한 문서를 get_document_context로 다시 조회할 식별자가 없었다(external_id 누락).
같은 파일의 get_conflict_context는 doc_contexts에 id(=external_id)를 이미 싣고 있어 도구마다
결과 형태가 달랐다 — 오프라인(fake session)으로 쿼리 문자열과 반환 값을 함께 검증한다.
"""

import asyncio
import unittest
from unittest.mock import patch

from tools.queries.changeset import get_changeset_context, get_pr_context


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


class GetChangesetContextDocumentExternalIdTest(unittest.TestCase):
    def test_query_selects_external_id_and_returns_it(self):
        row = {
            "hash": "abc123", "commit_message": "m", "occurredAt": None, "author": "A",
            "issues": [], "communications": [],
            "documents": [{"external_id": "page-1", "title": "설계 문서", "url": "u",
                            "source": "NOTION", "confidence": 1.0}],
            "pull_request": {"pr_number": None, "title": None, "url": None},
            "file_changes": [],
        }
        session = _FakeSession(records=[row])
        with patch("tools.queries.changeset.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_changeset_context("p1", "abc123"))

        query, _params = session.calls[0]
        self.assertIn("external_id: d.external_id", query)
        self.assertEqual(result["documents"][0]["external_id"], "page-1")


class GetPrContextDocumentExternalIdTest(unittest.TestCase):
    def test_query_selects_external_id_and_returns_it(self):
        row = {
            "pr_number": 18, "title": "t", "body": "b", "merged_at": None, "created_at": None,
            "url": "u", "author": "A", "changesets": [], "issues": [], "discussions": [],
            "documents": [{"external_id": "page-2", "title": "설계 문서", "url": "u",
                            "source": "NOTION", "confidence": 0.6}],
            "file_changes": [],
        }
        session = _FakeSession(records=[row])
        with patch("tools.queries.changeset.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_pr_context("p1", 18))

        query, _params = session.calls[0]
        self.assertIn("external_id: d.external_id", query)
        self.assertEqual(result["documents"][0]["external_id"], "page-2")


if __name__ == "__main__":
    unittest.main()
