#!/usr/bin/env python
"""e2e eval 채점기 (docs/measurement-plan.md).

runner.py가 저장한 결과 디렉터리를 입력으로 채점한다 (러너와 분리 — 재채점 가능).

- 기계 채점 (비용 0): evidence recall / precision / 오염 검사 / id 포맷 위반.
  id 비교는 정규화 후 수행한다 — PR '#' 유무 무시, 커밋 해시 prefix 매치,
  message는 골든셋 aliases 매치. 포맷 위반은 recall에서 버리지 않고 별도 집계한다.
- LLM judge (gpt-4.1-2025-04-14, temperature 0, Structured Outputs):
  환각률·사실 정답률·금지 사실·rule_checks. 판정 근거로 그래프에서 조회한
  evidence 원문 전체를 제공하며, 조회 원문은 grading/에 저장한다(재채점 재현성).

실행 (ai-engine venv 사용, judge는 OPENAI_API_KEY 환경변수 필요):
    services/ai-engine/.venv/Scripts/python.exe eval/grader.py eval/results/<run-id>
    옵션: --skip-judge (기계 채점만), --project-id (생략 시 자동 감지)
"""

import argparse
import hashlib
import json
import os
import sys
from glob import glob
from statistics import mean

import yaml

from graph_lookup import FORMAT_RULES, ID_RE, detect_project_id, fetch_evidence_body, open_driver

# Windows 콘솔(cp949)에서 em-dash 등 출력 크래시 방지
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

EVAL_DIR = os.path.dirname(os.path.abspath(__file__))
GOLDEN_DIR = os.path.join(EVAL_DIR, "golden")
JUDGE_PROMPT_PATH = os.path.join(EVAL_DIR, "judge_prompt.md")

# 채점 모델은 날짜 스냅샷 버전으로 동결한다 (alias 금지 — measurement-plan 측정 원칙 4)
JUDGE_MODEL = "gpt-4.1-2025-04-14"

_JUDGE_SCHEMA = {
    "name": "eval_judgement",
    "strict": True,
    "schema": {
        "type": "object",
        "properties": {
            "sentences": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "sentence": {"type": "string"},
                        "supported": {"type": "boolean"},
                        "reason": {"type": "string"},
                    },
                    "required": ["sentence", "supported", "reason"],
                    "additionalProperties": False,
                },
            },
            "expected_facts": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "fact": {"type": "string"},
                        "present": {"type": "boolean"},
                        "reason": {"type": "string"},
                    },
                    "required": ["fact", "present", "reason"],
                    "additionalProperties": False,
                },
            },
            "forbidden_facts": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "fact": {"type": "string"},
                        "violated": {"type": "boolean"},
                        "reason": {"type": "string"},
                    },
                    "required": ["fact", "violated", "reason"],
                    "additionalProperties": False,
                },
            },
            "rule_checks": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "rule": {"type": "string"},
                        "passed": {"type": "boolean"},
                        "reason": {"type": "string"},
                    },
                    "required": ["rule", "passed", "reason"],
                    "additionalProperties": False,
                },
            },
        },
        "required": ["sentences", "expected_facts", "forbidden_facts", "rule_checks"],
        "additionalProperties": False,
    },
}


# ─── 골든셋 로딩 ───────────────────────────────────────────────────────────────


def _parse_golden_id(entry) -> dict:
    """골든셋 evidence 항목(문자열 또는 {id, aliases})을 {type, value, aliases}로 정규화."""
    raw = entry if isinstance(entry, str) else entry["id"]
    id_type, value = ID_RE.match(raw).groups()
    aliases = []
    if isinstance(entry, dict):
        for alias in entry.get("aliases") or []:
            aliases.append(ID_RE.match(alias).groups()[1])
    return {"type": id_type, "value": value, "aliases": aliases, "raw": raw}


def load_golden_cases() -> dict[str, dict]:
    cases = {}
    for path in sorted(glob(os.path.join(GOLDEN_DIR, "case-*.yaml"))):
        with open(path, encoding="utf-8") as f:
            case = yaml.safe_load(f)
        cases[case["id"]] = case
    return cases


# ─── 기계 채점 — id 정규화 매치 ────────────────────────────────────────────────


def _normalize(id_type: str, value: str) -> str:
    if id_type == "pull_request":
        return value.lstrip("#")
    if id_type == "commit":
        return value.lower()
    return value


def _matches(id_type: str, golden: dict, cited_value: str) -> bool:
    cited = _normalize(id_type, cited_value)
    candidates = [_normalize(id_type, golden["value"])] + [_normalize(id_type, a) for a in golden["aliases"]]
    for cand in candidates:
        if id_type == "commit":
            if cited.startswith(cand) or cand.startswith(cited):
                return True
        elif cited == cand:
            return True
    return False


def grade_mechanical(case: dict, structured: dict | None) -> dict:
    expected = [_parse_golden_id(e) for e in case.get("expected_evidence_ids") or []]
    acceptable = [_parse_golden_id(e) for e in case.get("acceptable_evidence_ids") or []]
    absent = [_parse_golden_id(e) for e in (case.get("expected_absent") or {}).get("evidence_ids") or []]

    if structured is None:
        return {
            "structured_null": True,
            "recall": None, "precision": None,
            "expected_missing": [g["raw"] for g in expected],
            "contamination": [], "format_violations": [], "cited_count": 0,
        }

    cited = [
        {"type": ev.get("type", ""), "value": str(ev.get("id", ""))}
        for ev in structured.get("evidence") or []
    ]

    # recall: expected 중 인용된 비율
    hits, missing = [], []
    for g in expected:
        if any(c["type"] == g["type"] and _matches(g["type"], g, c["value"]) for c in cited):
            hits.append(g["raw"])
        else:
            missing.append(g["raw"])
    recall = len(hits) / len(expected) if expected else None

    # precision: 인용 중 expected+acceptable에 있는 비율
    allowed = expected + acceptable
    cited_ok = sum(
        1 for c in cited
        if any(g["type"] == c["type"] and _matches(g["type"], g, c["value"]) for g in allowed)
    )
    precision = cited_ok / len(cited) if cited else None

    # 오염: expected_absent.evidence_ids가 인용됐는가
    contamination = [
        g["raw"] for g in absent
        if any(c["type"] == g["type"] and _matches(g["type"], g, c["value"]) for c in cited)
    ]

    # id 포맷 위반 (recall과 분리 집계 — 검색 품질과 포맷 준수를 한 지표에 섞지 않는다)
    violations = []
    for c in cited:
        rule = FORMAT_RULES.get(c["type"])
        if rule is None:
            violations.append(f"{c['type']}:{c['value']} — 알 수 없는 type")
        elif not rule[0].match(c["value"]):
            violations.append(f"{c['type']}:{c['value']} — {rule[1]} 위반")

    return {
        "structured_null": False,
        "recall": recall, "precision": precision,
        "expected_missing": missing, "contamination": contamination,
        "format_violations": violations, "cited_count": len(cited),
    }


# ─── LLM judge ────────────────────────────────────────────────────────────────


def _load_judge_rubric() -> tuple[str, str]:
    with open(JUDGE_PROMPT_PATH, encoding="utf-8") as f:
        text = f.read()
    rubric = text.split("---", 1)[1].strip()  # 헤더 주석 이후가 실제 system 메시지
    return rubric, hashlib.sha256(text.encode()).hexdigest()[:12]


def fetch_cited_originals(session, pid: str, structured: dict) -> dict:
    """인용된 evidence의 그래프 원문을 조회한다. 미해석 id는 null (judge가 unsupported 처리)."""
    originals = {}
    for ev in structured.get("evidence") or []:
        key = f"{ev.get('type')}:{ev.get('id')}"
        if key in originals:
            continue
        id_type, value = ev.get("type", ""), str(ev.get("id", ""))
        if id_type not in FORMAT_RULES:
            originals[key] = None
            continue
        originals[key] = fetch_evidence_body(session, pid, id_type, _normalize(id_type, value))
    return originals


def grade_with_judge(client, rubric: str, case: dict, structured: dict, originals: dict) -> tuple[dict, dict]:
    """judge 1콜로 환각·기대사실·금지사실·rule_checks를 판정한다. (판정, usage) 반환."""
    payload = {
        "question": case["question"],
        "structured": structured,
        "evidence_originals": originals,
        "expected_facts": case.get("expected_facts") or [],
        "forbidden_facts": (case.get("expected_absent") or {}).get("facts") or [],
        "rule_checks": case.get("rule_checks") or [],
    }
    response = client.chat.completions.create(
        model=JUDGE_MODEL,
        temperature=0,
        messages=[
            {"role": "system", "content": rubric},
            {"role": "user", "content": json.dumps(payload, ensure_ascii=False)},
        ],
        response_format={"type": "json_schema", "json_schema": _JUDGE_SCHEMA},
    )
    judgement = json.loads(response.choices[0].message.content)
    usage = {
        "prompt_tokens": response.usage.prompt_tokens,
        "completion_tokens": response.usage.completion_tokens,
        "total_tokens": response.usage.total_tokens,
    }
    return judgement, usage


def judge_metrics(judgement: dict) -> dict:
    sentences = judgement["sentences"]
    facts = judgement["expected_facts"]
    forbidden = judgement["forbidden_facts"]
    rules = judgement["rule_checks"]
    return {
        "hallucination_rate": (
            sum(1 for s in sentences if not s["supported"]) / len(sentences) if sentences else None
        ),
        "fact_accuracy": (
            sum(1 for f in facts if f["present"]) / len(facts) if facts else None
        ),
        "forbidden_violations": [f["fact"] for f in forbidden if f["violated"]],
        "rule_pass_rate": (
            sum(1 for r in rules if r["passed"]) / len(rules) if rules else None
        ),
    }


# ─── 집계 ─────────────────────────────────────────────────────────────────────

_MEAN_METRICS = ["recall", "precision", "hallucination_rate", "fact_accuracy", "rule_pass_rate"]


def _mean_ignoring_none(values: list) -> float | None:
    present = [v for v in values if v is not None]
    return round(mean(present), 4) if present else None


def aggregate(case_scores: list[dict]) -> dict:
    agg = {m: _mean_ignoring_none([c["mean"].get(m) for c in case_scores]) for m in _MEAN_METRICS}
    agg["cases_with_contamination"] = sum(
        1 for c in case_scores if any(r["contamination"] for r in c["runs"])
    )
    agg["cases_with_forbidden_facts"] = sum(
        1 for c in case_scores if any(r.get("forbidden_violations") for r in c["runs"])
    )
    agg["runs_structured_null"] = sum(
        1 for c in case_scores for r in c["runs"] if r["structured_null"]
    )
    agg["runs_with_format_violations"] = sum(
        1 for c in case_scores for r in c["runs"] if r["format_violations"]
    )
    agg["runs_with_direct_quotes"] = sum(
        1 for c in case_scores for r in c["runs"] if r.get("direct_quote_spans")
    )
    # 서버 가드가 고친 건수와, 고쳐지지 않고 남은 건수를 나눠 본다 — 전자는 프롬프트 튜닝의
    # 신호이고, 후자는 절대값이 아니라 급증을 신호로 읽는다(자기참조 오탐이 섞여 0이 되지 않는다.
    # 키 게이트로 일부러 보존한 사용자 어휘와 detect-only 라벨이 그대로 들어온다 — measurement.md 3.1).
    agg["runs_with_internal_term_replacements"] = sum(
        1 for c in case_scores for r in c["runs"] if r.get("internal_terms_replaced")
    )
    agg["runs_with_internal_terms_left"] = sum(
        1 for c in case_scores for r in c["runs"] if r.get("internal_terms_detected")
    )
    return agg


# ─── main ─────────────────────────────────────────────────────────────────────


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results_dir", help="runner.py 결과 디렉터리 (eval/results/<run-id>)")
    parser.add_argument("--skip-judge", action="store_true", help="기계 채점만 수행 (LLM 비용 0)")
    parser.add_argument("--project-id", help="evidence 원문 조회 project_id (생략 시 자동 감지)")
    args = parser.parse_args()

    responses = sorted(glob(os.path.join(args.results_dir, "responses", "*.json")))
    if not responses:
        sys.exit(f"응답 파일이 없습니다: {args.results_dir}/responses")
    with open(os.path.join(args.results_dir, "meta.json"), encoding="utf-8") as f:
        meta = json.load(f)

    golden = load_golden_cases()
    rubric, prompt_sha = _load_judge_rubric()

    client = None
    if not args.skip_judge:
        if not os.environ.get("OPENAI_API_KEY"):
            # infra/docker/.env에서 OPENAI_API_KEY만 로드 (프로세스 내에서만).
            # load_dotenv로 전체를 로드하면 NEO4J_* 등 컨테이너용 값이 로컬 접속 설정을 덮는다.
            from dotenv import dotenv_values
            env_key = dotenv_values(os.path.join(EVAL_DIR, "..", "infra", "docker", ".env")).get("OPENAI_API_KEY")
            if env_key:
                os.environ["OPENAI_API_KEY"] = env_key
        if not os.environ.get("OPENAI_API_KEY"):
            sys.exit("judge에 OPENAI_API_KEY가 필요합니다 — infra/docker/.env 또는 환경변수로 설정 (기계 채점만 하려면 --skip-judge).")
        from openai import OpenAI
        client = OpenAI()

    grading_dir = os.path.join(args.results_dir, "grading")
    os.makedirs(grading_dir, exist_ok=True)

    per_case: dict[str, list[dict]] = {}
    judge_tokens = 0
    answer_tokens: list[int] = []

    with open_driver() as driver:
        with driver.session() as session:
            pid = args.project_id or meta.get("project_id") or detect_project_id(session)

            for path in responses:
                with open(path, encoding="utf-8") as f:
                    record = json.load(f)
                case_id, run_no = record["case_id"], record["run"]
                case = golden.get(case_id)
                if case is None:
                    print(f"WARN {case_id}: 골든셋에 없는 케이스 — 건너뜀")
                    continue

                structured = None if "error" in record else record["response"].get("structured")
                run_score = {"run": run_no, **grade_mechanical(case, structured)}
                if "error" in record:
                    run_score["runner_error"] = record["error"]

                debug = (record.get("response") or {}).get("debug") or {}
                usage = debug.get("usage") or {}
                if usage.get("total_tokens"):
                    answer_tokens.append(usage["total_tokens"])
                    run_score["answer_tokens"] = usage["total_tokens"]
                # 옛 결과 파일에는 direct_quotes 키가 없다 — 그 경우 0건으로 집계.
                run_score["direct_quote_spans"] = len(debug.get("direct_quotes") or [])
                # 내부 용어 노출 — 두 갈래를 나눠 센다.
                #   replaced  = 모델이 필드명을 썼지만 서버가 사용자 표현으로 고친 것 (프롬프트 준수 신호)
                #   detected  = 치환되지 않고 답변에 남은 내부 어휘 (사용자에게 실제로 보이는 노출)
                internal = debug.get("internal_terms") or {}
                run_score["internal_terms_replaced"] = sum(
                    item["count"] for item in internal.get("replaced") or []
                )
                run_score["internal_terms_detected"] = sum(
                    item["count"] for item in internal.get("detected") or []
                )

                if client is not None and structured is not None:
                    originals = fetch_cited_originals(session, pid, structured)
                    judgement, j_usage = grade_with_judge(client, rubric, case, structured, originals)
                    judge_tokens += j_usage["total_tokens"]
                    run_score.update(judge_metrics(judgement))
                    detail_path = os.path.join(grading_dir, f"{case_id}.run-{run_no}.judge.json")
                    with open(detail_path, "w", encoding="utf-8") as f:
                        json.dump(
                            {"evidence_originals": originals, "judgement": judgement, "usage": j_usage},
                            f, ensure_ascii=False, indent=2,
                        )

                per_case.setdefault(case_id, []).append(run_score)
                print(f"{case_id} run-{run_no}: " + json.dumps(
                    {k: run_score.get(k) for k in _MEAN_METRICS if run_score.get(k) is not None},
                    ensure_ascii=False,
                ))

    case_scores = [
        {
            "case_id": case_id,
            "runs": runs,
            "mean": {m: _mean_ignoring_none([r.get(m) for r in runs]) for m in _MEAN_METRICS},
        }
        for case_id, runs in sorted(per_case.items())
    ]
    agg = aggregate(case_scores)

    scores = {
        "meta": meta,
        "judge": {"model": JUDGE_MODEL, "prompt_sha": prompt_sha, "skipped": args.skip_judge},
        "cases": case_scores,
        "aggregate": agg,
        "cost": {
            "answer_tokens_mean_per_query": round(mean(answer_tokens)) if answer_tokens else None,
            "judge_tokens_total": judge_tokens,
        },
    }
    out_path = os.path.join(args.results_dir, "scores.json")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(scores, f, ensure_ascii=False, indent=2)

    print(f"\n집계: {json.dumps(agg, ensure_ascii=False)}")
    print(f"저장: {out_path}")


if __name__ == "__main__":
    main()
