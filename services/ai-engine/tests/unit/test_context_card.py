"""세션 메모리 개선 3단계(AE-3) — 대화 맥락 카드(_build_context_card) 단위 테스트.

모킹 패턴은 test_multiturn_history.py를 그대로 따른다(모듈 상단 tools.executor 스텁).
카드는 최종 structured 호출에만 실리므로, orchestrator.run() 전체를 거치지 않고
_build_context_card를 직접 호출해 검증한다.
"""

import os
import sys
from types import ModuleType
from unittest.mock import AsyncMock, patch
import unittest

os.environ.setdefault("OPENAI_API_KEY", "test-key")

executor_module = ModuleType("tools.executor")
executor_module.execute = AsyncMock()
sys.modules.setdefault("tools.executor", executor_module)

from agent import orchestrator


class BuildContextCardTest(unittest.TestCase):
    def test_strips_evidence_quote_but_keeps_body(self):
        history = [
            {"role": "user", "content": "이 PR 왜 머지됐어?"},
            {
                "role": "assistant",
                "content": (
                    "OAuth 콜백 버그를 고치기 위해 머지됐습니다."
                    "\n## 근거"
                    "\n- **[commit]** `abc1234` · 2024-01-01 · commit_authored · alice"
                    "\n  > 콜백 URL 검증 로직 인용문"
                ),
            },
        ]

        card = orchestrator._build_context_card(None, history)

        self.assertIsNotNone(card)
        self.assertEqual("system", card["role"])
        self.assertIn("OAuth 콜백 버그를 고치기 위해 머지됐습니다.", card["content"])
        self.assertNotIn("콜백 URL 검증 로직 인용문", card["content"])

    def test_budget_truncation_drops_oldest_turns(self):
        history = [
            {"role": "user", "content": "old question " * 20},
            {"role": "assistant", "content": "old answer " * 20},
            {"role": "user", "content": "recent question marker"},
            {"role": "assistant", "content": "recent answer marker"},
        ]

        with patch.object(orchestrator, "_CONTEXT_CARD_BUDGET_CHARS", 60):
            card = orchestrator._build_context_card(None, history)

        self.assertIsNotNone(card)
        self.assertIn("recent question marker", card["content"])
        self.assertIn("recent answer marker", card["content"])
        self.assertNotIn("old question", card["content"])
        self.assertNotIn("old answer", card["content"])

    def test_no_summary_and_no_history_returns_none(self):
        self.assertIsNone(orchestrator._build_context_card(None, []))


if __name__ == "__main__":
    unittest.main()
