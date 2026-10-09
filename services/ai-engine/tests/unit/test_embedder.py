"""임베딩 모델·차원 배선 단위 테스트 (오프라인 — openai_client 게이트웨이 mock).

모델을 바꾸면 그래프에 쌓인 벡터가 전부 무효가 되므로, 어떤 모델·차원으로 호출하는지가
곧 그래프의 계약이다. 그 계약을 코드에 박아둔다.
"""

import unittest
from types import SimpleNamespace
from unittest import mock

from graph import embedder


def _fake_response(count: int):
    return SimpleNamespace(data=[SimpleNamespace(embedding=[0.1, 0.2]) for _ in range(count)])


class EmbedCallContractTest(unittest.IsolatedAsyncioTestCase):
    async def test_embed_text_calls_gateway_with_model_and_dimensions(self):
        with mock.patch(
            "graph.embedder.embed", new=mock.AsyncMock(return_value=_fake_response(1))
        ) as gateway:
            await embedder.embed_text("본문")

        self.assertEqual(gateway.await_args.kwargs["model"], "text-embedding-3-large")
        self.assertEqual(gateway.await_args.kwargs["dimensions"], 1536)

    async def test_embed_batch_calls_gateway_with_model_and_dimensions(self):
        with mock.patch(
            "graph.embedder.embed", new=mock.AsyncMock(return_value=_fake_response(2))
        ) as gateway:
            await embedder.embed_batch(["a", "b"])

        self.assertEqual(gateway.await_args.kwargs["model"], "text-embedding-3-large")
        self.assertEqual(gateway.await_args.kwargs["dimensions"], 1536)

    def test_dimensions_match_vector_index(self):
        # 1536은 Neo4j 벡터 인덱스 차원 — 어긋나면 인덱스가 벡터를 거부한다
        self.assertEqual(embedder._DIMENSIONS, 1536)


class EmbedInputTruncationTest(unittest.IsolatedAsyncioTestCase):
    async def test_embed_batch_truncates_only_the_oversized_text(self):
        # 모델 입력 상한(8,192토큰)을 넘는 글 1건이 섞이면 요청 전체가 400으로 거절된다 — 그 1건만 잘라서 보낸다.
        # 길이는 tiktoken 유무와 무관하게 상한(토큰/글자 폴백 모두)을 넘도록 3만 자 이상으로 만든다
        long_text = "한국어 and English mix 변경 사항 `foo_bar` 수정 " * 800
        with mock.patch(
            "graph.embedder.embed", new=mock.AsyncMock(return_value=_fake_response(2))
        ) as gateway:
            await embedder.embed_batch(["짧은 글", long_text])

        sent = gateway.await_args.kwargs["input"]
        self.assertEqual(sent[0], "짧은 글")
        self.assertLess(len(sent[1]), len(long_text))
        self.assertTrue(long_text.startswith(sent[1]))

    async def test_embed_text_truncates_oversized_text(self):
        long_text = "한국어 and English mix 변경 사항 `foo_bar` 수정 " * 800
        with mock.patch(
            "graph.embedder.embed", new=mock.AsyncMock(return_value=_fake_response(1))
        ) as gateway:
            await embedder.embed_text(long_text)

        sent = gateway.await_args.kwargs["input"]
        self.assertLess(len(sent[0]), len(long_text))
        self.assertTrue(long_text.startswith(sent[0]))


class EmbedBatchRetryTest(unittest.IsolatedAsyncioTestCase):
    async def test_rejected_chunk_is_retried_one_by_one_and_only_failures_stay_empty(self):
        # 묶음 호출이 거절되면(문제 입력 1건 때문에 400) 같이 탄 정상 텍스트까지 결손되지 않게 1건씩 다시 보낸다
        async def gateway_behavior(*, model, input, priority, dimensions):
            if len(input) > 1 or input == ["bad"]:
                raise RuntimeError("rejected")
            return _fake_response(1)

        with mock.patch("graph.embedder.embed", new=mock.AsyncMock(side_effect=gateway_behavior)) as gateway:
            result = await embedder.embed_batch(["a", "bad", "c"])

        self.assertEqual(result, [[0.1, 0.2], [], [0.1, 0.2]])
        self.assertEqual(gateway.await_count, 4)  # 묶음 1회 + 단건 3회
        inputs = [c.kwargs["input"] for c in gateway.await_args_list]
        self.assertEqual(inputs[0], ["a", "bad", "c"])
        self.assertCountEqual(inputs[1:], [["a"], ["bad"], ["c"]])

    async def test_retry_keeps_priority_and_original_positions(self):
        # 빈 문자열은 호출에서 빠지므로 재시도 결과가 원래 인덱스로 돌아가야 한다
        async def gateway_behavior(*, model, input, priority, dimensions):
            if len(input) > 1:
                raise RuntimeError("rejected")
            return SimpleNamespace(data=[SimpleNamespace(embedding=[float(len(input[0]))])])

        with mock.patch("graph.embedder.embed", new=mock.AsyncMock(side_effect=gateway_behavior)) as gateway:
            result = await embedder.embed_batch(["x", "", "yyy"], priority=embedder.Priority.INTERACTIVE)

        self.assertEqual(result, [[1.0], [], [3.0]])
        self.assertTrue(all(c.kwargs["priority"] == embedder.Priority.INTERACTIVE for c in gateway.await_args_list))

    async def test_successful_chunk_is_not_retried(self):
        with mock.patch(
            "graph.embedder.embed", new=mock.AsyncMock(return_value=_fake_response(3))
        ) as gateway:
            result = await embedder.embed_batch(["a", "b", "c"])

        self.assertEqual(result, [[0.1, 0.2]] * 3)
        self.assertEqual(gateway.await_count, 1)

    async def test_single_item_chunk_is_not_retried(self):
        # 1건 묶음은 다시 보내도 같은 요청이다(일시 오류는 SDK가 이미 재시도) — 두 번 보내면 실패 알림만 두 배로 찍힌다
        with mock.patch(
            "graph.embedder.embed", new=mock.AsyncMock(side_effect=RuntimeError("rejected"))
        ) as gateway:
            result = await embedder.embed_batch(["only"])

        self.assertEqual(result, [[]])
        self.assertEqual(gateway.await_count, 1)


if __name__ == "__main__":
    unittest.main()
