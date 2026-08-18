"""LLM 실패 전파 정책 단위 테스트 — Neo4j·OpenAI 없이 실행.

회복 불가 클라이언트 오류(4xx: 모델명 오타·미지원 파라미터·인증 실패)는 삼키지 않고
전파해야 한다 — 삼키면 HTTP 200 + 빈 답변으로 위장돼 조용한 장애가 된다
(실례: reasoning_effort=low 설정 시 400이 로그에만 남고 전 질의가 빈 답변).
일시 오류(429·5xx·연결 오류)는 기존대로 None degrade로 재시도 안내 답변을 유지한다.
"""

import unittest
from unittest.mock import AsyncMock, patch

from agent import orchestrator


class _StubAPIError(Exception):
    """openai.APIStatusError 대역 — status_code 속성만 흉내낸다."""

    def __init__(self, status_code: int):
        super().__init__(f"stub http {status_code}")
        self.status_code = status_code


_MSG = [{"role": "user", "content": "질문"}]


class LlmFailurePropagationTest(unittest.IsolatedAsyncioTestCase):
    async def test_call_llm_raises_on_bad_request(self):
        with patch.object(orchestrator, "chat_completion", AsyncMock(side_effect=_StubAPIError(400))):
            with self.assertRaises(_StubAPIError):
                await orchestrator._call_llm(_MSG)

    async def test_call_llm_degrades_to_none_on_rate_limit(self):
        with patch.object(orchestrator, "chat_completion", AsyncMock(side_effect=_StubAPIError(429))):
            self.assertIsNone(await orchestrator._call_llm(_MSG))

    async def test_call_llm_degrades_to_none_on_server_error(self):
        with patch.object(orchestrator, "chat_completion", AsyncMock(side_effect=_StubAPIError(500))):
            self.assertIsNone(await orchestrator._call_llm(_MSG))

    async def test_structured_raises_on_auth_error(self):
        with patch.object(orchestrator, "chat_completion", AsyncMock(side_effect=_StubAPIError(401))):
            with self.assertRaises(_StubAPIError):
                await orchestrator._call_llm_structured(_MSG)

    async def test_structured_degrades_on_connection_error(self):
        # status_code 없는 예외(연결 실패 등)는 일시 오류로 취급
        with patch.object(orchestrator, "chat_completion", AsyncMock(side_effect=RuntimeError("conn refused"))):
            self.assertIsNone(await orchestrator._call_llm_structured(_MSG))

    async def test_summarize_history_raises_on_bad_request(self):
        with patch.object(orchestrator, "chat_completion", AsyncMock(side_effect=_StubAPIError(422))):
            with self.assertRaises(_StubAPIError):
                await orchestrator.summarize_history(None, [{"role": "user", "content": "q"}])


if __name__ == "__main__":
    unittest.main()
