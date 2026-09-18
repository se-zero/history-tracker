"""search_by_keyword의 스레드 dedupe 순서 단위 테스트 (오프라인 — fake session/driver).

Communication 인덱스 조회가 `LIMIT $top_k`로 먼저 자른 뒤 Python에서 같은 스레드를
합쳤다 — 한 스레드의 메시지가 상위 점수를 채우면 dedupe 후 결과가 1건으로 줄어든다.
LIMIT을 `$fetch_k`(넉넉한 over-fetch)로 올리고 dedupe **후**에 top_k로 자르면, 같은
conversation_id가 여러 건 섞여 있어도 서로 다른 스레드가 top_k개까지 살아남는다.
"""

import asyncio
import unittest
from unittest.mock import patch

from tools.queries.discovery import search_by_keyword


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


def _comm_row(conversation_id: str, score: float) -> dict:
    return {
        "type": "Communication", "text": f"msg {conversation_id}", "channel": "ch",
        "source": "SLACK", "conversation_id": conversation_id, "occurredAt": "2026-08-01T00:00:00Z",
        "score": score, "related_changesets": [], "related_issues": [],
    }


class SearchByKeywordDedupeTest(unittest.TestCase):
    def test_communication_query_limits_by_fetch_k_not_top_k(self):
        session = _FakeSession(records=[[], []])
        with patch("tools.queries.discovery.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(search_by_keyword("p1", [0.1, 0.2], top_k=3))

        comm_query, comm_params = session.calls[0]
        self.assertIn("LIMIT $fetch_k", comm_query)
        self.assertNotIn("LIMIT $top_k", comm_query)
        self.assertIn("fetch_k", comm_params)

    def test_thread_flood_does_not_crowd_out_other_threads(self):
        # "flood" 스레드 메시지 6건이 최고 점수를 모두 채워도, 넉넉한 fetch_k로 가져온 뒤
        # dedupe→top_k 슬라이스라 다른 스레드가 top_k(3)개까지 살아남는다.
        flood_rows = [_comm_row("flood", 0.99 - i * 0.01) for i in range(6)]
        other_rows = [_comm_row(cid, score) for cid, score in
                      (("t2", 0.93), ("t3", 0.92), ("t4", 0.91), ("t5", 0.90))]
        session = _FakeSession(records=[flood_rows + other_rows, []])
        with patch("tools.queries.discovery.get_driver", return_value=_FakeDriver(session)):
            result = asyncio.run(search_by_keyword("p1", [0.1, 0.2], top_k=3))

        self.assertEqual(len(result), 3)
        conversation_ids = {r["conversation_id"] for r in result}
        self.assertEqual(len(conversation_ids), 3)  # 서로 다른 스레드 3개 — "flood" 하나로 쪼그라들지 않음


if __name__ == "__main__":
    unittest.main()
