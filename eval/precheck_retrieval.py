#!/usr/bin/env python
"""질의 검색 근거 회수 — 골든 질문으로 검색했을 때 정답 근거가 상위 k개에 드는가 (docs/measurement.md §4.5).

임베딩 모델을 바꾸면 질의 쪽 시맨틱 검색(search_by_keyword)의 품질도 달라진다. 이 스크립트는 LLM 없이
그 품질을 근사해 후보별로 비교한다: 골든 케이스의 질문과 그래프의 Communication·Issue 원문을 후보
모델로 임베딩하고, 정확한 코사인으로 순위를 매겨 expected_evidence_ids가 상위 k개에 드는 비율을
잰다. 그래프에는 쓰지 않는다.

- 대상은 issue:·message: 근거뿐이다 — search_by_keyword가 훑는 인덱스가 issue_embedding·comm_embedding이다.
  그런 근거가 없는 케이스(commit:·pull_request:만 있는 케이스)는 건너뛴다. 영어 짝 케이스(case-*-en)도
  별개 케이스로 센다.
- message 근거는 스레드(conversation_id) 단위다. 스레드 점수는 그 스레드 메시지들과의 코사인 최댓값이다
  (search_by_keyword가 같은 스레드에서 최고 점수 메시지 한 건만 남기는 것과 같은 취지). 이슈는 코사인이다.
  근거 id는 conversation_id나 aliases 중 하나가 맞으면 맞힌 것으로 본다.
- 지표는 타입(issue·message)별로, 근거 id 단위 Recall@k와 케이스 단위 MRR(케이스에서 가장 앞선 정답
  순위의 역수 평균, 못 찾으면 0)이다. 그래프에 없는 근거는 순위가 null이고 분모에 남는다 — 건수를
  같이 찍으니, 많으면 골든과 스냅샷이 맞는지 validate_golden.py로 확인한다.

실제 도구와 다른 점 — 이 수치는 근사치다:
  1. 실제 도구는 LLM이 고른 키워드를 임베딩해 검색하지만, 여기서는 원 질문을 그대로 쓴다.
  2. 실제 도구는 Neo4j HNSW(근사 최근접, over-fetch 뒤 project_id 필터)이고, 여기서는 정확한 코사인이다.
  3. 질의 쪽 지시문(Qwen3 등이 권장하는 instruction prefix)은 붙이지 않는다.
  4. 입력 절단은 하지 않는다 — 수집 경로의 8,000토큰 절단은 재현하지 않고, 로컬 서버의 2,048토큰
     절단은 서버가 알아서 한다.
  5. conversation_id가 없는 Communication은 근거 id에 대응할 수 없어 코퍼스에서 뺀다.

실행 (ai-engine venv 사용):
    services/ai-engine/.venv/Scripts/python.exe eval/precheck_retrieval.py --only 3-large@1536
    services/ai-engine/.venv/Scripts/python.exe eval/precheck_retrieval.py --only bge-m3 --k 5,20
    옵션: --project-id --golden-dir <경로> --out <경로>
--only와 설정 레지스트리(CONFIGS)는 precheck_embedding_model.py와 같다 — 생략하면 OpenAI 설정만 돌고,
로컬 후보는 서버를 그 모델로 띄운 뒤 한 번에 하나씩 돈다. 결과는 results/precheck-retrieval[-<라벨>].json.
"""

import argparse
import json
import os
import sys
from datetime import datetime, timezone
from glob import glob
from statistics import mean

import numpy as np
import yaml

EVAL_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, EVAL_DIR)

from graph_lookup import ID_RE, detect_project_id, open_driver  # noqa: E402
from precheck_embedding_model import (  # noqa: E402
    embed_all,
    ensure_openai_key,
    make_client,
    normalize,
    select_configs,
)

GOLDEN_TYPES = ("issue", "message")
DEFAULT_KS = [5, 20]

COMM_Q = ("MATCH (c:Communication {project_id:$pid}) "
          "RETURN c.conversation_id AS conversation_id, c.body AS body")
ISSUE_Q = ("MATCH (i:Issue {project_id:$pid}) "
           "RETURN i.issue_key AS issue_key, i.title AS title, i.body AS body")


# --------------------------------------------------------------------------- 골든

def parse_evidence(entry) -> dict | None:
    """골든 근거 항목(문자열 또는 {id, aliases})을 해석한다. issue·message가 아니면 None.

    ids에는 본 id와 별칭이 모두 들어간다 — 이 중 하나라도 맞으면 맞힌 것으로 센다.
    """
    raw = entry if isinstance(entry, str) else entry["id"]
    match = ID_RE.match(raw)
    if not match:
        raise ValueError(f"골든 근거 id 형식이 아니다 (type:id 필요): {raw!r}")
    id_type, value = match.groups()
    if id_type not in GOLDEN_TYPES:
        return None
    aliases = []
    if isinstance(entry, dict):
        for alias in entry.get("aliases") or []:
            alias_match = ID_RE.match(alias)
            if not alias_match:
                raise ValueError(f"골든 별칭 형식이 아니다 (type:id 필요): {alias!r}")
            aliases.append(alias_match.group(2))
    return {"type": id_type, "id": value, "ids": [value, *aliases], "raw": raw}


def load_cases(golden_dir: str) -> tuple[list[dict], int]:
    """골든 케이스를 읽는다. (대상 케이스 목록, 대상 근거가 없어 건너뛴 케이스 수)."""
    cases, skipped = [], 0
    for path in sorted(glob(os.path.join(golden_dir, "case-*.yaml"))):
        with open(path, encoding="utf-8") as f:
            doc = yaml.safe_load(f)
        evidence = [ev for ev in map(parse_evidence, doc.get("expected_evidence_ids") or []) if ev]
        if not evidence:
            skipped += 1
            continue
        cases.append({"id": doc["id"], "question": doc["question"], "evidence": evidence})
    return cases, skipped


# --------------------------------------------------------------------------- 코퍼스

def fetch_rows(session, pid: str) -> tuple[list[dict], list[dict]]:
    return session.run(COMM_Q, pid=pid).data(), session.run(ISSUE_Q, pid=pid).data()


def build_corpus(comm_rows: list[dict], issue_rows: list[dict]) -> dict:
    """임베딩할 코퍼스를 만든다. 임베딩 못 하는 행은 빼고 건수를 남긴다."""
    dropped = {"no_conversation_id": 0, "empty_body": 0, "empty_issue": 0}
    conversation_ids, bodies, issue_keys, issue_texts = [], [], [], []

    for row in comm_rows:
        if not row["conversation_id"]:
            dropped["no_conversation_id"] += 1
        elif not (row["body"] and row["body"].strip()):
            dropped["empty_body"] += 1
        else:
            conversation_ids.append(str(row["conversation_id"]))
            bodies.append(row["body"])

    for row in issue_rows:
        # 수집 경로(event_handler)와 같은 텍스트 — precheck_embedding_model.fetch_texts와도 같다
        text = f"{row['title'] or ''}\n\n{row['body'] or ''}"
        if not text.strip():
            dropped["empty_issue"] += 1
        else:
            issue_keys.append(str(row["issue_key"]))
            issue_texts.append(text)

    return {"conversation_ids": conversation_ids, "bodies": bodies,
            "issue_keys": issue_keys, "issue_texts": issue_texts, "dropped": dropped}


# --------------------------------------------------------------------------- 순위

def thread_max_scores(sims, keys: list[str]) -> dict[str, float]:
    """키(스레드)별 최고 점수. 같은 스레드의 여러 메시지 중 가장 질문에 가까운 하나가 그 스레드의 점수다."""
    best: dict[str, float] = {}
    for key, sim in zip(keys, sims):
        sim = float(sim)
        if key not in best or sim > best[key]:
            best[key] = sim
    return best


def rank_by_cosine(query, matrix, keys: list[str]) -> list[str]:
    """matrix의 각 행(keys[i]에 속함)과 질문의 코사인으로 키를 내림차순 정렬한다. 동점은 키 순서로 고정한다.

    같은 키가 여러 행이면(스레드의 메시지들) 그 키의 점수는 행들 중 최댓값이다. 이슈는 키가 행마다 다르다.
    """
    if len(keys) == 0:
        return []
    sims = normalize(np.asarray(matrix, dtype=np.float32)) @ normalize(np.asarray(query, dtype=np.float32)[None, :])[0]
    scores = thread_max_scores(sims, keys)
    return sorted(scores, key=lambda key: (-scores[key], key))


def best_rank(ranked: list[str], ids: list[str]) -> int | None:
    """ids 중 하나라도 맞는 가장 앞선 순위(1부터). 순위에 없으면 None."""
    wanted = set(ids)
    for position, key in enumerate(ranked, start=1):
        if key in wanted:
            return position
    return None


def evaluate(cases: list[dict], question_vecs, comm_vecs, conversation_ids: list[str],
             issue_vecs, issue_keys: list[str]) -> list[dict]:
    """케이스마다 질문으로 순위를 매겨 근거 id별 순위를 돌려준다. rank가 None이면 그래프에 없는 근거다."""
    rows = []
    for case, question in zip(cases, question_vecs):
        needed = {ev["type"] for ev in case["evidence"]}
        ranked = {}
        if "issue" in needed:
            ranked["issue"] = rank_by_cosine(question, issue_vecs, issue_keys)
        if "message" in needed:
            ranked["message"] = rank_by_cosine(question, comm_vecs, conversation_ids)
        for ev in case["evidence"]:
            rows.append({"case": case["id"], "type": ev["type"], "evidence": ev["raw"],
                         "rank": best_rank(ranked[ev["type"]], ev["ids"])})
    return rows


# --------------------------------------------------------------------------- 지표

def recall_at_k(ranks: list[int | None], k: int) -> float | None:
    """근거 id 단위 Recall@k — 못 찾았거나 그래프에 없는 근거도 분모에 남는다."""
    if not ranks:
        return None
    return sum(1 for r in ranks if r is not None and r <= k) / len(ranks)


def mean_reciprocal_rank(ranks_by_case) -> float | None:
    """케이스 단위 MRR — 케이스마다 가장 앞선 정답 순위의 역수(하나도 못 찾으면 0)를 평균한다."""
    reciprocal = []
    for ranks in ranks_by_case:
        found = [r for r in ranks if r is not None]
        reciprocal.append(1 / min(found) if found else 0.0)
    return mean(reciprocal) if reciprocal else None


def summarize(rows: list[dict], ks: list[int]) -> dict[str, dict]:
    summary = {}
    for id_type in GOLDEN_TYPES:
        selected = [r for r in rows if r["type"] == id_type]
        if not selected:
            continue
        by_case: dict[str, list[int | None]] = {}
        for r in selected:
            by_case.setdefault(r["case"], []).append(r["rank"])
        ranks = [r["rank"] for r in selected]
        summary[id_type] = {
            "n_evidence": len(selected),
            "n_cases": len(by_case),
            "n_unresolved": sum(1 for r in ranks if r is None),
            "recall": {str(k): recall_at_k(ranks, k) for k in ks},
            "mrr": mean_reciprocal_rank(by_case.values()),
        }
    return summary


def _fmt(value: float | None) -> str:
    return "-" if value is None else f"{value:.3f}"


def format_table(results: dict[str, dict], ks: list[int]) -> str:
    header = ["후보", "타입", "근거", "케이스", *[f"R@{k}" for k in ks], "MRR"]
    lines = ["| " + " | ".join(header) + " |", "|" + "---|" * len(header)]
    notes = set()
    for label, result in results.items():
        for id_type, s in result["summary"].items():
            cells = [label, id_type, str(s["n_evidence"]), str(s["n_cases"]),
                     *[_fmt(s["recall"][str(k)]) for k in ks], _fmt(s["mrr"])]
            lines.append("| " + " | ".join(cells) + " |")
            if s["n_unresolved"]:
                notes.add(f"⚠ {id_type} 근거 {s['n_unresolved']}개가 그래프에 없다 — 골든과 스냅샷이 맞는지 "
                          "validate_golden.py로 확인 (모든 후보에 같은 불이익이 간다)")
    lines.append("Recall@k는 근거 id 단위, MRR은 케이스 단위. 원 질문 + 정확한 코사인이라 실제 도구의 근사치다")
    return "\n".join(lines + sorted(notes))


# --------------------------------------------------------------------------- main

def parse_ks(value: str) -> list[int]:
    try:
        ks = sorted({int(part) for part in value.split(",") if part.strip()})
    except ValueError:
        ks = []
    if not ks or ks[0] < 1:
        raise argparse.ArgumentTypeError("1 이상의 정수를 쉼표로 구분해 준다 (예: 5,20)")
    return ks


def default_out_path(selected: list[dict], only_given: bool) -> str:
    """기본 결과 경로. --only를 줬으면 라벨을 넣어 후보별로 따로 돌려도 덮어쓰지 않게 한다."""
    name = "precheck-retrieval-" + "+".join(c["label"] for c in selected) if only_given else "precheck-retrieval"
    return os.path.join(EVAL_DIR, "results", f"{name}.json")


def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--only", help="쉼표로 구분한 라벨만 실행 (예: bge-m3). 생략하면 OpenAI 설정만 돈다")
    ap.add_argument("--project-id", help="생략 시 자동 감지")
    ap.add_argument("--golden-dir", default=os.path.join(EVAL_DIR, "golden"), help="골든 케이스 디렉터리 (기본 eval/golden)")
    ap.add_argument("--k", type=parse_ks, default=DEFAULT_KS, help="Recall@k의 k 목록, 쉼표 구분 (기본 5,20)")
    ap.add_argument("--out", help="결과 JSON 경로 (생략 시 results/precheck-retrieval.json, "
                                  "--only를 주면 results/precheck-retrieval-<라벨>.json)")
    args = ap.parse_args(argv)

    selected = select_configs(args.only)
    cases, skipped = load_cases(args.golden_dir)
    if not cases:
        sys.exit(f"issue·message 근거가 있는 골든 케이스가 없다: {args.golden_dir}")
    out_path = args.out or default_out_path(selected, only_given=args.only is not None)
    # OpenAI 설정이 하나라도 있을 때만 키를 요구한다 — 그래프 조회·앞 후보 실행 뒤에 터지지 않게 미리 확인
    if any(c["base_url"] is None for c in selected):
        ensure_openai_key()

    with open_driver() as driver, driver.session() as session:
        pid = args.project_id or detect_project_id(session)
        print(f"project_id = {pid}")
        comm_rows, issue_rows = fetch_rows(session, pid)
    corpus = build_corpus(comm_rows, issue_rows)
    n_threads = len(set(corpus["conversation_ids"]))
    if not corpus["conversation_ids"] and not corpus["issue_keys"]:
        sys.exit(f"project_id {pid}에 임베딩할 Communication·Issue가 없다 — --project-id를 확인")
    print(f"골든 케이스 {len(cases)}개 (대상 근거 없어 제외 {skipped}개) · 코퍼스: 스레드 {n_threads}개"
          f"(메시지 {len(corpus['bodies'])}개) · 이슈 {len(corpus['issue_keys'])}개 · 제외 {corpus['dropped']}")

    questions = [c["question"] for c in cases]
    texts = questions + corpus["bodies"] + corpus["issue_texts"]
    n_q, n_c = len(questions), len(corpus["bodies"])

    results: dict[str, dict] = {}
    for cfg in selected:
        print(f"\n=== {cfg['label']} ({cfg['model']}, dims={cfg['dimensions'] or 'default'}) ===")
        vectors = np.asarray(embed_all(make_client(cfg), texts, cfg["model"], cfg["dimensions"]), dtype=np.float32)
        rows = evaluate(cases, vectors[:n_q], vectors[n_q:n_q + n_c], corpus["conversation_ids"],
                        vectors[n_q + n_c:], corpus["issue_keys"])
        results[cfg["label"]] = {"model": cfg["model"], "dimensions": cfg["dimensions"],
                                 "summary": summarize(rows, args.k), "ranks": rows}

    print(f"\n{format_table(results, args.k)}")

    report = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "project_id": pid,
        "golden_dir": os.path.basename(os.path.abspath(args.golden_dir)),
        "ks": args.k,
        "cases": {"used": len(cases), "skipped_without_target_evidence": skipped},
        "corpus": {"threads": n_threads, "messages": len(corpus["bodies"]),
                   "issues": len(corpus["issue_keys"]), "dropped": corpus["dropped"]},
        "configs": results,
    }
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print(f"\n저장: {out_path}")


if __name__ == "__main__":
    main()
