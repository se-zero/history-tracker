"""OpenAI 호환 임베딩 서버 — 오픈 임베딩 모델을 POST /v1/embeddings로 서빙한다.

eval 스크립트와 ai-engine은 OpenAI SDK로 임베딩을 부른다. 같은 형식의 서버를 띄우면 코드를 거의 그대로 둔 채
base_url·모델명만 바꿔 후보 모델을 같은 자로 잴 수 있다.

한 프로세스에 모델 하나만 올린다(Mac 16GB). 후보를 바꿔 재려면 서버를 내리고 다시 띄운다. 요청의 model이
서빙 이름과 다르면 400이다 — 다른 모델로 잰 결과가 엉뚱한 라벨로 기록되는 것을 막는다.

접두어·지시문(Qwen3의 질의 지시문 등)은 서버가 붙이지 않는다. 받은 텍스트를 그대로 임베딩하므로
필요하면 호출하는 쪽이 텍스트에 넣는다. 벡터는 L2 정규화해서 돌려준다.

토큰이 필요하면 환경변수 EMBED_SERVER_TOKEN을 준다. 비우면 인증이 없고, 기본 바인딩이 127.0.0.1인 이유다.

실행 (Mac venv — 설치는 README.md):
  eval/embedding_finetune/.venv/bin/python eval/embedding_finetune/serve.py --model BAAI/bge-m3
  eval/embedding_finetune/.venv/bin/python eval/embedding_finetune/serve.py \\
      --model kakaocorp/kanana-2-1.3b-embedding --trust-remote-code --revision <커밋 해시>

torch·sentence-transformers는 main() 안에서만 import한다 — 위쪽의 create_app을 모델 없이 import해
가짜 encoder로 검증하려는 것이다(eval/tests/test_embedding_server_app.py).
"""

import argparse
import logging
import os
import sys
import threading
import time

import numpy as np
import uvicorn
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from starlette.concurrency import run_in_threadpool

import server_core

logger = logging.getLogger("embed_server")


class SentenceTransformerEncoder:
    """SentenceTransformer를 create_app이 요구하는 두 메서드로 감싼다.

    ST 객체를 직접 받지 않는 이유: 가짜 encoder로 서버 로직을 검증할 때 HF 토크나이저와 ST의 호출 규약을
    흉내 내지 않아도 되게 하려는 것이다.
    """

    def __init__(self, model):
        self._model = model

    def count_tokens(self, texts: list[str]) -> list[int]:
        # 자르지 않은 길이가 필요하다 — model.tokenize()는 이미 max_seq_length에서 잘라 돌려준다.
        # 특수 토큰을 포함해 세는 것은 ST가 max_seq_length를 적용하는 기준과 같게 하려는 것이다.
        # verbose=False는 모델 상한을 넘는 입력마다 나오는 HF 경고를 끈다(길이는 우리가 직접 로그로 남긴다).
        ids = self._model.tokenizer(texts, add_special_tokens=True, truncation=False, verbose=False)["input_ids"]
        return [len(x) for x in ids]

    def encode(self, texts: list[str], batch_size: int) -> np.ndarray:
        return self._model.encode(
            texts, normalize_embeddings=True, batch_size=batch_size, convert_to_numpy=True, show_progress_bar=False
        )


def create_app(
    encoder,
    served_name: str,
    native_dim: int,
    token: str,
    max_seq_length: int,
    batch_size: int,
    *,
    device: str = "unknown",
    dtype: str = "unknown",
) -> FastAPI:
    """encoder를 끼운 FastAPI 앱을 만든다. encoder는 두 메서드만 있으면 되므로 테스트에서 가짜를 넣는다.

      count_tokens(texts) -> list[int]       자르기 전 토큰 수(특수 토큰 포함)
      encode(texts, batch_size) -> ndarray   L2 정규화된 (len(texts), native_dim) 행렬

    device·dtype은 /health에 보여 주기만 한다.
    """
    app = FastAPI(title="embedding-server")
    # 토크나이저와 가속기 모델에는 한 번에 한 요청만 들인다. HF 토크나이저는 호출마다 truncation·padding 설정을
    # 바꿔서 스레드가 겹치면 "Already borrowed"로 실패할 수 있고, MPS 커널도 동시 제출이 안전하다고 보장되지 않는다.
    # 계산은 이벤트 루프 밖 스레드에서 돌려 계산 중에도 /health가 응답하게 한다.
    lock = threading.Lock()

    def locked(fn, *args):
        with lock:
            return fn(*args)

    def error(status: int, message: str, code: str) -> JSONResponse:
        # OpenAI 오류 본문 형식 — SDK가 message를 예외 문구에 그대로 실어 준다
        kind = "server_error" if status >= 500 else "invalid_request_error"
        return JSONResponse(
            status_code=status,
            content={"error": {"message": message, "type": kind, "param": None, "code": code}},
        )

    @app.get("/health")
    async def health():
        return {
            "model": served_name,
            "dim": native_dim,
            "device": device,
            "dtype": dtype,
            "max_seq_length": max_seq_length,
        }

    @app.post("/v1/embeddings")
    async def create_embeddings(request: Request):
        # 인증이 맨 앞이다 — 토큰이 틀린 요청에는 입력 검증 결과(400)조차 알려 주지 않는다
        if not server_core.check_auth(request.headers.get("authorization"), token):
            return error(401, "Incorrect API key provided", "invalid_api_key")

        try:
            body = await request.json()
        except ValueError:
            return error(400, "요청 본문이 JSON이 아닙니다", "invalid_json")
        if not isinstance(body, dict):
            return error(400, "요청 본문은 JSON 객체여야 합니다", "invalid_json")

        try:
            texts = server_core.parse_inputs(body.get("input"))
        except ValueError as exc:
            return error(400, str(exc), "invalid_input")

        requested = body.get("model")
        if requested != served_name:
            return error(400, f"이 서버는 '{served_name}' 모델만 서빙합니다 (요청: {requested!r})", "model_mismatch")

        try:
            dimensions = server_core.check_dimensions(native_dim, body.get("dimensions"))
        except ValueError as exc:
            return error(400, str(exc), "invalid_dimensions")

        # 잘린 입력은 점수에 조용히 섞이므로 건수를 로그로 남긴다 — OpenAI(8,000토큰)와 절단 지점이 달라
        # 후보 비교 결과를 읽을 때 필요한 정보다
        counts = await run_in_threadpool(locked, encoder.count_tokens, texts)
        truncated = sum(1 for c in counts if c > max_seq_length)
        prompt_tokens = sum(min(c, max_seq_length) for c in counts)

        # 락 대기 시간을 포함한다 — 요청이 겹치면 계산 시간보다 길게 찍히는 게 정상이다
        started = time.perf_counter()
        vectors = await run_in_threadpool(locked, encoder.encode, texts, batch_size)
        elapsed_ms = (time.perf_counter() - started) * 1000
        logger.log(
            logging.WARNING if truncated else logging.INFO,
            "embeddings inputs=%d truncated=%d max_seq_length=%d prompt_tokens=%d elapsed_ms=%.0f",
            len(texts), truncated, max_seq_length, prompt_tokens, elapsed_ms,
        )

        # NaN은 JSON으로 못 나가 어차피 500이 되는데, fp16에서 흔한 원인과 대처를 알려 주는 편이 낫다
        if not np.isfinite(vectors).all():
            return error(500, "임베딩에 NaN/Inf가 섞였습니다 — fp16 오버플로가 흔한 원인이니 --dtype fp32로 다시 띄우세요",
                         "non_finite_embedding")

        vectors = server_core.apply_dimensions(vectors, native_dim, dimensions)
        # dict를 그대로 반환하면 FastAPI가 수십만 개의 float을 파이썬으로 훑으며 변환해 느려진다
        return JSONResponse(server_core.build_response(vectors, served_name, prompt_tokens))

    return app


def parse_args(argv=None) -> argparse.Namespace:
    ap = argparse.ArgumentParser(description="OpenAI 호환 임베딩 서버 (프로세스당 모델 1개)")
    ap.add_argument("--model", required=True, help="HF 모델 id 또는 로컬 경로")
    ap.add_argument("--name", help="서빙 이름 — 요청의 model과 같아야 한다 (기본: --model)")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8100)
    ap.add_argument("--max-seq-length", type=int, default=2048, help="입력 상한 토큰 수 — 넘는 부분은 잘린다")
    ap.add_argument("--batch-size", type=int, default=16)
    ap.add_argument("--dtype", choices=["auto", "fp16", "fp32"], default="auto",
                    help="auto = cuda·mps는 fp16, cpu는 fp32")
    ap.add_argument("--trust-remote-code", action="store_true",
                    help="모델 저장소의 코드를 실행한다 — --revision으로 고정한 리비전에서만 켠다")
    ap.add_argument("--revision", help="모델 리비전 (커밋 해시 권장)")
    args = ap.parse_args(argv)
    args.name = args.name or args.model
    return args


def main(argv=None) -> None:
    args = parse_args(argv)
    # 루트는 WARNING으로 둔다. INFO로 열면 모델을 받을 때 HF Hub 요청이 한 줄씩 찍혀서 정작 읽어야 할
    # truncated 로그가 묻힌다. 이 서버의 로그만 INFO로 연다(uvicorn 로그는 uvicorn이 따로 설정한다).
    logging.basicConfig(level=logging.WARNING, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    logger.setLevel(logging.INFO)

    # 무거운 import는 여기서만 한다 (모듈 docstring 참고)
    import torch
    from sentence_transformers import SentenceTransformer

    device = server_core.pick_device(torch.cuda.is_available(), torch.backends.mps.is_available())
    dtype = server_core.resolve_dtype(args.dtype, device)
    logger.info("모델 로드: model=%s revision=%s device=%s dtype=%s trust_remote_code=%s",
                args.model, args.revision, device, dtype, args.trust_remote_code)

    model = SentenceTransformer(
        args.model,
        device=device,
        trust_remote_code=args.trust_remote_code,
        revision=args.revision,
        model_kwargs={"dtype": torch.float16 if dtype == "fp16" else torch.float32},
    )
    model.max_seq_length = args.max_seq_length
    encoder = SentenceTransformerEncoder(model)

    # 첫 요청 전에 한 번 돌려 본다. 실제 출력에서 차원을 얻고(모델 메타의 차원은 None일 수 있다), 커널 준비
    # 시간이 첫 측정에 섞이지 않게 하며, fp16에서 NaN이 나는 모델을 실험 도중이 아니라 기동 때 알아낸다.
    probe = encoder.encode(["warm-up"], batch_size=1)
    encoder.count_tokens(["warm-up"])
    if not np.isfinite(probe).all():
        sys.exit(f"{args.model}: {dtype}에서 NaN/Inf가 나옵니다 — --dtype fp32로 다시 시도하세요")
    native_dim = int(probe.shape[1])

    token = os.environ.get("EMBED_SERVER_TOKEN", "")
    logger.info("준비 완료: name=%s dim=%d device=%s dtype=%s (파라미터 %s) max_seq_length=%d batch_size=%d auth=%s",
                args.name, native_dim, device, dtype, model.dtype, args.max_seq_length, args.batch_size,
                "on" if token else "off")

    app = create_app(encoder, args.name, native_dim, token, args.max_seq_length, args.batch_size,
                     device=device, dtype=dtype)
    uvicorn.run(app, host=args.host, port=args.port, log_level="info")


if __name__ == "__main__":
    main()
