"""봇 액터 격리 — graph/actor_store.py 쿼리 계약 단위 테스트 (오프라인 — Neo4j driver fake 주입).

NormalizedEvent의 actor.bot(Linear AI 에이전트 위임 등)을 사람 동일인 매칭에서 양방향으로
차단한다:
- _create_actor는 신규 Actor 노드에 bot 플래그를 SET한다.
- _lookup_actor_by_email / _lookup_actor_by_name은 봇 Actor를 후보에서 제외한다 —
  사람 매칭이 봇 Actor에 붙는 역방향(봇 → 사람 오매칭)까지 막는 것이 목적이다.
"""

import asyncio
import unittest
from unittest.mock import patch

from graph.actor_store import _create_actor, _lookup_actor_by_email, _lookup_actor_by_name


class _FakeResult:
    def __init__(self, record=None, rows=None):
        self._record = record
        self._rows = rows if rows is not None else []

    async def single(self):
        return self._record

    async def data(self):
        return self._rows


class _FakeSession:
    """호출된 (query, params)를 전부 기록하고 항상 같은 결과를 돌려주는 fake 세션."""

    def __init__(self, record=None, rows=None):
        self._record = record
        self._rows = rows
        self.calls: list[tuple[str, dict]] = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return None

    async def run(self, query, **params):
        self.calls.append((query, params))
        return _FakeResult(record=self._record, rows=self._rows)


class _FakeDriver:
    def __init__(self, session):
        self._session = session

    def session(self):
        return self._session


class CreateActorBotFlag(unittest.TestCase):
    def test_bot_true_is_set_on_actor_node(self):
        session = _FakeSession(
            record={"uuid": "actor-1", "name": "Cursor Agent (봇)", "aliases": ["LINEAR:agent-1"]}
        )
        driver = _FakeDriver(session)

        with patch("graph.actor_store.get_driver", return_value=driver):
            asyncio.run(_create_actor("proj-1", "LINEAR:agent-1", "Cursor Agent", None, bot=True))

        query, params = session.calls[0]
        self.assertIn("bot: $bot", query)
        self.assertTrue(params["bot"])

    def test_bot_defaults_to_false_for_backward_compatible_callers(self):
        session = _FakeSession(
            record={"uuid": "actor-1", "name": "Younghee Kim", "aliases": ["GITHUB:se-zero"]}
        )
        driver = _FakeDriver(session)

        with patch("graph.actor_store.get_driver", return_value=driver):
            asyncio.run(_create_actor("proj-1", "GITHUB:se-zero", "Younghee Kim", None))

        _query, params = session.calls[0]
        self.assertFalse(params["bot"])


class LookupByEmailExcludesBotActor(unittest.TestCase):
    def test_where_excludes_bot_actor(self):
        session = _FakeSession(record=None)
        driver = _FakeDriver(session)

        with patch("graph.actor_store.get_driver", return_value=driver):
            asyncio.run(_lookup_actor_by_email("proj-1", "kim@example.com"))

        query, _params = session.calls[0]
        self.assertIn("WHERE a.bot IS NULL OR a.bot = false", query)


class LookupByNameExcludesBotActor(unittest.TestCase):
    def test_where_excludes_bot_actor(self):
        session = _FakeSession(rows=[])
        driver = _FakeDriver(session)

        with patch("graph.actor_store.get_driver", return_value=driver):
            asyncio.run(_lookup_actor_by_name("proj-1", "cursoragent"))

        query, _params = session.calls[0]
        self.assertIn("WHERE a.bot IS NULL OR a.bot = false", query)


if __name__ == "__main__":
    unittest.main()
