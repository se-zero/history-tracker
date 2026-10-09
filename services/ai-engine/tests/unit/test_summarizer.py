"""파일 diff 요약(summarizer) 단위 테스트 (오프라인 — chat_completion 게이트웨이 mock).

요약 출력 상한과 상한에 걸려 잘린 응답의 처리를 검증한다. 요약이 임베딩 입력이 되므로
상한이 없으면 데이터·문서 파일에서 1만 자 넘는 요약이 나와 임베딩 모델 입력 상한을 넘긴다.
"""

import unittest
from types import SimpleNamespace
from unittest import mock

from graph import summarizer


def _response(content: str, finish_reason: str = "stop"):
    return SimpleNamespace(
        choices=[SimpleNamespace(message=SimpleNamespace(content=content), finish_reason=finish_reason)]
    )


_DIFF = "@@ -1 +1 @@\n-old\n+new\n"


class SummarizeDiffTest(unittest.IsolatedAsyncioTestCase):
    async def test_calls_gateway_with_output_cap_and_model(self):
        gateway = mock.AsyncMock(return_value=_response("[수정] `foo` 변경"))
        with mock.patch("graph.summarizer.chat_completion", gateway):
            await summarizer.summarize_diff("a.py", _DIFF, 1, 1, "msg")

        self.assertEqual(gateway.await_args.kwargs["max_completion_tokens"], 1000)
        self.assertEqual(gateway.await_args.kwargs["model"], "gpt-4o-mini")

    async def test_output_cut_by_limit_drops_the_last_partial_line(self):
        # 상한에 걸려 잘린 응답은 마지막 줄이 중간에 끊겼을 수 있어 그 줄을 버린다
        cut = _response("[추가] `a` 추가\n[수정] `b` 변경\n[제거] `c` 제거 (중간에 끊", finish_reason="length")
        with mock.patch("graph.summarizer.chat_completion", mock.AsyncMock(return_value=cut)):
            result = await summarizer.summarize_diff("data.json", _DIFF, 1, 1, "msg")

        self.assertEqual(result, "[추가] `a` 추가\n[수정] `b` 변경")

    async def test_complete_output_is_returned_whole_after_strip(self):
        done = _response("  [추가] `a` 추가\n[수정] `b` 변경\n[제거] `c` 제거\n ", finish_reason="stop")
        with mock.patch("graph.summarizer.chat_completion", mock.AsyncMock(return_value=done)):
            result = await summarizer.summarize_diff("a.py", _DIFF, 1, 1, "msg")

        self.assertEqual(result, "[추가] `a` 추가\n[수정] `b` 변경\n[제거] `c` 제거")

    async def test_output_cut_by_limit_keeps_a_single_line_as_is(self):
        # 줄이 하나뿐이면 떼어낼 마지막 줄이 곧 전부라 그대로 둔다 — 빈 요약보다 낫다
        cut = _response("[수정] `only` 한 줄뿐인 요약", finish_reason="length")
        with mock.patch("graph.summarizer.chat_completion", mock.AsyncMock(return_value=cut)):
            result = await summarizer.summarize_diff("a.py", _DIFF, 1, 1, "msg")

        self.assertEqual(result, "[수정] `only` 한 줄뿐인 요약")

    async def test_empty_diff_with_line_counts_returns_placeholder_without_llm_call(self):
        gateway = mock.AsyncMock()
        with mock.patch("graph.summarizer.chat_completion", gateway):
            result = await summarizer.summarize_diff("big.json", "", additions=3, deletions=0, message="msg")

        self.assertEqual(result, summarizer._size_placeholder("big.json", 3, 0, "msg"))
        gateway.assert_not_awaited()

    async def test_missing_content_falls_back_to_placeholder(self):
        # 본문 없는 응답(거부 등)은 "의미 있는 변경 없음"(빈 문자열)이 아니라 비정상 응답이다 — 빈 요약은 자동
        # 보정 대상도 아니라 영구 결손되므로 기존 안전망대로 placeholder를 쓴다
        none_resp = _response(None, finish_reason="content_filter")
        with mock.patch("graph.summarizer.chat_completion", mock.AsyncMock(return_value=none_resp)):
            result = await summarizer.summarize_diff("a.py", _DIFF, additions=2, deletions=1, message="msg")

        self.assertEqual(result, summarizer._size_placeholder("a.py", 2, 1, "msg"))


if __name__ == "__main__":
    unittest.main()
