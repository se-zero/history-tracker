import asyncio
import logging
import math
import os

from openai import OpenAI

logger = logging.getLogger(__name__)

_client = OpenAI(api_key=os.environ["OPENAI_API_KEY"], timeout=30.0)
_MODEL = "text-embedding-3-small"


async def embed_text(text: str) -> list[float]:
    """이벤트 실시간 처리용. RabbitMQ에서 이벤트가 도착할 때마다 즉시 호출.
    문장/문단 전체를 하나의 벡터로 변환. 빈 텍스트는 빈 리스트 반환."""
    if not text or not text.strip():
        return []
    vectors = await asyncio.to_thread(_call_embed, [text], _MODEL)
    return vectors[0]


async def embed_batch(texts: list[str]) -> list[list[float]]:
    """배치 처리용. reference_builder에서 embedding이 없는 노드를 보정하거나
    대량 초기 데이터를 처리할 때 사용. API 1번 호출로 N개 텍스트를 처리해 비용·속도 효율적.
    빈 문자열은 빈 리스트로 치환."""
    if not texts:
        return []

    indices: list[int] = []
    non_empty: list[str] = []
    for i, t in enumerate(texts):
        if t and t.strip():
            indices.append(i)
            non_empty.append(t)

    results: list[list[float]] = [[] for _ in texts]
    if non_empty:
        vectors = await asyncio.to_thread(_call_embed, non_empty, _MODEL)
        for idx, vec in zip(indices, vectors):
            results[idx] = vec

    return results


def cosine_similarity(a: list[float], b: list[float]) -> float:
    """두 임베딩 벡터의 코사인 유사도 반환 (0.0~1.0). 빈 벡터면 0.0."""
    if not a or not b:
        return 0.0
    dot = sum(x * y for x, y in zip(a, b))
    norm_a = math.sqrt(sum(x * x for x in a))
    norm_b = math.sqrt(sum(y * y for y in b))
    if norm_a == 0.0 or norm_b == 0.0:
        return 0.0
    return dot / (norm_a * norm_b)


def _call_embed(texts: list[str], model: str) -> list[list[float]]:
    """OpenAI Embeddings API 동기 호출. asyncio.to_thread()로 감싸서 사용."""
    response = _client.embeddings.create(model=model, input=texts)
    return [item.embedding for item in response.data]
