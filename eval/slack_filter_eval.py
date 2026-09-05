#!/usr/bin/env python
"""Slack LLM 노이즈 필터 측정 하네스 — 프롬프트 변경 전후를 같은 코퍼스로 비교한다.

프로덕션과 동일한 묶음 단위(graph.slack_batch_filter.group_for_filter)로 LLM 필터를 호출해
판정을 JSON으로 저장하고(run), 두 결과의 판정이 갈린 메시지를 사람 라벨링용 yaml로 뽑아
(compare), 라벨링 결과로 정확도·정밀도·재현율을 채점한다(score).

실행 (ai-engine venv 사용):
    services/ai-engine/.venv/Scripts/python.exe eval/slack_filter_eval.py run --tag baseline
    services/ai-engine/.venv/Scripts/python.exe eval/slack_filter_eval.py compare \\
        --base eval/results/slack-filter-baseline.json --new eval/results/slack-filter-new.json \\
        --out eval/slack_filter_labels/flips-2026-09-05.yaml --sample 20
    services/ai-engine/.venv/Scripts/python.exe eval/slack_filter_eval.py score \\
        --labels eval/slack_filter_labels/flips-2026-09-05.yaml \\
        --results eval/results/slack-filter-baseline.json eval/results/slack-filter-new.json
"""

import argparse
import asyncio
import hashlib
import json
import os
import random
import sys
from datetime import datetime, timezone

import yaml

EVAL_DIR = os.path.dirname(os.path.abspath(__file__))
AI_ENGINE_DIR = os.path.abspath(os.path.join(EVAL_DIR, "..", "services", "ai-engine"))
sys.path.insert(0, EVAL_DIR)
sys.path.insert(0, AI_ENGINE_DIR)

# Windows 콘솔(cp949)에서 이모지·화살표 등 출력 크래시 방지 (compare.py와 동일한 관행)
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

from graph.project_profile import (  # noqa: E402
    COMMIT_LIMIT,
    ISSUE_LIMIT,
    PR_LIMIT,
    mixed_sample,
    repo_names_from_urls,
    summarize_material,
    top_dirs_from_paths,
)
from graph.slack_batch_filter import Batch, group_for_filter  # noqa: E402
from graph.slack_filter import should_skip_slack  # noqa: E402
from graph.slack_llm_filter import build_prompt, filter_messages  # noqa: E402


def _str_representer(dumper, data):
    """여러 줄 문자열은 리터럴 블록(|)으로 덤프 — sample_edges.py와 동일한 스타일."""
    style = "|" if "\n" in data else None
    return dumper.represent_scalar("tag:yaml.org,2002:str", data, style=style)


yaml.add_representer(str, _str_representer, Dumper=yaml.SafeDumper)


# ─── 순수 함수 — 판정 집계·비교 (오프라인 테스트 대상) ─────────────────────────


def majority_verdict(runs: list[dict]) -> dict:
    """여러 런의 {url: bool} 판정을 url별 다수결로 합친다.

    런이 짝수라 동률이면 보존(True)한다 — 과삭제보다 과보존이 안전한 기본값이다.
    """
    urls: set[str] = set()
    for run in runs:
        urls.update(run.keys())

    out: dict[str, bool] = {}
    for url in urls:
        votes = [run[url] for run in runs if url in run]
        trues = sum(1 for v in votes if v)
        falses = len(votes) - trues
        out[url] = trues >= falses
    return out


def unstable_urls(runs: list[dict]) -> list[str]:
    """런 간 판정이 갈린 url (단일 런이면 갈릴 수 없으므로 빈 리스트)."""
    if len(runs) < 2:
        return []
    urls: set[str] = set()
    for run in runs:
        urls.update(run.keys())
    return sorted(
        url for url in urls
        if len({run[url] for run in runs if url in run}) > 1
    )


def find_flips(base_majority: dict, new_majority: dict) -> list[str]:
    """base/new 양쪽 다 LLM 입력이었던(majority에 존재하는) url 중 판정이 달라진 것."""
    common = set(base_majority) & set(new_majority)
    return sorted(url for url in common if base_majority[url] != new_majority[url])


def confusion(labels: dict, verdicts: dict) -> dict:
    """혼동행렬(보존=양성). verdicts에 판정이 없는 라벨 url은 skipped로 센다."""
    tp = fp = fn = tn = skipped = 0
    for url, label in labels.items():
        if url not in verdicts:
            skipped += 1
            continue
        predicted_keep = verdicts[url]
        actual_keep = label == "keep"
        if actual_keep and predicted_keep:
            tp += 1
        elif actual_keep and not predicted_keep:
            fn += 1
        elif not actual_keep and predicted_keep:
            fp += 1
        else:
            tn += 1
    return {"tp": tp, "fp": fp, "fn": fn, "tn": tn, "skipped": skipped}


def metrics(cm: dict) -> dict:
    """혼동행렬 → Accuracy·Precision·Recall·Specificity. 분모 0이면 None."""
    tp, fp, fn, tn = cm["tp"], cm["fp"], cm["fn"], cm["tn"]
    total = tp + fp + fn + tn
    return {
        "accuracy": (tp + tn) / total if total else None,
        "precision": tp / (tp + fp) if (tp + fp) else None,
        "recall": tp / (tp + fn) if (tp + fn) else None,
        "specificity": tn / (tn + fp) if (tn + fp) else None,
    }


def flip_analysis(labels: dict, base: dict, new: dict) -> dict:
    """base→new 판정이 바뀐 라벨 항목을 정답 일치 여부로 개선/악화 분류.

    base와 new는 뒤집힌(base != new) 항목뿐이므로 둘 중 정확히 하나만 라벨과 일치한다 —
    new가 맞으면 개선, base가 맞으면(new가 틀리면) 악화.
    """
    improved, regressed = [], []
    for url, label in labels.items():
        if url not in base or url not in new or base[url] == new[url]:
            continue
        label_keep = label == "keep"
        if new[url] == label_keep:
            improved.append(url)
        else:
            regressed.append(url)
    return {"improved": sorted(improved), "regressed": sorted(regressed)}


def thread_context(messages: list[dict], url: str, k: int = 2) -> list[str]:
    """같은 conversation_id의 앞뒤 최대 k건씩 시간순으로 뽑는다. "[+0]"이 대상 메시지."""
    target = next((m for m in messages if m["url"] == url), None)
    if target is None:
        return []

    thread = sorted(
        (m for m in messages if m["conversation_id"] == target["conversation_id"]),
        key=lambda m: m["occurred_at"] or 0,
    )
    idx = next(i for i, m in enumerate(thread) if m["url"] == url)
    lo, hi = max(0, idx - k), min(len(thread), idx + k + 1)
    return [f"[{i - idx:+d}] {thread[i]['body']}" for i in range(lo, hi)]


# ─── 스냅샷 로딩 — run/compare 공용 ─────────────────────────────────────────────


def _load_slack_messages(snapshot_path: str) -> list[dict]:
    """스냅샷 jsonl에서 Slack Communication 레코드만 추출."""
    records = []
    with open(snapshot_path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            record = json.loads(line)
            if record.get("source") == "SLACK" and record.get("nodeType") == "Communication":
                records.append(record)
    return records


def _to_group_input(record: dict) -> dict:
    """group_for_filter가 받는 dict로 변환 (fetch_unfiltered_communications 반환 모양과 동일)."""
    props = record["properties"]
    occurred_raw = record.get("occurredAt")
    occurred_at = datetime.fromisoformat(occurred_raw.replace("Z", "+00:00")) if occurred_raw else None
    return {
        "project_id": record.get("projectId"),
        "url": props["url"],
        "body": props.get("body") or "",
        "channel": props.get("channel"),
        "conversation_id": props["conversation_id"],
        "occurred_at": occurred_at,
    }


def _prompt_sha256(context_text: str | None) -> str:
    """현재 build_prompt(is_thread) 시그니처 기준 해시. ctx가 있으면 두 번째 인자로 넘긴다
    (build_prompt(is_thread, project_context)로 바뀔 예정 — 그때도 이 호출부는 그대로 유효)."""
    if context_text is None:
        combined = build_prompt(True) + build_prompt(False)
    else:
        combined = build_prompt(True, context_text) + build_prompt(False, context_text)
    return hashlib.sha256(combined.encode("utf-8")).hexdigest()


# ─── run ────────────────────────────────────────────────────────────────────


async def _run_llm_once(batches: list[Batch], concurrency: int, context_text: str | None) -> tuple[dict, int]:
    """배치 전체를 동시성 제한 하에 1회 실행. 배치 실패는 프로덕션과 같이 전체 보존한다."""
    sem = asyncio.Semaphore(concurrency)
    verdicts: dict[str, bool] = {}
    failed = 0

    async def _run_batch(_project_id: str, is_thread: bool, msgs: list[dict]) -> None:
        nonlocal failed
        bodies = [m["body"] for m in msgs]
        kwargs = {} if context_text is None else {"project_context": context_text}
        async with sem:
            try:
                keep_flags = await filter_messages(bodies, is_thread, **kwargs)
            except Exception:
                keep_flags = [True] * len(msgs)
                failed += 1
        for msg, keep in zip(msgs, keep_flags):
            verdicts[msg["url"]] = keep

    await asyncio.gather(*(_run_batch(p, t, m) for p, t, m in batches))
    return verdicts, failed


def _ensure_openai_api_key() -> None:
    if os.environ.get("OPENAI_API_KEY"):
        return
    # infra/docker/.env에서 OPENAI_API_KEY만 로드 (grader.py와 동일한 방식) —
    # load_dotenv로 전체를 로드하면 NEO4J_* 등 컨테이너용 값이 로컬 접속 설정을 덮는다.
    from dotenv import dotenv_values
    env_key = dotenv_values(os.path.join(EVAL_DIR, "..", "infra", "docker", ".env")).get("OPENAI_API_KEY")
    if env_key:
        os.environ["OPENAI_API_KEY"] = env_key
    if not os.environ.get("OPENAI_API_KEY"):
        sys.exit("OPENAI_API_KEY가 필요합니다 — infra/docker/.env 또는 환경변수로 설정")


def cmd_run(args: argparse.Namespace) -> None:
    records = _load_slack_messages(args.snapshot)
    total = len(records)

    rule_removed: list[str] = []
    survivors: list[dict] = []
    for record in records:
        props = record["properties"]
        if should_skip_slack(props.get("body") or ""):
            rule_removed.append(props["url"])
        else:
            survivors.append(_to_group_input(record))

    batches = group_for_filter(survivors)
    thread_batches = sum(1 for _, is_thread, _ in batches if is_thread)
    standalone_batches = len(batches) - thread_batches

    context_text = None
    context_sha256 = None
    if args.context_file:
        with open(args.context_file, encoding="utf-8") as f:
            context_text = f.read()
        context_sha256 = hashlib.sha256(context_text.encode("utf-8")).hexdigest()

    _ensure_openai_api_key()

    runs: list[dict] = []
    failed_batches = 0
    for i in range(args.runs):
        verdicts, failed_batches = asyncio.run(_run_llm_once(batches, args.concurrency, context_text))
        runs.append(verdicts)
        print(f"  run {i + 1}/{args.runs} 완료 (실패 배치 {failed_batches}건)")

    majority = majority_verdict(runs)
    unstable = unstable_urls(runs) if len(runs) > 1 else []
    last_run = runs[-1] if runs else {}
    kept = sum(1 for v in last_run.values() if v)
    deleted = sum(1 for v in last_run.values() if not v)

    result = {
        "tag": args.tag,
        "snapshot": os.path.basename(args.snapshot),
        "created_at": datetime.now(timezone.utc).isoformat(),
        "context_file": os.path.basename(args.context_file) if args.context_file else None,
        "context_sha256": context_sha256,
        "prompt_sha256": _prompt_sha256(context_text),
        "counts": {
            "total": total,
            "rule_removed": len(rule_removed),
            "llm_input": len(survivors),
            "batches": len(batches),
            "thread_batches": thread_batches,
            "standalone_batches": standalone_batches,
            "kept": kept,
            "deleted": deleted,
            "failed_batches": failed_batches,
        },
        "rule_removed": rule_removed,
        "runs": runs,
        "majority": majority,
        "unstable_urls": unstable,
    }

    os.makedirs(args.out_dir, exist_ok=True)
    out_path = os.path.join(args.out_dir, f"slack-filter-{args.tag}.json")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)

    print(f"total={total} rule_removed={len(rule_removed)} llm_input={len(survivors)} "
          f"batches={len(batches)}(thread={thread_batches} standalone={standalone_batches})")
    print(f"kept={kept} deleted={deleted} unstable={len(unstable)}")
    print(f"저장: {out_path}")


# ─── compare ────────────────────────────────────────────────────────────────


_COMPARE_HEADER = """\
# Slack LLM 필터 비교 라벨셋 — base/new 판정이 갈린(flip) 메시지 검수용
# label 칸만 채우세요: keep | remove | unsure
#   - keep  : 그래프에 남길 가치가 있음 (보존이 맞음)
#   - remove: 노이즈라 제거가 맞음
#   - unsure: 원문만으로 판단 불가
# unstable: true인 항목은 런 간 판정이 갈린 노이즈이므로 score 채점에서 제외된다.
"""


def cmd_compare(args: argparse.Namespace) -> None:
    with open(args.base, encoding="utf-8") as f:
        base = json.load(f)
    with open(args.new, encoding="utf-8") as f:
        new = json.load(f)

    records = _load_slack_messages(args.snapshot)
    by_url = {r["properties"]["url"]: r for r in records}
    messages = [_to_group_input(r) for r in records]

    base_majority = base.get("majority", {})
    new_majority = new.get("majority", {})
    base_unstable = set(base.get("unstable_urls", []))

    flips = find_flips(base_majority, new_majority)

    def _item(url: str, kind: str) -> dict:
        record = by_url.get(url)
        props = record["properties"] if record else {}
        return {
            "url": url,
            "channel": props.get("channel"),
            "body": props.get("body") or "",
            "thread_context": thread_context(messages, url),
            "verdicts": {
                "base": "keep" if base_majority[url] else "remove",
                "new": "keep" if new_majority[url] else "remove",
            },
            "unstable": url in base_unstable,
            "kind": kind,
            "label": "",
            "note": "",
        }

    items = [_item(url, "flip") for url in flips]

    sample_urls: list[str] = []
    if args.sample:
        candidates = sorted((set(base_majority) & set(new_majority)) - set(flips))
        sample_urls = random.Random(args.seed).sample(candidates, min(args.sample, len(candidates)))
    items += [_item(url, "sample") for url in sample_urls]

    doc = {
        "base": os.path.basename(args.base),
        "new": os.path.basename(args.new),
        "snapshot": os.path.basename(args.snapshot),
        "items": items,
    }

    out_dir = os.path.dirname(os.path.abspath(args.out))
    os.makedirs(out_dir, exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        f.write(_COMPARE_HEADER)
        yaml.safe_dump(doc, f, allow_unicode=True, sort_keys=False)

    n_unstable_flips = sum(1 for url in flips if url in base_unstable)
    print(f"뒤집힘 {len(flips)}건 (그중 unstable {n_unstable_flips}건) + 샘플 {len(sample_urls)}건 → {args.out}")


# ─── score ──────────────────────────────────────────────────────────────────


def _load_json(path: str) -> dict:
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def cmd_score(args: argparse.Namespace) -> None:
    with open(args.labels, encoding="utf-8") as f:
        labels_doc = yaml.safe_load(f)

    labels: dict[str, str] = {}
    for item in labels_doc.get("items", []):
        label = (item.get("label") or "").strip().lower()
        if label not in ("keep", "remove") or item.get("unstable"):
            continue
        labels[item["url"]] = label

    results = [(os.path.basename(path), _load_json(path)) for path in args.results]

    for name, result in results:
        cm = confusion(labels, result.get("majority", {}))
        m = metrics(cm)
        used = len(labels) - cm["skipped"]
        suffix = f", {cm['skipped']}건 판정 없음" if cm["skipped"] else ""
        print(f"\n### {name} (라벨 {used}/{len(labels)}건 사용{suffix})")
        print("| metric | value |")
        print("|---|---|")
        for key, kr in [("accuracy", "Accuracy"), ("precision", "Precision"),
                        ("recall", "Recall"), ("specificity", "Specificity")]:
            v = m[key]
            print(f"| {kr} | {'-' if v is None else f'{v * 100:.1f}%'} |")

    if len(results) == 2:
        (base_name, base_result), (new_name, new_result) = results
        analysis = flip_analysis(labels, base_result.get("majority", {}), new_result.get("majority", {}))
        print(f"\n### 뒤집힘 분석 ({base_name} → {new_name})")
        print(f"개선 {len(analysis['improved'])}건: {', '.join(analysis['improved']) or '-'}")
        print(f"악화 {len(analysis['regressed'])}건: {', '.join(analysis['regressed']) or '-'}")


# ─── profile ────────────────────────────────────────────────────────────────


def _truncate_first_line(text: str, limit: int = 120) -> str:
    """graph.project_profile._truncate_first_line과 동일한 규칙(첫 줄만·120자 절단).
    측정 하네스는 Neo4j 없이 스냅샷에서 material을 만들므로 이 정도 규모의 헬퍼는 복제한다."""
    lines = (text or "").splitlines()
    return lines[0][:limit] if lines else ""


def material_from_snapshot(records: list[dict]) -> dict:
    """스냅샷 jsonl 레코드에서 graph.project_profile.fetch_profile_material과 같은 모양의
    material을 만든다 — Neo4j 없이 프로덕션과 동일한 summarize_material을 호출하기 위함.

    PR·Issue·ChangeSet은 project_profile의 규칙과 같게 최신 절반 + 가장 오래된 절반을
    mixed_sample로 섞는다(한도는 project_profile의 상수를 그대로 쓴다). top_dirs·documents는
    한도가 작아 최신순 그대로 자른다.
    """

    def _sorted_by_recency(node_type: str, reverse: bool = True) -> list[dict]:
        rows = [r for r in records if r.get("nodeType") == node_type]
        return sorted(rows, key=lambda r: r.get("occurredAt") or "", reverse=reverse)

    def _mixed(node_type: str, limit: int, key) -> list[dict]:
        half = limit // 2
        latest = _sorted_by_recency(node_type, reverse=True)[:half]
        oldest = _sorted_by_recency(node_type, reverse=False)[:half]
        return mixed_sample(latest, oldest, key=key)

    pr_rows = _mixed("PullRequest", PR_LIMIT, key=lambda r: r["properties"].get("url"))
    issue_rows = _mixed(
        "Issue",
        ISSUE_LIMIT,
        key=lambda r: r["properties"].get("jira_key") or r["properties"].get("title"),
    )
    changeset_rows = _mixed("ChangeSet", COMMIT_LIMIT, key=lambda r: r["properties"].get("hash"))
    document_rows = _sorted_by_recency("Document")[:10]

    pr_urls = [r["properties"].get("url") for r in pr_rows]
    issue_titles = [
        f"[{r['properties']['issue_type']}] {r['properties'].get('title')}"
        if r["properties"].get("issue_type")
        else r["properties"].get("title")
        for r in issue_rows
    ]
    # top_dirs는 프로덕션이 File 노드 전체를 보므로, 한도 제한 없이 모든 ChangeSet의 파일 경로를 쓴다
    changeset_paths = [
        f["path"]
        for r in _sorted_by_recency("ChangeSet")
        for f in (r["properties"].get("files") or [])
        if f.get("path")
    ]

    return {
        "repo_names": repo_names_from_urls(pr_urls),
        "pr_titles": [t for t in (r["properties"].get("title") for r in pr_rows) if t],
        "issue_titles": issue_titles,
        "commit_messages": [m for m in (_truncate_first_line(r["properties"].get("message")) for r in changeset_rows) if m],
        "top_dirs": top_dirs_from_paths(changeset_paths),
        "document_titles": [t for t in (r["properties"].get("title") for r in document_rows) if t],
    }


def cmd_profile(args: argparse.Namespace) -> None:
    with open(args.snapshot, encoding="utf-8") as f:
        records = [json.loads(line) for line in f if line.strip()]

    material = material_from_snapshot(records)
    for key, items in material.items():
        print(f"{key}: {len(items)}건")

    _ensure_openai_api_key()
    profile = asyncio.run(summarize_material(material))

    print("\n[프로필]")
    print(profile or "(빈 문자열)")

    with open(args.out, "w", encoding="utf-8") as f:
        f.write(profile)
    print(f"저장: {args.out}")


# ─── main ───────────────────────────────────────────────────────────────────


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    sub = parser.add_subparsers(dest="command", required=True)

    p_run = sub.add_parser("run", help="스냅샷에 룰+LLM 필터를 적용해 판정 결과를 저장한다")
    p_run.add_argument("--snapshot", default=os.path.join(EVAL_DIR, "snapshots", "events-2026-07-05.jsonl"))
    p_run.add_argument("--runs", type=int, default=1)
    p_run.add_argument("--tag", required=True)
    p_run.add_argument("--context-file")
    p_run.add_argument("--concurrency", type=int, default=8)
    p_run.add_argument("--out-dir", default=os.path.join(EVAL_DIR, "results"))
    p_run.set_defaults(func=cmd_run)

    p_compare = sub.add_parser("compare", help="두 run 결과의 판정이 갈린 메시지를 라벨링용 yaml로 뽑는다")
    p_compare.add_argument("--base", required=True)
    p_compare.add_argument("--new", required=True)
    p_compare.add_argument("--snapshot", default=os.path.join(EVAL_DIR, "snapshots", "events-2026-07-05.jsonl"))
    p_compare.add_argument("--out", required=True)
    p_compare.add_argument("--sample", type=int, default=0)
    p_compare.add_argument("--seed", type=int, default=42)
    p_compare.set_defaults(func=cmd_compare)

    p_score = sub.add_parser("score", help="라벨셋으로 run 결과의 정확도·정밀도·재현율을 채점한다")
    p_score.add_argument("--labels", required=True)
    p_score.add_argument("--results", nargs="+", required=True)
    p_score.set_defaults(func=cmd_score)

    p_profile = sub.add_parser("profile", help="스냅샷 material로 프로젝트 프로필을 만든다 (Neo4j 불필요)")
    p_profile.add_argument("--snapshot", default=os.path.join(EVAL_DIR, "snapshots", "events-2026-07-05.jsonl"))
    p_profile.add_argument("--out", required=True)
    p_profile.set_defaults(func=cmd_profile)

    return parser


def main() -> None:
    args = build_parser().parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
