#!/usr/bin/env python
"""e2e eval 결과 비교기 — 두 채점 결과의 paired 비교 (docs/measurement.md 3.4의 5단계 자동화).

grader.py가 만든 scores.json 두 개를 받아, 집계 델타와 **케이스별 짝지은 비교**를
노이즈 플로어 기준으로 판정해 출력한다. 집계 평균만 보면 5개 좋아지고 5개 나빠진
상쇄를 놓친다 — 케이스별 개선/악화 카운트를 항상 함께 본다(측정 원칙).

측정 장치가 다른 비교(골든셋 버전·그래프 스냅샷·모델·judge 상이)는 그 사실을
경고로 명시한다 — 조용히 사과 대 오렌지를 비교하는 것을 막는다.

실행 (순수 stdlib — venv 불필요):
    python eval/compare.py eval/results/<baseline-run-id> eval/results/<candidate-run-id>
"""

import argparse
import json
import os
import sys

# Windows 콘솔(cp949)에서 em-dash 등 출력 크래시 방지
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

# 노이즈 플로어 — 두 런의 델타가 run-to-run 변동으로 설명되는 범위. 이 값 이하는 효과로
# 인정하지 않는다. **집계와 케이스별은 자가 다르다** — 집계 델타는 45케이스×3런을 평균한 값이라
# 표준오차가 √(3C)로 줄지만, 케이스별 델타는 3런 평균 하나의 차라 훨씬 크게 출렁인다.
# 하나의 값을 양쪽에 쓰면 케이스별 판정이 무의미해진다(2026-08-21 실측: 효과 없는 짝
# 20260821T024434Z↔060820Z에서 recall 오탐 60%, 25/42건).
#
# 산출: 기준선 20260821T060820Z(45케이스×3런)의 케이스 내부 분산을 pool해 σ를 구하고,
#   집계   = 2σ√(2/(3C))   케이스별 = 2σ√(2/3)      (두 평균 '차이'의 2σ)
# σ 실측: recall 0.136 · precision 0.171 · 환각 0.188 · 사실 0.157 · 규칙 0.235
# 기준선을 새로 잡으면 같은 방법으로 두 표를 함께 갱신한다.
AGGREGATE_FLOOR = {
    "recall": 0.034,
    "precision": 0.042,
    "hallucination_rate": 0.046,
    "fact_accuracy": 0.040,
    "rule_pass_rate": 0.057,
}

CASE_FLOOR = {
    "recall": 0.222,
    "precision": 0.279,
    "hallucination_rate": 0.307,
    "fact_accuracy": 0.256,
    "rule_pass_rate": 0.383,
}

# 방향 — hallucination_rate만 낮을수록 좋다.
LOWER_IS_BETTER = {"hallucination_rate"}

METRICS = ["recall", "precision", "hallucination_rate", "fact_accuracy", "rule_pass_rate"]

COUNTER_KEYS = [
    "cases_with_contamination",
    "cases_with_forbidden_facts",
    "runs_structured_null",
    "runs_with_format_violations",
    "runs_with_direct_quotes",
    "runs_with_internal_term_replacements",
    "runs_with_internal_terms_left",
]


def load_scores(results_dir: str) -> dict:
    path = os.path.join(results_dir, "scores.json")
    if not os.path.exists(path):
        sys.exit(f"scores.json 없음: {path} — 먼저 grader.py로 채점하세요.")
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def case_means(scores: dict) -> dict[str, dict]:
    return {c["case_id"]: c["mean"] for c in scores.get("cases", [])}


def classify(metric: str, delta: float | None, floors: dict[str, float] = None) -> str:
    """델타를 노이즈 플로어·방향 기준으로 판정: improved | regressed | noise | n/a.

    floors를 명시하지 않으면 케이스별 기준을 쓴다 — 집계 판정은 호출부가 AGGREGATE_FLOOR를
    넘겨야 한다. 기본값을 느슨한 쪽(집계)으로 두면 케이스별 판정이 조용히 과다 검출된다.
    """
    if delta is None:
        return "n/a"
    floor = (floors if floors is not None else CASE_FLOOR).get(metric, 0.0)
    if abs(delta) <= floor:
        return "noise"
    good = delta < 0 if metric in LOWER_IS_BETTER else delta > 0
    return "improved" if good else "regressed"


def paired_deltas(base: dict[str, dict], cand: dict[str, dict]) -> dict[str, dict]:
    """양쪽에 다 있는 케이스의 metric별 델타(cand - base). 한쪽이 None이면 None."""
    out: dict[str, dict] = {}
    for case_id in sorted(set(base) & set(cand)):
        deltas = {}
        for m in METRICS:
            b, c = base[case_id].get(m), cand[case_id].get(m)
            deltas[m] = round(c - b, 4) if (b is not None and c is not None) else None
        out[case_id] = deltas
    return out


def device_warnings(base_scores: dict, cand_scores: dict) -> list[str]:
    """측정 장치 차이 경고 — 다르면 점수 델타를 시스템 변경의 효과로 읽을 수 없는 항목들."""
    warnings = []
    bm, cm = base_scores.get("meta", {}), cand_scores.get("meta", {})

    if bm.get("golden_version") != cm.get("golden_version"):
        warnings.append(
            f"골든셋 버전 상이: {bm.get('golden_version')} vs {cm.get('golden_version')} "
            f"— 같은 자로 재려면 한쪽 응답을 현재 골든으로 재채점하세요 (grader.py 재실행)."
        )
    for key, label in [
        ("graph_snapshot", "그래프 스냅샷"),
        ("project_id", "project_id"),
        ("query_model", "질의 모델"),
    ]:
        if bm.get(key) != cm.get(key):
            warnings.append(f"{label} 상이: {bm.get(key) or '?'} vs {cm.get(key) or '?'}")

    bj, cj = base_scores.get("judge", {}), cand_scores.get("judge", {})
    if (bj.get("model"), bj.get("prompt_sha")) != (cj.get("model"), cj.get("prompt_sha")):
        warnings.append(
            f"judge 상이: {bj.get('model')}/{bj.get('prompt_sha')} vs {cj.get('model')}/{cj.get('prompt_sha')}"
        )
    if bj.get("skipped") or cj.get("skipped"):
        warnings.append("한쪽 이상이 --skip-judge 채점 — LLM 지표(환각·사실·규칙)는 비교 불가.")

    if bm.get("runs_per_case") != cm.get("runs_per_case"):
        warnings.append(
            f"runs_per_case 상이: {bm.get('runs_per_case')} vs {cm.get('runs_per_case')} "
            f"— 표본 수가 달라 케이스 평균의 분산이 다릅니다 (1-run은 스모크로만)."
        )
    for meta, name in [(bm, "baseline"), (cm, "candidate")]:
        if meta.get("git_dirty"):
            warnings.append(f"{name}는 미커밋 변경 포함 상태(git dirty)에서 측정됨 ({meta.get('git_commit', '?')[:8]}).")
    return warnings


def fmt(v) -> str:
    return "-" if v is None else f"{v:.3f}"


def fmt_delta(metric: str, delta: float | None) -> str:
    """집계 표 전용 — 45케이스 평균의 델타라 AGGREGATE_FLOOR로 판정한다."""
    if delta is None:
        return "    -"
    mark = {"improved": "▲", "regressed": "▼", "noise": " "}[
        classify(metric, delta, AGGREGATE_FLOOR)
    ]
    return f"{delta:+.3f}{mark}"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline_dir", help="기준 결과 디렉터리 (eval/results/<run-id>)")
    parser.add_argument("candidate_dir", help="비교 대상 결과 디렉터리")
    args = parser.parse_args()

    base_scores = load_scores(args.baseline_dir)
    cand_scores = load_scores(args.candidate_dir)
    base_id = base_scores.get("meta", {}).get("run_id", args.baseline_dir)
    cand_id = cand_scores.get("meta", {}).get("run_id", args.candidate_dir)

    print(f"baseline : {base_id}  (git {base_scores['meta'].get('git_commit', '?')[:8]})")
    print(f"candidate: {cand_id}  (git {cand_scores['meta'].get('git_commit', '?')[:8]})")

    warnings = device_warnings(base_scores, cand_scores)
    if warnings:
        print("\n⚠ 측정 장치 차이:")
        for w in warnings:
            print(f"  - {w}")

    base, cand = case_means(base_scores), case_means(cand_scores)
    only_base, only_cand = sorted(set(base) - set(cand)), sorted(set(cand) - set(base))
    if only_base or only_cand:
        print(f"\n케이스 집합 차이 — 교집합 {len(set(base) & set(cand))}개만 비교"
              + (f" (baseline에만: {', '.join(only_base)})" if only_base else "")
              + (f" (candidate에만: {', '.join(only_cand)})" if only_cand else ""))

    deltas = paired_deltas(base, cand)
    if not deltas:
        sys.exit("공통 케이스가 없어 비교 불가.")

    # ── 집계 델타 ──
    ba, ca = base_scores.get("aggregate", {}), cand_scores.get("aggregate", {})
    print(f"\n집계 (교집합 아님 — 각 런의 전체 케이스 평균, AGGREGATE_FLOOR 초과 = ▲/▼):")
    print(f"  {'metric':20} {'base':>7} {'cand':>7}   delta")
    for m in METRICS:
        b, c = ba.get(m), ca.get(m)
        d = round(c - b, 4) if (b is not None and c is not None) else None
        print(f"  {m:20} {fmt(b):>7} {fmt(c):>7}  {fmt_delta(m, d)}")
    for k in COUNTER_KEYS:
        print(f"  {k:32} {ba.get(k, '-'):>4} → {ca.get(k, '-')}")

    # ── 케이스별 짝지은 비교 ──
    print(f"\n케이스별 판정 (CASE_FLOOR 초과만 개선/악화 — 집계보다 자가 크다):")
    print(f"  {'metric':20} {'개선':>4} {'악화':>4} {'노이즈':>5}")
    for m in METRICS:
        cls = [classify(m, d[m]) for d in deltas.values()]
        print(f"  {m:20} {cls.count('improved'):>4} {cls.count('regressed'):>4} {cls.count('noise'):>5}")

    # ── 노이즈 플로어를 넘은 케이스 상세 (악화 먼저, |delta| 큰 순) ──
    movers = []
    for case_id, d in deltas.items():
        for m in METRICS:
            c = classify(m, d[m])
            if c in ("improved", "regressed"):
                movers.append((case_id, m, d[m], c))
    movers.sort(key=lambda x: (x[3] != "regressed", -abs(x[2])))
    if movers:
        print(f"\n노이즈 플로어 초과 델타 ({len(movers)}건, 악화 우선):")
        for case_id, m, d, c in movers:
            print(f"  {'▼' if c == 'regressed' else '▲'} {case_id:9} {m:20} {d:+.3f}")
    else:
        print("\n노이즈 플로어를 넘은 케이스 델타 없음 — 이 비교에서 유의미한 효과 없음.")


if __name__ == "__main__":
    main()
