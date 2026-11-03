"""임베딩 모델 후보 비교 — 그래프를 재구축하지 않고 관련/무관 쌍 분리도를 잰다.

정답지(precision 라벨 + recall 골든)의 양쪽 노드 원문만 그래프에서 읽어 후보 모델로
임베딩하고, 빌더와 같은 방식(쌍별 max)으로 점수를 계산해 비교한다. 그래프에는 쓰지 않는다 —
재임베딩·재구축(수십 분)에 들어가기 전에 모델을 고르는 용도다.

비교 기준은 절대 점수가 아니라 **선별성**이다. 모델이 바뀌면 점수 분포가 통째로 이동해
고정 임계값 비교가 무효이므로(improvement-log 2026-07-18), 스케일 무관 지표로 본다:
  - ROC AUC: relevant를 irrelevant보다 높게 매기는 비율
  - 동일-recall 운영점 precision: 골든을 전부 살리는 임계값에서의 precision

쌍별 점수는 결과 JSON의 pair_scores에 남는다 — 재임베딩 없이 부트스트랩(모델 간 차이의
신뢰구간)이나 임계값 스윕을 다시 돌릴 수 있다. 부트스트랩은 precheck_compare.py가 한다.

비교할 모델은 아래 CONFIGS에서 바꾼다. base_url이 있는 설정은 OpenAI 호환 임베딩 서버
(eval/embedding_finetune/serve.py, Mac)로 부른다. 서버는 프로세스당 모델 하나만 서빙하므로
로컬 후보는 서버를 그 모델로 띄운 뒤 한 번에 하나씩 돈다. --only를 주지 않으면 서버 없이
도는 OpenAI 설정만 돈다. 실행:
  services/ai-engine/.venv/Scripts/python.exe eval/precheck_embedding_model.py
  services/ai-engine/.venv/Scripts/python.exe eval/precheck_embedding_model.py --only bge-m3
--only를 주고 --out을 생략하면 결과는 results/precheck-<라벨>.json — 후보별로 따로 돌려도
서로 덮어쓰지 않는다.
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

from edge_eval import edge_exists  # noqa: E402
from graph_lookup import detect_project_id, open_driver  # noqa: E402

# Mac에서 띄우는 임베딩 서버(eval/embedding_finetune/serve.py). 인증은 EMBED_SERVER_TOKEN을 걸었을 때만 쓴다.
LOCAL_BASE_URL = "http://127.0.0.1:8100/v1"

# 비교할 임베딩 설정. label은 결과 JSON의 키이자 --only 인자다.
#   dimensions=None → 모델 기본 차원 (요청에 dimensions를 싣지 않는다)
#   base_url=None   → OpenAI. 값이 있으면 그 주소의 OpenAI 호환 서버
CONFIGS = [
    {"label": "3-small", "model": "text-embedding-3-small", "dimensions": None, "base_url": None},       # 교체 전 모델
    {"label": "3-large@1536", "model": "text-embedding-3-large", "dimensions": 1536, "base_url": None},  # 현행 — 절삭해 벡터 인덱스(1536) 유지
    {"label": "3-large@3072", "model": "text-embedding-3-large", "dimensions": None, "base_url": None},  # 전체 차원
    {"label": "bge-m3", "model": "BAAI/bge-m3", "dimensions": None, "base_url": LOCAL_BASE_URL},
    {"label": "kure-v1", "model": "nlpai-lab/KURE-v1", "dimensions": None, "base_url": LOCAL_BASE_URL},
    {"label": "qwen3-0.6b", "model": "Qwen/Qwen3-Embedding-0.6B", "dimensions": None, "base_url": LOCAL_BASE_URL},
    {"label": "kanana-1.3b", "model": "kakaocorp/kanana-2-1.3b-embedding", "dimensions": None, "base_url": LOCAL_BASE_URL},
]

# 재현 검증의 기준 설정 — 현재 그래프는 이 설정으로 만들어졌으므로, 이 설정의 쌍 점수가 그래프에 저장된
# 시맨틱 엣지 confidence와 맞으면 이 스크립트가 빌더의 점수를 재현한다는 뜻이다(실행 결과의 "재현 검증" 줄).
# 운영 설정(graph/embedder.py의 모델·차원)과 같아야 한다.
CURRENT_LABEL = "3-large@1536"

BATCH = 200


# --------------------------------------------------------------------------- 설정 선택

def select_configs(only: str | None) -> list[dict]:
    """--only 라벨 목록을 설정으로 해석한다. 생략하면 서버 없이 도는 OpenAI 설정만(기존 동작)."""
    if only is None:
        return [c for c in CONFIGS if c["base_url"] is None]

    by_label = {c["label"]: c for c in CONFIGS}
    wanted = list(dict.fromkeys(s.strip() for s in only.split(",") if s.strip()))
    unknown = [label for label in wanted if label not in by_label]
    if unknown or not wanted:
        backends: dict[str, list[str]] = {}
        for c in CONFIGS:
            backends.setdefault(c["base_url"] or "OpenAI", []).append(c["label"])
        listing = "\n".join(f"  {backend}: {', '.join(labels)}" for backend, labels in backends.items())
        sys.exit(f"--only에 알 수 없는 라벨: {', '.join(unknown) or '(비어 있음)'}\n쓸 수 있는 라벨:\n{listing}")

    # 서버는 프로세스당 모델 하나라, 같은 주소의 다른 모델은 400이 난다 — 앞 후보를 다 돌린 뒤에야
    # 터지고 그때까지의 결과는 저장되지 않으므로 시작 전에 막는다.
    local = [label for label in wanted if by_label[label]["base_url"]]
    if len(local) > 1:
        sys.exit(f"로컬 서버 후보는 한 번에 하나만 고를 수 있다 (서버가 프로세스당 모델 하나를 서빙): {', '.join(local)}")
    return [by_label[label] for label in wanted]


def default_out_path(selected: list[dict], only_given: bool) -> str:
    """기본 결과 경로. --only를 줬으면 라벨을 넣어 후보별로 따로 돌려도 덮어쓰지 않게 한다."""
    name = "precheck-" + "+".join(c["label"] for c in selected) if only_given else "precheck-embedding-model"
    return os.path.join(EVAL_DIR, "results", f"{name}.json")


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


def fetch_stored_confidences(session, pid: str, labels: list[dict]) -> dict[tuple[str, str, str], float]:
    """라벨 쌍마다 현재 그래프의 시맨틱 엣지 confidence를 모은다 — 엣지가 있는 쌍만 담긴다.

    재현 검증의 기준이다. 라벨 YAML에 박힌 confidence는 라벨을 뽑던 때의 그래프(구 모델) 값이라
    현행 설정의 점수와 대조할 수 없다. 지금 그래프를 직접 읽는다.
    """
    stored: dict[tuple[str, str, str], float] = {}
    for e in labels:
        key = (e["edge_type"], str(e["src"]["id"]), str(e["dst"]["id"]))
        confidence = edge_exists(session, pid, *key)
        if confidence is not None:
            stored[key] = float(confidence)
    return stored


# --------------------------------------------------------------------------- 임베딩

def ensure_openai_key() -> None:
    """OPENAI_API_KEY를 환경변수 → infra/docker/.env 순으로 찾는다. 없으면 종료."""
    if not os.environ.get("OPENAI_API_KEY"):
        # grader.py와 같은 방식 — 전체 로드는 NEO4J_* 등 컨테이너용 값이 로컬 설정을 덮는다
        from dotenv import dotenv_values
        key = dotenv_values(os.path.join(EVAL_DIR, "..", "infra", "docker", ".env")).get("OPENAI_API_KEY")
        if key:
            os.environ["OPENAI_API_KEY"] = key
    if not os.environ.get("OPENAI_API_KEY"):
        sys.exit("OPENAI_API_KEY가 필요합니다 — infra/docker/.env 또는 환경변수로 설정")


def make_client(cfg: dict):
    """설정에 맞는 임베딩 클라이언트 — base_url이 있으면 로컬 서버, 없으면 OpenAI."""
    from openai import OpenAI
    if cfg["base_url"]:
        # 로컬 서버는 기본적으로 인증이 없다. SDK가 키를 요구해서 자리표시자를 넣고, 서버에 토큰을
        # 걸었을 때만 EMBED_SERVER_TOKEN을 쓴다.
        return OpenAI(base_url=cfg["base_url"], api_key=os.environ.get("EMBED_SERVER_TOKEN") or "unused")
    ensure_openai_key()
    return OpenAI()


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


def replication_check(stored: dict[tuple[str, str, str], float], pair_scores: list[dict]) -> dict | None:
    """현행 설정의 라벨 쌍 점수를 그래프에 저장된 confidence와 대조한다. 엣지가 있는 쌍만 비교한다."""
    pairs = []
    for p in pair_scores:
        if p["kind"] != "label":
            continue
        confidence = stored.get((p["edge_type"], p["src"], p["dst"]))
        if confidence is not None:
            pairs.append((confidence, p["score"]))
    if not pairs:
        return None
    a = np.array([x for x, _ in pairs])
    b = np.array([y for _, y in pairs])
    return {
        "n": len(pairs),
        "mean_abs_diff": float(np.abs(a - b).mean()),
        "corr": float(np.corrcoef(a, b)[0, 1]),
    }


# --------------------------------------------------------------------------- main

def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--project-id")
    ap.add_argument("--precision", default=os.path.join(EVAL_DIR, "edge_labels", "precision-2026-07-05.yaml"))
    ap.add_argument("--recall", default=os.path.join(EVAL_DIR, "edge_labels", "recall-2026-07-05.yaml"))
    ap.add_argument("--only", help="쉼표로 구분한 라벨만 실행 (예: bge-m3). 생략하면 OpenAI 설정만 돈다. "
                                   f"쓸 수 있는 라벨: {', '.join(c['label'] for c in CONFIGS)}")
    ap.add_argument("--out", help="결과 JSON 경로 (생략 시 results/precheck-embedding-model.json, "
                                  "--only를 주면 results/precheck-<라벨>.json)")
    ap.add_argument("--dry-run", action="store_true", help="임베딩 호출 없이 대상 텍스트 수만 보고")
    args = ap.parse_args()

    selected = select_configs(args.only)
    out_path = args.out or default_out_path(selected, only_given=args.only is not None)

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
        # 재현 검증용 — 현행 설정을 돌릴 때만 쓰므로 그때만 모은다
        stored = (fetch_stored_confidences(session, pid, labels)
                  if any(c["label"] == CURRENT_LABEL for c in selected) else {})
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
    print(f"임베딩 대상 텍스트 {len(flat)}개 × 설정 {len(selected)}개")
    if args.dry_run:
        return

    # OpenAI 설정이 하나라도 있을 때만 키를 요구한다 — 앞 후보를 다 돌린 뒤에 터지지 않게 미리 확인
    if any(c["base_url"] is None for c in selected):
        ensure_openai_key()

    report: dict = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "project_id": pid,
        "precision_labels": os.path.basename(args.precision),
        "recall_golden": os.path.basename(args.recall),
        "configs": {},
    }

    for cfg in selected:
        label, model, dims = cfg["label"], cfg["model"], cfg["dimensions"]
        print(f"\n=== {label} ({model}, dims={dims or 'default'}) ===")
        vectors = embed_all(make_client(cfg), flat, model, dims)
        vecs = {"commit": {}, "issue": {}, "thread": {}}
        for (kind, node_id), (lo, hi) in index.items():
            vecs[kind][node_id] = vectors[lo:hi]

        by_type: dict[str, dict[str, list[float]]] = defaultdict(lambda: defaultdict(list))
        pair_scores: list[dict] = []
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

        if label == CURRENT_LABEL:
            rep = replication_check(stored, pair_scores)
            if rep:
                entry["replication_check"] = rep
                print(f"  [재현 검증] 저장된 confidence 대비 평균절대차 {rep['mean_abs_diff']:.4f} · "
                      f"상관 {rep['corr']:.4f} (n={rep['n']})")

        report["configs"][label] = entry

    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print(f"\n저장: {out_path}")


if __name__ == "__main__":
    main()
