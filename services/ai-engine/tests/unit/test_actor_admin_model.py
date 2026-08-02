"""Actor 신원 모델 개편(2/3) — actor_admin의 방향 자동화·split name 파라미터 단위 테스트.

Neo4j 없이 오프라인으로 검증한다(docs/actor-identity-model.md, docs/jira-personal-data-plan.md A-6):
- `_pick_canonical`: 병합 방향을 정하는 순수 함수 — 활동 많은 쪽 승리, 동수면 uuid 사전순,
  인자 순서를 바꿔도 결과가 같아야 한다(호출자의 uuid_a/uuid_b 순서가 결과를 바꾸면 안 된다).
- `split_alias`의 `name` 파라미터 — 주어지면 manual_name=true·name_updated_at을 포함한
  3필드를 SET하는지 FakeTx로 파라미터 수준까지 확인한다.
"""

import asyncio
import unittest
from unittest.mock import patch

from graph.actor_admin import _pick_canonical, split_alias


class PickCanonical(unittest.TestCase):
    def test_more_activity_wins(self):
        self.assertEqual(_pick_canonical(5, 2, "a", "b"), ("a", "b"))
        self.assertEqual(_pick_canonical(2, 5, "a", "b"), ("b", "a"))

    def test_tie_breaks_by_uuid_lexicographic_order(self):
        self.assertEqual(_pick_canonical(3, 3, "b-uuid", "a-uuid"), ("a-uuid", "b-uuid"))

    def test_argument_order_does_not_change_result(self):
        """uuid_a/uuid_b를 바꿔 호출해도 (승자, 패자) 조합 자체는 같아야 결정적이다."""
        self.assertEqual(_pick_canonical(1, 4, "x", "y"), _pick_canonical(4, 1, "y", "x"))


# ── split_alias의 name 파라미터 — FakeTx 파라미터 검증 ────────────────────


class _FakeResult:
    def __init__(self, record):
        self._record = record

    async def single(self):
        return self._record


class _RecordingTx:
    """호출된 (query, params)를 전부 기록하고, 등록된 마커에 해당하는 쿼리만 값 있는 결과를 준다.

    split_alias는 한 트랜잭션에서 여러 쿼리를 순차 실행하지만, 이 테스트가 검증하려는 것은
    "name을 주면 manual_name=true·name_updated_at까지 SET하는가" 하나뿐이다. 그래서 흐름
    전체를 재현하지 않고, 코드가 실제로 결과값을 읽는 쿼리(액터 조회·최종 이름 조회)만
    canned 값을 주고 나머지는 빈 결과(단일 조회 None)로 둔다 — 어차피 결과를 안 쓰는 쓰기
    쿼리들이라 무해하다.
    """

    def __init__(self, responses: dict):
        self._responses = responses
        self.calls: list[tuple[str, dict]] = []

    async def run(self, query, **params):
        self.calls.append((query, params))
        for marker, record in self._responses.items():
            if marker in query:
                return _FakeResult(record)
        return _FakeResult(None)


class _FakeSession:
    def __init__(self, tx: _RecordingTx):
        self.tx = tx

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return None

    async def execute_write(self, fn):
        return await fn(self.tx)


class _FakeDriver:
    def __init__(self, tx: _RecordingTx):
        self.session_obj = _FakeSession(tx)

    def session(self):
        return self.session_obj


class SplitAliasNameParameter(unittest.TestCase):
    def test_name_given_sets_manual_name_and_timestamp(self):
        tx = _RecordingTx(
            {
                "RETURN a.uuid AS uuid, a.name AS name, a.aliases AS aliases": {
                    "uuid": "actor-1", "name": "Mixed Actor", "aliases": ["GITHUB:a", "GITHUB:c"],
                },
                "RETURN b.name AS name": {"name": "Slack Actor"},
            }
        )
        driver = _FakeDriver(tx)

        with patch("graph.actor_admin.get_driver", return_value=driver):
            result = asyncio.run(split_alias("p1", "actor-1", ["GITHUB:a"], name="Slack Actor"))

        self.assertEqual(result["new_name"], "Slack Actor")

        name_set_calls = [
            params
            for query, params in tx.calls
            if "SET b.name = $name" in query and "b.manual_name = true" in query
            and "b.name_updated_at = datetime()" in query
        ]
        self.assertEqual(len(name_set_calls), 1, "3필드를 한 번에 SET하는 쿼리가 정확히 1번 호출돼야 한다")
        self.assertEqual(name_set_calls[0]["name"], "Slack Actor")

    def test_name_omitted_skips_manual_set(self):
        tx = _RecordingTx(
            {
                "RETURN a.uuid AS uuid, a.name AS name, a.aliases AS aliases": {
                    "uuid": "actor-1", "name": "Mixed Actor", "aliases": ["GITHUB:a", "GITHUB:c"],
                },
                "RETURN b.name AS name": {"name": "se-zero"},
            }
        )
        driver = _FakeDriver(tx)

        with patch("graph.actor_admin.get_driver", return_value=driver):
            asyncio.run(split_alias("p1", "actor-1", ["GITHUB:a"]))

        name_set_calls = [
            (query, params) for query, params in tx.calls if "b.manual_name = true" in query
        ]
        self.assertEqual(name_set_calls, [], "name을 생략하면 manual_name SET이 호출되면 안 된다")


if __name__ == "__main__":
    unittest.main()
