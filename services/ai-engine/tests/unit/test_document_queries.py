"""tools/queries/document.py 단위 테스트 (오프라인 — fake session/driver).

get_document_context의 소스 모호성 해소는 test_issue_query_ambiguity.py의
_resolve_issue_root 패턴을 그대로 미러한다(Document도 (project_id, source, external_id)
복합키라 같은 문제가 생긴다 — 지금은 Notion 하나뿐이라도 대비해 둔다).
"""

import asyncio
import unittest
from unittest.mock import patch

from tools.queries.document import get_document_context, search_documents


class _FakeResult:
    def __init__(self, record):
        self._record = record

    async def single(self):
        return self._record

    async def data(self):
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


_CANDIDATES_TWO = [
    {"source": "NOTION", "title": "Notion 설계"},
    {"source": "CONFLUENCE", "title": "Confluence 설계"},
]


class GetDocumentContextAmbiguityTest(unittest.TestCase):
    def test_zero_candidates_returns_not_found_message(self):
        session = _FakeSession(records=[[]])
        with patch("tools.queries.document.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_document_context("p1", "page-1"))

        self.assertEqual(len(session.calls), 1)
        self.assertEqual(result, {"message": "문서를 찾을 수 없습니다: page-1"})

    def test_two_candidates_without_source_returns_candidates_and_skips_further_queries(self):
        session = _FakeSession(records=[_CANDIDATES_TWO])
        with patch("tools.queries.document.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_document_context("p1", "page-1"))

        self.assertEqual(len(session.calls), 1)
        self.assertEqual(result["candidates"], _CANDIDATES_TWO)
        self.assertIn("message", result)

    def test_single_candidate_resolves_and_runs_all_downstream_queries(self):
        candidates = [_CANDIDATES_TWO[0]]
        base_row = {
            "title": "설계 문서", "body": "본문", "url": "https://notion.so/page-1",
            "createdAt": "2026-08-01T00:00:00Z", "occurredAt": "2026-08-10T00:00:00Z",
            "parent_type": "workspace", "parent_external_id": None,
            "author": "Author", "editors": ["Editor", None],
        }
        issues_row = {"issues": [
            {"issue_key": "HT-7", "title": "이슈", "source": "text", "confidence": 1.0, "section": None},
            {"issue_key": None, "title": None, "source": None, "confidence": None, "section": None},
        ]}
        changesets_row = {"changesets": [
            {"hash": "abc123", "message": "m", "occurredAt": "2026-08-05T00:00:00Z",
             "author": "Author", "source": "semantic", "confidence": 0.6, "section": "배경"},
        ]}
        discussions_row = {"discussions": []}
        session = _FakeSession(records=[candidates, base_row, issues_row, changesets_row, discussions_row])

        with patch("tools.queries.document.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_document_context("p1", "page-1"))

        self.assertEqual(len(session.calls), 5)
        self.assertEqual(result["title"], "설계 문서")
        # None 편집자는 걸러진다(OPTIONAL MATCH 미매치 잔재).
        self.assertEqual(result["editors"], ["Editor"])
        # 전부 None인 collect 더미는 걸러지고 실제 이슈만 남는다.
        self.assertEqual(len(result["issues"]), 1)
        self.assertEqual(result["issues"][0]["issue_key"], "HT-7")
        self.assertEqual(result["changesets"][0]["hash"], "abc123")
        self.assertEqual(result["changesets"][0]["source"], "semantic")

    def test_source_param_scopes_all_queries(self):
        candidates = [_CANDIDATES_TWO[0]]
        base_row = {
            "title": "t", "body": "b", "url": "u", "createdAt": None, "occurredAt": None,
            "parent_type": None, "parent_external_id": None, "author": None, "editors": [],
        }
        session = _FakeSession(records=[
            candidates, base_row, {"issues": []}, {"changesets": []}, {"discussions": []},
        ])

        with patch("tools.queries.document.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(get_document_context("p1", "page-1", source="notion"))

        resolve_query, resolve_params = session.calls[0]
        self.assertIn("d.source = $source", resolve_query)
        self.assertEqual(resolve_params["source"], "NOTION")
        for _query, params in session.calls[1:]:
            self.assertEqual(params["source"], "NOTION")


class SearchDocumentsTest(unittest.TestCase):
    def test_uses_doc_section_vector_index(self):
        session = _FakeSession(records=[[]])
        with patch("tools.queries.document.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(search_documents("p1", [0.1, 0.2], top_k=5, threshold=0.3))

        query, params = session.calls[0]
        self.assertIn("db.index.vector.queryNodes('doc_section_embedding', $fetch_k, $embedding)", query)
        self.assertIn("MATCH (s)-[:PART_OF]->(d:Document)", query)
        self.assertEqual(params["project_id"], "p1")
        self.assertEqual(params["threshold"], 0.3)

    def test_dedupes_to_highest_scoring_section_per_document_regardless_of_row_order(self):
        # 쿼리는 score DESC로 정렬해 주지만, 이 dedup은 정렬 순서에 기대지 않는다 —
        # 일부러 낮은 점수를 먼저 줘도 높은 점수 섹션이 대표로 남아야 한다.
        rows = [
            {"source": "NOTION", "external_id": "page-1", "title": "t", "url": "u",
             "section": "인증", "excerpt": "낮은 점수 섹션", "score": 0.5},
            {"source": "NOTION", "external_id": "page-1", "title": "t", "url": "u",
             "section": "토큰 갱신", "excerpt": "높은 점수 섹션", "score": 0.9},
        ]
        session = _FakeSession(records=[rows])
        with patch("tools.queries.document.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(search_documents("p1", [0.1, 0.2]))

        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]["section"], "토큰 갱신")
        self.assertEqual(result[0]["score"], 0.9)

    def test_no_results_returns_message(self):
        session = _FakeSession(records=[[]])
        with patch("tools.queries.document.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(search_documents("p1", [0.1, 0.2]))

        self.assertEqual(result, [{"message": "유사한 문서를 찾지 못했습니다. threshold를 낮추거나 다른 질의를 시도하세요."}])


if __name__ == "__main__":
    unittest.main()
