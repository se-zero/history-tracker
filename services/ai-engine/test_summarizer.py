import json
import os
import sys
from graph.summarizer import summarize_diff
from graph.path_filter import should_skip

if __name__ == "__main__":
    if not os.environ.get("OPENAI_API_KEY"):
        print("실행 방법: OPENAI_API_KEY=sk-... python test_summarizer.py")
        sys.exit(1)

    input_path = sys.argv[1] if len(sys.argv) > 1 else "../../normalized_github.json"
    output_path = input_path.replace(".json", "_summarized.json")

    with open(input_path) as f:
        events = json.load(f)

    results = []
    for event in events:
        if event["nodeType"] != "ChangeSet":
            results.append(event)
            continue

        message = event.get("properties", {}).get("message", "")
        files = event.get("properties", {}).get("files", [])
        summarized_files = []
        for file in files:
            path = file.get("path", "")
            if should_skip(path):
                summarized_files.append({"path": path, "diffSummary": ""})
                continue
            diff = file.get("diff", "")
            additions = file.get("additions", 0) or 0
            deletions = file.get("deletions", 0) or 0
            print(f"  처리 중: {path}")
            try:
                summary = summarize_diff(path, diff, additions, deletions, message)
                print(f"  [{path}] → {summary[:80]}..." if len(summary) > 80 else f"  [{path}] → {summary}")
            except Exception as e:
                print(f"  [ERROR] {path}: {e}")
                summary = ""
            summarized_files.append({"path": path, "diffSummary": summary})

        event["properties"]["files"] = summarized_files
        results.append(event)

    with open(output_path, "w") as f:
        json.dump(results, f, indent=2, ensure_ascii=False)

    print(f"\n저장 완료: {output_path}")
