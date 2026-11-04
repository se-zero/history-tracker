"""precheck_embedding_model.py 순수 로직 단위 테스트 (오프라인 — Neo4j·OpenAI·임베딩 서버 불필요).

후보 레지스트리·--only 해석·클라이언트 선택·재현 검증은 모델 비교 결과를 읽는 방식 자체를 정한다.
라벨이 틀리거나 재현 검증이 엉뚱한 기준과 대조하면, 나머지 숫자가 멀쩡해 보여도 비교가 무효다.
.env를 읽는 경로는 전부 대체해 두므로 이 테스트는 파일·네트워크를 건드리지 않는다.

실행: services/ai-engine/.venv/Scripts/python.exe -m pytest eval/tests
"""

import os
import sys
import types

import numpy as np
import pytest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

import precheck_embedding_model as pre  # noqa: E402

LOCAL_URL = "http://127.0.0.1:8100/v1"


# ─── 레지스트리 ─────────────────────────────────────────────────────────────────


def test_registry_keeps_existing_labels_and_adds_four_local_candidates():
    by = {c["label"]: c for c in pre.CONFIGS}

    assert by["3-small"]["model"] == "text-embedding-3-small" and by["3-small"]["dimensions"] is None
    assert by["3-large@1536"]["model"] == "text-embedding-3-large" and by["3-large@1536"]["dimensions"] == 1536
    assert by["3-large@3072"]["model"] == "text-embedding-3-large" and by["3-large@3072"]["dimensions"] is None

    local = {
        "bge-m3": "BAAI/bge-m3",
        "kure-v1": "nlpai-lab/KURE-v1",
        "qwen3-0.6b": "Qwen/Qwen3-Embedding-0.6B",
        "kanana-1.3b": "kakaocorp/kanana-2-1.3b-embedding",
    }
    for label, model in local.items():
        assert by[label]["model"] == model, label        # 모델 이름 오타는 서버가 400으로 거절한다
        assert by[label]["dimensions"] is None, label
        assert by[label]["base_url"] == LOCAL_URL, label

    assert all(set(c) == {"label", "model", "dimensions", "base_url"} for c in pre.CONFIGS)
    assert len(by) == len(pre.CONFIGS)                    # 라벨은 결과 JSON의 키라 중복되면 덮어쓴다


def test_fine_tuned_model_is_registered_under_the_name_serve_py_is_started_with():
    # 서빙 이름(`serve.py --name qwen3-0.6b-ft`)과 model이 다르면 서버가 400(model_mismatch)으로 거절한다
    config = next(c for c in pre.CONFIGS if c["label"] == "qwen3-0.6b-ft")

    assert config == {"label": "qwen3-0.6b-ft", "model": "qwen3-0.6b-ft", "dimensions": None, "base_url": LOCAL_URL}


def test_current_label_is_the_production_setting():
    # 재현 검증이 대조하는 설정 — 운영(embedder.py)과 같은 3-large@1536이어야 의미가 있다
    current = next(c for c in pre.CONFIGS if c["label"] == pre.CURRENT_LABEL)
    assert (current["model"], current["dimensions"], current["base_url"]) == ("text-embedding-3-large", 1536, None)


# ─── --only 해석 ────────────────────────────────────────────────────────────────


def test_without_only_runs_openai_configs_as_before():
    selected = pre.select_configs(None)

    assert [c["label"] for c in selected] == ["3-small", "3-large@1536", "3-large@3072"]
    assert all(c["base_url"] is None for c in selected)   # 서버 없이 도는 기존 동작


def test_only_keeps_given_order_and_drops_duplicates():
    selected = pre.select_configs("3-large@1536, bge-m3,3-large@1536")

    assert [c["label"] for c in selected] == ["3-large@1536", "bge-m3"]


def test_unknown_label_exits_and_lists_available_labels():
    with pytest.raises(SystemExit) as exc:
        pre.select_configs("bge-m3,nope")

    message = str(exc.value.code)
    assert "nope" in message
    for config in pre.CONFIGS:
        assert config["label"] in message


def test_empty_only_is_an_error_not_silently_the_default():
    with pytest.raises(SystemExit):
        pre.select_configs(" , ")


def test_two_local_candidates_in_one_run_are_rejected():
    # 서버는 프로세스당 모델 하나라 두 번째 후보에서 400이 나고, 그때까지의 결과는 저장되지 않는다
    with pytest.raises(SystemExit) as exc:
        pre.select_configs("bge-m3,kure-v1")

    assert "bge-m3" in str(exc.value.code) and "kure-v1" in str(exc.value.code)


def test_one_local_candidate_can_run_with_openai_configs():
    selected = pre.select_configs("3-large@1536,bge-m3")

    assert [c["label"] for c in selected] == ["3-large@1536", "bge-m3"]


# ─── 출력 파일 이름 ─────────────────────────────────────────────────────────────


def test_default_out_path_is_unchanged_without_only():
    path = pre.default_out_path(pre.select_configs(None), only_given=False)

    assert os.path.basename(path) == "precheck-embedding-model.json"
    assert os.path.basename(os.path.dirname(path)) == "results"


def test_default_out_path_names_the_labels_when_only_is_given():
    # 후보별로 따로 돌려도 서로 덮어쓰지 않게 라벨이 파일 이름에 들어간다
    one = pre.default_out_path(pre.select_configs("bge-m3"), only_given=True)
    two = pre.default_out_path(pre.select_configs("3-large@1536,bge-m3"), only_given=True)

    assert os.path.basename(one) == "precheck-bge-m3.json"
    assert os.path.basename(two) == "precheck-3-large@1536+bge-m3.json"


# ─── 클라이언트 ─────────────────────────────────────────────────────────────────


class _FakeOpenAI:
    created: list[dict] = []

    def __init__(self, **kwargs):
        _FakeOpenAI.created.append(kwargs)


@pytest.fixture
def fake_openai(monkeypatch):
    _FakeOpenAI.created = []
    monkeypatch.setattr("openai.OpenAI", _FakeOpenAI)
    return _FakeOpenAI


def _cfg(label):
    return next(c for c in pre.CONFIGS if c["label"] == label)


def test_local_client_uses_base_url_and_placeholder_key_without_openai_key(monkeypatch, fake_openai):
    monkeypatch.delenv("EMBED_SERVER_TOKEN", raising=False)
    monkeypatch.setattr(pre, "ensure_openai_key", lambda: pytest.fail("로컬 후보는 OpenAI 키를 요구하지 않는다"))

    pre.make_client(_cfg("bge-m3"))

    assert fake_openai.created == [{"base_url": LOCAL_URL, "api_key": "unused"}]


def test_local_client_sends_server_token_when_set(monkeypatch, fake_openai):
    monkeypatch.setenv("EMBED_SERVER_TOKEN", "tok")
    monkeypatch.setattr(pre, "ensure_openai_key", lambda: None)

    pre.make_client(_cfg("kure-v1"))

    assert fake_openai.created == [{"base_url": LOCAL_URL, "api_key": "tok"}]


def test_openai_client_requires_key_and_has_no_base_url(monkeypatch, fake_openai):
    required = []
    monkeypatch.setattr(pre, "ensure_openai_key", lambda: required.append(True))

    pre.make_client(_cfg("3-large@1536"))

    assert required == [True]
    assert fake_openai.created == [{}]    # 키·주소는 OPENAI_API_KEY 환경변수와 SDK 기본값에 맡긴다


def test_ensure_key_skips_dotenv_when_env_is_set(monkeypatch):
    monkeypatch.setenv("OPENAI_API_KEY", "sk-env")
    monkeypatch.setattr("dotenv.dotenv_values", lambda *_: pytest.fail("환경변수가 있으면 .env를 읽지 않는다"))

    pre.ensure_openai_key()

    assert os.environ["OPENAI_API_KEY"] == "sk-env"


def test_ensure_key_falls_back_to_dotenv_then_exits_when_missing(monkeypatch):
    monkeypatch.setenv("OPENAI_API_KEY", "placeholder")   # 이 테스트가 끝나면 원래 상태로 복원된다
    monkeypatch.delenv("OPENAI_API_KEY")
    monkeypatch.setattr("dotenv.dotenv_values", lambda *_: {"OPENAI_API_KEY": "sk-from-dotenv"})

    pre.ensure_openai_key()
    assert os.environ["OPENAI_API_KEY"] == "sk-from-dotenv"

    monkeypatch.delenv("OPENAI_API_KEY")
    monkeypatch.setattr("dotenv.dotenv_values", lambda *_: {})
    with pytest.raises(SystemExit):
        pre.ensure_openai_key()


# ─── embed_all — 서버와의 계약 ──────────────────────────────────────────────────


class _FakeEmbeddings:
    def __init__(self):
        self.requests = []
        self.embeddings = self    # client.embeddings.create(...)

    def create(self, **kwargs):
        self.requests.append(kwargs)
        return types.SimpleNamespace(data=[types.SimpleNamespace(embedding=[1.0, 0.0]) for _ in kwargs["input"]])


def test_embed_all_omits_dimensions_when_none_and_batches(monkeypatch):
    client = _FakeEmbeddings()
    monkeypatch.setattr(pre, "BATCH", 2)

    vectors = pre.embed_all(client, ["a", "b", "c"], "BAAI/bge-m3", None)

    assert len(vectors) == 3
    assert [r["input"] for r in client.requests] == [["a", "b"], ["c"]]
    assert all(r["model"] == "BAAI/bge-m3" and "dimensions" not in r for r in client.requests)


def test_embed_all_sends_dimensions_when_given():
    client = _FakeEmbeddings()

    pre.embed_all(client, ["a"], "text-embedding-3-large", 1536)

    assert client.requests[0]["dimensions"] == 1536


# ─── 재현 검증 — 현재 그래프의 confidence와 대조 ────────────────────────────────


def test_fetch_stored_confidences_keeps_only_pairs_with_an_edge(monkeypatch):
    calls = []

    def fake_exists(session, pid, edge_type, src, dst):
        calls.append((pid, edge_type, src, dst))
        return 0.5 if dst == "t1" else None

    monkeypatch.setattr(pre, "edge_exists", fake_exists)
    labels = [
        {"edge_type": "REFERENCE", "src": {"id": "aaaaaaa"}, "dst": {"id": "t1"}},
        # YAML에서 따옴표 없이 적히면 float로 읽힌다 — 그래프 질의는 문자열 id로 해야 한다
        {"edge_type": "DISCUSSED_IN", "src": {"id": "HT-1"}, "dst": {"id": 1781600092.933319}},
    ]

    stored = pre.fetch_stored_confidences(None, "pid", labels)

    assert stored == {("REFERENCE", "aaaaaaa", "t1"): 0.5}
    assert calls[1] == ("pid", "DISCUSSED_IN", "HT-1", "1781600092.933319")


def _label(edge_type, src, dst, score, kind="label"):
    return {"kind": kind, "edge_type": edge_type, "src": src, "dst": dst, "label": "relevant", "score": score}


def test_replication_check_compares_label_pairs_that_have_an_edge():
    stored = {("REFERENCE", "a", "t1"): 0.50, ("REFERENCE", "b", "t2"): 0.40, ("DISCUSSED_IN", "HT-1", "t3"): 0.60}
    pair_scores = [
        _label("REFERENCE", "a", "t1", 0.52),
        _label("REFERENCE", "b", "t2", 0.37),
        _label("DISCUSSED_IN", "HT-1", "t3", 0.61),
        _label("REFERENCE", "c", "t9", 0.10),                      # 그래프에 엣지가 없는 쌍 — 대조 대상이 아니다
        _label("REFERENCE", "a", "t1", 0.99, kind="golden"),       # 골든은 대조하지 않는다
    ]

    rep = pre.replication_check(stored, pair_scores)

    assert set(rep) == {"n", "mean_abs_diff", "corr"}
    assert rep["n"] == 3
    assert rep["mean_abs_diff"] == pytest.approx((0.02 + 0.03 + 0.01) / 3)
    expected_corr = np.corrcoef([0.50, 0.40, 0.60], [0.52, 0.37, 0.61])[0, 1]
    assert rep["corr"] == pytest.approx(expected_corr)


def test_replication_check_is_none_without_any_edge():
    assert pre.replication_check({}, [_label("REFERENCE", "a", "t1", 0.5)]) is None
