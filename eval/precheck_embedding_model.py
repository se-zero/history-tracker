"""임베딩 모델 후보 비교 — 그래프를 재구축하지 않고 관련/무관 쌍 분리도를 잰다.

정답지(precision 라벨 + recall 골든)의 양쪽 노드 원문만 그래프에서 읽어 후보 모델로
임베딩하고, 빌더와 같은 방식(쌍별 max)으로 점수를 계산해 비교한다. 그래프에는 쓰지 않는다 —
재임베딩·재구축(수십 분)에 들어가기 전에 모델을 고르는 용도다.

비교 기준은 절대 점수가 아니라 **선별성**이다. 모델이 바뀌면 점수 분포가 통째로 이동해
고정 임계값 비교가 무효이므로(improvement-log 2026-07-18), 스케일 무관 지표로 본다:
  - ROC AUC: relevant를 irrelevant보다 높게 매기는 비율
  - 동일-recall 운영점 precision: 골든을 전부 살리는 임계값에서의 precision

쌍별 점수는 결과 JSON의 pair_scores에 남는다 — 재임베딩 없이 부트스트랩(모델 간 차이의
신뢰구간)이나 임계값 스윕을 다시 돌릴 수 있다.

비교할 모델은 아래 CONFIGS에서 바꾼다. 실행:
  services/ai-engine/.venv/Scripts/python.exe eval/precheck_embedding_model.py
"""

import argparse
import json
import os
import sys
from collections import defaultdict
from datetime import datetime, timezone

import numpy as np
import yaml

EVAL_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, EVAL_DIR)

from graph_lookup import detect_project_id, open_driver  # noqa: E402

# 비교할 임베딩 설정 — (라벨, 모델, dimensions). dimensions=None이면 모델 기본 차원.
# 현행 모델을 반드시 하나 넣는다 — 저장된 confidence와 대조해 이 스크립트가 빌더의 점수를
# 재현하는지 확인하는 기준선이 된다(실행 결과의 "재현 검증" 줄).
CONFIGS = [
    ("3-small", "text-embedding-3-small", None),       # 교체 전 모델
    ("3-large@1536", "text-embedding-3-large", 1536),  # 현행 — 절삭해 벡터 인덱스(1536) 유지
    ("3-large@3072", "text-embedding-3-large", None),  # 전체 차원
]

BATCH = 200


# --------------------------------------------------------------------------- 그래프 조회

def fetch_texts(session, pid: str, commits: set[str], issues: set[str], threads: set[str]) -> dict:
    """정답지가 참조하는 노드의 임베딩 대상 원문을 조회한다.

    임베딩되는 텍스트는 수집 경로(event_handler)와 동일해야 한다:
      ChangeSet = c.message / MODIFIED = m.diffSummary / Issue = title\\n\\nbody / Communication = body
    """
    out = {"commit": {}, "issue": {}, "thread": {}}

    for h in commits:
        row = session.run(
            "MATCH (c:ChangeSet {project_id:$pid}) WHERE c.hash STARTS WITH $h "
            "OPTIONAL MATCH (c)-[m:MODIFIED]->(:File) "
            "RETURN c.message AS message, collect(m.diffSummary) AS diffs LIMIT 1",
            pid=pid, h=h,
        ).single()
        if not row:
            continue
        rows = []
        if row["message"] and row["message"].strip():
            rows.append(row["message"])
        rows += [d for d in (row["diffs"] or []) if d and d.strip()]
        out["commit"][h] = rows

    for k in issues:
        row = session.run(
            "MATCH (i:Issue {project_id:$pid, issue_key:$k}) "
            "RETURN i.title AS title, i.body AS body LIMIT 1",
            pid=pid, k=k,
        ).single()
        if row:
            out["issue"][k] = [f"{row['title'] or ''}\n\n{row['body'] or ''}"]

    for cid in threads:
        rows = session.run(
            "MATCH (n:Communication {project_id:$pid, conversation_id:$cid}) "
            "RETURN n.body AS body",
            pid=pid, cid=cid,
        ).data()
        bodies = [r["body"] for r in rows if r["body"] and r["body"].strip()]
        if bodies:
            out["thread"][cid] = bodies

    return out


# --------------------------------------------------------------------------- 임베딩

def embed_all(client, texts: list[str], model: str, dimensions: int | None) -> list[list[float]]:
    vectors: list[list[float]] = []
    for i in range(0, len(texts), BATCH):
        chunk = texts[i : i + BATCH]
        kwargs = {"model": model, "input": chunk}
        if dimensions:
            kwargs["dimensions"] = dimensions
        resp = client.embeddings.create(**kwargs)
        vectors += [d.embedding for d in resp.data]
        print(f"    ...{min(i + BATCH, len(texts))}/{len(texts)}", flush=True)
    return vectors


def normalize(mat: np.ndarray) -> np.ndarray:
    norms = np.linalg.norm(mat, axis=1, keepdims=True)
    norms[norms == 0.0] = 1.0
    return mat / norms


# --------------------------------------------------------------------------- 채점

def pair_score(vecs: dict, edge_type: str, src_id: str, dst_id: str) -> float | None:
    """빌더와 같은 방식의 쌍 점수 — 양쪽 행 집합의 전체 쌍 코사인 중 최고값.

    REFERENCE    : (커밋 메시지 + 파일 diff 행) × (스레드 내 메시지들)
    TRIGGERED_BY : (커밋 메시지 + 파일 diff 행) × (이슈)
    DISCUSSED_IN : (이슈) × (스레드 내 메시지들)
    쌍별 max 집계(reference_builder.select_reference_pairs)와 스레드 대표=최고점 규칙을 그대로 따른다.
    """
    kinds = {
        "REFERENCE": ("commit", "thread"),
        "TRIGGERED_BY": ("commit", "issue"),
        "DISCUSSED_IN": ("issue", "thread"),
    }[edge_type]
    a = vecs[kinds[0]].get(src_id)
    b = vecs[kinds[1]].get(dst_id)
    if a is None or b is None or len(a) == 0 or len(b) == 0:
        return None
    return float((normalize(np.asarray(a)) @ normalize(np.asarray(b)).T).max())


def roc_auc(pos: list[float], neg: list[float]) -> float | None:
    """relevant가 irrelevant보다 높은 점수를 받을 확률 (동점은 0.5). 스케일 무관 지표."""
    if not pos or not neg:
        return None
    wins = sum((p > n) + 0.5 * (p == n) for p in pos for n in neg)
    return wins / (len(pos) * len(neg))


def precision_at_full_recall(pos: list[float], neg: list[float], golden: list[float]) -> tuple:
    """골든을 전부 살리는 임계값(=골든 최저점)에서의 precision — 동일-recall 운영점 비교.

    골든이 없는 타입은 라벨 relevant의 최저점을 대용한다.
    """
    anchor_pool = golden or pos
    if not anchor_pool:
        return None, None, None
    t = min(anchor_pool)
    tp = sum(1 for p in pos if p >= t)
    fp = sum(1 for n in neg if n >= t)
    return (tp / (tp + fp) if (tp + fp) else None), t, (tp, fp)


# --------------------------------------------------------------------------- main

def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--project-id")
    ap.add_argument("--precision", default=os.path.join(EVAL_DIR, "edge_labels", "precision-2026-07-05.yaml"))
    ap.add_argument("--recall", default=os.path.join(EVAL_DIR, "edge_labels", "recall-2026-07-05.yaml"))
    ap.add_argument("--out", default=os.path.join(EVAL_DIR, "results", "precheck-embedding-model.json"))
    ap.add_argument("--dry-run", action="store_true", help="임베딩 호출 없이 대상 텍스트 수만 보고")
    args = ap.parse_args()

    labels = yaml.safe_load(open(args.precision, encoding="utf-8"))["edges"]
    golden_pairs = yaml.safe_load(open(args.recall, encoding="utf-8"))["pairs"]

    commits, issues, threads = set(), set(), set()
    for e in labels + golden_pairs:
        for side in ("src", "dst"):
            n = e[side]
            {"commit": commits, "issue": issues, "message": threads}[n["type"]].add(str(n["id"]))

    driver = open_driver()
    with driver.session() as session:
        pid = args.project_id or detect_project_id(session)
        print(f"project_id = {pid}")
        texts = fetch_texts(session, pid, commits, issues, threads)
    driver.close()

    # 노드 종류별 텍스트를 하나의 평탄한 리스트로 모아 한 번에 임베딩한다 (중복 호출 방지)
    flat: list[str] = []
    index: dict[tuple[str, str], tuple[int, int]] = {}
    for kind, table in texts.items():
        for node_id, rows in table.items():
            index[(kind, node_id)] = (len(flat), len(flat) + len(rows))
            flat += rows

    print(f"노드 해석: commit {len(texts['commit'])}/{len(commits)} · "
          f"issue {len(texts['issue'])}/{len(issues)} · thread {len(texts['thread'])}/{len(threads)}")
    print(f"임베딩 대상 텍스트 {len(flat)}개 × 설정 {len(CONFIGS)}개")
    if args.dry_run:
        return

    if not os.environ.get("OPENAI_API_KEY"):
        # grader.py와 같은 방식 — 전체 로드는 NEO4J_* 등 컨테이너용 값이 로컬 설정을 덮는다
        from dotenv import dotenv_values
        key = dotenv_values(os.path.join(EVAL_DIR, "..", "infra", "docker", ".env")).get("OPENAI_API_KEY")
        if key:
            os.environ["OPENAI_API_KEY"] = key
    if not os.environ.get("OPENAI_API_KEY"):
        sys.exit("OPENAI_API_KEY가 필요합니다 — infra/docker/.env 또는 환경변수로 설정")

    from openai import OpenAI
    client = OpenAI()

    report: dict = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "project_id": pid,
        "precision_labels": os.path.basename(args.precision),
        "recall_golden": os.path.basename(args.recall),
        "configs": {},
    }

    for label, model, dims in CONFIGS:
        print(f"\n=== {label} ({model}, dims={dims or 'default'}) ===")
        vectors = embed_all(client, flat, model, dims)
        vecs = {"commit": {}, "issue": {}, "thread": {}}
        for (kind, node_id), (lo, hi) in index.items():
            vecs[kind][node_id] = vectors[lo:hi]

        by_type: dict[str, dict[str, list[float]]] = defaultdict(lambda: defaultdict(list))
        pair_scores: list[dict] = []
        stored_vs_new: list[tuple[float, float]] = []
        unresolved = 0

        for e in labels:
            s = pair_score(vecs, e["edge_type"], str(e["src"]["id"]), str(e["dst"]["id"]))
            if s is None:
                unresolved += 1
                continue
            by_type[e["edge_type"]][e.get("label") or "unlabeled"].append(s)
            pair_scores.append({"kind": "label", "edge_type": e["edge_type"],
                                "src": str(e["src"]["id"]), "dst": str(e["dst"]["id"]),
                                "label": e.get("label"), "score": s})
            if label == "3-small" and e.get("confidence"):
                stored_vs_new.append((float(e["confidence"]), s))

        for g in golden_pairs:
            s = pair_score(vecs, g["edge_type"], str(g["src"]["id"]), str(g["dst"]["id"]))
            if s is None:
                unresolved += 1
                continue
            by_type[g["edge_type"]]["golden"].append(s)
            pair_scores.append({"kind": "golden", "edge_type": g["edge_type"],
                                "src": str(g["src"]["id"]), "dst": str(g["dst"]["id"]),
                                "label": "golden", "score": s})

        entry: dict = {"model": model, "dimensions": dims, "unresolved_pairs": unresolved, "by_type": {},
                       "pair_scores": pair_scores}

        for et in ("REFERENCE", "DISCUSSED_IN", "TRIGGERED_BY"):
            pos = by_type[et]["relevant"]
            neg = by_type[et]["irrelevant"]
            gold = by_type[et]["golden"]
            auc = roc_auc(pos, neg)
            prec, thr, counts = precision_at_full_recall(pos, neg, gold)
            entry["by_type"][et] = {
                "n_relevant": len(pos), "n_irrelevant": len(neg), "n_golden": len(gold),
                "auc": auc,
                "precision_at_full_golden_recall": prec,
                "threshold_at_full_golden_recall": thr,
                "tp_fp": counts,
                "mean_relevant": float(np.mean(pos)) if pos else None,
                "mean_irrelevant": float(np.mean(neg)) if neg else None,
                "min_golden": min(gold) if gold else None,
            }
            if auc is not None:
                print(f"  {et:13s} AUC {auc:.3f} | P@full-recall "
                      f"{prec if prec is None else round(prec, 3)} (t={thr:.3f}, tp/fp={counts}) | "
                      f"mean rel {np.mean(pos):.3f} vs irrel {np.mean(neg):.3f}")

        # 전 타입 합산 — 타입별 스케일이 달라 참고용
        all_pos = [s for et in by_type for s in by_type[et]["relevant"]]
        all_neg = [s for et in by_type for s in by_type[et]["irrelevant"]]
        entry["overall_auc"] = roc_auc(all_pos, all_neg)
        print(f"  {'ALL':13s} AUC {entry['overall_auc']:.3f}")

        if stored_vs_new:
            a = np.array([x for x, _ in stored_vs_new])
            b = np.array([y for _, y in stored_vs_new])
            entry["replication_check"] = {
                "n": len(stored_vs_new),
                "mean_abs_diff": float(np.abs(a - b).mean()),
                "corr": float(np.corrcoef(a, b)[0, 1]),
            }
            print(f"  [재현 검증] 저장된 confidence 대비 평균절대차 {np.abs(a - b).mean():.4f} · "
                  f"상관 {np.corrcoef(a, b)[0, 1]:.4f} (n={len(stored_vs_new)})")

        report["configs"][label] = entry

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print(f"\n저장: {args.out}")


if __name__ == "__main__":
    main()
