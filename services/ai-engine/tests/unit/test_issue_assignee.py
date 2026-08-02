"""Issue 이벤트 담당자(assignee) 처리 단위 테스트 (오프라인 — event_handler 의존성 mock 패치).

pipeline-worker가 개인정보인 담당자 이름 문자열(properties.assignee) 대신 refs에
assigneeId/assigneeName/assigneeEmail을 보내는 전환에 맞춰, event_handler가 담당자도
작성자와 동일하게 resolve_actor를 거쳐 Actor로 승격하고 ASSIGNED_TO로 연결하는지 검증한다.
"""

import asyncio
import unittest
from unittest.mock import AsyncMock, patch

from graph.event_handler import handle


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
        with patch("graph.event_handler.builder.upsert_issue", upsert_issue_mock), \
             patch("graph.event_handler.builder.link_issue_to_assignee", link_assignee_mock), \
             patch("graph.event_handler.resolve_actor", resolve_mock), \
             patch("graph.event_handler.make_neo4j_actor_store", return_value="STORE"), \
             patch("graph.event_handler.embed_text", AsyncMock(return_value=[])):
            asyncio.run(handle(event))
        return resolve_mock, upsert_issue_mock, link_assignee_mock

    def test_assignee_resolved_and_linked_when_refs_present(self):
        event = _issue_event(refs={
            "assigneeId": "assignee1",
            "assigneeName": "Assignee One",
            "assigneeEmail": "assignee1@example.com",
        })

        resolve_mock, _upsert_issue_mock, link_assignee_mock = self._run(
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

    def test_no_assignee_resolve_or_link_when_assignee_id_missing(self):
        event = _issue_event(refs={})

        resolve_mock, _upsert_issue_mock, link_assignee_mock = self._run(
            event, resolved_uuids=["author-uuid"],
        )

        self.assertEqual(resolve_mock.await_count, 1)
        link_assignee_mock.assert_not_awaited()

    def test_upsert_issue_kwargs_have_no_assignee_key(self):
        event = _issue_event(refs={})

        _resolve_mock, upsert_issue_mock, _link_assignee_mock = self._run(
            event, resolved_uuids=["author-uuid"],
        )

        upsert_issue_mock.assert_awaited_once()
        self.assertNotIn("assignee", upsert_issue_mock.await_args.kwargs)


if __name__ == "__main__":
    unittest.main()
