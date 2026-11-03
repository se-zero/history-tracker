#!/usr/bin/env python
"""라벨 큐 — 두 그래프의 균등 표본에서 라벨 없는 새 쌍을 섞어, 모델과 점수를 모른 채 라벨링한다 (docs/measurement.md §4.5).

임베딩 모델을 바꾸면 새 모델이 새로 긋는 엣지는 기존 라벨(과거 모델이 만든 엣지에서 뽑은 것)에 없다.
기준선 그래프와 후보 그래프에서 같은 크기의 균등 표본을 뽑아(sample_edges.py --uniform --reuse-labels --blind)
기존 라벨을 채워 오면, 남는 라벨 없는 쌍이 사람이 새로 판단할 대상이다. 이 쌍들을 두 그래프에서
한데 모아 섞어야 라벨러가 어느 모델의 엣지인지 짐작하지 못한다 — 순서가 곧 출처를 드러내기 때문이다.

  make  표본 파일들에서 label이 빈 항목을 모아 (edge_type, src.id, dst.id)로 중복을 제거하고, 시드 고정으로
        섞어 precision 형식의 큐 파일로 쓴다. 출처를 드러내는 필드(confidence·bucket)는 뺀다.
        이미 있는 --out은 덮어쓰지 않는다 — 라벨링 중인 큐를 날리지 않으려는 것이다.
  fill  라벨링을 마친 큐의 label·note를 표본 파일들에 키 기준으로 채워 `<이름>.labeled.yaml`로 쓴다.
        원본은 그대로 둔다. 표본에 이미 있는 라벨은 덮어쓰지 않는다 — 기존 라벨은 고치지 않는다.
        파일을 다시 쓰므로 표본 파일 안의 손으로 단 주석은 새 파일에 남지 않는다.

실행 (ai-engine venv 사용):
    services/ai-engine/.venv/Scripts/python.exe eval/label_queue.py make \\
        eval/edge_labels/_tmp-base.yaml eval/edge_labels/_tmp-cand.yaml --out eval/edge_labels/_tmp-queue.yaml
    (큐 파일의 label 칸을 채운다)
    services/ai-engine/.venv/Scripts/python.exe eval/label_queue.py fill \\
        --queue eval/edge_labels/_tmp-queue.yaml eval/edge_labels/_tmp-base.yaml eval/edge_labels/_tmp-cand.yaml
    → _tmp-base.labeled.yaml · _tmp-cand.labeled.yaml (edge_eval.py --precision에 넘긴다)
"""

import argparse
import os
import random
import sys

import yaml

EVAL_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, EVAL_DIR)

import sample_edges  # noqa: E402  (yaml의 여러 줄 문자열 덤프 설정도 이 import로 켜진다)
from edge_eval import VALID_LABELS  # noqa: E402

QUEUE_HEADER = """\
# 라벨 큐 — 두 그래프(기준선·후보)의 균등 표본에서 라벨 없는 새 쌍만 모아 섞은 파일.
# 어느 그래프에서 나온 쌍인지, 점수가 얼마인지 일부러 뺐다. 모델과 점수를 모른 채 판단해야 비교가 공정하다.
# 다 채우면 `label_queue.py fill --queue <이 파일> <표본 파일들>`로 표본 파일에 되돌려 채운다.
"""


# --------------------------------------------------------------------------- 순수 로직

def is_labeled(entry: dict) -> bool:
    return bool(str(entry.get("label") or "").strip())


def _normalized(label) -> str:
    return str(label).strip().lower()


def build_queue(edge_lists: list[list[dict]], seed: int) -> list[dict]:
    """표본들에서 라벨 없는 쌍만 모아 중복을 없애고 섞는다. 출처를 알려 주는 필드는 뺀다."""
    unlabeled: dict[tuple, dict] = {}
    for edges in edge_lists:
        for entry in edges:
            if not is_labeled(entry):
                unlabeled.setdefault(sample_edges.entry_key(entry), entry)   # 두 그래프에 다 있으면 첫 번째만

    # 정렬한 뒤 섞는다 — 파일을 넘기는 순서가 달라도 같은 시드면 같은 큐가 나오고, 입력 순서(=출처)가 남지 않는다
    ordered = [unlabeled[key] for key in sorted(unlabeled)]
    random.Random(seed).shuffle(ordered)
    return [{"edge_type": e["edge_type"], "src": e["src"], "dst": e["dst"], "label": None, "note": None}
            for e in ordered]


def queue_labels(queue_entries: list[dict]) -> dict[tuple, dict]:
    """라벨링이 끝난 큐 항목만 {키: {label, note}}로 모은다."""
    return {
        sample_edges.entry_key(e): {"label": e["label"], "note": e.get("note")}
        for e in queue_entries
        if is_labeled(e)
    }


def invalid_labels(labels: dict[tuple, dict]) -> list[str]:
    """허용값(relevant·irrelevant·unsure)이 아닌 라벨 — edge_eval은 이런 쌍을 채점에서 빼 버린다."""
    return [f"{edge_type} {src}->{dst}: label={found['label']!r}"
            for (edge_type, src, dst), found in labels.items()
            if _normalized(found["label"]) not in VALID_LABELS]


def fill_entries(entries: list[dict], labels: dict[tuple, dict]) -> dict[str, int]:
    """라벨이 빈 항목에 큐의 label·note를 채운다. 이미 라벨이 있는 항목은 건드리지 않는다.

    반환: filled(새로 채움) · kept(원래 라벨이 있어 둠) · conflicts(kept 중 큐와 라벨이 다른 것) · unlabeled(아직 빔)
    """
    stats = {"filled": 0, "kept": 0, "conflicts": 0, "unlabeled": 0}
    for entry in entries:
        found = labels.get(sample_edges.entry_key(entry))
        if is_labeled(entry):
            stats["kept"] += 1
            if found and _normalized(found["label"]) != _normalized(entry["label"]):
                stats["conflicts"] += 1
        elif found:
            entry["label"], entry["note"] = found["label"], found["note"]
            stats["filled"] += 1
        else:
            stats["unlabeled"] += 1
    return stats


def filled_path(path: str, out_dir: str | None) -> str:
    stem, ext = os.path.splitext(os.path.basename(path))
    return os.path.join(out_dir or os.path.dirname(os.path.abspath(path)), f"{stem}.labeled{ext}")


# --------------------------------------------------------------------------- 파일

def _load(path: str) -> dict:
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f) or {}


def _write(path: str, doc: dict, header: str) -> None:
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(header)
        yaml.safe_dump(doc, f, allow_unicode=True, sort_keys=False, default_flow_style=False, width=100)


# --------------------------------------------------------------------------- main

def _make(args: argparse.Namespace) -> None:
    edge_lists = [_load(path).get("edges") or [] for path in args.samples]
    queue = build_queue(edge_lists, args.seed)
    n_unlabeled = sum(1 for edges in edge_lists for e in edges if not is_labeled(e))
    if not queue:
        print("라벨 없는 쌍이 없어 큐를 만들지 않았다")
        return
    if os.path.exists(args.out):
        sys.exit(f"{args.out}이(가) 이미 있다 — 라벨링 중인 큐를 덮어쓰지 않는다. 다시 만들려면 파일을 직접 지운다")
    _write(args.out, {"seed": args.seed, "edges": queue}, QUEUE_HEADER + sample_edges.HEADER)
    print(f"라벨 없는 항목 {n_unlabeled}개 → 중복을 없애고 섞어 {len(queue)}개 → {args.out}")


def _fill(args: argparse.Namespace) -> None:
    queue_entries = _load(args.queue).get("edges") or []
    labels = queue_labels(queue_entries)
    if not labels:
        sys.exit(f"{args.queue}에 라벨이 채워진 항목이 없다")
    print(f"큐 {len(queue_entries)}개 중 라벨이 채워진 항목 {len(labels)}개")
    invalid = invalid_labels(labels)
    if invalid:
        print(f"⚠ 허용값(relevant·irrelevant·unsure)이 아닌 라벨 {len(invalid)}개 — edge_eval이 채점에서 뺀다: {invalid[:5]}")

    for path in args.samples:
        doc = _load(path)
        stats = fill_entries(doc.get("edges") or [], labels)
        out = filled_path(path, args.out_dir)
        _write(out, doc, sample_edges.HEADER)
        print(f"{path} → {out}: 채움 {stats['filled']} · 원래 라벨 있음 {stats['kept']} · 아직 빔 {stats['unlabeled']}")
        if stats["conflicts"]:
            print(f"  ⚠ 원래 라벨이 큐와 다른 항목 {stats['conflicts']}개는 원래 라벨을 유지했다")


def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="command", required=True)

    make = sub.add_parser("make", help="표본 파일들에서 라벨 없는 쌍을 모아 섞어 큐 파일을 만든다")
    make.add_argument("samples", nargs="+", help="sample_edges.py가 만든 precision 형식 파일들")
    make.add_argument("--out", required=True, help="큐 파일 경로 (이미 있으면 중단)")
    make.add_argument("--seed", type=int, default=sample_edges.SEED, help=f"섞는 시드 (기본 {sample_edges.SEED})")
    make.set_defaults(run=_make)

    fill = sub.add_parser("fill", help="라벨링한 큐의 label·note를 표본 파일들에 채워 새 파일로 쓴다")
    fill.add_argument("samples", nargs="+", help="채울 표본 파일들 (원본은 그대로, <이름>.labeled.yaml을 새로 쓴다)")
    fill.add_argument("--queue", required=True, help="라벨링을 마친 큐 파일")
    fill.add_argument("--out-dir", help="새 파일을 쓸 디렉터리 (생략 시 각 표본 파일 옆)")
    fill.set_defaults(run=_fill)

    args = ap.parse_args(argv)
    args.run(args)


if __name__ == "__main__":
    main()
