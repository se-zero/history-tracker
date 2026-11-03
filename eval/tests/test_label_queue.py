"""label_queue.py 단위 테스트 (오프라인 — 파일 입출력은 tmp_path, Neo4j 불필요).

큐는 사람이 모델과 점수를 모른 채 라벨링하게 만드는 장치다. 출처(점수·버킷)가 새거나, 라벨이 엉뚱한
쌍에 붙거나, 이미 있는 라벨이 덮어써지면 이후 precision 비교가 조용히 오염된다.

실행: services/ai-engine/.venv/Scripts/python.exe -m pytest eval/tests
"""

import os
import sys

import pytest
import yaml

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

import label_queue as lq  # noqa: E402


def _entry(edge_type, src, dst, label=None, note=None, confidence=0.45):
    """sample_edges.build_entry가 만드는 항목과 같은 모양 (원문은 자리표시)."""
    return {
        "edge_type": edge_type,
        "confidence": confidence,
        "bucket": "0.40-0.50",
        "src": {"type": "commit", "id": src, "body": {"message": f"src {src}"}},
        "dst": {"type": "message", "id": dst, "body": {"messages": [{"body": f"dst {dst}"}]}},
        "label": label,
        "note": note,
    }


def _keys(entries):
    return [(e["edge_type"], str(e["src"]["id"]), str(e["dst"]["id"])) for e in entries]


# ─── make — 큐 구성 ─────────────────────────────────────────────────────────────


def test_queue_collects_only_unlabeled_pairs_and_dedupes_across_samples():
    base = [_entry("REFERENCE", "aaaaaaa", "1.1", label="relevant"),
            _entry("REFERENCE", "bbbbbbb", "2.2"),
            _entry("REFERENCE", "ccccccc", "3.3")]
    cand = [_entry("REFERENCE", "bbbbbbb", "2.2", confidence=0.9),      # 두 그래프에 다 있는 새 쌍 — 한 번만
            _entry("REFERENCE", "ddddddd", "4.4"),
            _entry("DISCUSSED_IN", "HT-1", "5.5", label="unsure")]      # 라벨이 있으면(unsure도) 대상이 아니다

    queue = lq.build_queue([base, cand], seed=42)

    assert sorted(_keys(queue)) == [("REFERENCE", "bbbbbbb", "2.2"), ("REFERENCE", "ccccccc", "3.3"),
                                    ("REFERENCE", "ddddddd", "4.4")]


def test_queue_entries_carry_no_hint_of_model_or_score():
    queue = lq.build_queue([[_entry("REFERENCE", "bbbbbbb", "2.2", confidence=0.3333)]], seed=42)

    assert set(queue[0]) == {"edge_type", "src", "dst", "label", "note"}
    assert queue[0]["label"] is None and queue[0]["note"] is None
    assert queue[0]["src"]["body"] == {"message": "src bbbbbbb"}        # 라벨링에 필요한 원문은 남는다
    assert "0.3333" not in yaml.safe_dump(queue)


def test_queue_order_is_shuffled_but_reproducible_and_independent_of_input_order():
    base = [_entry("REFERENCE", f"s{i:02d}", f"{i}.5") for i in range(0, 10)]
    cand = [_entry("REFERENCE", f"s{i:02d}", f"{i}.5") for i in range(10, 20)]

    first = lq.build_queue([base, cand], seed=42)
    reversed_inputs = lq.build_queue([list(reversed(cand)), list(reversed(base))], seed=42)

    assert _keys(first) == _keys(reversed_inputs)
    assert _keys(first) != sorted(_keys(first))                          # 기준선 쌍이 앞, 후보 쌍이 뒤로 몰리지 않는다
    assert _keys(first) != _keys(lq.build_queue([base, cand], seed=43))


def test_queue_does_not_modify_its_inputs():
    base = [_entry("REFERENCE", "bbbbbbb", "2.2")]

    lq.build_queue([base], seed=42)

    assert base == [_entry("REFERENCE", "bbbbbbb", "2.2")]


# ─── fill — 라벨 되돌려 채우기 ──────────────────────────────────────────────────


def test_fill_labels_only_unlabeled_entries_and_never_overwrites_existing_labels():
    queue = [_entry("REFERENCE", "bbbbbbb", "2.2", label="irrelevant", note="무관"),
             _entry("REFERENCE", "ccccccc", "3.3", label="relevant"),
             _entry("REFERENCE", "ddddddd", "4.4"),                       # 아직 라벨링 안 한 큐 항목
             _entry("REFERENCE", "eeeeeee", "5.5", label="irrelevant")]
    entries = [_entry("REFERENCE", "bbbbbbb", "2.2"),                     # 채워진다
               _entry("REFERENCE", "aaaaaaa", "1.1", label="relevant", note="기존"),   # 큐에 없다 — 그대로
               _entry("REFERENCE", "ddddddd", "4.4"),                     # 큐 항목에 라벨이 없다 — 비어 있다
               _entry("REFERENCE", "eeeeeee", "5.5", label="relevant")]   # 이미 다른 라벨이 있다 — 유지하고 알린다

    labels = lq.queue_labels(queue)
    stats = lq.fill_entries(entries, labels)

    assert (entries[0]["label"], entries[0]["note"]) == ("irrelevant", "무관")
    assert (entries[1]["label"], entries[1]["note"]) == ("relevant", "기존")
    assert entries[2]["label"] is None
    assert entries[3]["label"] == "relevant"
    assert stats == {"filled": 1, "kept": 2, "conflicts": 1, "unlabeled": 1}


def test_fill_matches_ids_as_strings():
    # 사람이 편집하다 따옴표가 빠져 float로 읽히는 id도 같은 쌍으로 맞아야 한다
    queue = [_entry("REFERENCE", "bbbbbbb", 1781600092.933319, label="relevant")]
    entries = [_entry("REFERENCE", "bbbbbbb", "1781600092.933319")]

    lq.fill_entries(entries, lq.queue_labels(queue))

    assert entries[0]["label"] == "relevant"


def test_fill_does_not_cross_edge_types():
    queue = [_entry("REFERENCE", "bbbbbbb", "2.2", label="relevant")]
    entries = [_entry("TRIGGERED_BY", "bbbbbbb", "2.2")]

    lq.fill_entries(entries, lq.queue_labels(queue))

    assert entries[0]["label"] is None


def test_invalid_labels_are_reported_with_the_pair():
    labels = lq.queue_labels([_entry("REFERENCE", "bbbbbbb", "2.2", label="relavant"),      # 오타
                              _entry("REFERENCE", "ccccccc", "3.3", label=" Relevant "),    # edge_eval은 strip·lower로 받는다
                              _entry("REFERENCE", "ddddddd", "4.4", label="unsure")])

    invalid = lq.invalid_labels(labels)

    assert len(invalid) == 1 and "bbbbbbb" in invalid[0] and "relavant" in invalid[0]


# ─── 파일 · CLI ─────────────────────────────────────────────────────────────────


def _write_sample(path, entries):
    doc = {"graph_snapshot": "graph-x.dump", "project_id": "pid", "seed": 42, "edges": entries}
    path.write_text(lq.sample_edges.HEADER + yaml.safe_dump(doc, allow_unicode=True, sort_keys=False), encoding="utf-8")


def test_make_writes_a_queue_with_label_criteria_and_refuses_to_overwrite(tmp_path):
    base, cand, out = tmp_path / "base.yaml", tmp_path / "cand.yaml", tmp_path / "queue.yaml"
    _write_sample(base, [_entry("REFERENCE", "bbbbbbb", "2.2"), _entry("REFERENCE", "aaaaaaa", "1.1", label="relevant")])
    _write_sample(cand, [_entry("REFERENCE", "bbbbbbb", "2.2"), _entry("DISCUSSED_IN", "HT-1", "5.5")])

    lq.main(["make", str(base), str(cand), "--out", str(out)])

    text = out.read_text(encoding="utf-8")
    assert "relevant" in text and "irrelevant" in text and "unsure" in text        # 헤더에 라벨 기준
    doc = yaml.safe_load(text)
    assert len(doc["edges"]) == 2 and "confidence" not in text and "bucket" not in text

    out.write_text(text + "\n# 사람이 라벨링한 흔적\n", encoding="utf-8")
    with pytest.raises(SystemExit):                                                  # 라벨링 중인 큐를 덮어쓰지 않는다
        lq.main(["make", str(base), str(cand), "--out", str(out)])
    assert out.read_text(encoding="utf-8").endswith("# 사람이 라벨링한 흔적\n")


def test_make_writes_nothing_when_every_pair_is_already_labeled(tmp_path, capsys):
    base, out = tmp_path / "base.yaml", tmp_path / "queue.yaml"
    _write_sample(base, [_entry("REFERENCE", "aaaaaaa", "1.1", label="relevant")])

    lq.main(["make", str(base), "--out", str(out)])

    assert not out.exists()
    assert "라벨 없는" in capsys.readouterr().out


def test_fill_writes_new_files_and_keeps_the_originals_byte_identical(tmp_path):
    base, cand, queue = tmp_path / "base.yaml", tmp_path / "cand.yaml", tmp_path / "queue.yaml"
    _write_sample(base, [_entry("REFERENCE", "aaaaaaa", "1.1", label="relevant", note="기존"),
                         _entry("REFERENCE", "bbbbbbb", "2.2")])
    _write_sample(cand, [_entry("REFERENCE", "bbbbbbb", "2.2"), _entry("DISCUSSED_IN", "HT-1", "5.5")])
    original = {p: p.read_bytes() for p in (base, cand)}

    lq.main(["make", str(base), str(cand), "--out", str(queue)])
    labeled_queue = yaml.safe_load(queue.read_text(encoding="utf-8"))
    for e in labeled_queue["edges"]:                                                 # 사람이 하는 일을 흉내
        e["label"], e["note"] = ("relevant", "메모") if e["edge_type"] == "REFERENCE" else ("irrelevant", None)
    queue.write_text(yaml.safe_dump(labeled_queue, allow_unicode=True, sort_keys=False), encoding="utf-8")

    lq.main(["fill", "--queue", str(queue), str(base), str(cand)])

    assert {p: p.read_bytes() for p in (base, cand)} == original                     # 원본 보존
    out_base = yaml.safe_load((tmp_path / "base.labeled.yaml").read_text(encoding="utf-8"))
    out_cand = yaml.safe_load((tmp_path / "cand.labeled.yaml").read_text(encoding="utf-8"))
    assert out_base["project_id"] == "pid" and out_base["seed"] == 42               # 최상위 필드 유지
    assert [(e["label"], e["note"]) for e in out_base["edges"]] == [("relevant", "기존"), ("relevant", "메모")]
    assert [(e["label"], e["note"]) for e in out_cand["edges"]] == [("relevant", "메모"), ("irrelevant", None)]
    assert out_cand["edges"][0]["confidence"] == 0.45                                # 표본 파일의 다른 필드는 그대로


def test_fill_can_write_into_another_directory(tmp_path):
    base, queue, out_dir = tmp_path / "base.yaml", tmp_path / "queue.yaml", tmp_path / "labeled"
    _write_sample(base, [_entry("REFERENCE", "bbbbbbb", "2.2")])
    _write_sample(queue, [_entry("REFERENCE", "bbbbbbb", "2.2", label="relevant")])

    lq.main(["fill", "--queue", str(queue), str(base), "--out-dir", str(out_dir)])

    assert (out_dir / "base.labeled.yaml").exists()
    assert not (tmp_path / "base.labeled.yaml").exists()


def test_fill_exits_when_the_queue_has_no_labels_yet(tmp_path):
    base, queue = tmp_path / "base.yaml", tmp_path / "queue.yaml"
    _write_sample(base, [_entry("REFERENCE", "bbbbbbb", "2.2")])
    _write_sample(queue, [_entry("REFERENCE", "bbbbbbb", "2.2")])

    with pytest.raises(SystemExit):
        lq.main(["fill", "--queue", str(queue), str(base)])

    assert not (tmp_path / "base.labeled.yaml").exists()
