"""precheck_compare.py 단위 테스트 (오프라인 — 파일 입출력은 tmp_path, Neo4j·OpenAI 불필요).

이 비교기는 관문 1(파인튜닝 베이스 선택)의 판정 근거를 만든다. 가장 조용히 틀리기 쉬운 곳은
"쌍 단위로 뽑아 구간이 실제보다 좁아지는 것"이라, 클러스터 단위 추출을 두 겹으로 고정한다:
  - 헬퍼(resample_clusters)가 한 클러스터의 쌍을 함께 넣고 함께 빼는지
  - 부트스트랩 본체가 그 헬퍼를 써서 쌍 단위로 뽑았을 때와 다른 결과를 내는지(건너뛴 재표본 수)

실행: services/ai-engine/.venv/Scripts/python.exe -m pytest eval/tests
"""

import json
import os
import random
import sys
from collections import Counter

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

import precheck_compare as pc  # noqa: E402


def _items(spec):
    """spec: [(클러스터, 관련 여부, 기준 점수, 후보 점수), ...]"""
    return [{"cluster": c, "relevant": rel, "ref": ref, "cand": cand} for c, rel, ref, cand in spec]


def _per_cluster(n_clusters, rel_ref, irr_ref, rel_cand, irr_cand):
    """클러스터마다 관련 1개·무관 1개 — 어떤 재표본이든 양쪽 클래스가 비지 않는다."""
    spec = []
    for i in range(n_clusters):
        spec.append((f"t{i}", True, rel_ref, rel_cand))
        spec.append((f"t{i}", False, irr_ref, irr_cand))
    return _items(spec)


# ─── 부트스트랩 결과 — 값의 의미 ────────────────────────────────────────────────


def test_same_scores_give_zero_difference_and_zero_width_interval():
    # 같은 점수를 같은 재표본에서 재면 어느 재표본에서도 차이가 0이다
    # (두 모델을 따로 뽑으면 이 값이 0이 아니게 되므로, 짝지은 비교인지도 함께 검증된다)
    scores = [(0.9, 0.2), (0.7, 0.4), (0.6, 0.5), (0.8, 0.1), (0.5, 0.45), (0.65, 0.3), (0.55, 0.52)]
    spec = []
    for i, (rel, irr) in enumerate(scores):
        spec += [(f"t{i}", True, rel, rel), (f"t{i}", False, irr, irr)]

    res = pc.bootstrap_auc_diff(_items(spec), resamples=300, seed=42)

    assert res["auc_reference"] == res["auc_candidate"]
    assert res["diff"] == 0
    assert res["ci_low"] == 0 and res["ci_high"] == 0


def test_perfect_candidate_against_inverted_reference_has_positive_lower_bound():
    items = _per_cluster(8, rel_ref=0.1, irr_ref=0.9, rel_cand=0.9, irr_cand=0.1)

    res = pc.bootstrap_auc_diff(items, resamples=300, seed=42)

    assert res["auc_candidate"] == 1.0 and res["auc_reference"] == 0.0
    assert res["diff"] == 1.0
    assert res["ci_low"] > 0


def test_interval_straddles_zero_when_models_are_equally_good_overall():
    # 절반의 클러스터에선 후보가, 나머지 절반에선 기준이 잘 가른다 — 전체 AUC는 같고(차이 0),
    # 클러스터를 다시 뽑으면 어느 쪽이 많이 뽑히느냐에 따라 부호가 갈린다. 구간이 한쪽으로 쏠리면 안 된다.
    spec = []
    for i in range(10):
        good, bad = (0.8, 0.2), (0.2, 0.8)
        cand, ref = (good, bad) if i < 5 else (bad, good)
        spec += [(f"t{i}", True, ref[0], cand[0]), (f"t{i}", False, ref[1], cand[1])]

    res = pc.bootstrap_auc_diff(_items(spec), resamples=500, seed=42)

    assert res["diff"] == 0
    assert res["ci_low"] < 0 < res["ci_high"]


def test_percentile_interval_is_the_2_5_to_97_5_range():
    low, high = pc.percentile_interval(list(range(101)))

    assert (low, high) == pytest.approx((2.5, 97.5))


def test_same_seed_reproduces_and_result_has_expected_fields():
    items = _per_cluster(6, rel_ref=0.6, irr_ref=0.5, rel_cand=0.7, irr_cand=0.4)

    first = pc.bootstrap_auc_diff(items, resamples=100, seed=7)
    second = pc.bootstrap_auc_diff(items, resamples=100, seed=7)

    assert first == second
    assert {"n_relevant", "n_irrelevant", "n_clusters", "auc_reference", "auc_candidate", "diff",
            "ci_low", "ci_high", "resamples", "resamples_skipped"} <= set(first)
    assert (first["n_relevant"], first["n_irrelevant"], first["n_clusters"]) == (6, 6, 6)


def test_no_negatives_leaves_auc_and_interval_undefined():
    res = pc.bootstrap_auc_diff(_items([("t0", True, 0.5, 0.6), ("t1", True, 0.4, 0.7)]), resamples=50, seed=42)

    assert res["auc_reference"] is None and res["diff"] is None
    assert res["ci_low"] is None and res["ci_high"] is None


# ─── 클러스터 단위 복원 추출 ────────────────────────────────────────────────────

# 크기가 제각각인 클러스터 5개 (인덱스 0~11)
CLUSTERS = ["a", "a", "a", "b", "b", "c", "c", "c", "c", "d", "e", "e"]


def test_resample_takes_whole_clusters_with_replacement():
    members: dict[str, list[int]] = {}
    for i, c in enumerate(CLUSTERS):
        members.setdefault(c, []).append(i)

    repeated = dropped = False
    for seed in range(200):
        picked = pc.resample_clusters(CLUSTERS, random.Random(seed))
        counts = Counter(picked)

        multiplicity = []
        for ms in members.values():
            # 한 클러스터의 쌍은 모두 같은 횟수로 들어온다 = 함께 들어오고 함께 빠진다.
            # 쌍 단위로 뽑으면 같은 클러스터 안에서도 횟수가 제각각이 된다.
            per_member = {counts[m] for m in ms}
            assert len(per_member) == 1, f"seed={seed}: 클러스터가 쪼개져 뽑혔다 {sorted(per_member)}"
            multiplicity.append(per_member.pop())

        assert sum(multiplicity) == len(members)    # 클러스터 수만큼 뽑는다
        repeated |= max(multiplicity) >= 2          # 복원 추출이라 같은 클러스터가 다시 들어오기도 하고
        dropped |= min(multiplicity) == 0           # 한 번도 안 들어오기도 한다

    assert repeated and dropped


def test_resample_is_reproducible_for_the_same_rng_seed():
    assert (pc.resample_clusters(CLUSTERS, random.Random(7))
            == pc.resample_clusters(CLUSTERS, random.Random(7)))


def test_bootstrap_draws_clusters_not_pairs():
    # 클러스터 A는 관련 쌍만, B는 무관 쌍만 갖는다. 클러스터 단위로 뽑으면 두 번 다 A이거나 두 번 다 B인
    # 재표본(절반)은 한쪽 클래스가 비어 건너뛴다. 쌍 단위로 뽑았다면 20쌍 중 한쪽이 전부 빠질 확률이
    # 거의 0이라 건너뛴 수가 0에 가깝다 — 본체가 헬퍼를 우회해 쌍 단위로 뽑으면 여기서 걸린다.
    items = _items([("A", True, 0.8, 0.9)] * 10 + [("B", False, 0.2, 0.1)] * 10)

    res = pc.bootstrap_auc_diff(items, resamples=400, seed=42)

    assert 120 <= res["resamples_skipped"] <= 280
    assert res["n_clusters"] == 2


def test_skipped_resamples_are_excluded_from_interval_not_counted_as_zero():
    # 쓸 수 있는 재표본(A와 B가 하나씩)에서는 후보가 완벽(AUC 1)하고 기준이 뒤집혀(AUC 0) 차이가 늘 +1이다.
    # 건너뛴 재표본을 차이 0으로 세면 구간 하한이 0으로 끌려가므로, 하한이 1이어야 제외한 것이다.
    items = _items([("A", True, 0.4, 0.9), ("B", False, 0.6, 0.1)])

    res = pc.bootstrap_auc_diff(items, resamples=200, seed=42)

    assert res["resamples_skipped"] > 0
    assert res["ci_low"] == 1.0 and res["ci_high"] == 1.0


# ─── 짝짓기 ─────────────────────────────────────────────────────────────────────


def _pair(edge_type, src, dst, label, score, kind="label"):
    return {"kind": kind, "edge_type": edge_type, "src": src, "dst": dst, "label": label, "score": score}


def _entry(pairs, precision=None):
    by_type = {et: {"precision_at_full_golden_recall": p} for et, p in (precision or {}).items()}
    return {"model": "m", "dimensions": None, "unresolved_pairs": 0, "by_type": by_type, "pair_scores": pairs}


def test_pairs_are_matched_by_edge_type_src_dst_and_unusable_ones_are_dropped():
    ref = _entry([
        _pair("REFERENCE", "c1", "t1", "relevant", 0.8),
        _pair("REFERENCE", "c2", "t1", "irrelevant", 0.3),
        _pair("REFERENCE", "c3", "t2", "relevant", 0.7),
        _pair("REFERENCE", "c4", "t3", "unsure", 0.5),                      # 라벨이 relevant/irrelevant가 아니다
        _pair("REFERENCE", "c5", "t4", "irrelevant", 0.2),                  # 후보에 없다
        _pair("REFERENCE", "g1", "t9", "golden", 0.9, kind="golden"),       # 골든은 AUC 쌍이 아니다
        _pair("DISCUSSED_IN", "HT-1", "t1", "relevant", 0.6),               # 다른 타입
    ])
    cand = _entry([
        _pair("REFERENCE", "c1", "t1", "relevant", 0.85),
        _pair("REFERENCE", "c2", "t1", "irrelevant", 0.35),
        _pair("REFERENCE", "c3", "t2", "relevant", 0.75),
        _pair("REFERENCE", "c4", "t3", "unsure", 0.4),
        _pair("REFERENCE", "c6", "t5", "irrelevant", 0.1),                  # 기준에 없다
    ])

    items, unpaired = pc.paired_items(ref, cand, "REFERENCE")

    assert [(i["cluster"], i["relevant"], i["ref"], i["cand"]) for i in items] == [
        ("t1", True, 0.8, 0.85), ("t1", False, 0.3, 0.35), ("t2", True, 0.7, 0.75)]
    assert unpaired == 2                                                    # c5(기준만) + c6(후보만)


def test_cluster_is_the_destination_node():
    ref = _entry([_pair("DISCUSSED_IN", "HT-1", "thread-A", "relevant", 0.6),
                  _pair("DISCUSSED_IN", "HT-2", "thread-A", "irrelevant", 0.4)])

    items, _ = pc.paired_items(ref, ref, "DISCUSSED_IN")

    assert {i["cluster"] for i in items} == {"thread-A"}


# ─── 파일 합치기 ────────────────────────────────────────────────────────────────


def _write(path, configs):
    path.write_text(json.dumps({"configs": configs}, ensure_ascii=False), encoding="utf-8")
    return str(path)


def test_files_are_merged_by_label_and_later_file_wins_with_a_warning(tmp_path):
    a = _write(tmp_path / "a.json", {"3-large@1536": _entry([], {"REFERENCE": 0.1}), "3-small": _entry([])})
    b = _write(tmp_path / "b.json", {"bge-m3": _entry([]), "3-large@1536": _entry([], {"REFERENCE": 0.2})})

    configs, warnings = pc.load_configs([a, b])

    assert list(configs) == ["3-large@1536", "3-small", "bge-m3"]
    assert configs["3-large@1536"]["by_type"]["REFERENCE"]["precision_at_full_golden_recall"] == 0.2
    assert len(warnings) == 1 and "3-large@1536" in warnings[0] and "b.json" in warnings[0]


def test_no_warning_when_labels_do_not_overlap(tmp_path):
    a = _write(tmp_path / "a.json", {"3-small": _entry([])})
    b = _write(tmp_path / "b.json", {"bge-m3": _entry([])})

    _, warnings = pc.load_configs([a, b])

    assert warnings == []


# ─── 비교 · 출력 ────────────────────────────────────────────────────────────────


def _full_entry(rel_score, irr_score, precision):
    pairs = []
    for et, src_prefix in (("REFERENCE", "c"), ("DISCUSSED_IN", "HT-"), ("TRIGGERED_BY", "k")):
        for i in range(4):
            pairs.append(_pair(et, f"{src_prefix}{i}", f"t{i}", "relevant", rel_score))
            pairs.append(_pair(et, f"{src_prefix}{i}x", f"t{i}", "irrelevant", irr_score))
    return _entry(pairs, precision)


PRECISION = {"REFERENCE": 0.5, "DISCUSSED_IN": 0.6, "TRIGGERED_BY": 0.7}


def test_compare_entries_reports_each_type_with_precision_from_by_type():
    ref = _full_entry(0.4, 0.6, PRECISION)
    cand = _full_entry(0.9, 0.1, {"REFERENCE": 0.9, "DISCUSSED_IN": None, "TRIGGERED_BY": 0.8})

    per_type = pc.compare_entries(ref, cand, resamples=100, seed=42)

    assert list(per_type) == ["REFERENCE", "DISCUSSED_IN", "TRIGGERED_BY"]
    assert [row["advisory"] for row in per_type.values()] == [False, False, True]    # 라벨이 적은 TRIGGERED_BY만 참고용
    ref_row = per_type["REFERENCE"]
    assert ref_row["precision_reference"] == 0.5 and ref_row["precision_candidate"] == 0.9
    assert per_type["DISCUSSED_IN"]["precision_candidate"] is None
    assert ref_row["diff"] == 1.0 and ref_row["ci_low"] > 0
    assert (ref_row["n_relevant"], ref_row["n_irrelevant"]) == (4, 4)


def test_table_marks_only_triggered_by_as_reference_and_survives_missing_values():
    per_type = pc.compare_entries(_full_entry(0.4, 0.6, PRECISION), _full_entry(0.9, 0.1, {}),
                                  resamples=50, seed=42)

    table = pc.format_table("3-large@1536", "bge-m3", per_type)

    rows = {line.split("|")[1].strip(): line for line in table.splitlines() if line.startswith("| ")}
    assert "TRIGGERED_BY (참고)" in rows
    assert "REFERENCE" in rows and "REFERENCE (참고)" not in rows
    assert "DISCUSSED_IN" in rows and "DISCUSSED_IN (참고)" not in rows
    assert "0.500 → -" in rows["REFERENCE"]    # 후보 쪽 precision 값이 없는 칸은 '-'로 찍힌다
    assert "bge-m3" in table and "3-large@1536" in table


def test_table_footnotes_report_dropped_pairs_and_skipped_resamples():
    ref = _entry([_pair("REFERENCE", "c1", "A", "relevant", 0.4), _pair("REFERENCE", "c2", "B", "irrelevant", 0.6),
                  _pair("REFERENCE", "c3", "C", "irrelevant", 0.5)])          # c3는 후보에 없다
    cand = _entry([_pair("REFERENCE", "c1", "A", "relevant", 0.9), _pair("REFERENCE", "c2", "B", "irrelevant", 0.1)])

    per_type = pc.compare_entries(ref, cand, resamples=100, seed=42)
    table = pc.format_table("3-large@1536", "bge-m3", per_type)

    assert "REFERENCE: 한쪽 결과에만 있는 라벨 쌍 1개" in table
    assert "REFERENCE: 관련 또는 무관 쌍이 빈 재표본" in table
    assert "DISCUSSED_IN: 한쪽" not in table      # 문제가 없는 타입은 각주를 달지 않는다


def test_main_writes_json_and_prints_table(tmp_path, capsys):
    base = _write(tmp_path / "base.json", {"3-large@1536": _full_entry(0.4, 0.6, PRECISION)})
    cand = _write(tmp_path / "cand.json", {"bge-m3": _full_entry(0.9, 0.1, PRECISION)})
    out = tmp_path / "compare.json"

    pc.main([base, cand, "--resamples", "50", "--seed", "1", "--out", str(out)])

    printed = capsys.readouterr().out
    assert "| REFERENCE |" in printed and "TRIGGERED_BY (참고)" in printed
    doc = json.loads(out.read_text(encoding="utf-8"))
    assert doc["reference"] == "3-large@1536"
    assert (doc["resamples"], doc["seed"]) == (50, 1)
    assert list(doc["candidates"]) == ["bge-m3"]                 # 기준 자신은 후보로 비교하지 않는다
    row = doc["candidates"]["bge-m3"]["REFERENCE"]
    assert row["diff"] == 1.0 and row["precision_reference"] == 0.5


def test_main_exits_with_available_labels_when_reference_is_missing(tmp_path):
    only = _write(tmp_path / "only.json", {"bge-m3": _full_entry(0.9, 0.1, PRECISION)})

    with pytest.raises(SystemExit) as exc:
        pc.main([only, "--out", str(tmp_path / "x.json")])

    assert "3-large@1536" in str(exc.value.code) and "bge-m3" in str(exc.value.code)
