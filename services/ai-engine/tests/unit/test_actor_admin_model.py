"""Actor 신원 모델 개편(2/3) — actor_admin의 방향 자동화·unmerge 재배치 가드 단위 테스트.

Neo4j 없이 오프라인으로 검증한다:
- `_pick_canonical`: 병합 방향을 정하는 순수 함수 — 활동 많은 쪽 승리, 동수면 uuid 사전순,
  인자 순서를 바꿔도 결과가 같아야 한다(호출자의 uuid_a/uuid_b 순서가 결과를 바꾸면 안 된다).
- `split_alias`는 표시 이름을 입력받지 않는다(병합과 대칭) — manual_name SET이 호출되지
  않는지 FakeTx로 회귀 방지한다.
- `unmerge_actors`의 재배치 가드 — 병합 후 그 alias가 분리로 다른 Actor에 옮겨진 경우
  canonical에 더 이상 안 붙어 있는(movable 없음) alias는 복원을 거부하고, 일부만
  재배치됐으면(movable 일부) 그 alias만으로 복원하는지 FakeTx로 확인한다.
- `merge_actors`가 병합 대상 두 액터 사이의 기존 distinct 결정을 자동 삭제하는지 —
  재연동 시 veto가 canonical(자기 자신)을 막아 다시 쪼개지는 걸 막는 방침(docs/actor-manual-merge.md).
"""

import asyncio
import unittest
from unittest.mock import patch

from graph.actor_admin import _pick_canonical, merge_actors, split_alias, unmerge_actors


class PickCanonical(unittest.TestCase):
    def test_more_activity_wins(self):
        self.assertEqual(_pick_canonical(5, 2, "a", "b"), ("a", "b"))
        self.assertEqual(_pick_canonical(2, 5, "a", "b"), ("b", "a"))

    def test_tie_breaks_by_uuid_lexicographic_order(self):
        self.assertEqual(_pick_canonical(3, 3, "b-uuid", "a-uuid"), ("a-uuid", "b-uuid"))

    def test_argument_order_does_not_change_result(self):
        """uuid_a/uuid_b를 바꿔 호출해도 (승자, 패자) 조합 자체는 같아야 결정적이다."""
        self.assertEqual(_pick_canonical(1, 4, "x", "y"), _pick_canonical(4, 1, "y", "x"))


# ── FakeTx 공용 헬퍼 — split_alias·unmerge_actors 공통 사용 ────────────────


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

    응답값은 고정 dict 또는 callable(params) -> dict|None 둘 다 지원한다 — merge_actors는
    같은 쿼리 텍스트(액터 조회, activity count)를 uuid_a/uuid_b 각각에 대해 두 번 실행하므로
    마커 문자열만으로는 구분할 수 없고, params를 보고 갈라줘야 한다.
    """

    def __init__(self, responses: dict):
        self._responses = responses
        self.calls: list[tuple[str, dict]] = []

    async def run(self, query, **params):
        self.calls.append((query, params))
        for marker, record in self._responses.items():
            if marker in query:
                return _FakeResult(record(params) if callable(record) else record)
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


class SplitAliasHasNoNameParameter(unittest.TestCase):
    def test_split_never_sets_manual_name(self):
        """분리는 표시 이름을 입력받지 않는다(병합과 대칭) — manual_name SET이 전혀 호출되면 안 된다.

        표시 이름은 recompute_display_name이 alias 기준으로 정한다 — 직접 정하려면 rename_actor.
        """
        tx = _RecordingTx(
            {
                "RETURN a.uuid AS uuid, a.name AS name, a.aliases AS aliases": {
                    "uuid": "actor-1", "name": "Mixed Actor", "aliases": ["GITHUB:a", "GITHUB:c"],
                },
                "RETURN b.name AS name": {"name": "Mixed Actor"},
            }
        )
        driver = _FakeDriver(tx)

        with patch("graph.actor_admin.get_driver", return_value=driver):
            asyncio.run(split_alias("p1", "actor-1", ["GITHUB:a"]))

        name_set_calls = [
            (query, params) for query, params in tx.calls if "manual_name = true" in query
        ]
        self.assertEqual(name_set_calls, [], "split_alias는 manual_name을 SET하면 안 된다")


# ── unmerge_actors — 분리로 재배치된 계정 가드 ─────────────────────────────
#
# 실사용 버그: 병합 후 그 alias를 분리로 다른 Actor에 옮긴 상태에서 병합을 취소하면,
# canonical에 더 이상 안 붙어 있는 alias까지 복원하려다 빈 Actor를 만들게 된다.


class UnmergeMovableGuard(unittest.TestCase):
    def test_all_aliases_relocated_raises_value_error(self):
        """병합으로 넘어온 alias가 전부 분리로 재배치됐으면(movable 없음) 복원을 거부한다."""
        tx = _RecordingTx(
            {
                "d.merged_uuid AS merged_uuid": {
                    "canonical_uuid": "canon-1", "merged_uuid": "merged-1",
                    "aliases_b": ["GITHUB:a", "GITHUB:b"],
                },
                "RETURN a.uuid AS uuid, a.name AS name, a.aliases AS aliases": {
                    "uuid": "canon-1", "name": "Canonical", "aliases": ["GITHUB:a", "GITHUB:x"],
                },
                "RETURN collect(al.source_id) AS movable": {"movable": []},
            }
        )
        driver = _FakeDriver(tx)

        with patch("graph.actor_admin.get_driver", return_value=driver):
            with self.assertRaises(ValueError):
                asyncio.run(unmerge_actors("p1", "decision-1"))

    def test_partial_relocation_restores_only_movable_aliases(self):
        """일부만 재배치됐으면(movable 일부) 나머지만으로 복원하고, canonical 차감·자동 생성
        distinct 결정의 aliases_b도 movable 기준이어야 한다(전체 aliases_b가 아니라)."""
        tx = _RecordingTx(
            {
                "d.merged_uuid AS merged_uuid": {
                    "canonical_uuid": "canon-1", "merged_uuid": "merged-1",
                    "aliases_b": ["GITHUB:a", "GITHUB:b"],
                },
                "RETURN a.uuid AS uuid, a.name AS name, a.aliases AS aliases": {
                    "uuid": "canon-1", "name": "Canonical", "aliases": ["GITHUB:a", "GITHUB:x"],
                },
                "RETURN collect(al.source_id) AS movable": {"movable": ["GITHUB:a"]},
                "RETURN count(*) AS n": {"n": 0},
            }
        )
        driver = _FakeDriver(tx)

        with patch("graph.actor_admin.get_driver", return_value=driver):
            asyncio.run(unmerge_actors("p1", "decision-1"))

        create_calls = [p for q, p in tx.calls if q.strip().startswith("CREATE (b:Actor")]
        self.assertEqual(
            create_calls[0]["aliases"], ["GITHUB:a"], "복원 Actor의 aliases는 movable만이어야 함"
        )

        alias_of_calls = [p for q, p in tx.calls if "MERGE (al)-[:ALIAS_OF]->(b)" in q]
        self.assertEqual(alias_of_calls[0]["movable"], ["GITHUB:a"])

        canonical_set_calls = [p for q, p in tx.calls if "SET a.aliases = $aliases" in q]
        self.assertEqual(
            canonical_set_calls[0]["aliases"], ["GITHUB:x"],
            "canonical에는 재배치 안 된 alias만 남아야 함",
        )

        distinct_calls = [p for q, p in tx.calls if "kind: 'distinct'" in q]
        self.assertEqual(
            distinct_calls[0]["aliases_b"], ["GITHUB:a"],
            "자동 생성 distinct 결정의 aliases_b도 movable 기준이어야 함",
        )


# ── merge_actors — 반대 방향 distinct 결정 자동 삭제 ────────────────────────
#
# 실사용 버그: 수동 병합 후에도 두 액터 사이의 기존 distinct(다른 사람) 결정이 남아 있으면,
# 재연동 시 resolver veto 조회가 그 결정을 보고 canonical(자기 자신)을 차단해 방금 합친
# 사람이 다시 쪼개진다. 수동 병합은 이전 분리 결정을 명시적으로 뒤집는 행위이므로,
# 병합 트랜잭션 안에서 반대 방향 distinct를 자동 삭제한다(unmerge_actors가 same 결정을
# 지우는 선례와 대칭).


class MergeActorsRemovesOpposingDistinctDecision(unittest.TestCase):
    def test_merge_deletes_distinct_between_the_two_actors_and_still_creates_same(self):
        def _actor_record(params):
            uuid = params.get("uuid")
            if uuid == "actor-a":
                return {"uuid": "actor-a", "name": "A", "aliases": ["GITHUB:a1"]}
            if uuid == "actor-b":
                return {"uuid": "actor-b", "name": "B", "aliases": ["GITHUB:b1"]}
            return None

        def _activity_record(params):
            # actor-a가 활동이 더 많아 canonical로 뽑혀야 한다(회귀 방지).
            count = 5 if params.get("actor_uuid") == "actor-a" else 2
            return {"activity_count": count}

        tx = _RecordingTx(
            {
                "RETURN a.uuid AS uuid, a.name AS name, a.aliases AS aliases": _actor_record,
                "RETURN authored + assigned AS activity_count": _activity_record,
                "RETURN count(*) AS n": {"n": 0},
            }
        )
        driver = _FakeDriver(tx)

        with patch("graph.actor_admin.get_driver", return_value=driver):
            summary = asyncio.run(merge_actors("p1", "actor-a", "actor-b"))

        # 회귀 방지: 병합 방향 결정과 same 결정 생성은 여전히 일어나야 한다.
        self.assertEqual(summary["canonical_uuid"], "actor-a")
        self.assertEqual(summary["merged_uuid"], "actor-b")

        same_calls = [p for q, p in tx.calls if "kind: 'same'" in q]
        self.assertEqual(len(same_calls), 1, "same 결정 생성이 여전히 일어나야 한다")
        self.assertEqual(same_calls[0]["canonical_uuid"], "actor-a")
        self.assertEqual(same_calls[0]["merged_uuid"], "actor-b")

        distinct_delete_calls = [(q, p) for q, p in tx.calls if "kind: 'distinct'" in q]
        self.assertEqual(len(distinct_delete_calls), 1, "반대 방향 distinct 삭제 쿼리가 실행돼야 한다")
        delete_query, delete_params = distinct_delete_calls[0]
        self.assertIn("DELETE", delete_query)
        self.assertEqual(delete_params["aliases_a"], ["GITHUB:a1"], "canonical의 병합 전 aliases여야 함")
        self.assertEqual(delete_params["aliases_b"], ["GITHUB:b1"], "merged의 병합 전 aliases여야 함")


if __name__ == "__main__":
    unittest.main()
