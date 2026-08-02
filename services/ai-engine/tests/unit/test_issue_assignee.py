"""Issue 이벤트 담당자(assignee) 처리 단위 테스트.

전반부(HandleIssueAssigneeTest)는 오프라인 — event_handler 의존성 mock 패치로,
pipeline-worker가 개인정보인 담당자 이름 문자열(properties.assignee) 대신 refs에
assigneeId/assigneeName/assigneeEmail을 보내는 전환에 맞춰, event_handler가 담당자도
작성자와 동일하게 resolve_actor를 거쳐 Actor로 승격하고 ASSIGNED_TO로 연결하는지,
그리고 재배정·담당자 해제 시 이전 엣지를 정리하는 함수가 올바른 조건에서만 호출되는지 검증한다.

후반부(LinkIssueToAssigneeQueryTest / UnlinkIssueAssigneesQueryTest)는 graph.writes의
실제 Cypher가 이전 담당자 엣지를 지우는지 — 쿼리 문자열·파라미터 수준에서 검증한다
(fake session/driver 주입, 실제 Cypher 의미론은 live Neo4j가 필요한 integration 영역).
"""

import asyncio
import unittest
from unittest.mock import AsyncMock, patch

from graph.event_handler import handle
from graph.writes import link_issue_to_assignee, unlink_issue_assignees


def _issue_event(refs=None):
    return {
        "nodeType": "Issue",
        "source": "JIRA",
        "projectId": "p1",
        "occurredAt": "2026-07-01T00:00:00Z",
        "actor": {"id": "reporter1", "name": "Reporter One", "email": "reporter1@example.com"},
        "properties": {
            "jira_key": "HT-1",
            "title": "제목",
            "body": "본문",
            "status": "진행중",
            "issue_type": "Task",
            "priority": "Medium",
        },
        "refs": refs or {},
    }


class HandleIssueAssigneeTest(unittest.TestCase):
    def _run(self, event, resolved_uuids):
        """resolve_actor가 호출 순서대로 resolved_uuids의 uuid를 반환하도록 mock하고 handle()을 실행."""
        resolve_mock = AsyncMock(side_effect=[{"uuid": u} for u in resolved_uuids])
        upsert_issue_mock = AsyncMock()
        link_assignee_mock = AsyncMock()
        unlink_assignees_mock = AsyncMock()
        with patch("graph.event_handler.builder.upsert_issue", upsert_issue_mock), \
             patch("graph.event_handler.builder.link_issue_to_assignee", link_assignee_mock), \
             patch("graph.event_handler.builder.unlink_issue_assignees", unlink_assignees_mock), \
             patch("graph.event_handler.resolve_actor", resolve_mock), \
             patch("graph.event_handler.make_neo4j_actor_store", return_value="STORE"), \
             patch("graph.event_handler.embed_text", AsyncMock(return_value=[])):
            asyncio.run(handle(event))
        return resolve_mock, upsert_issue_mock, link_assignee_mock, unlink_assignees_mock

    def test_assignee_resolved_and_linked_when_refs_present(self):
        event = _issue_event(refs={
            "assigneeId": "assignee1",
            "assigneeName": "Assignee One",
            "assigneeEmail": "assignee1@example.com",
        })

        resolve_mock, _upsert_issue_mock, link_assignee_mock, unlink_assignees_mock = self._run(
            event, resolved_uuids=["author-uuid", "assignee-uuid"],
        )

        # 작성자 1회 + 담당자 1회 = 총 2회 resolve_actor 호출
        self.assertEqual(resolve_mock.await_count, 2)
        assignee_call_actor = resolve_mock.await_args_list[1].args[0]
        self.assertEqual(
            assignee_call_actor,
            {"id": "assignee1", "name": "Assignee One", "email": "assignee1@example.com"},
        )
        link_assignee_mock.assert_awaited_once_with("p1", "HT-1", "assignee-uuid")
        # 담당자가 있는 스냅샷이므로 해제 함수는 호출되지 않는다 — 재배정 정리는
        # link_issue_to_assignee 내부 쿼리가 담당한다.
        unlink_assignees_mock.assert_not_awaited()

    def test_no_assignee_resolve_or_link_when_assignee_id_missing(self):
        event = _issue_event(refs={})

        resolve_mock, _upsert_issue_mock, link_assignee_mock, _unlink_assignees_mock = self._run(
            event, resolved_uuids=["author-uuid"],
        )

        self.assertEqual(resolve_mock.await_count, 1)
        link_assignee_mock.assert_not_awaited()

    def test_unlink_called_when_assignee_id_missing(self):
        # 이슈 이벤트는 그 이슈의 최신 스냅샷이므로, assigneeId가 없다는 건 담당자가
        # 해제됐다는 뜻이다 — 기존 ASSIGNED_TO 엣지를 그대로 두면 "현재 담당자" 조회가
        # 더 이상 담당하지 않는 Actor를 계속 가리킨다.
        event = _issue_event(refs={})

        _resolve_mock, _upsert_issue_mock, _link_assignee_mock, unlink_assignees_mock = self._run(
            event, resolved_uuids=["author-uuid"],
        )

        unlink_assignees_mock.assert_awaited_once_with("p1", "HT-1")

    def test_upsert_issue_kwargs_have_no_assignee_key(self):
        event = _issue_event(refs={})

        _resolve_mock, upsert_issue_mock, _link_assignee_mock, _unlink_assignees_mock = self._run(
            event, resolved_uuids=["author-uuid"],
        )

        upsert_issue_mock.assert_awaited_once()
        self.assertNotIn("assignee", upsert_issue_mock.await_args.kwargs)

    def test_non_issue_event_does_not_call_unlink(self):
        # Communication처럼 이슈를 refs.jiraKey로 "참조"만 하는 이벤트는 그 이슈의
        # assignee 정보를 담고 있지 않다 — assigneeId 부재 분기와 같은 취급을 하면
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
            "refs": {"jiraKey": "HT-1"},
        }
        unlink_assignees_mock = AsyncMock()
        with patch("graph.event_handler.builder.upsert_communication", AsyncMock()), \
             patch("graph.event_handler.builder.link_issue_to_communication", AsyncMock()), \
             patch("graph.event_handler.builder.unlink_issue_assignees", unlink_assignees_mock), \
             patch("graph.event_handler.resolve_actor", AsyncMock(return_value={"uuid": "author-uuid"})), \
             patch("graph.event_handler.make_neo4j_actor_store", return_value="STORE"), \
             patch("graph.event_handler.embed_text", AsyncMock(return_value=[])):
            asyncio.run(handle(event))

        unlink_assignees_mock.assert_not_awaited()


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


class LinkIssueToAssigneeQueryTest(unittest.TestCase):
    def test_reassignment_deletes_other_assignee_edges(self):
        session = _FakeSession()
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(link_issue_to_assignee("p1", "HT-1", "new-actor-uuid"))

        self.assertEqual(len(session.calls), 1)
        query, params = session.calls[0]
        self.assertIn("ASSIGNED_TO", query)
        self.assertIn("DELETE r", query)
        self.assertEqual(params["project_id"], "p1")
        self.assertEqual(params["jira_key"], "HT-1")
        self.assertEqual(params["actor_uuid"], "new-actor-uuid")


class UnlinkIssueAssigneesQueryTest(unittest.TestCase):
    def test_deletes_all_assigned_to_edges_for_issue(self):
        session = _FakeSession()
        with patch("graph.writes.get_driver", return_value=_FakeDriver(session)):
            asyncio.run(unlink_issue_assignees("p1", "HT-1"))

        self.assertEqual(len(session.calls), 1)
        query, params = session.calls[0]
        self.assertIn("ASSIGNED_TO", query)
        self.assertIn("DELETE r", query)
        self.assertEqual(params, {"project_id": "p1", "jira_key": "HT-1"})


if __name__ == "__main__":
    unittest.main()
