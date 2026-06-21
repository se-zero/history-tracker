import asyncio
import logging
import math

from openai_client import get_openai_client

logger = logging.getLogger(__name__)

_MODEL = "text-embedding-3-small"
_BATCH_CHUNK_SIZE = 200  # OpenAI Embedding API 호출당 입력 수 상한 (요청당 토큰 한도 회피)


async def embed_text(text: str) -> list[float]:
    """이벤트 실시간 처리용. RabbitMQ에서 이벤트가 도착할 때마다 즉시 호출.
    문장/문단 전체를 하나의 벡터로 변환. 빈 텍스트나 호출 실패 시 빈 리스트 반환."""
    if not text or not text.strip():
        return []
    vectors = await asyncio.to_thread(_call_embed, [text], _MODEL)
    return vectors[0] if vectors else []


async def embed_batch(texts: list[str]) -> list[list[float]]:
    """배치 처리용. reference_builder에서 embedding이 없는 노드를 보정하거나
    대량 초기 데이터를 처리할 때 사용. _BATCH_CHUNK_SIZE 단위로 잘라 호출.
    빈 문자열은 빈 리스트로 치환. 청크 호출 실패 시 해당 청크만 빈 리스트로 채움."""
    if not texts:
        return []

    indices: list[int] = []
    non_empty: list[str] = []
    for i, t in enumerate(texts):
        if t and t.strip():
            indices.append(i)
            non_empty.append(t)

    results: list[list[float]] = [[] for _ in texts]

    for offset in range(0, len(non_empty), _BATCH_CHUNK_SIZE):
        chunk = non_empty[offset : offset + _BATCH_CHUNK_SIZE]
        chunk_indices = indices[offset : offset + _BATCH_CHUNK_SIZE]
        vectors = await asyncio.to_thread(_call_embed, chunk, _MODEL)
        if not vectors:
            logger.warning("embed_batch 청크 실패 (offset=%d, size=%d) — 빈 벡터로 채움", offset, len(chunk))
            continue
        for idx, vec in zip(chunk_indices, vectors):
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
    """OpenAI Embeddings API 동기 호출. asyncio.to_thread()로 감싸서 사용.
    실패 시 빈 리스트 반환 — 호출자가 빈 벡터로 처리해 이벤트 처리 흐름이 끊기지 않도록.
    """
    try:
        response = get_openai_client().embeddings.create(model=model, input=texts)
        return [item.embedding for item in response.data]
    except Exception:
        logger.exception("Embedding API 호출 실패 (input %d개)", len(texts))
        return []
