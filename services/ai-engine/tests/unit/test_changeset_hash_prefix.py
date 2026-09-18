"""커밋 조회의 짧은 해시(접두어) 허용 — get_changeset_context·get_conflict_context 단위 테스트
(오프라인 — fake session/driver).

사용자·시스템 프롬프트가 커밋을 앞 7자 해시로 지칭하는데, 기존 쿼리는 40자 전체 일치라
정상 입력이 "커밋을 찾을 수 없습니다"로 떨어졌다. `_resolve_changeset_hash`가 STARTS WITH로
접두어를 허용하고, 후보가 여러 개면 candidates를 돌려 재호출을 유도한다
(패턴은 test_issue_query_ambiguity.py의 _resolve_issue_root 미러).
"""

import asyncio
import unittest
from unittest.mock import patch

from tools.queries.changeset import (
    _HASH_CANDIDATE_LIMIT,
    get_changeset_context,
    get_conflict_context,
)


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


_TWO_CANDIDATES = [
    {"hash": "abc1234full", "message": "fix: 첫 줄\n본문", "occurredAt": "2026-08-01T00:00:00Z"},
    {"hash": "abc1235full", "message": "chore: 다른 커밋", "occurredAt": "2026-08-02T00:00:00Z"},
]

# 상한(_HASH_CANDIDATE_LIMIT=6)보다 1건 많은 후보 — 쿼리가 상한+1을 조회하므로 이 형태로 온다
_OVER_LIMIT_CANDIDATES = [
    {"hash": f"abc123{i}full", "message": f"commit {i}", "occurredAt": "2026-08-01T00:00:00Z"}
    for i in range(_HASH_CANDIDATE_LIMIT + 1)
]

_FULL_HASH = "a" * 40


class GetChangesetContextHashPrefixTest(unittest.TestCase):
    def test_seven_char_prefix_queries_with_starts_with(self):
        session = _FakeSession(records=[[]])
        with patch("tools.queries.changeset.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(get_changeset_context("p1", "abc1234"))

        self.assertEqual(len(session.calls), 1)
        query, params = session.calls[0]
        self.assertIn("cs.hash STARTS WITH $hash", query)
        self.assertEqual(params["hash"], "abc1234")

    def test_single_match_uses_full_hash_in_main_query(self):
        candidates = [_TWO_CANDIDATES[0]]
        main_row = {
            "hash": "abc1234full", "commit_message": "m", "occurredAt": None, "author": "A",
            "issues": [], "communications": [], "documents": [],
            "pull_request": {"pr_number": None, "title": None, "url": None},
            "file_changes": [],
        }
        session = _FakeSession(records=[candidates, main_row])
        with patch("tools.queries.changeset.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_changeset_context("p1", "abc1234"))

        self.assertEqual(len(session.calls), 2)
        main_query, main_params = session.calls[1]
        self.assertIn("hash: $hash", main_query)
        self.assertEqual(main_params["hash"], "abc1234full")
        self.assertEqual(result["hash"], "abc1234full")

    def test_multiple_matches_returns_candidates_and_skips_main_query(self):
        session = _FakeSession(records=[_TWO_CANDIDATES])
        with patch("tools.queries.changeset.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_changeset_context("p1", "abc1234"))

        self.assertEqual(len(session.calls), 1)  # 해석 쿼리만 실행, 본 쿼리는 안 나감
        self.assertIn("candidates", result)
        self.assertEqual(len(result["candidates"]), 2)
        # candidates의 message는 첫 줄만 담는다 — 본문까지 부풀지 않도록.
        self.assertEqual(result["candidates"][0]["message"], "fix: 첫 줄")
        self.assertIn("message", result)

    def test_full_hash_skips_resolve_query(self):
        # 40자 전체 해시는 접두어 해석이 불필요하다 — 타임라인·파일 이력이 넘기는 형태라
        # 가장 흔한 경로이고, 왕복 한 번과 STARTS WITH 스캔을 아낀다.
        main_row = {
            "hash": _FULL_HASH, "commit_message": "m", "occurredAt": None, "author": "A",
            "issues": [], "communications": [], "documents": [],
            "pull_request": {"pr_number": None, "title": None, "url": None},
            "file_changes": [],
        }
        session = _FakeSession(records=[main_row])
        with patch("tools.queries.changeset.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_changeset_context("p1", _FULL_HASH))

        self.assertEqual(len(session.calls), 1)  # 해석 쿼리 없음 — 본 쿼리만
        main_query, main_params = session.calls[0]
        self.assertNotIn("STARTS WITH", main_query)
        self.assertEqual(main_params["hash"], _FULL_HASH)
        self.assertEqual(result["hash"], _FULL_HASH)

    def test_candidates_over_limit_are_trimmed_and_reported(self):
        # 후보가 상한을 넘으면 목록을 자르되 잘렸다는 사실을 알려야 한다 — 고지가 없으면
        # 모델이 목록을 전부로 믿고 진짜 대상이 빠진 채 고른다.
        session = _FakeSession(records=[_OVER_LIMIT_CANDIDATES])
        with patch("tools.queries.changeset.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_changeset_context("p1", "abc1234"))

        self.assertEqual(len(result["candidates"]), _HASH_CANDIDATE_LIMIT)
        self.assertIn("일부만 표시", result["message"])

    def test_resolve_query_fetches_one_more_than_limit(self):
        # 잘림을 판단하려면 상한보다 1건 더 조회해야 한다
        session = _FakeSession(records=[[]])
        with patch("tools.queries.changeset.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(get_changeset_context("p1", "abc1234"))

        _query, params = session.calls[0]
        self.assertEqual(params["limit"], _HASH_CANDIDATE_LIMIT + 1)

    def test_prefix_shorter_than_seven_chars_rejected_without_query(self):
        session = _FakeSession()
        with patch("tools.queries.changeset.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_changeset_context("p1", "abc123"))

        self.assertEqual(len(session.calls), 0)
        self.assertEqual(result, {"message": "커밋 해시는 앞 7자 이상으로 지정하세요: abc123"})

    def test_zero_matches_keeps_not_found_message(self):
        session = _FakeSession(records=[[]])
        with patch("tools.queries.changeset.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_changeset_context("p1", "abc1234"))

        self.assertEqual(result, {"message": "커밋을 찾을 수 없습니다: abc1234"})


class GetConflictContextHashPrefixTest(unittest.TestCase):
    def test_seven_char_prefix_queries_with_starts_with(self):
        session = _FakeSession(records=[[]])
        with patch("tools.queries.changeset.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(get_conflict_context("p1", "abc1234"))

        self.assertEqual(len(session.calls), 1)
        query, params = session.calls[0]
        self.assertIn("cs.hash STARTS WITH $hash", query)
        self.assertEqual(params["hash"], "abc1234")

    def test_single_match_uses_full_hash_in_main_query(self):
        candidates = [_TWO_CANDIDATES[0]]
        main_row = {
            "hash": "abc1234full", "commit_message": "m", "occurredAt": None,
            "issue_contexts": [], "comm_contexts": [], "pr_contexts": [], "doc_contexts": [],
            "file_changes": [],
        }
        session = _FakeSession(records=[candidates, main_row])
        with patch("tools.queries.changeset.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_conflict_context("p1", "abc1234"))

        self.assertEqual(len(session.calls), 2)
        main_query, main_params = session.calls[1]
        self.assertIn("hash: $hash", main_query)
        self.assertEqual(main_params["hash"], "abc1234full")
        self.assertEqual(result["hash"], "abc1234full")

    def test_multiple_matches_returns_candidates_and_skips_main_query(self):
        session = _FakeSession(records=[_TWO_CANDIDATES])
        with patch("tools.queries.changeset.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_conflict_context("p1", "abc1234"))

        self.assertEqual(len(session.calls), 1)
        self.assertIn("candidates", result)
        self.assertEqual(len(result["candidates"]), 2)

    def test_full_hash_skips_resolve_query(self):
        main_row = {
            "hash": _FULL_HASH, "commit_message": "m", "occurredAt": None,
            "issue_contexts": [], "comm_contexts": [], "pr_contexts": [], "doc_contexts": [],
            "file_changes": [],
        }
        session = _FakeSession(records=[main_row])
        with patch("tools.queries.changeset.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_conflict_context("p1", _FULL_HASH))

        self.assertEqual(len(session.calls), 1)
        self.assertNotIn("STARTS WITH", session.calls[0][0])
        self.assertEqual(result["hash"], _FULL_HASH)

    def test_prefix_shorter_than_seven_chars_rejected_without_query(self):
        session = _FakeSession()
        with patch("tools.queries.changeset.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(get_conflict_context("p1", "abc123"))

        self.assertEqual(len(session.calls), 0)
        self.assertEqual(result, {"message": "커밋 해시는 앞 7자 이상으로 지정하세요: abc123"})


if __name__ == "__main__":
    unittest.main()
