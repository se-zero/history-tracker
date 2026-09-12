"""사람 식별자 관대 매칭(_resolve_actor) 단위 테스트 (오프라인 — fake session/driver).

1차 정확 일치가 있으면 2차(대소문자 무시 부분 일치) 쿼리를 실행하지 않는다 — 정확 일치
입력의 동작을 오늘과 완전히 같게 보존하기 위한 순서다. 1차가 0건일 때만 2차로 폴백해
"junsu"처럼 부분 이름·대소문자가 다른 입력을 구제한다. 후보가 2명 이상이면 candidates를
반환하고 활동 조회는 실행하지 않는다. 단일 해석이면 이후 쿼리가 모두 actor_uuid로 스코프된다.

get_actor_activity·inspect_actor·get_timeline(actor=...) 세 진입점을 각각 확인한다
(패턴은 test_issue_query_ambiguity.py의 _resolve_issue_root 미러).
"""

import asyncio
import unittest
from unittest.mock import patch

from tools.queries.actor import _ACTOR_RESOLVE_LIMIT, get_actor_activity, inspect_actor
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


_ONE_MATCH = [{"uuid": "u1", "name": "Junsu Seo", "aliases": ["junsu-dev"]}]
_TWO_MATCHES = [
    {"uuid": "u1", "name": "Junsu Seo", "aliases": ["junsu-dev"]},
    {"uuid": "u2", "name": "Junsu Kim", "aliases": []},
]
_EXPECTED_CANDIDATES = [
    {"name": "Junsu Seo", "aliases": ["junsu-dev"]},
    {"name": "Junsu Kim", "aliases": []},
]


# 상한보다 1건 많은 후보 — 쿼리가 상한+1을 조회하므로 이 형태로 온다
_OVER_LIMIT_MATCHES = [
    {"uuid": f"u{i}", "name": f"Junsu {i}", "aliases": [f"junsu-{i}"]}
    for i in range(_ACTOR_RESOLVE_LIMIT + 1)
]


def _actor_meta_row():
    return {"name": "Junsu Seo", "aliases": ["junsu-dev"], "emails": ["junsu@example.com"]}


class GetActorActivityMatchTest(unittest.TestCase):
    def test_exact_match_skips_fuzzy_query(self):
        session = _FakeSession(records=[
            _ONE_MATCH, _actor_meta_row(), [], [], [], {"issues_created": [], "issues_assigned": []},
        ])
        with patch("tools.queries.actor.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(get_actor_activity("p1", "Junsu Seo"))

        resolve_query, _ = session.calls[0]
        self.assertIn("a.name = $identifier", resolve_query)
        self.assertFalse(any("toLower" in q for q, _ in session.calls))  # 2차(부분 일치)는 안 나감

    def test_fallback_to_fuzzy_when_exact_has_zero_matches(self):
        session = _FakeSession(records=[
            [], _ONE_MATCH, _actor_meta_row(), [], [], [], {"issues_created": [], "issues_assigned": []},
        ])
        with patch("tools.queries.actor.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(get_actor_activity("p1", "junsu"))

        fuzzy_query, fuzzy_params = session.calls[1]
        self.assertIn("toLower(a.name) CONTAINS toLower($identifier)", fuzzy_query)
        self.assertEqual(fuzzy_params["identifier"], "junsu")

    def test_multiple_matches_returns_candidates_and_skips_activity_queries(self):
        session = _FakeSession(records=[_TWO_MATCHES])
        with patch("tools.queries.actor.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_actor_activity("p1", "junsu"))

        self.assertEqual(len(session.calls), 1)
        self.assertIn("candidates", result)
        self.assertEqual(result["candidates"], _EXPECTED_CANDIDATES)

    def test_single_match_scopes_downstream_queries_by_actor_uuid(self):
        session = _FakeSession(records=[
            _ONE_MATCH, _actor_meta_row(), [], [], [], {"issues_created": [], "issues_assigned": []},
        ])
        with patch("tools.queries.actor.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(get_actor_activity("p1", "Junsu Seo"))

        for query, params in session.calls[1:]:
            self.assertEqual(params["actor_uuid"], "u1")
            self.assertIn("uuid: $actor_uuid", query)

    def test_ambiguous_message_offers_alias_as_escape_hatch(self):
        # 동일인 판단 실패로 표시 이름까지 같아지면 "이름으로 재호출"은 같은 모호함으로
        # 돌아온다 — alias는 소스별로 유일하므로 안내에 함께 있어야 한다.
        session = _FakeSession(records=[_TWO_MATCHES])
        with patch("tools.queries.actor.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_actor_activity("p1", "junsu"))

        self.assertIn("alias", result["message"])

    def test_candidates_over_limit_are_trimmed_and_reported(self):
        session = _FakeSession(records=[_OVER_LIMIT_MATCHES])
        with patch("tools.queries.actor.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_actor_activity("p1", "junsu"))

        self.assertEqual(len(result["candidates"]), _ACTOR_RESOLVE_LIMIT)
        self.assertIn("일부만 표시", result["message"])

    def test_resolve_query_fetches_one_more_than_limit(self):
        session = _FakeSession(records=[_ONE_MATCH, _actor_meta_row(), [], [], [],
                                        {"issues_created": [], "issues_assigned": []}])
        with patch("tools.queries.actor.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(get_actor_activity("p1", "Junsu Seo"))

        _query, params = session.calls[0]
        self.assertEqual(params["resolve_limit"], _ACTOR_RESOLVE_LIMIT + 1)

    def test_actor_node_vanishing_between_queries_returns_message(self):
        # resolve 직후 연동 해제가 끼어 노드가 사라진 경우 — dict(None) TypeError가 아니라
        # 안내로 떨어져야 한다(가드 제거 회귀 방지).
        session = _FakeSession(records=[_ONE_MATCH, None])
        with patch("tools.queries.actor.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_actor_activity("p1", "Junsu Seo"))

        self.assertEqual(result, {"message": "Actor를 찾을 수 없습니다: Junsu Seo"})

    def test_no_matches_returns_not_found_message(self):
        session = _FakeSession(records=[[], []])
        with patch("tools.queries.actor.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_actor_activity("p1", "nobody"))

        self.assertEqual(result, {"message": "Actor를 찾을 수 없습니다: nobody"})


class InspectActorMatchTest(unittest.TestCase):
    def test_single_match_scopes_query_by_actor_uuid(self):
        detail_row = {
            "uuid": "u1", "display_name": "Junsu Seo", "all_aliases": ["junsu-dev"],
            "emails": ["junsu@example.com"], "commit_count": 3, "pr_count": 1,
            "message_count": 5, "issue_created_count": 2,
        }
        session = _FakeSession(records=[_ONE_MATCH, detail_row])
        with patch("tools.queries.actor.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(inspect_actor("p1", "Junsu Seo"))

        self.assertEqual(len(session.calls), 2)
        query, params = session.calls[1]
        self.assertIn("uuid: $actor_uuid", query)
        self.assertEqual(params["actor_uuid"], "u1")
        self.assertEqual(result["uuid"], "u1")

    def test_multiple_matches_returns_candidates_and_skips_detail_query(self):
        session = _FakeSession(records=[_TWO_MATCHES])
        with patch("tools.queries.actor.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(inspect_actor("p1", "junsu"))

        self.assertEqual(len(session.calls), 1)
        self.assertEqual(result["candidates"], _EXPECTED_CANDIDATES)


class GetTimelineActorMatchTest(unittest.TestCase):
    def test_single_match_scopes_query_by_actor_uuid(self):
        detail_row = {
            "name": "Junsu Seo",
            "changesets": [], "pull_requests": [], "communications": [], "issues": [],
        }
        session = _FakeSession(records=[_ONE_MATCH, detail_row])
        with patch("tools.queries.issue.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_timeline("p1", actor="Junsu Seo"))

        self.assertEqual(len(session.calls), 2)
        query, params = session.calls[1]
        self.assertIn("uuid: $actor_uuid", query)
        self.assertEqual(params["actor_uuid"], "u1")
        self.assertEqual(result["scope"]["resolved_name"], "Junsu Seo")

    def test_multiple_matches_returns_candidates_and_skips_activity_query(self):
        session = _FakeSession(records=[_TWO_MATCHES])
        with patch("tools.queries.issue.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_timeline("p1", actor="junsu"))

        self.assertEqual(len(session.calls), 1)
        self.assertEqual(result["type"], "actor")
        self.assertEqual(result["candidates"], _EXPECTED_CANDIDATES)


if __name__ == "__main__":
    unittest.main()
