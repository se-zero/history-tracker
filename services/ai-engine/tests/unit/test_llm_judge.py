"""수동 정밀 구축의 LLM 판정기 단위 테스트 (오프라인 — chat_completion mock).

검증 대상은 세 가지다.
  1. 실패 전파 정책 — 설정 오류(4xx)는 raise, 일시 오류는 skip(None)+집계.
     기존 _verify_pair는 모든 예외를 0.0(무관 판정)으로 삼켜서 "LLM이 무관하다고 답했다"와
     "호출이 실패했다"가 측정 결과에서 구분되지 않았다. 이걸 분리하는 것이 이 모듈의 존재 이유.
  2. LLM 입력에 커밋 메시지가 포함되는지 — Phase 4 통제 조건(중간 측정 기각 원인의 교정).
  3. 판정 결과 파싱 — confidence 추출.
"""

import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

from graph import llm_judge


class _StubAPIError(Exception):
    """openai.APIStatusError 대역 — status_code 속성만 흉내낸다."""

    def __init__(self, status_code: int):
        super().__init__(f"stub http {status_code}")
        self.status_code = status_code


def _resp(content: str):
    return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=content))])


class JudgePairTest(unittest.IsolatedAsyncioTestCase):
    async def test_returns_confidence_on_success(self):
        with patch.object(llm_judge, "chat_completion",
                          AsyncMock(return_value=_resp('{"confidence": 0.82, "reason": "동일 기능"}'))):
            stats = llm_judge.JudgeStats()
            score = await llm_judge.judge_pair("Issue", "로그인 오류", "커밋", "fix login", stats=stats)
        self.assertAlmostEqual(score, 0.82)
        self.assertEqual(stats.judged, 1)
        self.assertEqual(stats.skipped, 0)

    async def test_raises_on_unrecoverable_client_error(self):
        """설정 오류(모델명 오타·인증 실패)는 삼키면 전 쌍이 0.0으로 위장된다 — 반드시 전파."""
        with patch.object(llm_judge, "chat_completion", AsyncMock(side_effect=_StubAPIError(400))):
            with self.assertRaises(_StubAPIError):
                await llm_judge.judge_pair("Issue", "a", "커밋", "b")

    async def test_skips_on_rate_limit(self):
        """일시 오류는 그 쌍만 스킵하고 집계에 남긴다 — 0.0(무관 판정)으로 위장하지 않는다."""
        with patch.object(llm_judge, "chat_completion", AsyncMock(side_effect=_StubAPIError(429))):
            stats = llm_judge.JudgeStats()
            score = await llm_judge.judge_pair("Issue", "a", "커밋", "b", stats=stats)
        self.assertIsNone(score)
        self.assertEqual(stats.skipped, 1)
        self.assertEqual(stats.judged, 0)

    async def test_skips_on_malformed_json(self):
        with patch.object(llm_judge, "chat_completion", AsyncMock(return_value=_resp("설명만 있고 JSON이 아님"))):
            stats = llm_judge.JudgeStats()
            score = await llm_judge.judge_pair("Issue", "a", "커밋", "b", stats=stats)
        self.assertIsNone(score)
        self.assertEqual(stats.skipped, 1)

    async def test_prompt_contains_both_sides(self):
        mock = AsyncMock(return_value=_resp('{"confidence": 0.1}'))
        with patch.object(llm_judge, "chat_completion", mock):
            await llm_judge.judge_pair("Issue", "로그인이 실패한다", "커밋", "fix: 세션 만료 처리")
        prompt = mock.await_args.kwargs["messages"][0]["content"]
        self.assertIn("로그인이 실패한다", prompt)
        self.assertIn("fix: 세션 만료 처리", prompt)


class FormatCommitTextTest(unittest.TestCase):
    """Phase 4 통제 조건 — LLM 입력에 커밋 메시지가 diff 요약과 함께 들어가야 한다."""

    def test_includes_message_and_diff(self):
        text = llm_judge.format_commit_text("fix: 세션 만료 처리", "auth/session.py 수정")
        self.assertIn("fix: 세션 만료 처리", text)
        self.assertIn("auth/session.py 수정", text)

    def test_survives_missing_parts(self):
        self.assertIn("fix: only message", llm_judge.format_commit_text("fix: only message", ""))
        self.assertIn("only diff", llm_judge.format_commit_text("", "only diff"))
        self.assertEqual(llm_judge.format_commit_text("", ""), "")


if __name__ == "__main__":
    unittest.main()
