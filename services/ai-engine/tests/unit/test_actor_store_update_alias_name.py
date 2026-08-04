"""_update_alias_name(Step 0 이름 갱신 콜백) 쿼리 계약 단위 테스트 (오프라인 — Neo4j driver fake 주입).

graph/actor_store.py의 _update_alias_name이 지켜야 하는 세 가지 대칭성을 고정한다:
- al.source SET — _merge_actor(292 부근)·_create_actor(342 부근)와 동일하게 source_id 접두어로
  유도해 SET한다. 없으면 backfill_actor_aliases(graph/maintenance.py)가 만든 bare alias의
  source가 영구 null로 남아 graph/privacy.py의 al.source='JIRA' 필터에서 누락된다.
- pd_email의 coalesce — access_lost 자동 복구 경로(resolve_actor Step 0)가 이벤트에 email이
  없을 때(null) 기존 pd_email을 지우지 않는다.
- closed 부활 방지 — resolve_actor Step 0가 이미 걸러내지만, 심층 방어로 store 계층에서도
  pd_erased='closed' alias를 MATCH 이후 WHERE로 한 번 더 차단한다.
"""

import asyncio
import unittest
from unittest.mock import patch

from graph.actor_store import _update_alias_name


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


class UpdateAliasNameQuery(unittest.TestCase):
    def test_sets_source_from_source_id_prefix(self):
        """al.source 대칭 — _merge_actor/_create_actor와 동일하게 SET한다."""
        session = _FakeSession(record={"uuid": "actor-1"})
        driver = _FakeDriver(session)

        with patch("graph.actor_store.get_driver", return_value=driver), \
             patch("graph.actor_store.recompute_display_name"):
            asyncio.run(_update_alias_name("proj-1", "JIRA:5b10a2", "김영희", None))

        query, params = session.calls[0]
        self.assertIn("al.source = $source", query)
        self.assertEqual(params["source"], "JIRA")

    def test_coalesces_pd_email_instead_of_overwriting(self):
        """이벤트에 email이 없어도(None) 기존 pd_email을 지우지 않는다."""
        session = _FakeSession(record={"uuid": "actor-1"})
        driver = _FakeDriver(session)

        with patch("graph.actor_store.get_driver", return_value=driver), \
             patch("graph.actor_store.recompute_display_name"):
            asyncio.run(_update_alias_name("proj-1", "JIRA:5b10a2", "김영희", None))

        query, params = session.calls[0]
        self.assertIn("al.pd_email = coalesce($email, al.pd_email)", query)
        self.assertIsNone(params["email"])

    def test_where_excludes_closed_alias(self):
        """MATCH 뒤 WHERE로 pd_erased='closed' alias를 걸러 store 계층에서도 부활을 막는다."""
        session = _FakeSession(record={"uuid": "actor-1"})
        driver = _FakeDriver(session)

        with patch("graph.actor_store.get_driver", return_value=driver), \
             patch("graph.actor_store.recompute_display_name"):
            asyncio.run(_update_alias_name("proj-1", "JIRA:5b10a2", "김영희", None))

        query, _params = session.calls[0]
        self.assertIn("WHERE al.pd_erased IS NULL OR al.pd_erased <> 'closed'", query)


if __name__ == "__main__":
    unittest.main()
