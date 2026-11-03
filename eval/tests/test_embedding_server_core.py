"""embedding_finetune/server_core.py 순수 함수 단위 테스트 (오프라인 — 모델·네트워크 불필요).

임베딩 서버의 이 부분이 틀리면 서버는 정상으로 뜨고 200을 돌려주는데 숫자만 조용히 틀어진다
(절단 뒤 재정규화 누락 → 그 후보만 점수가 낮게 나옴, 인증 우회, 입력 형식 오해 등).
그래서 경계와 방향을 값으로 고정한다.

실행: services/ai-engine/.venv/bin/python -m pytest eval/tests -q
"""

import json
import os
import sys

import numpy as np
import pytest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "embedding_finetune"))

from server_core import (  # noqa: E402
    apply_dimensions,
    build_response,
    check_auth,
    check_dimensions,
    parse_inputs,
    pick_device,
    resolve_dtype,
)


# ─── parse_inputs — 문자열 또는 문자열 목록만 받는다 ───────────────────────────


def test_parse_inputs_single_string_becomes_one_item_list():
    # 문자열을 그대로 list()에 넣으면 글자 단위로 쪼개진다 — 한 건으로 다뤄야 한다
    assert parse_inputs("안녕하세요") == ["안녕하세요"]


def test_parse_inputs_string_list_keeps_order():
    assert parse_inputs(["b", "a", "안녕"]) == ["b", "a", "안녕"]


def test_parse_inputs_returns_new_list():
    # 호출자가 만든 목록을 이후 처리가 건드리지 않도록 복사본을 돌려준다
    original = ["a", "b"]
    parsed = parse_inputs(original)
    parsed.append("c")
    assert original == ["a", "b"]


def test_parse_inputs_rejects_empty_list():
    with pytest.raises(ValueError):
        parse_inputs([])


def test_parse_inputs_rejects_token_id_array():
    # OpenAI는 토큰 id 배열도 받지만, 이 서버의 모델에는 뜻이 없어 에러 없이 엉뚱한 벡터가 나온다
    with pytest.raises(ValueError):
        parse_inputs([1, 2, 3])


def test_parse_inputs_rejects_batch_of_token_id_arrays():
    with pytest.raises(ValueError):
        parse_inputs([[1, 2], [3]])


def test_parse_inputs_rejects_mixed_list():
    with pytest.raises(ValueError):
        parse_inputs(["문자열", 42])


@pytest.mark.parametrize("payload", [None, 42, 1.5, {"text": "a"}, True])
def test_parse_inputs_rejects_non_string_non_list(payload):
    with pytest.raises(ValueError):
        parse_inputs(payload)


def test_parse_inputs_rejects_empty_string():
    # OpenAI도 빈 문자열을 거절한다 — 수집 쪽 버그가 "특수 토큰만 인코딩한 벡터"로 덮이면 찾을 수 없다
    with pytest.raises(ValueError):
        parse_inputs("")


def test_parse_inputs_rejects_empty_string_inside_list():
    with pytest.raises(ValueError):
        parse_inputs(["정상", ""])


def test_parse_inputs_accepts_whitespace_only_string():
    # 거절 기준은 OpenAI와 같은 "빈 문자열"뿐이다 — 공백만 있는 입력까지 막지는 않는다
    assert parse_inputs(" ") == [" "]


# ─── check_dimensions / apply_dimensions — MRL 절단 ──────────────────────────


def _vectors():
    # 열마다 값이 달라 앞쪽/뒤쪽 어느 쪽을 남겼는지 구분된다. 길이가 1이 아니라서(정규화 안 됨)
    # "그대로 둔다"와 "다시 정규화한다"도 구분된다.
    return np.array([[3.0, 4.0, 12.0, 0.0], [1.0, 2.0, 2.0, 100.0]], dtype=np.float32)


def test_apply_dimensions_none_returns_vectors_unchanged():
    v = _vectors()
    out = apply_dimensions(v, 4, None)
    assert np.array_equal(out, _vectors())


def test_apply_dimensions_equal_to_native_returns_vectors_unchanged():
    # 정규화돼 있지 않은 입력으로 확인한다 — 같은 차원인데 재정규화하면 값이 바뀐다
    out = apply_dimensions(_vectors(), 4, 4)
    assert np.array_equal(out, _vectors())


def test_apply_dimensions_smaller_keeps_leading_components_and_renormalizes():
    out = apply_dimensions(_vectors(), 4, 2)
    assert out.shape == (2, 2)
    # 앞 2개 성분의 방향만 남는다: (3,4)/5, (1,2)/sqrt(5)
    assert np.allclose(out[0], [0.6, 0.8])
    assert np.allclose(out[1], [1 / np.sqrt(5), 2 / np.sqrt(5)])


def test_apply_dimensions_smaller_result_has_unit_norm():
    out = apply_dimensions(_vectors(), 4, 3)
    assert np.allclose(np.linalg.norm(out, axis=1), 1.0, atol=1e-6)


def test_apply_dimensions_does_not_mutate_input():
    v = _vectors()
    apply_dimensions(v, 4, 2)
    assert np.array_equal(v, _vectors())


def test_apply_dimensions_preserves_float32():
    assert apply_dimensions(_vectors(), 4, 2).dtype == np.float32


def test_apply_dimensions_zero_prefix_row_stays_finite():
    # 앞쪽 성분이 전부 0인 행을 0으로 나누면 NaN이 되어 JSON 직렬화 단계에서 서버가 터진다
    v = np.array([[0.0, 0.0, 1.0, 1.0]], dtype=np.float32)
    out = apply_dimensions(v, 4, 2)
    assert np.isfinite(out).all()
    assert np.array_equal(out, [[0.0, 0.0]])


def test_apply_dimensions_larger_than_native_raises():
    with pytest.raises(ValueError):
        apply_dimensions(_vectors(), 4, 5)


@pytest.mark.parametrize("dimensions", [0, -1, -3])
def test_apply_dimensions_non_positive_raises(dimensions):
    # 통과시키면 [:, :-1]처럼 뒤에서부터 자른 벡터가 에러 없이 나간다
    with pytest.raises(ValueError):
        apply_dimensions(_vectors(), 4, dimensions)


@pytest.mark.parametrize("dimensions", [2.0, "2", True, [2]])
def test_apply_dimensions_non_integer_raises(dimensions):
    with pytest.raises(ValueError):
        apply_dimensions(_vectors(), 4, dimensions)


def test_check_dimensions_returns_value_or_none():
    assert check_dimensions(1024, None) is None
    assert check_dimensions(1024, 256) == 256
    assert check_dimensions(1024, 1024) == 1024


def test_check_dimensions_boundaries():
    assert check_dimensions(8, 1) == 1
    with pytest.raises(ValueError):
        check_dimensions(8, 9)


# ─── check_auth — 정확히 `Bearer <token>`만 통과 ─────────────────────────────


@pytest.mark.parametrize("header", [None, "", "Bearer anything", "garbage"])
def test_check_auth_empty_token_allows_everything(header):
    assert check_auth(header, "") is True
    assert check_auth(header, None) is True


def test_check_auth_exact_bearer_token_passes():
    assert check_auth("Bearer s3cret", "s3cret") is True


def test_check_auth_missing_header_fails():
    assert check_auth(None, "s3cret") is False


@pytest.mark.parametrize(
    "header",
    [
        "Bearer wrong",
        "s3cret",                # 스킴 없음
        "bearer s3cret",         # 스킴 대소문자 — 정확히 `Bearer`만 허용
        "Basic s3cret",
        "Bearer s3cret ",        # 끝 공백
        " Bearer s3cret",        # 앞 공백
        "Bearer  s3cret",        # 공백 두 칸
        "Bearer s3cretXYZ",      # 토큰이 접두로 시작
        "Bearer s3cre",          # 토큰의 앞부분만
        "Bearer",
        "",
    ],
)
def test_check_auth_anything_but_exact_bearer_fails(header):
    assert check_auth(header, "s3cret") is False


def test_check_auth_non_ascii_token_does_not_raise():
    # compare_digest는 비ASCII str에 TypeError를 낸다 — 토큰이 한글이어도 500이 아니라 정상 판정이어야 한다
    assert check_auth("Bearer 비밀토큰", "비밀토큰") is True
    assert check_auth("Bearer 다른토큰", "비밀토큰") is False


# ─── build_response — OpenAI 임베딩 응답 형식 ────────────────────────────────


def test_build_response_matches_openai_shape():
    v = np.array([[0.5, -0.25], [1.0, 0.0], [0.0, 1.0]], dtype=np.float32)
    resp = build_response(v, "my-model", 7)
    assert set(resp) == {"object", "data", "model", "usage"}
    assert resp["object"] == "list"
    assert resp["model"] == "my-model"
    assert resp["usage"] == {"prompt_tokens": 7, "total_tokens": 7}
    assert [d["index"] for d in resp["data"]] == [0, 1, 2]
    for d in resp["data"]:
        assert set(d) == {"object", "index", "embedding"}
        assert d["object"] == "embedding"
    assert resp["data"][0]["embedding"] == [0.5, -0.25]
    assert resp["data"][2]["embedding"] == [0.0, 1.0]


def test_build_response_embeddings_are_plain_float_lists():
    # numpy 스칼라가 섞이면 JSON 직렬화가 실패한다 — 엄격 모드로 직렬화해 확인
    v = np.array([[0.1, 0.2]], dtype=np.float32)
    resp = build_response(v, "m", 1)
    assert all(type(x) is float for x in resp["data"][0]["embedding"])
    json.dumps(resp, allow_nan=False)


def test_build_response_does_not_alter_values():
    v = np.array([[0.1, 0.123456789]], dtype=np.float32)
    resp = build_response(v, "m", 1)
    assert resp["data"][0]["embedding"] == v[0].tolist()


def test_build_response_single_vector():
    resp = build_response(np.ones((1, 3), dtype=np.float32), "m", 2)
    assert len(resp["data"]) == 1
    assert resp["data"][0]["index"] == 0


# ─── pick_device — cuda → mps → cpu ─────────────────────────────────────────


@pytest.mark.parametrize(
    "cuda, mps, expected",
    [
        (True, True, "cuda"),
        (True, False, "cuda"),
        (False, True, "mps"),
        (False, False, "cpu"),
    ],
)
def test_pick_device_priority(cuda, mps, expected):
    assert pick_device(cuda, mps) == expected


# ─── resolve_dtype — auto는 가속기 fp16, cpu fp32 ────────────────────────────


@pytest.mark.parametrize("device, expected", [("cuda", "fp16"), ("mps", "fp16"), ("cpu", "fp32")])
def test_resolve_dtype_auto(device, expected):
    assert resolve_dtype("auto", device) == expected


@pytest.mark.parametrize("device", ["cuda", "mps", "cpu"])
def test_resolve_dtype_explicit_wins_over_device(device):
    assert resolve_dtype("fp16", device) == "fp16"
    assert resolve_dtype("fp32", device) == "fp32"


def test_resolve_dtype_unknown_raises():
    with pytest.raises(ValueError):
        resolve_dtype("bf16", "cuda")
