"""Jira 개인정보 보고 결과 반영(apply_report) 단위 테스트 (오프라인 — Neo4j driver fake 주입).

graph/privacy.py의 계약을 코드로 고정한다:
- erase·refresh와 Actor.name 재계산은 반드시 같은 트랜잭션(execute_write 단일 호출) —
  나뉘면 "alias는 비웠는데 Actor.name은 안 바뀌는" 정합성 결함이 조용히 생긴다.
- erase는 closed가 access_lost(휴면) alias를 영구 삭제로 격상할 수 있다.
- refresh 쿼리는 pd_erased='closed'인 alias를 절대 매치하지 않는다 — store 계층에 가드가
  없어 이 WHERE절이 유일한 방어선이다.
- 입력 검증(reported_at·reason·account_id·name)은 트랜잭션 밖에서 ValueError로 던진다.
"""

import asyncio
import unittest
from unittest.mock import patch

from graph.privacy import apply_report


class _FakeResult:
    def __init__(self, rows: list[dict]):
        self._rows = rows

    async def data(self):
        return self._rows

    async def single(self):
        return self._rows[0] if self._rows else None


class _RecordingTx:
    """호출된 (query, params)를 전부 기록하고, 등록된 마커가 매치되는 쿼리에만 canned rows를 준다.

    매치되는 마커가 없으면 빈 결과([])를 준다 — 반환값을 읽지 않는 SET 전용 쿼리엔 무해하다.
    """

    def __init__(self, responses: dict | None = None):
        self._responses = responses or {}
        self.calls: list[tuple[str, dict]] = []

    async def run(self, query, **params):
        self.calls.append((query, params))
        for marker, rows in self._responses.items():
            if marker in query:
                return _FakeResult(rows)
        return _FakeResult([])


class _FakeSession:
    def __init__(self, tx: _RecordingTx):
        self.tx = tx
        self.execute_write_calls = 0

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return None

    async def execute_write(self, fn):
        self.execute_write_calls += 1
        return await fn(self.tx)


class _FakeDriver:
    def __init__(self, tx: _RecordingTx):
        self.session_obj = _FakeSession(tx)

    def session(self):
        return self.session_obj


class ApplyReportTransaction(unittest.TestCase):
    def test_execute_write_called_exactly_once(self):
        """ok+erase+refresh가 섞여도 트랜잭션은 정확히 한 번만 연다."""
        tx = _RecordingTx(
            {"OPTIONAL MATCH (al)-[:ALIAS_OF]->(a:Actor)": [{"source_id": "x", "actor_uuid": "actor-1"}]}
        )
        driver = _FakeDriver(tx)

        with patch("graph.privacy.get_driver", return_value=driver), \
             patch("graph.privacy.recompute_display_name"):
            asyncio.run(apply_report(
                "2026-01-01T00:00:00Z",
                ok=["ok-1"],
                erase=[{"account_id": "e-1", "reason": "closed"}],
                refresh=[{"account_id": "r-1", "name": "New Name", "email": None}],
            ))

        self.assertEqual(driver.session_obj.execute_write_calls, 1)


class ApplyReportErase(unittest.TestCase):
    def test_erase_sets_five_pd_fields_null_and_recomputes_with_tx(self):
        tx = _RecordingTx(
            {"OPTIONAL MATCH (al)-[:ALIAS_OF]->(a:Actor)": [{"source_id": "JIRA:acc-1", "actor_uuid": "actor-1"}]}
        )
        driver = _FakeDriver(tx)

        with patch("graph.privacy.get_driver", return_value=driver), \
             patch("graph.privacy.recompute_display_name") as recompute_mock:
            result = asyncio.run(apply_report(
                "2026-01-01T00:00:00Z", ok=[], erase=[{"account_id": "acc-1", "reason": "closed"}], refresh=[],
            ))

        erase_calls = [q for q, _p in tx.calls if "pd_name = null" in q]
        self.assertEqual(len(erase_calls), 1)
        query = erase_calls[0]
        for clause in (
            "pd_name = null", "pd_normalized_name = null", "pd_email = null",
            "pd_updated_at = null", "pd_reported_at = null", "pd_erased = $reason",
        ):
            self.assertIn(clause, query)

        recompute_mock.assert_called_once_with(tx, "actor-1")
        self.assertEqual(result["erased"], 1)

    def test_erase_query_guard_allows_closed_to_upgrade_access_lost(self):
        tx = _RecordingTx()
        driver = _FakeDriver(tx)

        with patch("graph.privacy.get_driver", return_value=driver), \
             patch("graph.privacy.recompute_display_name"):
            asyncio.run(apply_report(
                "2026-01-01T00:00:00Z", ok=[], erase=[{"account_id": "acc-1", "reason": "closed"}], refresh=[],
            ))

        query = [q for q, _p in tx.calls if "pd_name = null" in q][0]
        self.assertIn("pd_erased IS NULL OR", query)
        self.assertIn("'closed'", query)


class ApplyReportRefresh(unittest.TestCase):
    def test_refresh_query_excludes_closed_and_has_no_closed_revival_clause(self):
        """'closed'가 되살아나지 않는 유일한 방어선 — 가드 문구가 정확히 있어야 한다."""
        tx = _RecordingTx()
        driver = _FakeDriver(tx)

        with patch("graph.privacy.get_driver", return_value=driver), \
             patch("graph.privacy.recompute_display_name"):
            asyncio.run(apply_report(
                "2026-01-01T00:00:00Z", ok=[], erase=[],
                refresh=[{"account_id": "acc-1", "name": "New Name", "email": None}],
            ))

        query = [q for q, _p in tx.calls if "pd_erased = null" in q][0]
        self.assertIn("pd_erased IS NULL OR al.pd_erased = 'access_lost'", query)
        # 가드를 설명하는 주석에는 'closed'가 등장할 수 있다 — 되살리는 비교절(al.pd_erased = 'closed')만 없으면 된다.
        self.assertNotIn("al.pd_erased = 'closed'", query)

    def test_refresh_passes_none_email_as_null_param_and_resets_pd_erased(self):
        tx = _RecordingTx()
        driver = _FakeDriver(tx)

        with patch("graph.privacy.get_driver", return_value=driver), \
             patch("graph.privacy.recompute_display_name"):
            asyncio.run(apply_report(
                "2026-01-01T00:00:00Z", ok=[], erase=[],
                refresh=[{"account_id": "acc-1", "name": "New Name", "email": None}],
            ))

        query, params = [(q, p) for q, p in tx.calls if "pd_erased = null" in q][0]
        self.assertIsNone(params["email"])
        self.assertIn("pd_erased = null", query)


class ApplyReportValidation(unittest.TestCase):
    def test_invalid_erase_reason_raises_before_transaction(self):
        tx = _RecordingTx()
        driver = _FakeDriver(tx)

        with patch("graph.privacy.get_driver", return_value=driver) as get_driver_mock:
            with self.assertRaises(ValueError):
                asyncio.run(apply_report(
                    "2026-01-01T00:00:00Z", ok=[],
                    erase=[{"account_id": "acc-1", "reason": "deleted"}], refresh=[],
                ))

        get_driver_mock.assert_not_called()

    def test_refresh_without_name_raises(self):
        with self.assertRaises(ValueError):
            asyncio.run(apply_report(
                "2026-01-01T00:00:00Z", ok=[], erase=[],
                refresh=[{"account_id": "acc-1", "name": "", "email": None}],
            ))


class ApplyReportIdempotent(unittest.TestCase):
    def test_empty_input_returns_zero_counts(self):
        tx = _RecordingTx()
        driver = _FakeDriver(tx)

        with patch("graph.privacy.get_driver", return_value=driver), \
             patch("graph.privacy.recompute_display_name"):
            result = asyncio.run(apply_report("2026-01-01T00:00:00Z", ok=[], erase=[], refresh=[]))

        self.assertEqual(result, {"erased": 0, "refreshed": 0, "stamped": 0})


class ApplyReportOk(unittest.TestCase):
    def test_ok_stamp_query_excludes_erased(self):
        tx = _RecordingTx({"RETURN count(al) AS n": [{"n": 2}]})
        driver = _FakeDriver(tx)

        with patch("graph.privacy.get_driver", return_value=driver), \
             patch("graph.privacy.recompute_display_name"):
            result = asyncio.run(apply_report(
                "2026-01-01T00:00:00Z", ok=["ok-1", "ok-2"], erase=[], refresh=[],
            ))

        ok_calls = [q for q, _p in tx.calls if "RETURN count(al) AS n" in q]
        self.assertEqual(len(ok_calls), 1)
        self.assertIn("pd_erased IS NULL", ok_calls[0])
        self.assertEqual(result["stamped"], 2)


if __name__ == "__main__":
    unittest.main()
