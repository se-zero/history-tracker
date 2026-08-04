"""_merge_actor·_create_actor의 closed(계정 폐쇄로 영구 삭제) 부활 방지 가드 계약 테스트
(오프라인 — Neo4j driver fake 주입).

pd_erased='closed'는 절대 되살아나면 안 되는 상태다. resolve_actor Step 0 가드(alias 조회 후
갱신 여부 판단)와 privacy.py의 refresh WHERE절이 1차 방어선이지만, ALIAS_OF 엣지가 없는 alias는
Step 0 lookup 자체가 미스 나 _merge_actor/_create_actor의 MERGE가 그대로 잡을 수 있다.
되돌릴 수 없는 데이터라 쓰기 지점(store 계층)에도 CASE 가드를 심어, closed alias에는 pd_*
프로퍼티를 일절 쓰지 않는다 — al.source는 개인정보가 아닌 구조 정보라 예외로 무조건 SET한다.
"""

import asyncio
import unittest
from unittest.mock import patch

from graph.actor_store import _create_actor, _merge_actor


class _FakeResult:
    def __init__(self, record=None):
        self._record = record

    async def single(self):
        return self._record


class _FakeSession:
    """호출된 (query, params)를 전부 기록하고 항상 같은 레코드를 돌려주는 fake 세션."""

    def __init__(self, record=None):
        self._record = record
        self.calls: list[tuple[str, dict]] = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return None

    async def run(self, query, **params):
        self.calls.append((query, params))
        return _FakeResult(self._record)


class _FakeDriver:
    def __init__(self, session):
        self._session = session

    def session(self):
        return self._session


class MergeActorClosedGuard(unittest.TestCase):
    def test_pd_fields_conditioned_on_closed(self):
        """pd_* SET 전체가 al.pd_erased='closed'면 건너뛰는 CASE로 감싸져 있다."""
        session = _FakeSession()
        driver = _FakeDriver(session)

        with patch("graph.actor_store.get_driver", return_value=driver), \
             patch("graph.actor_store.recompute_display_name"):
            asyncio.run(
                _merge_actor(
                    {"uuid": "actor-1"}, "JIRA:5b10a2", "kim@example.com", "김영희"
                )
            )

        query, _params = session.calls[0]
        self.assertIn("SET al += CASE WHEN al.pd_erased = 'closed' THEN {} ELSE {", query)
        self.assertIn("pd_name: $new_name", query)
        self.assertIn("pd_erased: null", query)
        self.assertIn("} END", query)

    def test_source_set_unconditionally_outside_case(self):
        """al.source는 개인정보가 아닌 구조 정보라 CASE 밖에서 무조건 SET한다."""
        session = _FakeSession()
        driver = _FakeDriver(session)

        with patch("graph.actor_store.get_driver", return_value=driver), \
             patch("graph.actor_store.recompute_display_name"):
            asyncio.run(
                _merge_actor(
                    {"uuid": "actor-1"}, "JIRA:5b10a2", "kim@example.com", "김영희"
                )
            )

        query, _params = session.calls[0]
        self.assertIn("SET al.source = $source", query)
        # al.source SET이 CASE...END 블록 뒤에(밖에) 있어야 한다.
        self.assertLess(query.index("} END"), query.index("SET al.source = $source"))


class CreateActorClosedGuard(unittest.TestCase):
    def test_pd_fields_conditioned_on_closed(self):
        """pd_* SET 전체가 al.pd_erased='closed'면 건너뛰는 CASE로 감싸져 있다."""
        session = _FakeSession(record={"uuid": "actor-1", "name": "김영희", "aliases": ["JIRA:5b10a2"]})
        driver = _FakeDriver(session)

        with patch("graph.actor_store.get_driver", return_value=driver):
            asyncio.run(_create_actor("proj-1", "JIRA:5b10a2", "김영희", "kim@example.com"))

        query, _params = session.calls[0]
        self.assertIn("SET al += CASE WHEN al.pd_erased = 'closed' THEN {} ELSE {", query)
        self.assertIn("pd_name: $name", query)
        self.assertIn("pd_erased: null", query)
        self.assertIn("} END", query)

    def test_source_set_unconditionally_outside_case(self):
        """al.source는 개인정보가 아닌 구조 정보라 CASE 밖에서 무조건 SET한다."""
        session = _FakeSession(record={"uuid": "actor-1", "name": "김영희", "aliases": ["JIRA:5b10a2"]})
        driver = _FakeDriver(session)

        with patch("graph.actor_store.get_driver", return_value=driver):
            asyncio.run(_create_actor("proj-1", "JIRA:5b10a2", "김영희", "kim@example.com"))

        query, _params = session.calls[0]
        self.assertIn("SET al.source = $source", query)
        self.assertLess(query.index("} END"), query.index("SET al.source = $source"))


if __name__ == "__main__":
    unittest.main()
