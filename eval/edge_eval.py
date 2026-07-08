#!/usr/bin/env python
"""엣지 레벨 eval 채점기 — 라벨셋 대비 현재 그래프의 엣지 precision/recall (docs/measurement-plan.md B).

빌더(구축 로직·모델·임계값)를 바꿀 때마다 실행해 "변경 → 엣지 지표 델타"를 기록한다.
라벨은 엣지 id가 아니라 "노드 쌍 + 타입"으로 저장돼 있어(sample_edges.py), 엣지가 사라지거나
다시 생겨도 같은 쌍을 재평가할 수 있다.

- precision (구현 완료): precision-*.yaml의 라벨된 쌍이 현재 그래프에 엣지로 존재하는지 질의해,
  존재하는 것들 중 relevant 비율을 낸다(전체·타입별·버킷별). unsure는 분모 제외.
  라벨했지만 현재 그래프에 없는 쌍(vanished)은 별도 집계한다.
- recall (미구현 — 팀원 이어서 작성): recall-*.yaml의 "연결됐어야 하는 쌍"이 그래프에 존재하는 비율.
  grade_recall() 스텁 참고. 공유 헬퍼 edge_exists()를 그대로 쓰면 된다.

실행 (ai-engine venv 사용):
    services/ai-engine/.venv/Scripts/python.exe eval/edge_eval.py
    옵션: --precision <경로> --recall <경로> --project-id --out <경로>
"""

import argparse
import datetime
import json
import os
import sys

import yaml

from graph_lookup import detect_project_id, open_driver
from sample_edges import EDGE_SPECS, _bucket_label

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

EVAL_DIR = os.path.dirname(os.path.abspath(__file__))
VALID_LABELS = {"relevant", "irrelevant", "unsure"}

# 타입별 "이 노드 쌍에 시맨틱 엣지가 존재하나?" 질의. sample_edges의 추출 규칙과 동일한
# 노드 식별(커밋 hash 앞 7자 / message conversation_id / issue jira_key)·시맨틱 필터를 쓴다.
# 같은 쌍에 엣지가 여러 개면 최고 confidence(샘플러의 dedupe와 일치).
EXISTS_Q = {
    "REFERENCE": (
        "MATCH (c:ChangeSet {project_id:$pid})-[r:REFERENCE]->(comm:Communication) "
        "WHERE left(c.hash,7)=$src AND comm.conversation_id=$dst "
        "RETURN r.confidence AS confidence ORDER BY r.confidence DESC LIMIT 1"
    ),
    "DISCUSSED_IN": (
        "MATCH (i:Issue {project_id:$pid})-[r:DISCUSSED_IN]->(comm:Communication) "
        "WHERE i.jira_key=$src AND comm.conversation_id=$dst AND r.confidence IS NOT NULL "
        "RETURN r.confidence AS confidence ORDER BY r.confidence DESC LIMIT 1"
    ),
    "TRIGGERED_BY": (
        "MATCH (c:ChangeSet {project_id:$pid})-[r:TRIGGERED_BY]->(i:Issue) "
        "WHERE left(c.hash,7)=$src AND i.jira_key=$dst AND r.source='semantic' "
        "RETURN r.confidence AS confidence ORDER BY r.confidence DESC LIMIT 1"
    ),
}


def edge_exists(session, pid, edge_type, src, dst):
    """노드 쌍에 시맨틱 엣지가 있으면 confidence(float), 없으면 None. precision·recall 공용 헬퍼."""
    query = EXISTS_Q.get(edge_type)
    if query is None:
        raise ValueError(f"지원하지 않는 edge_type: {edge_type}")
    rec = session.run(query, pid=pid, src=src, dst=dst).single()
    return rec["confidence"] if rec else None


def _bucket_for(edge_type, conf):
    spec = EDGE_SPECS.get(edge_type)
    return _bucket_label(spec["buckets"], conf) if spec else "n/a"


def _precision(counts):
    denom = counts["relevant"] + counts["irrelevant"]
    return round(counts["relevant"] / denom, 4) if denom else None


# ─── precision (구현 완료) ─────────────────────────────────────────────────────


def grade_precision(session, pid, edges):
    """라벨된 쌍을 현재 그래프에 대조해 precision을 낸다(전체·타입별·버킷별)."""
    def new_counts():
        return {"relevant": 0, "irrelevant": 0, "unsure": 0}

    overall = new_counts()
    by_type, by_bucket = {}, {}
    vanished, invalid = [], []

    for e in edges:
        edge_type = e["edge_type"]
        src, dst = e["src"]["id"], e["dst"]["id"]
        label = str(e.get("label") or "").strip().lower()
        tag = f"{edge_type} {src}->{dst}"

        if label not in VALID_LABELS:
            invalid.append(f"{tag}: label={e.get('label')!r}")
            continue

        conf = edge_exists(session, pid, edge_type, src, dst)
        if conf is None:
            vanished.append(tag)   # 라벨했지만 현재 그래프에 없음 → precision 분모에서 제외
            continue

        bucket_key = f"{edge_type} {_bucket_for(edge_type, conf)}"
        for bucket in (overall, by_type.setdefault(edge_type, new_counts()),
                       by_bucket.setdefault(bucket_key, new_counts())):
            bucket[label] += 1

    def summarize(counts):
        present = counts["relevant"] + counts["irrelevant"] + counts["unsure"]
        return {"precision": _precision(counts), "present": present, **counts}

    return {
        "overall": summarize(overall),
        "by_type": {t: summarize(c) for t, c in sorted(by_type.items())},
        "by_bucket": {b: summarize(c) for b, c in sorted(by_bucket.items())},
        "vanished": vanished,           # 빌더 변경으로 사라진 라벨 쌍 (같은 스냅샷이면 비어야 정상)
        "invalid_labels": invalid,
        "labeled_total": len(edges),
        # 주의: 빌더 변경으로 "새로 생긴" 엣지(라벨에 없는 쌍)는 이 스크립트로 잡히지 않는다.
        # 새 엣지의 precision까지 재려면 sample_edges.py로 재샘플 후 새 쌍만 추가 라벨해야 한다.
    }


# ─── recall (미구현 — 팀원 이어서 작성) ─────────────────────────────────────────


def grade_recall(session, pid, pairs):
    """recall: "연결됐어야 하는 쌍" 중 그래프에 실제 엣지가 존재하는 비율.

    TODO(팀원): recall-*.yaml 스키마 확정 후 아래를 구현한다. 각 쌍은 아래 형태를 가정한다:
        {edge_type: REFERENCE|DISCUSSED_IN|TRIGGERED_BY,
         src: {type, id}, dst: {type, id}, note: "..."}
    구현 뼈대:
        hits, misses = [], []
        for p in pairs:
            conf = edge_exists(session, pid, p["edge_type"], p["src"]["id"], p["dst"]["id"])
            (hits if conf is not None else misses).append(...)
        recall = len(hits) / len(pairs)
    반환은 grade_precision과 같은 결(overall/by_type + hits/misses 목록)로 맞춘다.
    엣지 존재 질의는 위 edge_exists()를 그대로 재사용하면 된다.
    """
    raise NotImplementedError("recall 미구현 — recall 골든 쌍 스키마 확정 후 팀원이 작성")


# ─── main ─────────────────────────────────────────────────────────────────────


def _load(path):
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f)


def _print_precision(prec):
    o = prec["overall"]
    print(f"\n=== PRECISION (relevant / relevant+irrelevant) ===")
    print(f"전체: {o['precision']}  (relevant {o['relevant']} / irrelevant {o['irrelevant']} / unsure {o['unsure']})")
    print("타입별:")
    for t, c in prec["by_type"].items():
        print(f"  {t:13} {c['precision']}  (rel {c['relevant']} / irr {c['irrelevant']} / uns {c['unsure']})")
    print("버킷별:")
    for b, c in prec["by_bucket"].items():
        print(f"  {b:26} {c['precision']}  (rel {c['relevant']} / irr {c['irrelevant']} / uns {c['unsure']})")
    if prec["vanished"]:
        print(f"⚠ 사라진 라벨 쌍 {len(prec['vanished'])}개 (precision 제외): {prec['vanished'][:5]}")
    if prec["invalid_labels"]:
        print(f"⚠ 허용값 아닌 라벨 {len(prec['invalid_labels'])}개: {prec['invalid_labels'][:5]}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--precision", default=os.path.join(EVAL_DIR, "edge_labels", "precision-2026-07-05.yaml"),
                        help="precision 라벨 파일")
    parser.add_argument("--recall", help="recall 골든 쌍 파일 (있으면 recall도 계산 — 현재 미구현)")
    parser.add_argument("--project-id", help="생략 시 자동 감지")
    parser.add_argument("--out", help="결과 JSON 경로 (생략 시 eval/results/edge-<오늘>.json)")
    args = parser.parse_args()

    prec_doc = _load(args.precision)

    with open_driver() as driver, driver.session() as session:
        pid = args.project_id or prec_doc.get("project_id") or detect_project_id(session)
        if prec_doc.get("project_id") and prec_doc["project_id"] != pid:
            print(f"⚠ 라벨 파일 project_id({prec_doc['project_id']}) != 대상({pid}) — 스냅샷 확인 필요")

        precision = grade_precision(session, pid, prec_doc.get("edges") or [])

        recall = None
        if args.recall:
            try:
                recall = grade_recall(session, pid, (_load(args.recall) or {}).get("pairs") or [])
            except NotImplementedError as ex:
                print(f"recall: {ex}")

    _print_precision(precision)

    result = {
        "meta": {
            "graph_snapshot": prec_doc.get("graph_snapshot"),
            "project_id": pid,
            "precision_file": os.path.relpath(args.precision, EVAL_DIR),
            "recall_file": os.path.relpath(args.recall, EVAL_DIR) if args.recall else None,
            "run_at": datetime.datetime.now().isoformat(timespec="seconds"),
        },
        "precision": precision,
        "recall": recall,   # 미구현이면 null
    }
    out = args.out or os.path.join(EVAL_DIR, "results", f"edge-{datetime.date.today()}.json")
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    print(f"\n저장: {out}")


if __name__ == "__main__":
    main()
