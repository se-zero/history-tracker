"""임베딩 서버(serve.py)의 순수 로직 — numpy만 쓰고 torch·sentence-transformers는 import하지 않는다.

serve.py가 모델과 HTTP를 맡고, 여기에는 요청 해석·차원 절단·인증·응답 조립만 둔다. 이 부분은 틀려도
서버가 멀쩡히 뜨고 200을 돌려주면서 숫자만 조용히 틀어진다(예: 절단 뒤 재정규화 누락 → 그 후보의
점수만 낮게 측정된다). 그래서 모델 없이 값으로 검증할 수 있게 떼어 놓았다.

테스트:
  services/ai-engine/.venv/bin/python -m pytest eval/tests -q
"""

import hmac

import numpy as np


def parse_inputs(payload) -> list[str]:
    """요청 본문의 input을 문자열 목록으로 바꾼다.

    OpenAI API는 토큰 id 배열도 받지만 여기서는 거절한다. 토큰 id는 모델마다 어휘가 달라 이 서버의
    모델에는 뜻이 없는데, 문자열처럼 흘려 넣으면 에러 없이 엉뚱한 벡터가 나온다. 빈 문자열도 OpenAI와
    같이 거절한다 — 수집 쪽 버그를 "특수 토큰만 인코딩한 벡터"로 덮으면 측정에서 찾을 수 없다.
    """
    if isinstance(payload, str):
        texts = [payload]
    elif isinstance(payload, list):
        texts = list(payload)
    else:
        raise ValueError("input은 문자열 또는 문자열 목록이어야 합니다")
    if not texts:
        raise ValueError("input 목록이 비어 있습니다")
    for i, text in enumerate(texts):
        if not isinstance(text, str):
            raise ValueError(f"input[{i}]가 문자열이 아닙니다 (토큰 id 배열은 지원하지 않습니다)")
        if text == "":
            raise ValueError(f"input[{i}]가 빈 문자열입니다")
    return texts


def check_dimensions(native_dim: int, dimensions) -> int | None:
    """요청의 dimensions가 이 모델에서 쓸 수 있는 값인지 확인한다. 없으면 None.

    인코딩 전에 불러 쓸모없는 계산을 막는다. 0 이하를 통과시키면 `vectors[:, :-5]`처럼 파이썬 슬라이싱이
    뒤에서부터 자른 벡터를 에러 없이 돌려주므로, 하한도 여기서 막는다.
    """
    if dimensions is None:
        return None
    # bool은 int의 하위 클래스라 JSON의 true가 1차원 요청으로 통과하지 않게 따로 거른다
    if isinstance(dimensions, bool) or not isinstance(dimensions, int):
        raise ValueError("dimensions는 정수여야 합니다")
    if dimensions < 1:
        raise ValueError(f"dimensions는 1 이상이어야 합니다 (요청: {dimensions})")
    if dimensions > native_dim:
        raise ValueError(f"dimensions={dimensions}는 모델 차원 {native_dim}보다 클 수 없습니다")
    return dimensions


def apply_dimensions(vectors: np.ndarray, native_dim: int, dimensions: int | None) -> np.ndarray:
    """OpenAI의 dimensions 파라미터를 따른다 — 앞쪽 dimensions개만 남기고 L2 재정규화한다(MRL 절단).

    재정규화가 빠지면 길이가 1 미만인 벡터가 나가서, 내적으로 비교하는 쪽은 절단한 모델만 점수가 낮게 나온다.
    None이거나 모델 차원과 같으면 입력을 그대로 돌려준다(이미 정규화돼 있어 다시 계산할 이유가 없다).
    """
    d = check_dimensions(native_dim, dimensions)
    if d is None or d == native_dim:
        return vectors
    truncated = vectors[:, :d]
    norms = np.linalg.norm(truncated, axis=1, keepdims=True)
    # 앞쪽 d개가 전부 0인 행은 0으로 나눠 NaN이 되지 않게 그대로 둔다
    norms[norms == 0.0] = 1.0
    return truncated / norms


def check_auth(authorization_header: str | None, token: str | None) -> bool:
    """token이 비어 있으면 모두 허용(인증을 끈 상태), 아니면 정확히 `Bearer <token>`만 허용한다.

    비교는 compare_digest로 한다 — 127.0.0.1 밖으로 열 때 응답 시간으로 토큰을 추측하지 못하게 하려는 것이고,
    비ASCII 문자열은 compare_digest가 TypeError를 내므로 bytes로 바꿔 넘긴다.
    """
    if not token:
        return True
    if authorization_header is None:
        return False
    expected = f"Bearer {token}"
    return hmac.compare_digest(authorization_header.encode("utf-8"), expected.encode("utf-8"))


def build_response(vectors: np.ndarray, model_name: str, prompt_tokens: int) -> dict:
    """OpenAI /v1/embeddings 응답 형식. 벡터는 항상 float 배열로 내보낸다.

    OpenAI SDK는 encoding_format을 안 주면 base64를 요청하지만, 응답 항목이 문자열이 아니면 디코딩을
    건너뛰고 그대로 통과시킨다(openai/resources/embeddings.py의 post-parser). 그래서 base64 인코딩을
    구현하지 않는다.
    """
    rows = np.asarray(vectors).tolist()
    return {
        "object": "list",
        "data": [{"object": "embedding", "index": i, "embedding": row} for i, row in enumerate(rows)],
        "model": model_name,
        "usage": {"prompt_tokens": prompt_tokens, "total_tokens": prompt_tokens},
    }


def pick_device(cuda_available: bool, mps_available: bool) -> str:
    """cuda → mps → cpu 순으로 고른다. 같은 코드가 Mac(MPS)과 GPU 서버(CUDA) 양쪽에서 돌게 한다."""
    if cuda_available:
        return "cuda"
    if mps_available:
        return "mps"
    return "cpu"


def resolve_dtype(requested: str, device: str) -> str:
    """--dtype 인자를 실제 dtype("fp16"/"fp32")으로 정한다.

    auto는 가속기(cuda·mps)에서 fp16, cpu에서 fp32다. CPU의 반정밀도 연산은 느리고 일부 연산이 지원되지
    않아서 가속기에서만 메모리를 아끼려고 fp16을 쓴다.
    """
    if requested == "auto":
        return "fp32" if device == "cpu" else "fp16"
    if requested in ("fp16", "fp32"):
        return requested
    raise ValueError(f"알 수 없는 dtype: {requested!r} (auto|fp16|fp32)")
