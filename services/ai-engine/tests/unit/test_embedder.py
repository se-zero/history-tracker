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


if __name__ == "__main__":
    unittest.main()
