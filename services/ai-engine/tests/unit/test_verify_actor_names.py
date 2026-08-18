"""Actor 이름 정합성 검증(읽기 전용) 단위 테스트 (오프라인 — Neo4j driver fake 주입).

verify_actor_name_consistency가 개인정보 삭제 불변식("alias 비우기 + Actor.name 재계산"이
한 트랜잭션이어야 함)의 조용한 붕괴를 잡아내는 감사 함수임을 고정한다:
- 쓰기 없음(SET/DELETE/MERGE/CREATE 전부 부재) — 이 함수의 정체성이자 존재 이유
- 기대값 계산은 derive_display_name을 실제로 태워 recompute_display_name과 같은 결과를 낸다
  (mock하면 두 계산이 갈라져도 테스트가 못 잡는다)
- manual_name=true Actor는 alias가 무엇이든 불일치로 잡히지 않는다
- alias가 전부 빈 Actor의 기대값은 "(삭제된 사용자)"
- project_id 스코프가 Actor 목록 쿼리에 전달된다
"""

import asyncio
import re
import unittest
from unittest.mock import patch

from graph.maintenance import verify_actor_name_consistency

_FORBIDDEN_WRITE_KEYWORDS = re.compile(r"\b(SET|DELETE|MERGE|CREATE)\b")


class _FakeResult:
    """단일 레코드(.single())와 행 목록(.data()) 둘 다 지원하는 fake 결과.

    verify_actor_name_consistency는 Actor 목록 조회에 .data()를 쓰고, 그 안에서 재사용하는
    _compute_display_name은 alias 조회에 .single()을, 활동량 조회에 .data()를 쓴다.
    """

    def __init__(self, record=None, rows=None):
        self._record = record
        self._rows = rows if rows is not None else []

    async def single(self):
        return self._record

    async def data(self):
        return self._rows


class _FakeSession:
    """호출 순서대로 canned 결과를 소비하며 실행된 (query, params)를 전부 기록한다."""

    def __init__(self, results):
        self._results = list(results)
        self.queries: list[tuple[str, dict]] = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return None

    async def run(self, query, **params):
        self.queries.append((query, params))
        return self._results.pop(0)


class _FakeDriver:
    def __init__(self, session):
        self._session = session

    def session(self):
        return self._session


def _actor_results(actors):
    """Actor 목록 조회 1개 + Actor마다 (alias 상세, outgoing 활동, incoming 활동) 3개.

    verify_actor_name_consistency → _compute_display_name의 실제 쿼리 순서를 그대로 흉내낸다.
    이 테스트의 모든 케이스는 활동량 비교 없이(GitHub 프로필/전원 무이름) 결정되는 alias
    구성만 쓰므로 활동량 쿼리는 항상 빈 결과로 둔다.
    """
    results = [_FakeResult(rows=[
        {"uuid": a["uuid"], "project_id": a["project_id"], "name": a["name"]} for a in actors
    ])]
    for a in actors:
        results.append(_FakeResult(record={
            "manual_name": a["manual_name"], "name": a["name"], "aliases": a["aliases"],
        }))
        results.append(_FakeResult(rows=[]))  # outgoing
        results.append(_FakeResult(rows=[]))  # incoming
    return results


def _run(actors, project_id=None):
    session = _FakeSession(_actor_results(actors))
    with patch("graph.maintenance.get_driver", return_value=_FakeDriver(session)):
        result = asyncio.run(verify_actor_name_consistency(project_id))
    return result, session


class NoWrites(unittest.TestCase):
    def test_no_write_keywords_in_any_query(self):
        actors = [
            {
                "uuid": "a1", "project_id": "p1", "name": "Old Name", "manual_name": False,
                "aliases": [{"source_id": "GITHUB:se-zero", "source": "GITHUB", "pd_name": "New Name"}],
            },
        ]
        _result, session = _run(actors)

        self.assertTrue(session.queries)
        for query, _params in session.queries:
            self.assertIsNone(_FORBIDDEN_WRITE_KEYWORDS.search(query), query)


class MismatchDetection(unittest.TestCase):
    def test_mismatched_actor_is_reported_with_uuid_project_and_expected(self):
        actors = [
            {
                "uuid": "a1", "project_id": "p1", "name": "Old Name", "manual_name": False,
                "aliases": [{"source_id": "GITHUB:se-zero", "source": "GITHUB", "pd_name": "New Name"}],
            },
        ]
        result, _session = _run(actors)

        self.assertEqual(result["checked"], 1)
        self.assertEqual(result["mismatches"], [
            {"uuid": "a1", "project_id": "p1", "name": "Old Name", "expected": "New Name"},
        ])

    def test_matching_actor_is_not_reported_but_is_checked(self):
        actors = [
            {
                "uuid": "a1", "project_id": "p1", "name": "Same Name", "manual_name": False,
                "aliases": [{"source_id": "GITHUB:se-zero", "source": "GITHUB", "pd_name": "Same Name"}],
            },
        ]
        result, _session = _run(actors)

        self.assertEqual(result["checked"], 1)
        self.assertEqual(result["mismatches"], [])

    def test_manual_name_actor_is_never_flagged(self):
        # manual_name=true면 alias가 전혀 다른 이름을 가리켜도 현재 이름이 곧 기대값이다.
        actors = [
            {
                "uuid": "a1", "project_id": "p1", "name": "김영희(전 PM)", "manual_name": True,
                "aliases": [{"source_id": "GITHUB:se-zero", "source": "GITHUB", "pd_name": "Younghee Kim"}],
            },
        ]
        result, _session = _run(actors)

        self.assertEqual(result["mismatches"], [])

    def test_all_aliases_empty_expects_deleted_user_label(self):
        actors = [
            {
                "uuid": "a1", "project_id": "p1", "name": "(삭제된 사용자)", "manual_name": False,
                "aliases": [{"source_id": "JIRA:5b10a2", "source": "JIRA", "pd_name": None}],
            },
        ]
        result, _session = _run(actors)

        self.assertEqual(result["mismatches"], [])

    def test_project_id_is_passed_to_actor_list_query(self):
        actors = [
            {
                "uuid": "a1", "project_id": "p1", "name": "Same Name", "manual_name": False,
                "aliases": [{"source_id": "GITHUB:se-zero", "source": "GITHUB", "pd_name": "Same Name"}],
            },
        ]
        _result, session = _run(actors, project_id="p1")

        list_query, list_params = session.queries[0]
        self.assertEqual(list_params["project_id"], "p1")
        self.assertIn("a.project_id = $project_id", list_query)


if __name__ == "__main__":
    unittest.main()
