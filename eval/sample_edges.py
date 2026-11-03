#!/usr/bin/env python
"""엣지 레벨 eval — 시맨틱 엣지 층화 샘플링 → precision 라벨 파일 생성 (docs/measurement-plan.md B).

그래프 스냅샷에 존재하는 시맨틱 엣지를 타입 × confidence 버킷으로 층화 샘플링하고,
각 쌍에 양쪽 노드의 그래프 원문을 인라인으로 붙여 사람이 조회 없이 relevant/irrelevant를
매길 수 있는 YAML을 만든다. 라벨은 엣지 id가 아니라 "노드 쌍 + 타입"으로 저장한다 —
빌더를 고쳐 엣지가 사라지거나 다시 생겨도 같은 쌍을 재평가할 수 있어야 하기 때문이다.

대상 타입: REFERENCE(ChangeSet→Communication), DISCUSSED_IN 시맨틱(Issue→Communication),
  TRIGGERED_BY 시맨틱(ChangeSet→Issue).
  시맨틱 판별: REFERENCE는 전부 시맨틱 / DISCUSSED_IN·TRIGGERED_BY는 source='semantic'인 것만
  (text·스레드전파 엣지는 제외). 임계값 인하 전 스냅샷에는 TRIGGERED_BY 시맨틱이 0개였다.

실행 (ai-engine venv 사용):
    services/ai-engine/.venv/Scripts/python.exe eval/sample_edges.py --count-only
    services/ai-engine/.venv/Scripts/python.exe eval/sample_edges.py --per-type 25 --out eval/edge_labels/precision-2026-07-05.yaml
접속: NEO4J_URI / NEO4J_USER / NEO4J_PASSWORD 환경변수로 덮어쓸 수 있다(기본 localhost / neo4j / password1234).

임베딩 모델을 교체한 그래프의 precision을 재는 표본용 플래그 (docs/measurement.md §4.5) — 켜지 않으면 위 동작 그대로다:
    --uniform        버킷 없이 타입별 --per-type개를 시드 고정 단순 무작위로 뽑는다. 버킷 경계는 하드코딩이라
                     새 모델의 점수가 범위를 벗어나면 그 엣지가 조용히 표본에서 빠진다.
    --reuse-labels P 기존 precision YAML에서 (edge_type, src.id, dst.id)가 같은 쌍의 label·note를 채워 온다.
                     id는 문자열로 비교한다. 라벨이 비어 있는 쌍이 사람이 새로 라벨링할 대상이다.
    --blind          항목에서 confidence·bucket을 뺀다 — 모델과 점수를 모른 채 라벨링하기 위해서다.
    --graph-snapshot N
                     파일의 graph_snapshot에 적을 그래프 이름(생략하면 예전처럼 graph-2026-07-05.dump). edge_eval이
                     결과 JSON의 meta로 옮겨 적으므로, 표본을 뽑은 그래프 이름을 줘야 수치가 어느 그래프의 것인지 남는다.
"""

import argparse
import os
import random
import sys

import yaml

from graph_lookup import detect_project_id, fetch_evidence_body, open_driver

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def _str_representer(dumper, data):
    """여러 줄 문자열은 리터럴 블록(|)으로 덤프 — 사람이 읽는 라벨 파일이라 \\n 이스케이프를 피한다."""
    style = "|" if "\n" in data else None
    return dumper.represent_scalar("tag:yaml.org,2002:str", data, style=style)


yaml.add_representer(str, _str_representer, Dumper=yaml.SafeDumper)

EVAL_DIR = os.path.dirname(os.path.abspath(__file__))
SEED = 42

# 타입별 정의: (추출 쿼리, src 노드 타입, dst 노드 타입, confidence 버킷 경계).
# 버킷은 각 타입의 실제 confidence 범위에 맞춰 잡는다(임계값·분포가 타입마다 다르다).
# 경계는 [lo, hi) 반열림, 마지막 버킷만 상한 포함.
EDGE_SPECS = {
    "REFERENCE": {
        "query": (
            "MATCH (c:ChangeSet {project_id:$pid})-[r:REFERENCE]->(comm:Communication) "
            "WHERE comm.conversation_id IS NOT NULL "
            "RETURN left(c.hash,7) AS src_id, comm.conversation_id AS dst_id, r.confidence AS confidence"
        ),
        "src_type": "commit",
        "dst_type": "message",
        "buckets": [(0.30, 0.40), (0.40, 0.50), (0.50, 1.01)],
    },
    "DISCUSSED_IN": {
        "query": (
            "MATCH (i:Issue {project_id:$pid})-[r:DISCUSSED_IN]->(comm:Communication) "
            "WHERE r.confidence IS NOT NULL AND comm.conversation_id IS NOT NULL "
            "RETURN i.issue_key AS src_id, comm.conversation_id AS dst_id, r.confidence AS confidence"
        ),
        "src_type": "issue",
        "dst_type": "message",
        "buckets": [(0.40, 0.50), (0.50, 0.70), (0.70, 1.01)],
    },
    "TRIGGERED_BY": {
        "query": (
            "MATCH (c:ChangeSet {project_id:$pid})-[r:TRIGGERED_BY]->(i:Issue) "
            "WHERE r.source='semantic' "
            "RETURN left(c.hash,7) AS src_id, i.issue_key AS dst_id, r.confidence AS confidence"
        ),
        "src_type": "commit",
        "dst_type": "issue",
        # 버킷 경계를 임계값 스윕 포인트(0.30/0.35/0.40)에 맞춘다 — 버킷별 precision이 곧
        # "이 임계값이었다면 남았을 엣지"의 정밀도가 되어 스윕 평가에 바로 쓰인다.
        "buckets": [(0.30, 0.35), (0.35, 0.40), (0.40, 1.01)],
    },
}


def _bucket_label(buckets, conf):
    for lo, hi in buckets:
        if lo <= conf < hi:
            return f"{lo:.2f}-{hi:.2f}" if hi <= 1.0 else f"{lo:.2f}+"
    return "out-of-range"


def fetch_edges(session, pid, spec):
    """한 타입의 시맨틱 엣지를 전량 조회하고 (src_id, dst_id) 기준으로 중복 제거."""
    seen = {}
    for row in session.run(spec["query"], pid=pid):
        key = (row["src_id"], row["dst_id"])
        # 같은 쌍에 엣지가 여러 개면 최고 confidence만 (스레드 여러 메시지가 같은 conversation으로 접힐 때)
        if key not in seen or row["confidence"] > seen[key]:
            seen[key] = row["confidence"]
    return [{"src_id": s, "dst_id": d, "confidence": c} for (s, d), c in seen.items()]


def stratified_sample(edges, buckets, target, rng):
    """버킷별 균등 배분으로 target개 추출. 부족한 버킷은 있는 만큼, 부족분은 남은 엣지에서 보충."""
    by_bucket = {i: [] for i in range(len(buckets))}
    for e in edges:
        for i, (lo, hi) in enumerate(buckets):
            if lo <= e["confidence"] < hi:
                by_bucket[i].append(e)
                break
    # 재현성: 샘플 전에 정렬해 rng 입력 순서를 고정
    for i in by_bucket:
        by_bucket[i].sort(key=lambda e: (e["src_id"], e["dst_id"]))

    base, rem = divmod(target, len(buckets))
    quotas = [base + (1 if i < rem else 0) for i in range(len(buckets))]

    picked, leftovers = [], []
    for i, q in enumerate(quotas):
        pool = by_bucket[i]
        if len(pool) <= q:
            picked.extend(pool)
        else:
            chosen = rng.sample(pool, q)
            picked.extend(chosen)
            leftovers.extend(e for e in pool if e not in chosen)
    # 짧은 버킷 때문에 target에 못 미치면 남은 엣지에서 채운다
    shortfall = target - len(picked)
    if shortfall > 0 and leftovers:
        leftovers.sort(key=lambda e: (e["src_id"], e["dst_id"]))
        picked.extend(rng.sample(leftovers, min(shortfall, len(leftovers))))
    return picked


def uniform_sample(edges, target, rng):
    """버킷 없이 target개를 단순 무작위로 추출. 모자라면 있는 만큼 전부."""
    # 재현성: 조회 순서가 달라져도 같은 표본이 나오도록 샘플 전에 정렬한다 (stratified_sample과 같은 이유)
    pool = sorted(edges, key=lambda e: (e["src_id"], e["dst_id"]))
    return pool if len(pool) <= target else rng.sample(pool, target)


def _simplify_body(body):
    """라벨 파일 가독성을 위해 커밋 body의 파일별 diff_summary는 버리고 경로 목록만 남긴다."""
    if body and isinstance(body.get("files"), list):
        body = {**body, "files": [f.get("path") for f in body["files"] if f.get("path")]}
    return body


def build_entry(session, pid, edge_type, spec, edge):
    """샘플 엣지 한 건을 라벨 항목(양쪽 노드 원문 인라인 + 빈 label)으로 변환."""
    src_body = _simplify_body(fetch_evidence_body(session, pid, spec["src_type"], edge["src_id"]))
    dst_body = _simplify_body(fetch_evidence_body(session, pid, spec["dst_type"], edge["dst_id"]))
    return {
        "edge_type": edge_type,
        "confidence": round(edge["confidence"], 4),
        "bucket": _bucket_label(spec["buckets"], edge["confidence"]),
        "src": {"type": spec["src_type"], "id": edge["src_id"], "body": src_body},
        "dst": {"type": spec["dst_type"], "id": edge["dst_id"], "body": dst_body},
        "label": None,   # ← relevant | irrelevant | unsure 로 채우세요
        "note": None,    # (선택) 판단 근거 한 줄
    }


def entry_key(entry):
    """라벨 항목을 같은 쌍으로 묶는 키. id는 문자열로 비교한다 — 따옴표 없이 적힌 conversation_id 같은
    숫자형 문자열은 YAML이 float로 읽어, 그대로 비교하면 같은 쌍이 어긋난다."""
    return (entry["edge_type"], str(entry["src"]["id"]), str(entry["dst"]["id"]))


def load_reusable_labels(path):
    """기존 precision YAML에서 라벨이 채워진 항목만 {entry_key: {label, note}}로 읽는다."""
    with open(path, encoding="utf-8") as f:
        doc = yaml.safe_load(f)
    return {
        entry_key(e): {"label": e["label"], "note": e.get("note")}
        for e in (doc.get("edges") or [])
        if str(e.get("label") or "").strip()
    }


def apply_reused_labels(entries, reusable):
    """같은 쌍의 기존 label·note를 항목에 채운다. 채운 항목 수를 돌려준다."""
    reused = 0
    for entry in entries:
        found = reusable.get(entry_key(entry))
        if found:
            entry["label"], entry["note"] = found["label"], found["note"]
            reused += 1
    return reused


def blind_entry(entry):
    """모델과 점수를 모른 채 라벨링하도록 confidence·bucket을 뺀 사본."""
    return {k: v for k, v in entry.items() if k not in ("confidence", "bucket")}


HEADER = """\
# 엣지 레벨 eval — precision 라벨셋 (docs/measurement-plan.md B)
# 각 항목의 src/dst 원문을 읽고 "이 둘이 실제로 의미적으로 관련 있나?"를 판단해
# label 칸만 채우세요: relevant | irrelevant | unsure
#   - relevant  : 이 커밋/이슈가 이 대화(스레드)와 실제로 같은 사안을 다룬다
#   - irrelevant: 유사도로 이어졌지만 실제로는 무관하다 (false positive)
#   - unsure    : 원문만으로 판단 불가
# note는 선택(애매했던 근거). label/판정은 그래프와 독립적인 정답지이므로,
# 빌더를 바꿔 엣지가 사라지거나 새로 생겨도 이 판정은 유지하고 새 쌍만 추가한다.
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--count-only", action="store_true", help="분포만 출력(샘플링·파일 생성 안 함)")
    parser.add_argument("--per-type", type=int, default=25, help="타입당 표본 수 (기본 25)")
    parser.add_argument("--out", default=os.path.join(EVAL_DIR, "edge_labels", "precision.yaml"),
                        help="라벨 파일 출력 경로")
    parser.add_argument("--project-id", help="생략 시 자동 감지")
    parser.add_argument("--uniform", action="store_true",
                        help="버킷 없이 타입별 --per-type개를 시드 고정 단순 무작위로 추출 "
                             "(새 모델의 점수가 하드코딩 버킷 범위를 벗어나도 빠지지 않는다)")
    parser.add_argument("--reuse-labels", metavar="PATH",
                        help="기존 precision YAML — (edge_type, src.id, dst.id)가 같은 쌍의 label·note를 채워 온다")
    parser.add_argument("--blind", action="store_true",
                        help="항목에서 confidence·bucket을 뺀다 (모델과 점수를 모른 채 라벨링하기 위함)")
    parser.add_argument("--graph-snapshot", metavar="NAME", default="graph-2026-07-05.dump",
                        help="파일의 graph_snapshot에 적을 그래프 이름 — edge_eval이 결과 JSON의 meta로 옮겨 적는다 "
                             "(기본 graph-2026-07-05.dump)")
    args = parser.parse_args()

    # 오타난 경로는 그래프를 훑기 전에 드러내려고 먼저 읽는다
    reusable = load_reusable_labels(args.reuse_labels) if args.reuse_labels else None

    rng = random.Random(SEED)
    with open_driver() as driver, driver.session() as session:
        pid = args.project_id or detect_project_id(session)

        if args.count_only:
            print(f"project_id: {pid}\n")
            for edge_type, spec in EDGE_SPECS.items():
                edges = fetch_edges(session, pid, spec)
                counts = {}
                for e in edges:
                    b = _bucket_label(spec["buckets"], e["confidence"])
                    counts[b] = counts.get(b, 0) + 1
                bstr = "  ".join(f"{_bucket_label(spec['buckets'], lo)}={counts.get(_bucket_label(spec['buckets'], lo), 0)}"
                                 for (lo, hi) in spec["buckets"])
                print(f"{edge_type:14} 고유쌍={len(edges):4}  " + bstr)
            return

        entries = []
        for edge_type, spec in EDGE_SPECS.items():
            edges = fetch_edges(session, pid, spec)
            sample = (uniform_sample(edges, args.per_type, rng) if args.uniform
                      else stratified_sample(edges, spec["buckets"], args.per_type, rng))
            print(f"{edge_type}: 고유쌍 {len(edges)} 중 {len(sample)}개 샘플")
            for e in sample:
                entries.append(build_entry(session, pid, edge_type, spec, e))

    if reusable is not None:
        reused = apply_reused_labels(entries, reusable)
        print(f"기존 라벨 재사용 {reused}개 · 라벨 없는 새 쌍 {len(entries) - reused}개")
    if args.blind:
        entries = [blind_entry(e) for e in entries]

    doc = {
        "graph_snapshot": args.graph_snapshot,
        "project_id": pid,
        "seed": SEED,
        "edges": entries,
    }
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        f.write(HEADER)
        yaml.safe_dump(doc, f, allow_unicode=True, sort_keys=False, default_flow_style=False, width=100)
    print(f"\n총 {len(entries)}개 → {args.out}")


if __name__ == "__main__":
    main()
