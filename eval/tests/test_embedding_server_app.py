"""embedding_finetune/serve.py 의 create_app 테스트 — 가짜 encoder로 서버 로직만 검증한다.

torch·sentence-transformers·모델 다운로드 없이 돈다(serve.py는 무거운 import를 main() 안에서만 한다).
검증 대상은 "모델이 무엇을 내든 서버가 지켜야 하는 것"이다: 인증이 입력 검증보다 먼저인지, 다른 모델로
잰 결과가 엉뚱한 라벨로 남지 않게 막는지, 입력 순서와 벡터 순서가 맞는지, 잘린 건수가 기록되는지.
실제 모델·장치(MPS)는 embedding_finetune/README.md의 "확인" 절차로 서버를 띄워 확인한다.

실행: services/ai-engine/.venv/bin/python -m pytest eval/tests -q
"""

import logging
import os
import sys
import threading
import time
import zlib

import numpy as np
import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "embedding_finetune"))

import serve  # noqa: E402

NAME = "fake-model"
DIM = 8


def unit_vector(text: str, dim: int = DIM) -> np.ndarray:
    """텍스트마다 다르고 호출마다 같은 단위 벡터 — 입력 순서와 벡터 순서의 대응을 확인하는 데 쓴다."""
    rng = np.random.default_rng(zlib.crc32(text.encode("utf-8")))
    v = rng.normal(size=dim).astype(np.float32)
    return v / np.linalg.norm(v)


class FakeEncoder:
    """create_app이 요구하는 두 메서드만 가진 가짜. 글자 수를 토큰 수로 친다."""

    def __init__(self, delay: float = 0.0, nan: bool = False):
        self.delay = delay
        self.nan = nan
        self.encode_calls: list[tuple[list[str], int]] = []
        self._guard = threading.Lock()
        self._in_flight = 0
        self.max_in_flight = 0

    def _work(self):
        # 동시에 몇 개가 안에 들어와 있었는지 센다 — 서버가 요청을 직렬화하는지 보려는 것
        with self._guard:
            self._in_flight += 1
            self.max_in_flight = max(self.max_in_flight, self._in_flight)
        time.sleep(self.delay)
        with self._guard:
            self._in_flight -= 1

    def count_tokens(self, texts):
        self._work()
        return [len(t) for t in texts]

    def encode(self, texts, batch_size):
        self.encode_calls.append((list(texts), batch_size))
        self._work()
        if self.nan:
            return np.full((len(texts), DIM), np.nan, dtype=np.float32)
        return np.stack([unit_vector(t) for t in texts])


def make_client(encoder=None, *, token="", max_seq_length=100, batch_size=16):
    encoder = encoder or FakeEncoder()
    app = serve.create_app(encoder, NAME, DIM, token, max_seq_length, batch_size, device="cpu", dtype="fp32")
    return TestClient(app), encoder


def post(client, body, headers=None):
    return client.post("/v1/embeddings", json=body, headers=headers or {})


def server_records(caplog):
    return [r for r in caplog.records if r.name == "embed_server"]


# ─── /health ────────────────────────────────────────────────────────────────


def test_health_reports_serving_state():
    client, _ = make_client(max_seq_length=2048)
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json() == {"model": NAME, "dim": DIM, "device": "cpu", "dtype": "fp32", "max_seq_length": 2048}


def test_health_does_not_require_token():
    client, _ = make_client(token="s3cret")
    assert client.get("/health").status_code == 200


# ─── 인증 ───────────────────────────────────────────────────────────────────


def test_no_token_configured_accepts_requests_without_authorization():
    client, _ = make_client(token="")
    assert post(client, {"model": NAME, "input": "a"}).status_code == 200


def test_wrong_token_is_401_and_does_not_encode():
    client, encoder = make_client(token="s3cret")
    body = {"model": NAME, "input": "a"}
    for headers in ({}, {"Authorization": "Bearer wrong"}, {"Authorization": "s3cret"}):
        resp = post(client, body, headers)
        assert resp.status_code == 401
        assert resp.json()["error"]["code"] == "invalid_api_key"
    assert encoder.encode_calls == []


def test_correct_token_is_accepted():
    client, _ = make_client(token="s3cret")
    resp = post(client, {"model": NAME, "input": "a"}, {"Authorization": "Bearer s3cret"})
    assert resp.status_code == 200


def test_auth_is_checked_before_body_and_model_validation():
    # 토큰이 틀린 요청에는 본문이 깨졌든 모델이 다르든 400이 아니라 401이어야 한다
    client, _ = make_client(token="s3cret")
    bad = {"Authorization": "Bearer wrong"}
    assert client.post("/v1/embeddings", content=b"not json", headers=bad).status_code == 401
    assert post(client, {"model": "other", "input": []}, bad).status_code == 401


# ─── 입력·모델 검증 ──────────────────────────────────────────────────────────


def test_model_mismatch_is_400_and_names_served_model():
    client, encoder = make_client()
    resp = post(client, {"model": "text-embedding-3-large", "input": "a"})
    assert resp.status_code == 400
    assert NAME in resp.json()["error"]["message"]
    assert encoder.encode_calls == []


def test_missing_model_is_400():
    client, encoder = make_client()
    assert post(client, {"input": "a"}).status_code == 400
    assert encoder.encode_calls == []


@pytest.mark.parametrize(
    "body",
    [
        {"model": NAME},                           # input 없음
        {"model": NAME, "input": None},
        {"model": NAME, "input": []},
        {"model": NAME, "input": ""},
        {"model": NAME, "input": [1, 2, 3]},       # 토큰 id 배열
        {"model": NAME, "input": [[1, 2], [3]]},
        {"model": NAME, "input": ["정상", 7]},
    ],
)
def test_invalid_input_is_400(body):
    client, encoder = make_client()
    resp = post(client, body)
    assert resp.status_code == 400
    assert resp.json()["error"]["type"] == "invalid_request_error"
    assert encoder.encode_calls == []


def test_non_json_body_is_400():
    client, _ = make_client()
    assert client.post("/v1/embeddings", content=b"not json").status_code == 400


def test_json_body_that_is_not_an_object_is_400():
    client, _ = make_client()
    assert post(client, ["a", "b"]).status_code == 400


@pytest.mark.parametrize("dimensions", [DIM + 1, 0, -1, "4", 4.5, True])
def test_invalid_dimensions_is_400_before_encoding(dimensions):
    # 어차피 실패할 요청에 모델 계산을 쓰지 않는다
    client, encoder = make_client()
    resp = post(client, {"model": NAME, "input": "a", "dimensions": dimensions})
    assert resp.status_code == 400
    assert encoder.encode_calls == []


# ─── 정상 응답 ──────────────────────────────────────────────────────────────


def test_response_has_openai_embedding_shape():
    client, _ = make_client()
    resp = post(client, {"model": NAME, "input": ["하나", "two"]})
    assert resp.status_code == 200
    data = resp.json()
    assert data["object"] == "list"
    assert data["model"] == NAME
    assert [d["index"] for d in data["data"]] == [0, 1]
    assert all(d["object"] == "embedding" and len(d["embedding"]) == DIM for d in data["data"])
    assert set(data["usage"]) == {"prompt_tokens", "total_tokens"}


def test_vectors_follow_input_order():
    client, _ = make_client()
    texts = ["첫째", "second", "셋째 문장입니다"]
    data = post(client, {"model": NAME, "input": texts}).json()["data"]
    for text, item in zip(texts, data):
        assert np.allclose(item["embedding"], unit_vector(text), atol=1e-6)


def test_single_string_input_returns_one_vector():
    client, _ = make_client()
    data = post(client, {"model": NAME, "input": "hello"}).json()["data"]
    assert len(data) == 1
    assert np.allclose(data[0]["embedding"], unit_vector("hello"), atol=1e-6)


def test_same_input_gives_same_vector_across_requests():
    client, _ = make_client()
    first = post(client, {"model": NAME, "input": ["안녕", "hello"]}).json()["data"]
    second = post(client, {"model": NAME, "input": ["안녕", "hello"]}).json()["data"]
    assert first == second


def test_extra_openai_fields_are_ignored():
    # SDK는 encoding_format(기본 base64)·user를 실어 보낸다 — 거절하지 않고 항상 float로 답한다
    client, _ = make_client()
    resp = post(client, {"model": NAME, "input": "a", "encoding_format": "base64", "user": "u1"})
    assert resp.status_code == 200
    assert all(isinstance(x, float) for x in resp.json()["data"][0]["embedding"])


def test_batch_size_is_forwarded_to_encoder():
    client, encoder = make_client(batch_size=7)
    post(client, {"model": NAME, "input": ["a", "b"]})
    assert encoder.encode_calls == [(["a", "b"], 7)]


# ─── dimensions (MRL 절단) ───────────────────────────────────────────────────


def test_dimensions_truncates_and_renormalizes():
    client, _ = make_client()
    emb = np.array(post(client, {"model": NAME, "input": "hello", "dimensions": 3}).json()["data"][0]["embedding"])
    full = unit_vector("hello")
    assert emb.shape == (3,)
    assert np.isclose(np.linalg.norm(emb), 1.0, atol=1e-6)
    assert np.allclose(emb, full[:3] / np.linalg.norm(full[:3]), atol=1e-6)


def test_dimensions_equal_to_native_returns_full_vector():
    client, _ = make_client()
    emb = post(client, {"model": NAME, "input": "hello", "dimensions": DIM}).json()["data"][0]["embedding"]
    assert np.allclose(emb, unit_vector("hello"), atol=1e-6)


# ─── 토큰 집계·절단 로그 ──────────────────────────────────────────────────────


def test_prompt_tokens_caps_each_input_at_max_seq_length():
    # 3 + min(8, 5) + 5 = 13 — 잘린 부분은 모델이 보지 않았으므로 세지 않는다
    client, _ = make_client(max_seq_length=5)
    usage = post(client, {"model": NAME, "input": ["abc", "abcdefgh", "abcde"]}).json()["usage"]
    assert usage == {"prompt_tokens": 13, "total_tokens": 13}


def test_truncated_inputs_are_counted_and_logged_as_warning(caplog):
    client, _ = make_client(max_seq_length=5)
    with caplog.at_level(logging.INFO, logger="embed_server"):
        # 길이 5는 상한과 같으므로 잘리지 않고, 6과 8만 잘린다
        post(client, {"model": NAME, "input": ["abcde", "abcdef", "abcdefgh", "ab"]})
    records = server_records(caplog)
    assert len(records) == 1
    assert "truncated=2" in records[0].getMessage()
    assert "inputs=4" in records[0].getMessage()
    assert records[0].levelno == logging.WARNING


def test_no_truncation_is_logged_as_info_with_zero(caplog):
    client, _ = make_client(max_seq_length=5)
    with caplog.at_level(logging.INFO, logger="embed_server"):
        post(client, {"model": NAME, "input": ["abcde", "ab"]})
    records = server_records(caplog)
    assert len(records) == 1
    assert "truncated=0" in records[0].getMessage()
    assert records[0].levelno == logging.INFO


def test_log_does_not_contain_token_or_input_text(caplog):
    client, _ = make_client(token="s3cret-token-value")
    with caplog.at_level(logging.DEBUG, logger="embed_server"):
        post(client, {"model": NAME, "input": ["민감한 본문 내용"]}, {"Authorization": "Bearer s3cret-token-value"})
    logged = "\n".join(r.getMessage() for r in server_records(caplog))
    assert "s3cret-token-value" not in logged
    assert "민감한 본문 내용" not in logged


# ─── 이상 출력·동시 요청 ──────────────────────────────────────────────────────


def test_non_finite_embeddings_are_reported_as_500_with_fp32_hint():
    client, _ = make_client(FakeEncoder(nan=True))
    resp = post(client, {"model": NAME, "input": "a"})
    assert resp.status_code == 500
    assert "fp32" in resp.json()["error"]["message"]


def test_concurrent_requests_are_serialized():
    # 토크나이저·가속기 모델에는 한 번에 한 요청만 들어간다 (create_app의 lock 주석 참고)
    client, encoder = make_client(FakeEncoder(delay=0.03))
    statuses = []

    def call():
        statuses.append(post(client, {"model": NAME, "input": ["a", "b"]}).status_code)

    threads = [threading.Thread(target=call) for _ in range(6)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert statuses == [200] * 6
    assert encoder.max_in_flight == 1


# ─── OpenAI SDK 왕복 — 이 서버의 존재 이유(코드 그대로 모델만 교체)를 확인 ─────────


def test_openai_sdk_default_request_roundtrip():
    # SDK는 encoding_format을 안 주면 base64를 요청하고, 응답이 문자열이 아니면 float 목록을 그대로 둔다
    openai = pytest.importorskip("openai")
    client, _ = make_client()
    sdk = openai.OpenAI(base_url="http://testserver/v1", api_key="x", http_client=client, max_retries=0)

    resp = sdk.embeddings.create(model=NAME, input=["안녕", "hello"])
    assert [d.index for d in resp.data] == [0, 1]
    assert np.allclose(resp.data[0].embedding, unit_vector("안녕"), atol=1e-6)
    assert np.allclose(resp.data[1].embedding, unit_vector("hello"), atol=1e-6)
    assert resp.model == NAME
    assert resp.usage.total_tokens == len("안녕") + len("hello")

    # ai-engine의 embed()가 쓰는 경로(with_raw_response → parse)와 dimensions 인자
    raw = sdk.embeddings.with_raw_response.create(model=NAME, input="hello", dimensions=3)
    assert len(raw.parse().data[0].embedding) == 3

    with pytest.raises(openai.BadRequestError):
        sdk.embeddings.create(model="text-embedding-3-large", input="a")


def test_openai_sdk_with_wrong_token_raises_authentication_error():
    openai = pytest.importorskip("openai")
    client, _ = make_client(token="s3cret")
    sdk = openai.OpenAI(base_url="http://testserver/v1", api_key="wrong", http_client=client, max_retries=0)
    with pytest.raises(openai.AuthenticationError):
        sdk.embeddings.create(model=NAME, input="a")


# ─── SentenceTransformerEncoder 어댑터 — 가짜 ST로 호출 규약만 확인 ─────────────


class FakeTokenizer:
    """공백으로 토큰을 나누고 특수 토큰 2개를 더한다. truncation=True면 4토큰에서 자른다."""

    def __call__(self, texts, add_special_tokens=True, truncation=False, verbose=True):
        ids = []
        for t in texts:
            n = len(t.split()) + (2 if add_special_tokens else 0)
            ids.append(list(range(min(n, 4) if truncation else n)))
        return {"input_ids": ids}


class FakeSentenceTransformer:
    tokenizer = FakeTokenizer()

    def __init__(self):
        self.encode_kwargs = None

    def encode(self, texts, **kwargs):
        self.encode_kwargs = kwargs
        return np.zeros((len(texts), 3), dtype=np.float32)


def test_adapter_counts_untruncated_tokens_including_special_tokens():
    # model.tokenize()처럼 이미 잘린 길이를 세면 상한을 넘는 입력을 영영 못 센다
    adapter = serve.SentenceTransformerEncoder(FakeSentenceTransformer())
    assert adapter.count_tokens(["a b c d e f", "x"]) == [8, 3]


def test_adapter_encodes_normalized_with_given_batch_size():
    model = FakeSentenceTransformer()
    out = serve.SentenceTransformerEncoder(model).encode(["a", "b"], 5)
    assert out.shape == (2, 3)
    assert model.encode_kwargs["normalize_embeddings"] is True
    assert model.encode_kwargs["batch_size"] == 5


# ─── CLI 인자·import 부작용 ───────────────────────────────────────────────────


def test_parse_args_defaults():
    args = serve.parse_args(["--model", "BAAI/bge-m3"])
    assert (args.host, args.port) == ("127.0.0.1", 8100)
    assert (args.max_seq_length, args.batch_size) == (2048, 16)
    assert args.dtype == "auto"
    assert args.trust_remote_code is False
    assert args.revision is None
    assert args.name == "BAAI/bge-m3"


def test_parse_args_explicit_name_and_flags():
    args = serve.parse_args(
        ["--model", "x/y", "--name", "alias", "--trust-remote-code", "--revision", "abc123", "--dtype", "fp32"]
    )
    assert args.name == "alias"
    assert args.trust_remote_code is True
    assert args.revision == "abc123"
    assert args.dtype == "fp32"


def test_parse_args_rejects_unknown_dtype():
    with pytest.raises(SystemExit):
        serve.parse_args(["--model", "x", "--dtype", "bf16"])


def test_importing_serve_does_not_load_heavy_libraries():
    # main() 밖에서 torch를 import하면 이 테스트들이 모델 환경 없이는 돌지 않는다
    assert "torch" not in sys.modules
    assert "sentence_transformers" not in sys.modules
