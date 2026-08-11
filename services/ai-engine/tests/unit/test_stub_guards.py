"""스텁 센티널 Issue(source='__stub__') 제외 가드 회귀 테스트.

이 브랜치는 "아직 수집 안 된 이슈"를 커밋이 언급하면 자리표시 노드
(`Issue {source: '__stub__'}`)를 만들어 두는 구조를 도입했다(graph/writes.py).
조회 경로(tools/queries, graph/overview)에는 이 스텁을 결과에서 걸러내는 가드가
필요하다 — 빠지면 title이 전부 null인 가짜 이슈가 LLM 답변·대시보드에 노출된다.

여기서는 실제 Neo4j 의미론을 검증하지 않는다 — 실행된 Cypher 문자열에 가드
술어가 포함되는지만 오프라인으로 확인한다(패턴은 test_issue_stub.py 미러).
"""

import asyncio
import unittest
from unittest.mock import patch

from graph.overview import (
    _node_query,
    _RECENT_CONTENT_QUERY,
    _work_unit_query,
    _WORK_UNIT_NEIGHBORHOOD_QUERY,
)
from tools.queries.changeset import get_changeset_context
from tools.queries.issue import get_timeline


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


class OverviewStubGuardTest(unittest.TestCase):
    """드라이버 불필요 — Cypher 조각 문자열 자체를 검사한다."""

    def test_recent_content_query_excludes_stub_issue(self):
        self.assertIn("__stub__", _RECENT_CONTENT_QUERY)

    def test_work_unit_query_excludes_stub_issue(self):
        self.assertIn("__stub__", _work_unit_query("Issue"))

    def test_node_query_excludes_stub_issue(self):
        query = _node_query("n:Issue", "nb:Actor")
        self.assertIn("__stub__", query)

    def test_work_unit_neighborhood_query_excludes_stub_issue(self):
        self.assertIn("__stub__", _WORK_UNIT_NEIGHBORHOOD_QUERY)


class IssueTimelineStubGuardTest(unittest.TestCase):
    def test_issue_scope_root_queries_exclude_stub(self):
        # 0번째 쿼리(source 해석 — 후보 단일) → 1건, 1번째 쿼리(root 생명주기+work) → 유효한
        # issue 1건, 2번째 쿼리(논의) → 빈 결과.
        candidates = [{"source": "JIRA", "issue_key": "HT-1", "title": "t", "status": "open"}]
        row = {
            "root_created": None, "root_closed": None, "root_status": "open",
            "issues": [{"createdAt": None, "closedAt": None,
                        "issue_key": "HT-1", "title": "t", "status": "open"}],
            "changesets": [], "pull_requests": [],
        }
        row2 = {"communications": []}
        session = _FakeSession(records=[candidates, row, row2])
        with patch("tools.queries.issue.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(get_timeline("p1", issue_key="HT-1"))

        self.assertEqual(len(session.calls), 3)
        # 0번째(source 해석)는 i.source <> '__stub__'로 가드하고, 나머지 두 개는 root 별칭이다.
        self.assertIn("i.source <> '__stub__'", session.calls[0][0])
        for query, _params in session.calls[1:]:
            self.assertIn("root.source <> '__stub__'", query)


class ChangesetContextStubGuardTest(unittest.TestCase):
    def test_triggered_by_issue_match_excludes_stub(self):
        session = _FakeSession(records=[None])
        with patch("tools.queries.changeset.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(get_changeset_context("p1", "abc123"))

        self.assertEqual(len(session.calls), 1)
        query, _params = session.calls[0]
        self.assertIn("i.source <> '__stub__'", query)


if __name__ == "__main__":
    unittest.main()
