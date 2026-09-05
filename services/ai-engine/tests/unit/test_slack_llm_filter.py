"""slack_llm_filter 프롬프트·filter_messages 오프라인 단위 테스트.

프로젝트 정체성(캡스톤 문구 등)이 프롬프트에 하드코딩되지 않고, 호출자가 넘긴
project_context가 있을 때만 컨텍스트 블록·조건부 제거 기준이 들어가는지 검증한다
(멀티테넌트 배포에서 다른 팀 데이터를 우리 캡스톤 프로젝트 기준으로 오판정하는 결함 방지).
"""

import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

from graph import slack_llm_filter


def _stub_response(content: str):
    return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=content))])


class BuildPromptNoContextTest(unittest.TestCase):
    def test_thread_prompt_has_no_hardcoded_identity_and_no_placeholders(self):
        prompt = slack_llm_filter.build_prompt(True)

        for absent in (
            "캡스톤",
            "GitHub, Jira, Slack",
            "다른 수업",
            "[프로젝트 컨텍스트]",
            "[프로젝트 컨텍스트]와 무관한 내용",
            "{context_block}",
            "{context_criteria}",
        ):
            self.assertNotIn(absent, prompt)

        for present in (
            "보존 기준",
            "제거 기준",
            '{"keep": [0, 2, 3]}',
            "업무·프로젝트 진행과 무관한 사적 대화",
            "제거 (사적 대화, 업무 무관)",
            "스레드 맥락",
        ):
            self.assertIn(present, prompt)

        self.assertNotIn("\n\n\n", prompt)

    def test_standalone_prompt_has_no_hardcoded_identity_and_no_placeholders(self):
        prompt = slack_llm_filter.build_prompt(False)

        for absent in (
            "캡스톤",
            "GitHub, Jira, Slack",
            "다른 수업",
            "[프로젝트 컨텍스트]",
            "[프로젝트 컨텍스트]와 무관한 내용",
            "{context_block}",
            "{context_criteria}",
            "스레드 맥락",
        ):
            self.assertNotIn(absent, prompt)

        for present in (
            "보존 기준",
            "제거 기준",
            '{"keep": [0, 2, 3]}',
            "업무·프로젝트 진행과 무관한 사적 대화",
            "제거 (사적 대화, 업무 무관)",
        ):
            self.assertIn(present, prompt)

        self.assertNotIn("\n\n\n", prompt)


class BuildPromptWithContextTest(unittest.TestCase):
    def test_context_block_follows_first_sentence(self):
        prompt = slack_llm_filter.build_prompt(False, "결제 서비스 프로젝트")

        first_sentence = "당신은 팀의 지식 그래프 구축을 위해 슬랙 메시지를 분류하는 도우미입니다."
        context_block = "[프로젝트 컨텍스트]\n결제 서비스 프로젝트\n"
        self.assertIn(context_block, prompt)
        self.assertLess(prompt.index(first_sentence), prompt.index(context_block))

    def test_conditional_context_criteria_and_output_format_present(self):
        prompt = slack_llm_filter.build_prompt(False, "결제 서비스 프로젝트")

        self.assertIn("[프로젝트 컨텍스트]와 무관한 내용", prompt)
        self.assertNotIn("애매하면 보존", prompt)
        self.assertIn('{"keep": [0, 2, 3]}', prompt)

    def test_whitespace_only_context_is_treated_as_no_context(self):
        self.assertEqual(
            slack_llm_filter.build_prompt(False, "   "),
            slack_llm_filter.build_prompt(False),
        )


class FilterMessagesTest(unittest.IsolatedAsyncioTestCase):
    async def test_returns_keep_flags_and_builds_prompt_from_args(self):
        with patch.object(
            slack_llm_filter,
            "chat_completion",
            AsyncMock(return_value=_stub_response('{"keep": [0]}')),
        ) as mock_chat:
            result = await slack_llm_filter.filter_messages(["a", "b"])

        self.assertEqual(result, [True, False])
        messages = mock_chat.call_args.kwargs["messages"]
        self.assertEqual(messages[0]["content"], slack_llm_filter.build_prompt(False, ""))
        self.assertEqual(messages[1]["content"], "[0] a\n[1] b")

    async def test_passes_is_thread_and_project_context_into_prompt(self):
        with patch.object(
            slack_llm_filter,
            "chat_completion",
            AsyncMock(return_value=_stub_response('{"keep": [0]}')),
        ) as mock_chat:
            await slack_llm_filter.filter_messages(
                ["a", "b"], is_thread=True, project_context="결제 서비스"
            )

        messages = mock_chat.call_args.kwargs["messages"]
        self.assertEqual(
            messages[0]["content"], slack_llm_filter.build_prompt(True, "결제 서비스")
        )

    async def test_empty_input_returns_empty_and_skips_llm(self):
        with patch.object(slack_llm_filter, "chat_completion", AsyncMock()) as mock_chat:
            result = await slack_llm_filter.filter_messages([])

        self.assertEqual(result, [])
        mock_chat.assert_not_called()


if __name__ == "__main__":
    unittest.main()
