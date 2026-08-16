"""이슈 키가 트래커 간 충돌할 때(같은 issue_key가 JIRA·LINEAR 등에 공존) 조회 모호성을
처리하는 단위 테스트.

Issue 유니크 키가 (project_id, source, external_id)로 바뀌면서 같은 프로젝트 안에서 다른
트래커의 issue_key가 우연히 겹칠 수 있다. get_issue_context·get_timeline(issue_key 스코프)은
_resolve_issue_root로 후보를 먼저 가려내 0/1/N건에 따라 다르게 응답해야 한다.

오프라인 — fake session/driver로 쿼리 문자열·파라미터·호출 순서만 검증한다
(패턴은 test_issue_stub.py 28-60행, patch 경로는 test_stub_guards.py와 동일하게
tools.queries.issue.get_driver).
"""

import asyncio
import unittest
from unittest.mock import patch

from tools.queries._common import _MIN_CONFIDENCE
from tools.queries.issue import get_issue_context, get_timeline


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
    {"source": "JIRA", "issue_key": "ENG-123", "title": "jira title", "status": "open"},
    {"source": "LINEAR", "issue_key": "ENG-123", "title": "linear title", "status": "Todo"},
]


class GetIssueContextAmbiguityTest(unittest.TestCase):
    def test_two_real_candidates_without_source_returns_message_and_skips_further_queries(self):
        session = _FakeSession(records=[_CANDIDATES_TWO])
        with patch("tools.queries.issue.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_issue_context("p1", "ENG-123"))

        self.assertEqual(len(session.calls), 1)  # 해석 쿼리만 실행, 상세 조회는 안 나감
        self.assertEqual(result["candidates"], _CANDIDATES_TWO)
        self.assertIn("message", result)
        self.assertNotIn("issue_key", result)

    def test_source_narrows_candidates_and_flows_into_downstream_queries(self):
        candidates = [_CANDIDATES_TWO[1]]  # LINEAR 하나로 좁혀진 상태
        base_row = {
            "issue_key": "ENG-123", "title": "linear title", "body": "body",
            "status": "Todo", "issue_type": "Task", "priority": "Medium",
            "occurredAt": None, "creator": None, "assignee": None,
        }
        scope_issues = [{"issue_key": "ENG-123", "title": "linear title", "status": "Todo"}]
        work_rows = [{"issue_key": "ENG-123", "changesets": [], "pull_requests": []}]
        disc_rows = [{"issue_key": "ENG-123", "discussions": []}]
        doc_rows = [{"issue_key": "ENG-123", "documents": []}]
        session = _FakeSession(
            records=[candidates, base_row, scope_issues, work_rows, disc_rows, doc_rows]
        )

        # 소문자 입력("linear")도 저장값(대문자 "LINEAR")과 매칭되도록 대문자 정규화한다.
        with patch("tools.queries.issue.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_issue_context("p1", "ENG-123", source="linear"))

        self.assertEqual(len(session.calls), 6)  # 해석 1 + 기존 5단계

        resolve_query, resolve_params = session.calls[0]
        self.assertIn("i.source = $source", resolve_query)
        self.assertEqual(resolve_params["source"], "LINEAR")

        for query, params in session.calls[1:]:
            self.assertIn("source = $source", query)
            self.assertEqual(params["source"], "LINEAR")

        self.assertEqual(result["issue_key"], "ENG-123")
        self.assertNotIn("candidates", result)

    def test_single_candidate_without_source_keeps_backward_compatible_shape(self):
        candidates = [_CANDIDATES_TWO[0]]
        base_row = {
            "issue_key": "ENG-123", "title": "jira title", "body": "body",
            "status": "open", "issue_type": "Task", "priority": "Medium",
            "occurredAt": None, "creator": "Reporter", "assignee": None,
        }
        scope_issues = [{"issue_key": "ENG-123", "title": "jira title", "status": "open"}]
        work_rows = [{"issue_key": "ENG-123", "changesets": [], "pull_requests": []}]
        disc_rows = [{"issue_key": "ENG-123", "discussions": []}]
        doc_rows = [{"issue_key": "ENG-123", "documents": []}]
        session = _FakeSession(
            records=[candidates, base_row, scope_issues, work_rows, disc_rows, doc_rows]
        )

        with patch("tools.queries.issue.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_issue_context("p1", "ENG-123"))

        self.assertEqual(len(session.calls), 6)
        # 하위 호환: 기존 단일 매칭 시 반환 구조(키 목록)가 그대로 유지된다(documents는 신규 추가).
        self.assertEqual(
            set(result.keys()),
            {
                "issue_key", "title", "body", "status", "issue_type", "priority",
                "occurredAt", "creator", "assignee", "changesets", "pull_requests",
                "discussions", "documents", "descendants",
            },
        )
        self.assertEqual(result["descendants"], [])

    def test_zero_candidates_keeps_not_found_message(self):
        session = _FakeSession(records=[[]])
        with patch("tools.queries.issue.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_issue_context("p1", "ENG-999"))

        self.assertEqual(len(session.calls), 1)
        self.assertEqual(result, {"message": "이슈를 찾을 수 없습니다: ENG-999"})


class GetIssueContextDocumentsTest(unittest.TestCase):
    """get_issue_context의 documents 필드 — DESCRIBED_IN(Issue→Document) 유입 조회.

    문서→이슈 방향은 document.get_document_context의 issues 필드가 이미 커버한다
    (test_document_queries.py). 이 테스트는 그 반대 방향, 이슈→문서 경로가 실제로
    연결·필터링되는지 검증한다.
    """

    def test_documents_query_scoped_by_described_in_and_filters_empty_rows(self):
        candidates = [_CANDIDATES_TWO[0]]
        base_row = {
            "issue_key": "ENG-123", "title": "jira title", "body": "body",
            "status": "open", "issue_type": "Task", "priority": "Medium",
            "occurredAt": None, "creator": None, "assignee": None,
        }
        scope_issues = [{"issue_key": "ENG-123", "title": "jira title", "status": "open"}]
        work_rows = [{"issue_key": "ENG-123", "changesets": [], "pull_requests": []}]
        disc_rows = [{"issue_key": "ENG-123", "discussions": []}]
        doc_rows = [{"issue_key": "ENG-123", "documents": [
            {"external_id": "page-1", "title": "설계 문서", "source": "NOTION",
             "confidence": 1.0, "link_source": "text", "section": None},
            # OPTIONAL MATCH 미매치 잔재 — 전 필드 None 더미는 걸러져야 한다.
            {"external_id": None, "title": None, "source": None,
             "confidence": None, "link_source": None, "section": None},
        ]}]
        session = _FakeSession(
            records=[candidates, base_row, scope_issues, work_rows, disc_rows, doc_rows]
        )

        with patch("tools.queries.issue.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_issue_context("p1", "ENG-123"))

        doc_query, doc_params = session.calls[5]
        self.assertIn("DESCRIBED_IN", doc_query)
        self.assertIn("r.source = 'text' OR r.confidence >= $min_conf", doc_query)
        self.assertEqual(doc_params["min_conf"], _MIN_CONFIDENCE)

        self.assertEqual(len(result["documents"]), 1)
        self.assertEqual(result["documents"][0]["external_id"], "page-1")
        self.assertEqual(result["documents"][0]["link_source"], "text")


class GetTimelineIssueScopeAmbiguityTest(unittest.TestCase):
    def test_two_candidates_returns_scope_with_candidates_and_message(self):
        session = _FakeSession(records=[_CANDIDATES_TWO])
        with patch("tools.queries.issue.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_timeline("p1", issue_key="ENG-123"))

        self.assertEqual(len(session.calls), 1)
        self.assertEqual(result["type"], "issue")
        self.assertEqual(result["value"], "ENG-123")
        self.assertEqual(result["candidates"], _CANDIDATES_TWO)
        self.assertIn("message", result)

    def test_source_resolves_single_candidate_and_filters_downstream_queries(self):
        candidates = [_CANDIDATES_TWO[1]]
        detail_row = {
            "root_created": None, "root_closed": None, "root_status": "Todo",
            "issues": [{"createdAt": None, "closedAt": None,
                        "issue_key": "ENG-123", "title": "linear title", "status": "Todo"}],
            "changesets": [], "pull_requests": [],
        }
        comm_row = {"communications": []}
        session = _FakeSession(records=[candidates, detail_row, comm_row])

        with patch("tools.queries.issue.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_timeline("p1", issue_key="ENG-123", source="LINEAR"))

        self.assertEqual(len(session.calls), 3)
        for query, params in session.calls[1:]:
            self.assertIn("root.source = $source", query)
            self.assertEqual(params["source"], "LINEAR")
        self.assertEqual(result["scope"]["value"], "ENG-123")


if __name__ == "__main__":
    unittest.main()
