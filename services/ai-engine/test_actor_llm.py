import asyncio
import json
import os
import sys

from graph.actor_llm import judge_same_person


async def main():
    if not os.environ.get("OPENAI_API_KEY"):
        print("실행 방법: OPENAI_API_KEY=sk-... python test_actor_llm.py [input.json]")
        sys.exit(1)

    input_path = sys.argv[1] if len(sys.argv) > 1 else "test_actor_cases.json"
    output_path = input_path.replace(".json", "_results.json")

    with open(input_path, encoding="utf-8") as f:
        cases = json.load(f)

    results = []
    for i, c in enumerate(cases):
        new_actor = c["new_actor"]
        source = c["source"]
        print(f"\n[케이스 {i+1}] {source} - {new_actor.get('name') or new_actor.get('id')}")
        try:
            result = await judge_same_person(
                c["existing_actor"],
                c.get("activities", []),
                new_actor,
                source,
                c["event"],
            )
            label = "✓ 동일인" if result["same_person"] else "✗ 다른 사람"
            print(f"  → {label} (confidence: {result['confidence']})")
            print(f"  → {result['reason']}")
        except Exception as e:
            print(f"  [ERROR] {e}")
            result = {"error": str(e)}
        results.append({"input": new_actor, "source": source, "result": result})

    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2, ensure_ascii=False)

    print(f"\n저장 완료: {output_path}")


asyncio.run(main())
