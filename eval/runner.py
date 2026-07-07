#!/usr/bin/env python
"""e2e eval 러너 (docs/measurement-plan.md).

골든셋 질문을 ai-engine /query에 직접(backend 미경유) 던지고 구조화 응답을 수집한다.
LLM은 비결정적이므로 케이스당 --runs 회(기본 3) 실행한다. 결과는
eval/results/<UTC타임스탬프>/ 아래에 저장하고, 채점은 grader.py가 별도로 수행한다.

실행 (ai-engine venv 사용):
    services/ai-engine/.venv/Scripts/python.exe eval/runner.py --project-id <uuid>
    옵션: --base-url(기본 http://localhost:8000) --runs(기본 3)
          --cases case-03,case-21  --graph-snapshot/--events-snapshot(기록용 라벨)
"""

import argparse
import hashlib
import json
import os
import subprocess
import sys
import time
from datetime import datetime, timezone
from glob import glob

import httpx
import yaml

from graph_lookup import detect_project_id, open_driver

# Windows 콘솔(cp949)에서 em-dash 등 출력 크래시 방지
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

EVAL_DIR = os.path.dirname(os.path.abspath(__file__))
GOLDEN_DIR = os.path.join(EVAL_DIR, "golden")
RESULTS_DIR = os.path.join(EVAL_DIR, "results")


def golden_version(case_files: list[str]) -> str:
    """골든셋 버전 = 케이스 파일 내용의 sha256 (파일 추가·수정 시 버전이 바뀐다)."""
    h = hashlib.sha256()
    for path in case_files:
        with open(path, "rb") as f:
            h.update(os.path.basename(path).encode())
            h.update(f.read())
    return h.hexdigest()[:12]


def git_commit_hash() -> str:
    try:
        return subprocess.run(
            ["git", "rev-parse", "HEAD"], capture_output=True, text=True, cwd=EVAL_DIR, check=True
        ).stdout.strip()
    except Exception:
        return "unknown"


def graph_structure_metrics(pid: str) -> dict:
    """재구축 빌드 편차 가시화용 구조 지표 — 노드 라벨/관계 타입별 카운트."""
    with open_driver() as driver:
        with driver.session() as session:
            nodes = session.run(
                "MATCH (n {project_id:$pid}) RETURN labels(n)[0] AS label, count(*) AS c ORDER BY label",
                pid=pid,
            ).data()
            rels = session.run(
                "MATCH (a {project_id:$pid})-[r]->() RETURN type(r) AS type, count(*) AS c ORDER BY type",
                pid=pid,
            ).data()
    return {
        "nodes": {r["label"]: r["c"] for r in nodes},
        "relationships": {r["type"]: r["c"] for r in rels},
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-id", help="질의 스코프 project_id (생략 시 그래프에서 자동 감지)")
    parser.add_argument("--base-url", default="http://localhost:8000")
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--cases", help="쉼표 구분 케이스 필터 (예: case-03,case-21)")
    parser.add_argument("--graph-snapshot", default="", help="기록용 그래프 스냅샷 라벨 (예: graph-2026-07-05.dump)")
    parser.add_argument("--events-snapshot", default="", help="기록용 원천 스냅샷 라벨 (예: events-2026-07-05.jsonl)")
    parser.add_argument("--timeout", type=float, default=180.0)
    args = parser.parse_args()

    case_files = sorted(glob(os.path.join(GOLDEN_DIR, "case-*.yaml")))
    if not case_files:
        sys.exit(f"골든셋 파일이 없습니다: {GOLDEN_DIR}")
    version = golden_version(case_files)  # 필터와 무관하게 전체 셋 기준

    if args.cases:
        wanted = {c.strip() for c in args.cases.split(",")}
        case_files = [p for p in case_files if os.path.splitext(os.path.basename(p))[0] in wanted]
        if not case_files:
            sys.exit(f"필터와 일치하는 케이스 없음: {args.cases}")

    if args.project_id:
        pid = args.project_id
    else:
        with open_driver() as driver:
            with driver.session() as session:
                pid = detect_project_id(session)

    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out_dir = os.path.join(RESULTS_DIR, run_id)
    responses_dir = os.path.join(out_dir, "responses")
    os.makedirs(responses_dir)

    meta = {
        "run_id": run_id,
        "date": datetime.now(timezone.utc).isoformat(),
        "git_commit": git_commit_hash(),
        "golden_version": version,
        "graph_snapshot": args.graph_snapshot,
        "events_snapshot": args.events_snapshot,
        "project_id": pid,
        "base_url": args.base_url,
        "runs_per_case": args.runs,
        "cases": [os.path.splitext(os.path.basename(p))[0] for p in case_files],
        "graph_structure": graph_structure_metrics(pid),
    }
    with open(os.path.join(out_dir, "meta.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)
    print(f"결과 디렉터리: {out_dir}")
    print(f"golden_version={version}  git={meta['git_commit'][:8]}  케이스 {len(case_files)}개 × {args.runs}회\n")

    failures = 0
    with httpx.Client(timeout=args.timeout) as client:
        for path in case_files:
            with open(path, encoding="utf-8") as f:
                case = yaml.safe_load(f)
            case_id = case["id"]

            for run_no in range(1, args.runs + 1):
                request_body = {
                    "question": case["question"],
                    "project_id": pid,
                    "focus_evidence": case.get("focus_evidence") or [],
                    "include_debug": True,
                }
                record = {"case_id": case_id, "run": run_no, "request": request_body}
                t0 = time.monotonic()
                try:
                    resp = client.post(f"{args.base_url}/query", json=request_body)
                    resp.raise_for_status()
                    record["response"] = resp.json()
                except Exception as exc:  # 실패도 기록으로 남기고 계속 진행
                    record["error"] = repr(exc)
                    failures += 1
                record["latency_s"] = round(time.monotonic() - t0, 2)
                record["at"] = datetime.now(timezone.utc).isoformat()

                out_path = os.path.join(responses_dir, f"{case_id}.run-{run_no}.json")
                with open(out_path, "w", encoding="utf-8") as f:
                    json.dump(record, f, ensure_ascii=False, indent=2)

                if "error" in record:
                    print(f"{case_id} run-{run_no}: ERROR {record['error']} ({record['latency_s']}s)")
                else:
                    structured = record["response"].get("structured")
                    debug = record["response"].get("debug") or {}
                    n_evidence = len(structured["evidence"]) if structured else "-"
                    n_tools = len(debug.get("tool_calls") or [])
                    tokens = (debug.get("usage") or {}).get("total_tokens", "-")
                    print(
                        f"{case_id} run-{run_no}: evidence={n_evidence} tools={n_tools} "
                        f"tokens={tokens} ({record['latency_s']}s)"
                        + ("  [structured=null]" if structured is None else "")
                    )

    print(f"\n완료 — 실패 {failures}건. 채점: grader.py {out_dir}")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
