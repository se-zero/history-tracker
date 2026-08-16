"""focus_evidence 단위 테스트 (오프라인).

사용자가 관련 그래프에서 지정한 노드를 질의까지 실어 보내는 경로를 검증한다.
- QueryRequest.focus_evidence 파싱 — PriorEvidence와 달리 관대(blank/quote 강제 없음).
- orchestrator.run이 focus 지시를 '현재 턴'에 두어 최종 structured 답변까지 살려
  지정 노드를 근거로 고정하는지 (prior_evidence가 정반대로 근거에서 제외되는 것과 대비).
LLM 호출은 test_multiturn_history.py와 동일하게 모킹한다.
"""

import os
import sys
import unittest
from types import ModuleType, SimpleNamespace
from unittest.mock import AsyncMock, patch

os.environ.setdefault("OPENAI_API_KEY", "test-key")

executor_module = ModuleType("tools.executor")
executor_module.execute = AsyncMock()
sys.modules.setdefault("tools.executor", executor_module)

from agent import orchestrator
from query_models import QueryRequest


class FocusEvidenceRequestTest(unittest.TestCase):
    def test_focus_evidence_defaults_to_empty_list(self):
        request = QueryRequest(question="q")
        self.assertEqual([], request.focus_evidence)

    def test_focus_evidence_accepts_type_and_id(self):
        request = QueryRequest(
            question="q",
            focus_evidence=[{"type": "commit", "id": "abc1234def"}],
        )
        self.assertEqual("commit", request.focus_evidence[0].type)
        self.assertEqual("abc1234def", request.focus_evidence[0].id)


class OrchestratorFocusEvidenceTest(unittest.IsolatedAsyncioTestCase):
    async def test_focus_evidence_grounds_structured_answer(self):
        focus_evidence = [{"type": "commit", "id": "abc1234def5678"}]
        response = SimpleNamespace(
            choices=[SimpleNamespace(message=SimpleNamespace(tool_calls=None, content="fallback"))]
        )
        captured_exploration_messages = []
        captured_structured_messages = []

        async def capture_exploration(messages, with_tools=True):
            captured_exploration_messages.extend(messages)
            return response

        async def capture_structured(messages, debug=None):
            captured_structured_messages.extend(messages)
            return {"summary": "done", "evidence": [], "unknown_aspects": []}

        with (
            patch.object(orchestrator, "_call_llm", side_effect=capture_exploration),
            patch.object(orchestrator, "_call_llm_structured", side_effect=capture_structured),
        ):
            await orchestrator.run(
                "이 커밋이 왜 필요했어?",
                focus_evidence=focus_evidence,
            )

        # 탐색 메시지: system 프롬프트 → focus 지시(system) → 현재 질문 순서
        self.assertEqual(
            ["system", "system", "user"],
            [m["role"] for m in captured_exploration_messages],
        )
        focus_msg = captured_exploration_messages[1]
        self.assertIn("abc1234def5678", focus_msg["content"])
        # 유형별 도구를 명시해 지정 노드를 먼저 조회하도록 유도한다 — Notion 문서 노드도
        # 첨부 가능해졌으므로 document→get_document_context 매핑이 빠지면 첨부한 문서를
        # 무시하고 다른 도구로 새는 경로가 열린다.
        self.assertIn("get_changeset_context", focus_msg["content"])
        self.assertIn("get_document_context", focus_msg["content"])

        # prior_evidence와 정반대 — focus 지시는 최종 structured 근거 호출까지 살아남는다.
        self.assertEqual(
            ["system", "system", "user"],
            [m["role"] for m in captured_structured_messages],
        )
        self.assertIn("abc1234def5678", str(captured_structured_messages))

    async def test_no_focus_evidence_keeps_current_turn_minimal(self):
        # focus_evidence가 없으면 기존 동작 그대로 — structured 근거는 system + 현재 질문뿐.
        response = SimpleNamespace(
            choices=[SimpleNamespace(message=SimpleNamespace(tool_calls=None, content="fallback"))]
        )
        captured_structured_messages = []

        async def capture_exploration(messages, with_tools=True):
            return response

        async def capture_structured(messages, debug=None):
            captured_structured_messages.extend(messages)
            return {"summary": "done", "evidence": [], "unknown_aspects": []}

        with (
            patch.object(orchestrator, "_call_llm", side_effect=capture_exploration),
            patch.object(orchestrator, "_call_llm_structured", side_effect=capture_structured),
        ):
            await orchestrator.run("그냥 질문")

        self.assertEqual(
            ["system", "user"],
            [m["role"] for m in captured_structured_messages],
        )

    async def test_focus_prior_history_together_grounding_contract(self):
        # 핵심 계약: history·prior_evidence는 탐색에만 쓰고 근거(structured)에서 제외,
        # focus_evidence는 현재 턴에 두어 근거에 포함 — 셋이 함께 있을 때가 진짜 시나리오.
        history = [
            {"role": "user", "content": "이전 질문"},
            {"role": "assistant", "content": "이전 답변 PR #18"},
        ]
        prior_evidence = [{"type": "pull_request", "id": "#18", "quote": "OAuth callback update"}]
        focus_evidence = [{"type": "commit", "id": "abc1234def5678"}]
        response = SimpleNamespace(
            choices=[SimpleNamespace(message=SimpleNamespace(tool_calls=None, content="fallback"))]
        )
        captured_exploration = []
        captured_structured = []

        async def capture_exploration(messages, with_tools=True):
            captured_exploration.extend(messages)
            return response

        async def capture_structured(messages, debug=None):
            captured_structured.extend(messages)
            return {"summary": "done", "evidence": [], "unknown_aspects": []}

        with (
            patch.object(orchestrator, "_call_llm", side_effect=capture_exploration),
            patch.object(orchestrator, "_call_llm_structured", side_effect=capture_structured),
        ):
            await orchestrator.run(
                "이 커밋 왜 바뀜?",
                history=history,
                prior_evidence=prior_evidence,
                focus_evidence=focus_evidence,
            )

        # 탐색: system → history(user,assistant) → prior(system) → focus(system) → 현재 질문(user)
        self.assertEqual(
            ["system", "user", "assistant", "system", "system", "user"],
            [m["role"] for m in captured_exploration],
        )
        # 탐색엔 prior·focus 모두 포함
        self.assertIn("OAuth callback update", str(captured_exploration))
        self.assertIn("abc1234def5678", str(captured_exploration))

        # 근거(structured): focus는 포함, prior·history는 제외
        self.assertEqual(
            ["system", "system", "user"],
            [m["role"] for m in captured_structured],
        )
        self.assertIn("abc1234def5678", str(captured_structured))
        self.assertNotIn("OAuth callback update", str(captured_structured))
        self.assertNotIn("이전 답변 PR #18", str(captured_structured))


class GroundedAnswerDocumentEvidenceTest(unittest.TestCase):
    """document 노드를 채팅에 첨부해 get_document_context로 조회해도, 최종 structured
    답변의 evidence[].type enum에 "document"가 없으면 인용 자체가 불가능하다 — focus 지시의
    도구 라우팅만 고쳐서는 문서를 근거로 답하는 경로가 완성되지 않는다.
    """

    def test_evidence_type_enum_includes_document(self):
        self.assertIn("document", orchestrator._EVIDENCE_TYPES)
        self.assertIn(
            "document",
            orchestrator._GROUNDED_ANSWER_SCHEMA["schema"]["properties"]["evidence"]["items"]
            ["properties"]["type"]["enum"],
        )

    def test_event_meanings_include_document_created_and_updated(self):
        self.assertIn("document_created", orchestrator._EVENT_MEANINGS)
        self.assertIn("document_updated", orchestrator._EVENT_MEANINGS)
        self.assertIn(
            "document_created",
            orchestrator._GROUNDED_ANSWER_SCHEMA["schema"]["properties"]["evidence"]["items"]
            ["properties"]["event_meaning"]["enum"],
        )


if __name__ == "__main__":
    unittest.main()
