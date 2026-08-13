"""Actor 관리 목록/상세 조회 단위 테스트 (오프라인 — Neo4j driver fake 주입).

"관리 화면은 동일인 판단에 쓰이는 것만 보여준다" 원칙을 코드 계약으로 고정한다:
- list_actors(목록)는 "같은 사람인가" 판단 재료(소스별 이름)만 내려준다 — 이메일·계정ID 없음
- get_actor_detail(병합·분리 폼)에서만 이메일·계정ID(source_id)를 내려준다 — 획득/보고 시각은 제외

Cypher의 정렬·집계 자체는 여기서 검증할 수 없다(FakeDriver는 쿼리와 무관하게 canned 값을 준다) —
쿼리 문자열에 의도한 절이 있는지, 그리고 함수가 반환값에 금지된 키를 섞지 않는지만 확인한다.
"""

import asyncio
import unittest
from unittest.mock import patch

from graph.actor_admin import get_actor_detail, list_actors


class _FakeDataResult:
    def __init__(self, rows):
        self._rows = rows

    async def data(self):
        return self._rows


class _FakeSingleResult:
    def __init__(self, record):
        self._record = record

    async def single(self):
        return self._record


class _FakeSession:
    """호출된 (query, params)를 기록하고, 지정된 canned 결과를 돌려주는 fake 세션.

    list_actors는 result.data()를, get_actor_detail은 result.single()을 쓰므로
    어느 쪽을 감쌀지 result_factory로 주입받는다.
    """

    def __init__(self, result_factory, canned):
        self._result_factory = result_factory
        self._canned = canned
        self.last_query = None
        self.last_params = None

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return None

    async def run(self, query, **params):
        self.last_query = query
        self.last_params = params
        return self._result_factory(self._canned)


class _FakeDriver:
    def __init__(self, result_factory, canned):
        self.session_obj = _FakeSession(result_factory, canned)

    def session(self):
        return self.session_obj


class ListActors(unittest.TestCase):
    def test_query_collects_source_names_and_drops_aliases_array(self):
        driver = _FakeDriver(_FakeDataResult, [])

        with patch("graph.actor_admin.get_driver", return_value=driver):
            asyncio.run(list_actors("p1"))

        query = driver.session_obj.last_query
        self.assertIn("ActorAlias", query)
        self.assertIn("ALIAS_OF", query)
        self.assertIn("source_names", query)
        self.assertNotIn("a.aliases", query)

    def test_returned_actor_has_no_aliases_emails_or_confidence_keys(self):
        rows = [
            {
                "uuid": "a1",
                "name": "Younghee Kim",
                "activity_count": 5,
                "source_names": [
                    {"source": "GITHUB", "name": "Younghee Kim", "erased": None},
                    {"source": "JIRA", "name": None, "erased": "closed"},
                ],
            }
        ]
        driver = _FakeDriver(_FakeDataResult, rows)

        with patch("graph.actor_admin.get_driver", return_value=driver):
            result = asyncio.run(list_actors("p1"))

        self.assertEqual(result, rows)
        for actor in result:
            self.assertNotIn("aliases", actor)
            self.assertNotIn("emails", actor)
            self.assertNotIn("confidence", actor)


class GetActorDetail(unittest.TestCase):
    def test_missing_actor_raises_lookup_error(self):
        driver = _FakeDriver(_FakeSingleResult, None)

        with patch("graph.actor_admin.get_driver", return_value=driver):
            with self.assertRaises(LookupError):
                asyncio.run(get_actor_detail("p1", "missing"))

    def test_detail_includes_email_and_source_id_but_not_report_timestamps(self):
        record = {
            "uuid": "a1",
            "name": "Younghee Kim",
            "aliases": [
                {
                    "source_id": "GITHUB:se-zero",
                    "source": "GITHUB",
                    "name": "Younghee Kim",
                    "email": None,
                    "erased": None,
                },
                {
                    "source_id": "JIRA:5b10a2",
                    "source": "JIRA",
                    "name": "김영희",
                    "email": "yh@corp.com",
                    "erased": None,
                },
            ],
        }
        driver = _FakeDriver(_FakeSingleResult, record)

        with patch("graph.actor_admin.get_driver", return_value=driver):
            result = asyncio.run(get_actor_detail("p1", "a1"))

        self.assertNotIn("pd_updated_at", result)
        self.assertNotIn("pd_reported_at", result)
        jira_alias = next(a for a in result["aliases"] if a["source"] == "JIRA")
        self.assertEqual(jira_alias["source_id"], "JIRA:5b10a2")
        self.assertEqual(jira_alias["email"], "yh@corp.com")
        for alias in result["aliases"]:
            self.assertNotIn("pd_updated_at", alias)
            self.assertNotIn("pd_reported_at", alias)

    def test_null_aliases_are_filtered_out(self):
        record = {"uuid": "a1", "name": "Younghee Kim", "aliases": [None]}
        driver = _FakeDriver(_FakeSingleResult, record)

        with patch("graph.actor_admin.get_driver", return_value=driver):
            result = asyncio.run(get_actor_detail("p1", "a1"))

        self.assertEqual(result["aliases"], [])


class ListActorsBotFlag(unittest.TestCase):
    def test_query_returns_bot_flag_via_coalesce(self):
        """봇 격리 — 관리 목록 조회가 bot 플래그를 노출한다(레거시 Actor는 coalesce로 false)."""
        driver = _FakeDriver(_FakeDataResult, [])

        with patch("graph.actor_admin.get_driver", return_value=driver):
            asyncio.run(list_actors("p1"))

        query = driver.session_obj.last_query
        self.assertIn("coalesce(a.bot, false) AS bot", query)

    def test_bot_flag_passed_through_in_result(self):
        rows = [
            {
                "uuid": "a1", "name": "Cursor Agent (봇)", "bot": True,
                "activity_count": 3, "source_names": [],
            }
        ]
        driver = _FakeDriver(_FakeDataResult, rows)

        with patch("graph.actor_admin.get_driver", return_value=driver):
            result = asyncio.run(list_actors("p1"))

        self.assertTrue(result[0]["bot"])


class GetActorDetailBotFlag(unittest.TestCase):
    def test_bot_flag_included_when_present(self):
        record = {"uuid": "a1", "name": "Cursor Agent (봇)", "bot": True, "aliases": []}
        driver = _FakeDriver(_FakeSingleResult, record)

        with patch("graph.actor_admin.get_driver", return_value=driver):
            result = asyncio.run(get_actor_detail("p1", "a1"))

        self.assertTrue(result["bot"])

    def test_bot_flag_defaults_false_when_absent_from_legacy_result(self):
        """레거시 fake/구버전 응답처럼 bot 키가 아예 없어도 안전하게 false로 취급한다."""
        record = {"uuid": "a1", "name": "Younghee Kim", "aliases": []}
        driver = _FakeDriver(_FakeSingleResult, record)

        with patch("graph.actor_admin.get_driver", return_value=driver):
            result = asyncio.run(get_actor_detail("p1", "a1"))

        self.assertFalse(result["bot"])


if __name__ == "__main__":
    unittest.main()
