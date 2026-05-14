import json
import os
import sys

from graph.slack_filter import should_skip_slack

if __name__ == "__main__":
    args = sys.argv[1:]
    rule_only = "--rule-only" in args

    repo_arg = next((a.split("=", 1)[1] for a in args if a.startswith("--repo=")), None)
    args = [a for a in args if not a.startswith("--")]

    if not rule_only and not os.environ.get("OPENAI_API_KEY"):
        print("실행 방법: OPENAI_API_KEY=sk-... python3 test_slack_filter.py [--rule-only] [--repo=owner/repo]")
        sys.exit(1)

    # 프로젝트 컨텍스트 로드 (--repo 또는 GITHUB_REPO 환경변수)
    project_context = ""
    if not rule_only:
        repo = repo_arg or os.environ.get("GITHUB_REPO", "")
        if repo and "/" in repo:
            from graph.project_context import get_project_summary
            owner, repo_name = repo.split("/", 1)
            print(f"\n[프로젝트 컨텍스트 로드 중] {owner}/{repo_name}")
            project_context = get_project_summary(owner, repo_name) or ""
            print(f"[컨텍스트]\n{project_context}\n")
        else:
            print("[경고] --repo=owner/repo 또는 GITHUB_REPO 환경변수가 없어 기본 컨텍스트 사용")

    input_path = args[0] if args else "../../normalized_slack.json"
    # 파일이 없으면 프로젝트 루트 기준으로 재시도
    if not os.path.exists(input_path):
        script_dir = os.path.dirname(os.path.abspath(__file__))
        input_path = os.path.join(script_dir, "..", "..", os.path.basename(input_path))
    output_path = input_path.replace(".json", "_filtered.json")

    with open(input_path) as f:
        events = json.load(f)

    communications = [e for e in events if e["nodeType"] == "Communication"]
    others = [e for e in events if e["nodeType"] != "Communication"]

    # 1차: 룰 기반 필터
    after_rule = []
    rule_skipped = 0
    for event in communications:
        body = event.get("properties", {}).get("body", "")
        if should_skip_slack(body):
            rule_skipped += 1
            print(f"  [룰 제거] {body[:60]}")
        else:
            after_rule.append(event)

    print(f"\n룰 기반 필터: {len(communications)}개 → {len(after_rule)}개 ({rule_skipped}개 제거)")

    if rule_only:
        results = others + after_rule
        with open(output_path, "w") as f:
            json.dump(results, f, indent=2, ensure_ascii=False)
        print(f"\n저장 완료 (룰 기반만): {output_path}")
        sys.exit(0)

    # 2차: LLM 필터 (스레드 단위로 묶어서 처리)
    from graph.slack_llm_filter import filter_messages
    from itertools import groupby

    threads = {}
    for event in after_rule:
        cid = event.get("properties", {}).get("conversation_id") or "no_thread"
        threads.setdefault(cid, []).append(event)

    llm_kept = []
    llm_skipped = 0
    for cid, thread_events in threads.items():
        bodies = [e.get("properties", {}).get("body", "") for e in thread_events]
        print(f"\n  [LLM 판단 중] 스레드 {cid} ({len(bodies)}개 메시지)")
        try:
            keep_flags = filter_messages(bodies, project_context=project_context)
            for event, keep, body in zip(thread_events, keep_flags, bodies):
                if keep:
                    llm_kept.append(event)
                    print(f"    [보존] {body[:60]}")
                else:
                    llm_skipped += 1
                    print(f"    [LLM 제거] {body[:60]}")
        except Exception as e:
            print(f"    [ERROR] {e}")
            llm_kept.extend(thread_events)

    print(f"\nLLM 필터: {len(after_rule)}개 → {len(llm_kept)}개 ({llm_skipped}개 제거)")
    print(f"최종: {len(communications)}개 → {len(llm_kept)}개")

    results = others + llm_kept
    with open(output_path, "w") as f:
        json.dump(results, f, indent=2, ensure_ascii=False)

    print(f"\n저장 완료: {output_path}")
