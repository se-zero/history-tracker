import asyncio
import logging
import math

import numpy as np

from openai_client import Priority, embed
from rate_limiter import truncate_to_tokens

logger = logging.getLogger(__name__)

_MODEL = "text-embedding-3-large"
_DIMENSIONS = 1536  # 절삭 — Neo4j 벡터 인덱스 차원 유지 (근거는 docs/embedding-design.md)
_BATCH_CHUNK_SIZE = 200  # OpenAI Embedding API 호출당 입력 수 상한 (요청당 토큰 한도 회피)
# 입력 1건당 토큰 상한. 모델 상한 8,192에 여유를 둔다 — 배포 실측: 파일 요약 1건이 8,192토큰을 넘어
# 400으로 거절되며 같이 보낸 묶음 23건이 전부 결손됐다.
_MAX_INPUT_TOKENS = 8000
# 청크 실패 시 1건 재시도의 동시 상한. 옛 커밋 메시지 묶음기의 재시도 폭(COALESCE_MAX 기본 8)과 같다 —
# 200건을 한꺼번에 쏘면 OpenAI 전면 장애 때 to_thread 스레드풀을 오래 점유해 질의 경로까지 줄 세운다.
_RETRY_CONCURRENCY = 8


async def embed_text(text: str, priority: Priority = Priority.BACKGROUND) -> list[float]:
    """단일 텍스트를 하나의 벡터로 변환한다. 빈 텍스트나 호출 실패 시 빈 리스트 반환.

    priority: 기본은 수집용 BACKGROUND. 질의 경로(executor)는 INTERACTIVE로 호출해
    rate_limiter가 수집 임베딩보다 먼저 처리하도록 한다(질의 latency 보호)."""
    if not text or not text.strip():
        return []
    vectors = await _call_embed([text], _MODEL, priority)
    return vectors[0] if vectors else []


async def embed_batch(texts: list[str], priority: Priority = Priority.BACKGROUND) -> list[list[float]]:
    """배치 처리용. reference_builder에서 embedding이 없는 노드를 보정하거나
    대량 초기 데이터를 처리할 때 사용. _BATCH_CHUNK_SIZE 단위로 잘라 호출.
    빈 문자열은 빈 리스트로 치환. 청크 호출이 실패하면 그 청크를 1건씩 재시도하고,
    그래도 실패한 항목만 빈 리스트로 남김."""
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
        vectors = await _call_embed(chunk, _MODEL, priority)
        if not vectors:
            if len(chunk) == 1:
                # 1건 묶음은 다시 보내도 같은 요청이다(일시 오류는 SDK가 이미 재시도) — 빈 벡터로 둔다
                logger.warning("embed_batch 청크 실패 (offset=%d, size=1) — 빈 벡터로 채움", offset)
                continue
            # 입력 1건이 거절되면 묶음 전체가 같이 거절된다 — 1건씩 다시 보내 정상 항목은 살린다
            logger.warning("embed_batch 청크 실패 (offset=%d, size=%d) — 1건씩 재시도", offset, len(chunk))
            # gather로 동시 실행하되 _RETRY_CONCURRENCY로 폭을 묶는다 — 직렬이면 SDK 백오프가 겹쳐 오래 붙들고,
            # 무제한이면 전면 장애 때 스레드풀을 독점한다
            sem = asyncio.Semaphore(_RETRY_CONCURRENCY)

            async def retry_one(text: str) -> list[list[float]]:
                async with sem:
                    return await _call_embed([text], _MODEL, priority)

            singles = await asyncio.gather(*[retry_one(t) for t in chunk])
            for idx, single in zip(chunk_indices, singles):
                if single:
                    results[idx] = single[0]
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


async def _call_embed(texts: list[str], model: str, priority: Priority = Priority.BACKGROUND) -> list[list[float]]:
    """OpenAI Embeddings API 호출 (rate-limited 게이트웨이 경유).
    실패 시 빈 리스트 반환 — 호출자가 빈 벡터로 처리해 이벤트 처리 흐름이 끊기지 않도록.
    """
    try:
        truncated = [truncate_to_tokens(t, model, _MAX_INPUT_TOKENS) for t in texts]
        cut = sum(1 for before, after in zip(texts, truncated) if before != after)
        if cut:
            logger.warning("임베딩 입력 절단: %d/%d건 (상한 %d토큰)", cut, len(texts), _MAX_INPUT_TOKENS)
        response = await embed(model=model, input=truncated, priority=priority, dimensions=_DIMENSIONS)
        return [item.embedding for item in response.data]
    except Exception:
        logger.exception("Embedding API 호출 실패 (input %d개)", len(texts))
        return []
