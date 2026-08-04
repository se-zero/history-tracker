"""개인정보 보고 대상 조회(list_due_accounts) 단위 테스트 (오프라인 — Neo4j driver fake 주입).

graph/privacy.py의 계약을 코드로 고정한다:
- accountId 접두어("JIRA:")는 substring으로 벗긴다 — split(':')는 Atlassian accountId에
  포함된 콜론(예: "712020:<uuid>")을 깨뜨리므로 절대 쓰지 않는다. 쿼리 문자열에
  'split(' 이 없다는 것과, 콜론 포함 accountId가 절단 없이 그대로 반환됨을 함께 확인한다.
- pd_updated_at은 neo4j.time.DateTime(=iso_format()을 갖는 객체)으로 올 수 있어
  ISO-8601 문자열로 직렬화한다.
- pd_updated_at이 NULL인 alias는 애초에 조회 대상에서 제외한다 — backend가
  DateTimeFormatter.ISO_INSTANT.format(null)에서 NPE로 죽는 것을 쿼리에서 막는다.
- due_before(RFC3339)·limit(1~90) 검증은 쿼리 실행 **전**에 ValueError로 던진다 — fake driver가
  아예 호출되지 않았는지까지 확인해 검증 실패 시 그래프에 손도 대지 않음을 보장한다.

Cypher의 정렬·집계 자체는 여기서 검증할 수 없다(FakeDriver는 쿼리와 무관하게 canned 값을 준다) —
tests/unit/test_actor_admin_list.py와 동일하게 쿼리 문자열의 절 존재 여부만 확인한다.

라우터 응답 형태(PrivacyJiraAccountsRoute)는 routers.privacy.list_due_accounts를 patch한다 —
routers/privacy.py가 `from graph.privacy import list_due_accounts`로 이름을 직접 들여와
바인딩하므로, graph.privacy 쪽 원본을 patch해서는 라우터가 호출하는 참조가 바뀌지 않는다.
"""

import asyncio
import unittest
from unittest.mock import AsyncMock, patch

from graph.privacy import list_due_accounts
from routers.privacy import privacy_jira_accounts


class _FakeDataResult:
    def __init__(self, rows):
        self._rows = rows

    async def data(self):
        return self._rows


class _FakeSession:
    def __init__(self, canned):
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
        return _FakeDataResult(self._canned)


class _FakeDriver:
    def __init__(self, canned):
        self.session_obj = _FakeSession(canned)

    def session(self):
        return self.session_obj


class _FakeNeo4jDateTime:
    """neo4j.time.DateTime 대역 — iso_format()만 있으면 충분하다."""

    def __init__(self, text):
        self._text = text

    def iso_format(self):
        return self._text


class ListDueAccounts(unittest.TestCase):
    def test_query_has_expected_clauses_and_no_split(self):
        driver = _FakeDriver([])

        with patch("graph.privacy.get_driver", return_value=driver):
            asyncio.run(list_due_accounts("2026-01-01T00:00:00Z"))

        query = driver.session_obj.last_query
        self.assertIn("al.source = 'JIRA'", query)
        self.assertIn("pd_erased IS NULL", query)
        self.assertIn("al.pd_updated_at IS NOT NULL", query)
        self.assertIn("min(al.pd_updated_at)", query)
        self.assertIn("substring(al.source_id, 5)", query)
        self.assertIn("LIMIT", query)
        self.assertNotIn("split(", query)

    def test_due_before_and_limit_are_passed_through_as_params(self):
        driver = _FakeDriver([])

        with patch("graph.privacy.get_driver", return_value=driver):
            asyncio.run(list_due_accounts("2026-01-01T00:00:00Z", limit=30))

        params = driver.session_obj.last_params
        self.assertEqual(params["due_before"], "2026-01-01T00:00:00Z")
        self.assertEqual(params["limit"], 30)

    def test_account_id_with_colon_is_returned_untruncated(self):
        rows = [{"account_id": "712020:abc-def", "updated_at": "2026-01-01T00:00:00Z"}]
        driver = _FakeDriver(rows)

        with patch("graph.privacy.get_driver", return_value=driver):
            result = asyncio.run(list_due_accounts("2026-01-01T00:00:00Z"))

        self.assertEqual(
            result, [{"account_id": "712020:abc-def", "updated_at": "2026-01-01T00:00:00Z"}]
        )

    def test_neo4j_datetime_updated_at_is_serialized_to_iso_string(self):
        rows = [{"account_id": "abc", "updated_at": _FakeNeo4jDateTime("2026-01-01T00:00:00.000Z")}]
        driver = _FakeDriver(rows)

        with patch("graph.privacy.get_driver", return_value=driver):
            result = asyncio.run(list_due_accounts("2026-01-01T00:00:00Z"))

        self.assertEqual(result[0]["updated_at"], "2026-01-01T00:00:00.000Z")

    def test_invalid_due_before_raises_before_query(self):
        driver = _FakeDriver([])

        with patch("graph.privacy.get_driver", return_value=driver) as get_driver_mock:
            with self.assertRaises(ValueError):
                asyncio.run(list_due_accounts("not-a-date"))

        get_driver_mock.assert_not_called()

    def test_limit_out_of_range_raises_before_query(self):
        driver = _FakeDriver([])

        with patch("graph.privacy.get_driver", return_value=driver) as get_driver_mock:
            with self.assertRaises(ValueError):
                asyncio.run(list_due_accounts("2026-01-01T00:00:00Z", limit=0))
            with self.assertRaises(ValueError):
                asyncio.run(list_due_accounts("2026-01-01T00:00:00Z", limit=91))

        get_driver_mock.assert_not_called()


class PrivacyJiraAccountsRoute(unittest.TestCase):
    """GET /privacy/jira/accounts 응답이 {"accounts": [...]}로 래핑되지 않고 raw list여야 한다.

    backend AiEnginePrivacyClient.fetchDueAccounts가 ParameterizedTypeReference<List<PrivacyDueAccount>>로
    역직렬화한다 — 래핑된 dict를 보내면 매번 역직렬화가
    실패해 보고 배치가 공회전한다. 래핑 회귀가 재발하면 이 테스트가 잡는다.
    """

    def test_response_is_raw_list_not_wrapped_in_dict(self):
        canned = [{"account_id": "acc-1", "updated_at": "2026-01-01T00:00:00Z"}]

        with patch("routers.privacy.list_due_accounts", AsyncMock(return_value=canned)):
            result = asyncio.run(privacy_jira_accounts("2026-01-01T00:00:00Z"))

        self.assertIsInstance(result, list)
        self.assertEqual(result, canned)


if __name__ == "__main__":
    unittest.main()
