"""handle()의 GitHub 봇 게이트(커밋/PR/이슈) 배선 단위 테스트 (오프라인).

graph.event_handler에 직접 patch해 Neo4j·OpenAI 없이 검증한다(test_changeset_prepare.py 관행).
"""

import asyncio
import unittest
from unittest.mock import AsyncMock, patch

from graph.event_handler import handle


def _issue_event(actor_id="github-actions[bot]", project_id="p1"):
    return {
        "nodeType": "Issue", "source": "GITHUB", "projectId": project_id,
        "occurredAt": "2026-07-01T00:00:00Z",
        "actor": {"id": actor_id, "name": "bot", "email": ""},
        "properties": {"external_id": "1", "issue_key": "#1", "title": "t"},
        "refs": {},
    }


class BotGateIssueTest(unittest.TestCase):
    def test_bot_actor_github_issue_is_skipped(self):
        handle_issue_mock = AsyncMock()
        with patch("graph.event_handler._handle_issue", handle_issue_mock):
            asyncio.run(handle(_issue_event(actor_id="github-actions[bot]")))
        handle_issue_mock.assert_not_awaited()

    def test_non_bot_actor_github_issue_is_handled(self):
        handle_issue_mock = AsyncMock()
        with patch("graph.event_handler._handle_issue", handle_issue_mock):
            asyncio.run(handle(_issue_event(actor_id="human1")))
        handle_issue_mock.assert_awaited_once()


if __name__ == "__main__":
    unittest.main()
