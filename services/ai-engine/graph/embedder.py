import logging
import math

import numpy as np

from openai_client import Priority, embed

logger = logging.getLogger(__name__)

_MODEL = "text-embedding-3-small"
_BATCH_CHUNK_SIZE = 200  # OpenAI Embedding API 호출당 입력 수 상한 (요청당 토큰 한도 회피)


async def embed_text(text: str) -> list[float]:
    """이벤트 실시간 처리용. RabbitMQ에서 이벤트가 도착할 때마다 즉시 호출.
    문장/문단 전체를 하나의 벡터로 변환. 빈 텍스트나 호출 실패 시 빈 리스트 반환."""
    if not text or not text.strip():
        return []
    vectors = await _call_embed([text], _MODEL)
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
        vectors = await _call_embed(chunk, _MODEL)
        if not vectors:
            logger.warning("embed_batch 청크 실패 (offset=%d, size=%d) — 빈 벡터로 채움", offset, len(chunk))
            continue
        for idx, vec in zip(chunk_indices, vectors):
            results[idx] = vec

    return results


def cosine_similarity(a: list[float], b: list[float]) -> float:
    """두 임베딩 벡터의 코사인 유사도 반환 (0.0~1.0). 빈 벡터면 0.0.

    단건 비교용 (issue_verifier 등). 전체 쌍을 한꺼번에 비교할 때는
    similarity_matrix()를 쓴다 (Python 루프 대신 numpy 행렬곱).
    """
    if not a or not b:
        return 0.0
    dot = sum(x * y for x, y in zip(a, b))
    norm_a = math.sqrt(sum(x * x for x in a))
    norm_b = math.sqrt(sum(y * y for y in b))
    if norm_a == 0.0 or norm_b == 0.0:
        return 0.0
    return dot / (norm_a * norm_b)


def similarity_matrix(a_vectors: list[list[float]], b_vectors: list[list[float]]) -> np.ndarray:
    """코사인 유사도 행렬 (len(a) × len(b))을 numpy 행렬곱으로 한 번에 계산한다.

    a_vectors[i] ↔ b_vectors[j] 의 코사인 유사도가 결과[i, j]에 담긴다.
    각 행을 L2 정규화한 뒤 내적하면 곧 코사인 유사도이므로, 정규화 1회 +
    행렬곱 1회로 전체 쌍을 일괄 계산한다 (순수 Python 이중 루프 대비 대폭 빠름).

    전제: 모든 입력 벡터는 차원이 같아야 한다. 빈 벡터(임베딩 실패)는 차원이
    달라 행렬화가 깨지므로 호출 전에 걸러야 한다 (어차피 유사도 0이라 임계값 미달).
    """
    a_norm = _normalize_rows(a_vectors)
    b_norm = _normalize_rows(b_vectors)
    return a_norm @ b_norm.T


def _normalize_rows(vectors: list[list[float]]) -> np.ndarray:
    """행 단위 L2 정규화 행렬 반환. 노름이 0인 행은 0으로 두어 유사도가 0이 되게 한다."""
    mat = np.asarray(vectors, dtype=np.float64)
    norms = np.linalg.norm(mat, axis=1, keepdims=True)
    norms[norms == 0.0] = 1.0  # 0 division 방지 — 해당 행은 이미 0이라 결과도 0
    return mat / norms


async def _call_embed(texts: list[str], model: str) -> list[list[float]]:
    """OpenAI Embeddings API 호출 (rate-limited 게이트웨이 경유).
    실패 시 빈 리스트 반환 — 호출자가 빈 벡터로 처리해 이벤트 처리 흐름이 끊기지 않도록.
    """
    try:
        response = await embed(model=model, input=texts, priority=Priority.BACKGROUND)
        return [item.embedding for item in response.data]
    except Exception:
        logger.exception("Embedding API 호출 실패 (input %d개)", len(texts))
        return []
