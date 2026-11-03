"""sample_edges.py 플래그(--uniform · --reuse-labels · --blind · --graph-snapshot) 단위 테스트 (오프라인 — Neo4j 불필요).

세 플래그는 임베딩 모델을 바꾼 그래프의 precision을 공정하게 재는 표본을 만든다. 추출이 재현되지 않거나
라벨이 어긋난 쌍에 붙거나 점수가 새어 나가면, 사람이 쓴 라벨이 엉뚱한 쌍·편향된 판단으로 남는다.
--graph-snapshot은 그 표본을 뽑은 그래프 이름을 남긴다 — 틀리면 채점 결과가 엉뚱한 그래프를 가리킨다.
그래프 조회(fetch_edges·fetch_evidence_body)만 대체하고 main의 배선까지 실제 경로로 확인한다.

실행: services/ai-engine/.venv/Scripts/python.exe -m pytest eval/tests
"""

import os
import random
import sys

import yaml

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

import sample_edges as se  # noqa: E402


def _edges(n, conf=0.45):
    return [{"src_id": f"s{i:03d}", "dst_id": f"t{i % 7}", "confidence": conf} for i in range(n)]


def _entry(edge_type, src, dst, confidence=0.45):
    """build_entry가 만드는 항목과 같은 모양 (원문은 자리표시)."""
    return {
        "edge_type": edge_type,
        "confidence": confidence,
        "bucket": "0.40-0.50",
        "src": {"type": "commit", "id": src, "body": {"message": f"src {src}"}},
        "dst": {"type": "message", "id": dst, "body": {"messages": [{"body": f"dst {dst}"}]}},
        "label": None,
        "note": None,
    }


# ─── --uniform ──────────────────────────────────────────────────────────────────


def test_uniform_sample_is_reproducible_for_a_fixed_seed_and_has_the_requested_size():
    edges = _edges(100)

    first = se.uniform_sample(edges, 10, random.Random(42))
    # 조회 순서는 그래프 질의마다 달라질 수 있다 — 입력 순서가 바뀌어도 같은 표본이어야 재현된다
    second = se.uniform_sample(list(reversed(edges)), 10, random.Random(42))

    assert len(first) == 10 and first == second
    assert len({(e["src_id"], e["dst_id"]) for e in first}) == 10
    assert first != se.uniform_sample(edges, 10, random.Random(43))


def test_uniform_sample_returns_every_edge_when_there_are_fewer_than_target():
    edges = _edges(4)

    picked = se.uniform_sample(edges, 10, random.Random(42))

    assert sorted(e["src_id"] for e in picked) == [e["src_id"] for e in edges]


def test_uniform_sample_keeps_edges_that_fall_outside_every_bucket():
    # 새 모델의 점수가 하드코딩된 버킷(REFERENCE는 0.30부터) 밖이면 층화 추출은 그 엣지를 조용히 버린다.
    # 균등 추출은 점수를 보지 않으므로 그런 엣지도 표본에 든다.
    buckets = se.EDGE_SPECS["REFERENCE"]["buckets"]
    edges = _edges(20, conf=0.25)

    assert se.stratified_sample(edges, buckets, 10, random.Random(42)) == []
    assert len(se.uniform_sample(edges, 10, random.Random(42))) == 10


# ─── --reuse-labels ─────────────────────────────────────────────────────────────

OLD_LABELS = """\
graph_snapshot: graph-2026-07-21.dump
edges:
- edge_type: REFERENCE
  src: {type: commit, id: aaaaaaa}
  dst: {type: message, id: 1781600092.933319}      # 따옴표가 없으면 YAML이 float로 읽는다
  label: relevant
  note: 같은 사안
- edge_type: REFERENCE
  src: {type: commit, id: bbbbbbb}
  dst: {type: message, id: '1781600093.000100'}
  label: irrelevant
  note: null
- edge_type: DISCUSSED_IN
  src: {type: issue, id: HT-1}
  dst: {type: message, id: '5.5'}
  label: null                                      # 라벨이 비어 있으면 가져올 것이 없다
  note: 메모만 있음
"""


def test_reuse_labels_fills_label_and_note_when_type_and_both_ids_match(tmp_path):
    old = tmp_path / "old.yaml"
    old.write_text(OLD_LABELS, encoding="utf-8")
    entries = [
        _entry("REFERENCE", "aaaaaaa", "1781600092.933319"),        # 숫자형 id — 문자열로 비교해 맞아야 한다
        _entry("REFERENCE", "bbbbbbb", "1781600093.000100"),
        _entry("DISCUSSED_IN", "HT-1", "5.5"),                       # 기존 쪽 라벨이 비어 있다
        _entry("REFERENCE", "ccccccc", "9.9"),                       # 새 쌍
        _entry("TRIGGERED_BY", "aaaaaaa", "1781600092.933319"),      # 타입이 다르면 다른 쌍이다
    ]

    reused = se.apply_reused_labels(entries, se.load_reusable_labels(str(old)))

    assert reused == 2
    assert (entries[0]["label"], entries[0]["note"]) == ("relevant", "같은 사안")
    assert (entries[1]["label"], entries[1]["note"]) == ("irrelevant", None)
    assert all(e["label"] is None and e["note"] is None for e in entries[2:])


def test_reused_labels_do_not_touch_other_fields(tmp_path):
    old = tmp_path / "old.yaml"
    old.write_text(OLD_LABELS, encoding="utf-8")
    entry = _entry("REFERENCE", "aaaaaaa", "1781600092.933319", confidence=0.4321)

    se.apply_reused_labels([entry], se.load_reusable_labels(str(old)))

    assert entry["confidence"] == 0.4321 and entry["bucket"] == "0.40-0.50"
    assert entry["src"]["body"] == {"message": "src aaaaaaa"}


# ─── --blind ────────────────────────────────────────────────────────────────────


def test_blind_entry_drops_confidence_and_bucket_only():
    entry = _entry("REFERENCE", "aaaaaaa", "1.1")

    blinded = se.blind_entry(entry)

    assert "confidence" not in blinded and "bucket" not in blinded
    assert set(blinded) == set(entry) - {"confidence", "bucket"}
    assert "confidence" in entry      # 원본은 그대로


# ─── main — 플래그 배선 ─────────────────────────────────────────────────────────


class _Ctx:
    """open_driver()가 돌려주는 드라이버·세션 자리 — with 문만 통과시킨다."""

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def session(self):
        return self


def _pool(conf):
    """타입별 고유 엣지 풀 — 타입마다 12개."""
    return {
        ("commit", "message"): [{"src_id": f"c{i:02d}", "dst_id": f"{100 + i}.5", "confidence": conf} for i in range(12)],
        ("issue", "message"): [{"src_id": f"HT-{i}", "dst_id": f"{200 + i}.5", "confidence": conf} for i in range(12)],
        ("commit", "issue"): [{"src_id": f"d{i:02d}", "dst_id": f"HT-{50 + i}", "confidence": conf} for i in range(12)],
    }


def _run_main(tmp_path, monkeypatch, pool, *flags):
    out = tmp_path / "out.yaml"
    monkeypatch.setattr(se, "open_driver", lambda: _Ctx())
    monkeypatch.setattr(se, "fetch_edges", lambda session, pid, spec: pool[(spec["src_type"], spec["dst_type"])])
    monkeypatch.setattr(se, "fetch_evidence_body", lambda session, pid, id_type, value: {"stub": f"{id_type}:{value}"})
    monkeypatch.setattr(sys, "argv", ["sample_edges.py", "--project-id", "pid", "--per-type", "3",
                                      "--out", str(out), *flags])

    se.main()

    return yaml.safe_load(out.read_text(encoding="utf-8"))


def test_main_default_behavior_is_unchanged(tmp_path, monkeypatch):
    # 버킷 안에 있는 엣지(0.45)와 밖에 있는 엣지(0.25)가 섞여 있다 — 층화 추출은 버킷 안에서만 뽑는다
    pool = _pool(conf=0.45)
    pool[("commit", "message")] += [{"src_id": f"low{i}", "dst_id": f"{900 + i}.5", "confidence": 0.25} for i in range(6)]

    doc = _run_main(tmp_path, monkeypatch, pool)

    assert len(doc["edges"]) == 9
    assert all("confidence" in e and "bucket" in e for e in doc["edges"])
    assert all(e["label"] is None and e["note"] is None for e in doc["edges"])
    assert not any(e["src"]["id"].startswith("low") for e in doc["edges"])
    assert doc["graph_snapshot"] == "graph-2026-07-05.dump"      # 플래그가 없으면 예전에 고정돼 있던 이름 그대로


def test_main_with_all_three_flags(tmp_path, monkeypatch):
    # 모든 엣지가 버킷 밖(0.25) — 기본 동작이면 표본이 비지만 --uniform이면 타입당 3개씩 나온다
    pool = _pool(conf=0.25)
    old = tmp_path / "old.yaml"
    old.write_text(yaml.safe_dump({"edges": [
        {"edge_type": "REFERENCE", "src": {"id": e["src_id"]}, "dst": {"id": e["dst_id"]},
         "label": "relevant", "note": "재사용"}
        for e in pool[("commit", "message")]]}), encoding="utf-8")        # REFERENCE 쌍 전부에 라벨이 있다

    doc = _run_main(tmp_path, monkeypatch, pool, "--uniform", "--reuse-labels", str(old), "--blind")

    edges = doc["edges"]
    assert len(edges) == 9
    assert doc["seed"] == 42
    assert all("confidence" not in e and "bucket" not in e for e in edges)
    by_type = {t: [e for e in edges if e["edge_type"] == t] for t in ("REFERENCE", "DISCUSSED_IN", "TRIGGERED_BY")}
    assert all(len(v) == 3 for v in by_type.values())
    assert all(e["label"] == "relevant" and e["note"] == "재사용" for e in by_type["REFERENCE"])
    assert all(e["label"] is None for e in by_type["DISCUSSED_IN"] + by_type["TRIGGERED_BY"])


def test_main_records_the_graph_snapshot_given_by_flag(tmp_path, monkeypatch):
    # edge_eval이 이 값을 결과 JSON의 meta로 옮긴다 — 실험 그래프의 표본에 옛 덤프 이름이 남으면 수치가 엉뚱한 그래프를 가리킨다
    doc = _run_main(tmp_path, monkeypatch, _pool(conf=0.45), "--graph-snapshot", "exp-base-2026-11-03.dump")

    assert doc["graph_snapshot"] == "exp-base-2026-11-03.dump"


def test_main_uniform_sample_is_reproducible_across_runs(tmp_path, monkeypatch):
    pool = _pool(conf=0.45)

    first = _run_main(tmp_path, monkeypatch, pool, "--uniform")
    second = _run_main(tmp_path, monkeypatch, pool, "--uniform")

    assert first == second
