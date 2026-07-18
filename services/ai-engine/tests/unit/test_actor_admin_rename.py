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
    def test_renames_actor_and_updates_normalized_name(self):
        driver = _FakeDriver({"uuid": "a1", "name": "John Doe", "normalized_name": "johndoe"})

        with patch("graph.actor_admin.get_driver", return_value=driver):
            result = asyncio.run(rename_actor("p1", "a1", "  John Doe  "))

        self.assertEqual(result["name"], "John Doe")
        self.assertEqual(driver.session_obj.tx.params["name"], "John Doe")
        self.assertEqual(driver.session_obj.tx.params["normalized_name"], "johndoe")

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
