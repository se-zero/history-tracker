"""Actor 이름 수동 변경 단위 테스트 (오프라인 — Neo4j driver fake 주입)."""

import asyncio
import unittest
from unittest.mock import patch

from graph.actor_admin import rename_actor


class _FakeResult:
    def __init__(self, record):
        self._record = record

    async def single(self):
        return self._record


class _FakeTx:
    def __init__(self, record):
        self.record = record
        self.params = None

    async def run(self, _query, **params):
        self.params = params
        return _FakeResult(self.record)


class _FakeSession:
    def __init__(self, record):
        self.tx = _FakeTx(record)

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return None

    async def execute_write(self, fn):
        return await fn(self.tx)


class _FakeDriver:
    def __init__(self, record):
        self.session_obj = _FakeSession(record)

    def session(self):
        return self.session_obj


class RenameActor(unittest.TestCase):
    def test_renames_actor(self):
        driver = _FakeDriver({"uuid": "a1", "name": "John Doe"})

        with patch("graph.actor_admin.get_driver", return_value=driver):
            result = asyncio.run(rename_actor("p1", "a1", "  John Doe  "))

        self.assertEqual(result, {"uuid": "a1", "name": "John Doe"})
        self.assertEqual(driver.session_obj.tx.params["name"], "John Doe")

    def test_does_not_touch_normalized_name_or_alias_pd_fields(self):
        """운영자가 입력한 이름이 매칭 키로 새면 오매칭·검색 축소를 만든다 — 회귀 방지.

        rename_actor는 Actor.name/manual_name/name_updated_at 3개만 SET한다. normalized_name이나
        ActorAlias의 pd_* 파라미터가 섞여 들어오면 검색·Step 2 후보 조회에 운영자 라벨이
        새어 들어가는 회귀다.
        """
        driver = _FakeDriver({"uuid": "a1", "name": "John Doe"})

        with patch("graph.actor_admin.get_driver", return_value=driver):
            asyncio.run(rename_actor("p1", "a1", "John Doe"))

        params = driver.session_obj.tx.params
        self.assertNotIn("normalized_name", params)
        self.assertNotIn("pd_name", params)
        self.assertNotIn("pd_normalized_name", params)

    def test_missing_actor_raises_lookup_error(self):
        driver = _FakeDriver(None)

        with patch("graph.actor_admin.get_driver", return_value=driver):
            with self.assertRaises(LookupError):
                asyncio.run(rename_actor("p1", "missing", "John Doe"))

    def test_blank_name_is_rejected(self):
        with self.assertRaises(ValueError):
            asyncio.run(rename_actor("p1", "a1", "  "))


if __name__ == "__main__":
    unittest.main()
