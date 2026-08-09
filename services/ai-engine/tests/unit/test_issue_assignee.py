"""Issue 이벤트 담당자(assignees) 처리 단위 테스트.

전반부(HandleIssueAssigneeTest)는 오프라인 — event_handler 의존성 mock 패치로, refs.assignees
배열(각 {id, name, email, bot})의 각 항목을 순차 resolve_actor에 태워 Actor로 승격하고, 확정된
uuid 리스트를 통째로 set_issue_assignees에 넘기는지 검증한다. 배열이 없거나 비어 있으면
빈 리스트로 호출해 담당자 전원 해제를 표현하고, external_id가 없는 이벤트는 아무 그래프
쓰기도 호출하지 않아야 한다.

후반부(SetIssueAssigneesQueryTest)는 graph.writes의 실제 Cypher가 목록 밖 엣지를 지우고
목록 안 uuid를 새로 잇는지 — 쿼리 문자열·파라미터 수준에서 검증한다 (fake session/driver
주입, 실제 Cypher 의미론은 live Neo4j가 필요한 integration 영역).
"""

import asyncio
import unittest
from unittest.mock import AsyncMock, patch

from graph.event_handler import handle
from graph.writes import set_issue_assignees


def _issue_event(refs=None, issue_key="HT-1", external_id="JIRA-100"):
    props = {
        "title": "제목",
        "body": "본문",
        "status": "진행중",
        "status_category": "in_progress",
        "issue_type": "Task",
        "priority": "Medium",
    }
    if external_id is not None:
        props["external_id"] = external_id
    if issue_key is not None:
        props["issue_key"] = issue_key
    return {
        "nodeType": "Issue",
        "source": "JIRA",
        "projectId": "p1",
        "occurredAt": "2026-07-01T00:00:00Z",
        "actor": {"id": "reporter1", "name": "Reporter One", "email": "reporter1@example.com"},
        "properties": props,
        "refs": refs or {},
    }


class HandleIssueAssigneeTest(unittest.TestCase):
    def _run(self, event, resolved_uuids):
        """resolve_actor가 호출 순서대로 resolved_uuids의 uuid를 반환하도록 mock하고 handle()을 실행."""
        resolve_mock = AsyncMock(side_effect=[{"uuid": u} for u in resolved_uuids])
        upsert_issue_mock = AsyncMock()
        absorb_mock = AsyncMock()
        set_assignees_mock = AsyncMock()
        with patch("graph.event_handler.builder.upsert_issue", upsert_issue_mock), \
             patch("graph.event_handler.builder.absorb_issue_stub", absorb_mock), \
             patch("graph.event_handler.builder.set_issue_assignees", set_assignees_mock), \
             patch("graph.event_handler.resolve_actor", resolve_mock), \
             patch("graph.event_handler.make_neo4j_actor_store", return_value="STORE"), \
             patch("graph.event_handler.embed_text", AsyncMock(return_value=[])):
            asyncio.run(handle(event))
        return resolve_mock, upsert_issue_mock, set_assignees_mock

    def test_assignees_resolved_and_set_when_refs_present(self):
        event = _issue_event(refs={
            "assignees": [{"id": "assignee1", "name": "Assignee One", "email": "assignee1@example.com"}],
        })

        resolve_mock, _upsert_issue_mock, set_assignees_mock = self._run(
            event, resolved_uuids=["author-uuid", "assignee-uuid"],
        )

        # 작성자 1회 + 담당자 1회 = 총 2회 resolve_actor 호출
        self.assertEqual(resolve_mock.await_count, 2)
        assignee_call_actor = resolve_mock.await_args_list[1].args[0]
        self.assertEqual(
            assignee_call_actor,
            {"id": "assignee1", "name": "Assignee One", "email": "assignee1@example.com", "bot": None},
        )
        set_assignees_mock.assert_awaited_once_with("p1", "JIRA", "JIRA-100", ["assignee-uuid"])

    def test_assignee_bot_flag_passed_through_to_resolve(self):
        """봇 격리 — 담당자 dict 재구성이 bot을 떨어뜨리면 봇 담당자가 사람 매칭(Step 1~3)을 탄다.

        Linear 봇의 주 유입로는 작성자보다 담당자다(AI 에이전트에게 이슈를 할당하는 흐름).
        """
        event = _issue_event(refs={
            "assignees": [{"id": "agent-1", "name": "Cursor Agent", "email": None, "bot": True}],
        })

        resolve_mock, _upsert_issue_mock, set_assignees_mock = self._run(
            event, resolved_uuids=["author-uuid", "bot-uuid"],
        )

        assignee_call_actor = resolve_mock.await_args_list[1].args[0]
        self.assertTrue(assignee_call_actor["bot"])
        set_assignees_mock.assert_awaited_once_with("p1", "JIRA", "JIRA-100", ["bot-uuid"])

    def test_multiple_assignees_resolved_in_order(self):
        event = _issue_event(refs={
            "assignees": [
                {"id": "a1", "name": "A One", "email": "a1@example.com"},
                {"id": "a2", "name": "A Two", "email": "a2@example.com"},
            ],
        })

        resolve_mock, _upsert_issue_mock, set_assignees_mock = self._run(
            event, resolved_uuids=["author-uuid", "uuid1", "uuid2"],
        )

        self.assertEqual(resolve_mock.await_count, 3)
        set_assignees_mock.assert_awaited_once_with("p1", "JIRA", "JIRA-100", ["uuid1", "uuid2"])

    def test_set_issue_assignees_called_with_empty_list_when_assignees_missing(self):
        event = _issue_event(refs={})

        resolve_mock, _upsert_issue_mock, set_assignees_mock = self._run(
            event, resolved_uuids=["author-uuid"],
        )

        self.assertEqual(resolve_mock.await_count, 1)
        set_assignees_mock.assert_awaited_once_with("p1", "JIRA", "JIRA-100", [])

    def test_no_graph_writes_when_external_id_missing(self):
        event = _issue_event(external_id=None)

        resolve_mock, upsert_issue_mock, set_assignees_mock = self._run(event, resolved_uuids=[])

        resolve_mock.assert_not_awaited()
        upsert_issue_mock.assert_not_awaited()
        set_assignees_mock.assert_not_awaited()

    def test_non_issue_event_does_not_call_set_issue_assignees(self):
        # Communication처럼 이슈를 refs.issueKey로 "참조"만 하는 이벤트는 그 이슈의
        # assignees 정보를 담고 있지 않다 — assignees 부재 분기와 같은 취급을 하면
        # 코멘트가 올 때마다 정상 담당자 엣지가 오삭제된다.
        event = {
            "nodeType": "Communication",
            "source": "SLACK",
            "projectId": "p1",
            "occurredAt": "2026-07-01T00:00:00Z",
            "actor": {"id": "author1", "name": "Author One", "email": "author1@example.com"},
            "properties": {
                "body": "이 이슈 관련해서 논의가 필요한 것 같습니다",
                "url": "https://slack.com/archives/C1/p1",
                "channel": "general",
                "conversation_id": "C1",
            },
            "refs": {"issueKey": "HT-1"},
        }
        set_assignees_mock = AsyncMock()
        with patch("graph.event_handler.builder.upsert_communication", AsyncMock()), \
             patch("graph.event_handler.builder.link_issue_to_communication", AsyncMock()), \
             patch("graph.event_handler.builder.set_issue_assignees", set_assignees_mock), \
             patch("graph.event_handler.resolve_actor", AsyncMock(return_value={"uuid": "author-uuid"})), \
             patch("graph.event_handler.make_neo4j_actor_store", return_value="STORE"), \
             patch("graph.event_handler.embed_text", AsyncMock(return_value=[])):
            asyncio.run(handle(event))

        set_assignees_mock.assert_not_awaited()


class _FakeSession:
    """실행된 (query, params)를 순서대로 기록한다."""

    def __init__(self):
        self.calls = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return None

    async def run(self, query, **params):
        self.calls.append((query, params))


class _FakeDriver:
    def __init__(self, session):
        self._session = session

    def session(self):
        return self._session


class SetIssueAssigneesQueryTest(unittest.TestCase):
    def test_deletes_out_of_list_then_connects_each_uuid(self):
        session = _FakeSession()
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(set_issue_assignees("p1", "JIRA", "JIRA-100", ["u1", "u2"]))

        self.assertEqual(len(session.calls), 2)

        delete_query, delete_params = session.calls[0]
        self.assertIn("ASSIGNED_TO", delete_query)
        self.assertIn("DELETE r", delete_query)
        self.assertEqual(delete_params["project_id"], "p1")
        self.assertEqual(delete_params["source"], "JIRA")
        self.assertEqual(delete_params["external_id"], "JIRA-100")
        self.assertEqual(delete_params["actor_uuids"], ["u1", "u2"])

        connect_query, connect_params = session.calls[1]
        self.assertIn("MERGE", connect_query)
        self.assertIn("ASSIGNED_TO", connect_query)
        self.assertEqual(connect_params["actor_uuids"], ["u1", "u2"])

    def test_empty_list_only_runs_delete_statement(self):
        session = _FakeSession()
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(set_issue_assignees("p1", "JIRA", "JIRA-100", []))

        # 빈 리스트면 목록 밖 엣지 삭제문만 실행하고, 연결문(빈 UNWIND라 no-op)은 건너뛴다.
        self.assertEqual(len(session.calls), 1)
        delete_query, delete_params = session.calls[0]
        self.assertIn("DELETE r", delete_query)
        self.assertEqual(delete_params["actor_uuids"], [])


if __name__ == "__main__":
    unittest.main()
