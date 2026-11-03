#!/usr/bin/env python
"""임베딩 후보 비교 — 기준 모델 대비 AUC 차이와 95% 신뢰구간 (docs/measurement.md §4.5).

precheck_embedding_model.py가 남긴 결과 JSON(pair_scores)을 여러 개 받아 라벨 기준으로 합치고,
타입(REFERENCE·DISCUSSED_IN·TRIGGERED_BY)별로 각 후보와 기준의 AUC 차이를 낸다. 임베딩은 다시
부르지 않는다. 같은 라벨이 여러 파일에 있으면 뒤 파일이 이기고 경고를 낸다.

라벨이 수백 쌍뿐이라 AUC 차이는 우연일 수 있다 — 점 추정이 아니라 신뢰구간으로 판단한다.
구간은 부트스트랩이다. 라벨 쌍들은 같은 스레드·이슈를 도착 노드로 공유해 서로 독립이 아니다 —
쌍 단위로 복원 추출하면 표본이 실제보다 많아 보여 구간이 좁게 나오므로 도착 노드(dst) 단위
클러스터를 복원 추출한다. 매번 같은 재표본에서 두 모델의 AUC를 계산해 차이를 구하고(짝지은 비교),
구간은 그 차이 분포의 2.5·97.5 백분위다. 구간이 0을 지나면 두 모델을 구분할 수 없다.

차이 = 후보 − 기준이다. 한쪽 결과에만 있는 쌍은 뺀다(양쪽에 다 있는 쌍만 짝지어 쓴다).
TRIGGERED_BY는 라벨이 15쌍뿐이라 행에 (참고)를 붙인다.

실행 (ai-engine venv 사용):
    services/ai-engine/.venv/Scripts/python.exe eval/precheck_compare.py \\
        eval/results/precheck-embedding-model.json eval/results/precheck-bge-m3.json
    옵션: --reference 3-large@1536 --resamples 2000 --seed 42 --out <경로>
"""

import argparse
import json
import os
import random
import sys
from datetime import datetime, timezone

import numpy as np

EVAL_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, EVAL_DIR)

from precheck_embedding_model import CURRENT_LABEL, roc_auc  # noqa: E402

EDGE_TYPES = ("REFERENCE", "DISCUSSED_IN", "TRIGGERED_BY")
USABLE_LABELS = ("relevant", "irrelevant")      # unsure·미라벨은 AUC에 넣지 않는다 (precheck와 같은 규칙)
ADVISORY_TYPES = ("TRIGGERED_BY",)              # 라벨이 너무 적어 판정이 아니라 참고용 — 행에 (참고)를 붙인다
CI_PERCENTILES = (2.5, 97.5)


# --------------------------------------------------------------------------- 입력

def load_configs(paths: list[str]) -> tuple[dict[str, dict], list[str]]:
    """결과 JSON들의 configs를 라벨 기준으로 합친다. 같은 라벨은 뒤 파일이 이기고 경고를 돌려준다."""
    merged: dict[str, dict] = {}
    source: dict[str, str] = {}
    warnings: list[str] = []
    for path in paths:
        with open(path, encoding="utf-8") as f:
            doc = json.load(f)
        for label, entry in doc["configs"].items():
            if label in merged:
                warnings.append(f"라벨 {label}이(가) {os.path.basename(source[label])}에도 있다 — "
                                f"뒤 파일 {os.path.basename(path)}의 값을 쓴다")
            merged[label] = entry
            source[label] = path
    return merged, warnings


def _labeled_scores(entry: dict, edge_type: str) -> dict[tuple[str, str], tuple[str, float]]:
    """(src, dst) → (라벨, 점수). AUC에 쓰는 쌍만: 라벨 쌍이면서 relevant/irrelevant인 것."""
    return {
        (p["src"], p["dst"]): (p["label"], p["score"])
        for p in entry["pair_scores"]
        if p["kind"] == "label" and p["edge_type"] == edge_type and p["label"] in USABLE_LABELS
    }


def paired_items(ref_entry: dict, cand_entry: dict, edge_type: str) -> tuple[list[dict], int]:
    """두 결과에 다 있는 쌍을 (edge_type, src, dst)로 짝짓는다. (짝 목록, 한쪽에만 있어 뺀 쌍 수).

    클러스터는 도착 노드(dst)다 — 같은 스레드·이슈로 이어진 쌍들은 함께 움직인다.
    짝 목록은 (src, dst) 순으로 정렬해 시드가 같으면 같은 재표본이 나오게 한다.
    """
    ref = _labeled_scores(ref_entry, edge_type)
    cand = _labeled_scores(cand_entry, edge_type)
    items = [
        {"cluster": dst, "relevant": ref[(src, dst)][0] == "relevant",
         "ref": ref[(src, dst)][1], "cand": cand[(src, dst)][1]}
        for src, dst in sorted(ref.keys() & cand.keys())
    ]
    return items, len(ref.keys() ^ cand.keys())


# --------------------------------------------------------------------------- 부트스트랩

def resample_clusters(cluster_ids: list, rng: random.Random) -> list[int]:
    """클러스터 단위 복원 추출 — 뽑힌 클러스터에 속한 쌍의 인덱스를 전부 모아 돌려준다.

    클러스터 수 K개를 K번 뽑는다. 같은 클러스터가 여러 번 들어올 수도, 한 번도 안 들어올 수도 있다.
    """
    members: dict = {}
    for i, cluster in enumerate(cluster_ids):
        members.setdefault(cluster, []).append(i)
    groups = list(members.values())     # 첫 등장 순서 — 입력 순서가 같으면 시드가 같을 때 결과도 같다
    picked: list[int] = []
    for _ in groups:
        picked.extend(groups[rng.randrange(len(groups))])
    return picked


def _auc(items: list[dict], idx, field: str) -> float | None:
    pos = [items[i][field] for i in idx if items[i]["relevant"]]
    neg = [items[i][field] for i in idx if not items[i]["relevant"]]
    return roc_auc(pos, neg)


def percentile_interval(diffs: list[float]) -> tuple[float, float]:
    low, high = np.percentile(diffs, CI_PERCENTILES)
    return float(low), float(high)


def bootstrap_auc_diff(items: list[dict], resamples: int, seed: int) -> dict:
    """후보 − 기준 AUC 차이와 95% 구간. 양성이나 음성이 빈 재표본은 건너뛰고 그 수를 남긴다."""
    everyone = range(len(items))
    auc_ref = _auc(items, everyone, "ref")
    auc_cand = _auc(items, everyone, "cand")
    result = {
        "n_relevant": sum(1 for it in items if it["relevant"]),
        "n_irrelevant": sum(1 for it in items if not it["relevant"]),
        "n_clusters": len({it["cluster"] for it in items}),
        "auc_reference": auc_ref,
        "auc_candidate": auc_cand,
        "diff": None if auc_ref is None else auc_cand - auc_ref,
        "ci_low": None,
        "ci_high": None,
        "resamples": resamples,
        "resamples_skipped": 0,
    }
    if auc_ref is None:     # 관련이나 무관 쌍이 하나도 없어 AUC가 정의되지 않는다
        return result

    rng = random.Random(seed)
    clusters = [it["cluster"] for it in items]
    diffs: list[float] = []
    for _ in range(resamples):
        idx = resample_clusters(clusters, rng)
        ref, cand = _auc(items, idx, "ref"), _auc(items, idx, "cand")
        if ref is None:     # 같은 재표본이라 후보도 None이다
            result["resamples_skipped"] += 1
            continue
        diffs.append(cand - ref)
    if diffs:
        result["ci_low"], result["ci_high"] = percentile_interval(diffs)
    return result


# --------------------------------------------------------------------------- 비교 · 출력

def _precision(entry: dict, edge_type: str) -> float | None:
    return (entry.get("by_type") or {}).get(edge_type, {}).get("precision_at_full_golden_recall")


def compare_entries(ref_entry: dict, cand_entry: dict, resamples: int, seed: int) -> dict[str, dict]:
    """타입별 비교 결과. precision은 precheck 결과의 by_type 값을 그대로 가져온다(다시 계산하지 않는다)."""
    out = {}
    for edge_type in EDGE_TYPES:
        items, unpaired = paired_items(ref_entry, cand_entry, edge_type)
        row = bootstrap_auc_diff(items, resamples, seed)
        row["n_unpaired"] = unpaired
        row["precision_reference"] = _precision(ref_entry, edge_type)
        row["precision_candidate"] = _precision(cand_entry, edge_type)
        row["advisory"] = edge_type in ADVISORY_TYPES
        out[edge_type] = row
    return out


def _fmt(value: float | None, signed: bool = False) -> str:
    if value is None:
        return "-"
    return f"{value:+.3f}" if signed else f"{value:.3f}"


def format_table(ref_label: str, cand_label: str, per_type: dict[str, dict]) -> str:
    lines = [
        f"{cand_label} vs {ref_label} (기준)",
        "| 타입 | 기준 AUC | 후보 AUC | 차이 [95% 구간] | n_rel/n_irr | 골든 전량 recall 지점 precision (기준 → 후보) |",
        "|---|---|---|---|---|---|",
    ]
    notes = []
    for edge_type, row in per_type.items():
        name = f"{edge_type} (참고)" if row["advisory"] else edge_type
        interval = f"[{_fmt(row['ci_low'], True)}, {_fmt(row['ci_high'], True)}]"
        lines.append(
            f"| {name} | {_fmt(row['auc_reference'])} | {_fmt(row['auc_candidate'])} "
            f"| {_fmt(row['diff'], True)} {interval} | {row['n_relevant']}/{row['n_irrelevant']} "
            f"| {_fmt(row['precision_reference'])} → {_fmt(row['precision_candidate'])} |"
        )
        if row["n_unpaired"]:
            notes.append(f"⚠ {edge_type}: 한쪽 결과에만 있는 라벨 쌍 {row['n_unpaired']}개는 뺐다")
        if row["resamples_skipped"]:
            notes.append(f"⚠ {edge_type}: 관련 또는 무관 쌍이 빈 재표본 "
                         f"{row['resamples_skipped']}/{row['resamples']}회는 건너뛰었다")
    lines.append("차이 = 후보 − 기준. 구간은 도착 노드(스레드·이슈) 단위 부트스트랩의 2.5~97.5 백분위 — 0을 지나면 구분 불가")
    return "\n".join(lines + notes)


# --------------------------------------------------------------------------- main

def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("results", nargs="+", help="precheck_embedding_model.py가 남긴 결과 JSON (여러 개면 라벨 기준으로 합친다)")
    ap.add_argument("--reference", default=CURRENT_LABEL, help=f"기준 라벨 (기본 {CURRENT_LABEL})")
    ap.add_argument("--resamples", type=int, default=2000, help="부트스트랩 재표본 수 (기본 2000)")
    ap.add_argument("--seed", type=int, default=42, help="부트스트랩 시드 (기본 42)")
    ap.add_argument("--out", default=os.path.join(EVAL_DIR, "results", "precheck-compare.json"),
                    help="결과 JSON 경로 (기본 results/precheck-compare.json)")
    args = ap.parse_args(argv)

    configs, warnings = load_configs(args.results)
    for warning in warnings:
        print(f"⚠ {warning}")
    if args.reference not in configs:
        sys.exit(f"기준 라벨 {args.reference}이(가) 입력에 없다. 입력에 있는 라벨: {', '.join(configs)}")
    candidates = {label: entry for label, entry in configs.items() if label != args.reference}
    if not candidates:
        sys.exit("비교할 후보가 없다 — 기준 외의 라벨이 든 결과 파일을 함께 넘긴다")

    results = {}
    for label, entry in candidates.items():
        results[label] = compare_entries(configs[args.reference], entry, args.resamples, args.seed)
        print(f"\n{format_table(args.reference, label, results[label])}")

    doc = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "inputs": args.results,
        "reference": args.reference,
        "resamples": args.resamples,
        "seed": args.seed,
        "warnings": warnings,
        "candidates": results,
    }
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=2)
    print(f"\n저장: {args.out}")


if __name__ == "__main__":
    main()
