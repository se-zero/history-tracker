"""compare.py 핵심 로직 단위 테스트 (오프라인 — 파일·네트워크 불필요).

비교기는 채택/롤백 판정의 계측기다 — classify의 방향(환각은 낮을수록 좋음)이나
노이즈 플로어 경계가 뒤집히면 롤백할 것을 채택한다. 판정 경로를 여기서 고정한다.

실행: services/ai-engine/.venv/Scripts/python.exe -m pytest eval/tests
"""

import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

from compare import AGGREGATE_FLOOR, CASE_FLOOR, classify, device_warnings, paired_deltas


# ─── classify — 방향·노이즈 경계 ────────────────────────────────────────────────


def test_recall_up_is_improvement():
    assert classify("recall", +0.30) == "improved"
    assert classify("recall", -0.30) == "regressed"


def test_hallucination_down_is_improvement():
    # 환각률은 낮을수록 좋다 — 방향이 뒤집히면 회귀를 개선으로 판정한다
    assert classify("hallucination_rate", -0.40) == "improved"
    assert classify("hallucination_rate", +0.40) == "regressed"


def test_delta_within_noise_floor_is_noise():
    assert classify("recall", CASE_FLOOR["recall"]) == "noise"          # 경계값 포함
    assert classify("recall", -CASE_FLOOR["recall"]) == "noise"
    assert classify("recall", CASE_FLOOR["recall"] + 0.001) == "improved"


def test_default_floor_is_the_stricter_case_floor():
    # 기본값이 느슨한 집계 기준으로 새면 케이스별 판정이 조용히 과다 검출된다
    # (2026-08-21 실측: 집계용 값을 케이스별에 쓰던 동안 recall 오탐 60%).
    delta = (AGGREGATE_FLOOR["recall"] + CASE_FLOOR["recall"]) / 2
    assert classify("recall", delta) == "noise"
    assert classify("recall", delta, AGGREGATE_FLOOR) == "improved"


def test_aggregate_floor_is_tighter_than_case_floor():
    for metric, agg in AGGREGATE_FLOOR.items():
        assert agg < CASE_FLOOR[metric], metric


def test_none_delta_is_na():
    assert classify("recall", None) == "n/a"


# ─── paired_deltas — 교집합·None 전파 ──────────────────────────────────────────


def test_paired_deltas_intersection_only():
    base = {"case-01": {"recall": 0.5}, "case-02": {"recall": 0.3}}
    cand = {"case-01": {"recall": 0.7}, "case-03": {"recall": 0.9}}
    deltas = paired_deltas(base, cand)
    assert set(deltas) == {"case-01"}
    assert deltas["case-01"]["recall"] == 0.2


def test_paired_deltas_none_propagates():
    # expected_facts 없는 케이스는 fact_accuracy가 None — 델타도 None(n/a)이어야 한다
    base = {"c": {"recall": 0.5, "fact_accuracy": None}}
    cand = {"c": {"recall": 0.5, "fact_accuracy": 0.8}}
    assert paired_deltas(base, cand)["c"]["fact_accuracy"] is None


# ─── device_warnings — 측정 장치 불일치 감지 ───────────────────────────────────


def _scores(meta=None, judge=None):
    return {
        "meta": {"golden_version": "aaa", "graph_snapshot": "g.dump", "project_id": "p",
                 "query_model": "m", "runs_per_case": 3, "git_commit": "abc12345",
                 **(meta or {})},
        "judge": {"model": "j", "prompt_sha": "s", "skipped": False, **(judge or {})},
    }


def test_identical_devices_no_warnings():
    assert device_warnings(_scores(), _scores()) == []


def test_golden_version_mismatch_warned():
    ws = device_warnings(_scores(), _scores(meta={"golden_version": "bbb"}))
    assert any("골든셋" in w for w in ws)


def test_judge_prompt_change_warned():
    ws = device_warnings(_scores(), _scores(judge={"prompt_sha": "zzz"}))
    assert any("judge" in w for w in ws)


def test_skip_judge_warned():
    ws = device_warnings(_scores(), _scores(judge={"skipped": True}))
    assert any("skip-judge" in w for w in ws)


def test_runs_and_dirty_warned():
    ws = device_warnings(_scores(), _scores(meta={"runs_per_case": 1, "git_dirty": True}))
    assert any("runs_per_case" in w for w in ws)
    assert any("dirty" in w for w in ws)
