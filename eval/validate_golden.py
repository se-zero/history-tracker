#!/usr/bin/env python
"""골든셋 유효성 검사기 (C-4, docs/measurement-plan.md).

eval/golden/case-*.yaml의 모든 evidence id(expected / acceptable / expected_absent,
aliases 포함)가 스냅샷 그래프의 실제 노드로 해석되는지 러너 실행 전에 검증한다.
오타·스냅샷 불일치로 측정 한 판을 버리는 것을 막는 장치.
그래프를 보기 전에 파일만으로 id·answer_language·paired_with(영어 짝 케이스)도 검사한다.

실행 (ai-engine venv의 드라이버 사용):
    services/ai-engine/.venv/Scripts/python.exe eval/validate_golden.py
    옵션: --project-id <uuid>  (생략 시 그래프에서 자동 감지)
"""

import argparse
import os
import sys
from glob import glob

import yaml

from graph_lookup import FORMAT_RULES, ID_RE, detect_project_id, open_driver, resolve

# Windows 콘솔(cp949)에서 em-dash 등 출력 크래시 방지
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

GOLDEN_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "golden")

# 짝 케이스는 질문만 다른 언어로 바꾸고 이 필드들은 원본 그대로 둔다(docs/answer-language.md §4-4) —
# 어긋나면 짝별 차이가 언어가 아니라 기대값 차이를 잰다.
PAIR_FIELDS = (
    "focus_evidence", "expected_evidence_ids", "acceptable_evidence_ids",
    "expected_facts", "expected_absent", "rule_checks",
)
ANSWER_LANGUAGES = ("ko", "en")


def check_pairs(cases: dict[str, dict]) -> list[tuple[str, str, str, str]]:
    """id·answer_language·paired_with 검사. cases는 {파일 이름(확장자 없음): 케이스}, (파일, 필드, 값, 사유) 목록을 돌려준다."""
    errors = []
    for name, case in cases.items():
        if case.get("id") != name:
            errors.append((name, "id", str(case.get("id")), "파일 이름과 다름 — 같은 id가 둘이면 grader가 한쪽을 덮어쓴다"))
        language = case.get("answer_language", "ko")
        if language not in ANSWER_LANGUAGES:
            errors.append((name, "answer_language", str(language), "ko 또는 en이어야 함"))
        base_id = case.get("paired_with")
        if base_id is None:
            continue
        base = cases.get(base_id)
        if base is None:
            errors.append((name, "paired_with", str(base_id), "짝 원본 케이스 없음"))
            continue
        if base.get("paired_with") is not None:
            errors.append((name, "paired_with", str(base_id), "원본이 아니라 다른 짝을 가리킴"))
        if base.get("answer_language", "ko") == language:
            errors.append((name, "answer_language", str(language), f"짝 원본({base_id})과 답변 언어가 같음"))
        for field in PAIR_FIELDS:
            if case.get(field) != base.get(field):
                errors.append((name, field, "", f"짝 원본({base_id})과 다름 — 기대 근거·사실·규칙은 원본 그대로여야 함"))
    return errors


def iter_evidence_ids(case):
    """케이스의 모든 evidence id를 (필드 경로, id 문자열)로 순회한다."""
    for field in ("expected_evidence_ids", "acceptable_evidence_ids"):
        for entry in case.get(field) or []:
            if isinstance(entry, str):
                yield field, entry
            else:
                yield field, entry["id"]
                for alias in entry.get("aliases") or []:
                    yield f"{field} (alias)", alias
    absent = (case.get("expected_absent") or {}).get("evidence_ids") or []
    for entry in absent:
        yield "expected_absent.evidence_ids", entry if isinstance(entry, str) else entry["id"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-id", help="검증 대상 project_id (생략 시 자동 감지)")
    args = parser.parse_args()

    case_files = sorted(glob(os.path.join(GOLDEN_DIR, "case-*.yaml")))
    if not case_files:
        sys.exit(f"골든셋 파일이 없습니다: {GOLDEN_DIR}")

    cases = {}
    for path in case_files:
        with open(path, encoding="utf-8") as f:
            cases[os.path.splitext(os.path.basename(path))[0]] = yaml.safe_load(f)

    # (case, field, id, 사유)
    errors = [(f"{name}.yaml", field, value, reason) for name, field, value, reason in check_pairs(cases)]
    warnings = []  # (case, field, id, 사유)

    with open_driver() as driver:
        with driver.session() as session:
            pid = args.project_id or detect_project_id(session)
            print(f"project_id: {pid}")
            print(f"케이스: {len(case_files)}개\n")

            for stem, case in cases.items():
                name = f"{stem}.yaml"

                for field, raw in iter_evidence_ids(case):
                    m = ID_RE.match(raw)
                    if not m:
                        errors.append((name, field, raw, "id 형식 아님 — {type}:{id} 필요"))
                        continue
                    id_type, value = m.groups()
                    fmt_re, fmt_desc = FORMAT_RULES[id_type]
                    if not fmt_re.match(value):
                        errors.append((name, field, raw, f"{id_type} 표기 위반 — {fmt_desc}"))
                        continue
                    count, note = resolve(session, pid, id_type, value)
                    if count == 0:
                        errors.append((name, field, raw, "그래프에 노드 없음"))
                    elif note:
                        warnings.append((name, field, raw, note))

    for name, field, raw, reason in errors:
        print(f"ERROR  {name}  [{field}]  {raw}  — {reason}")
    for name, field, raw, reason in warnings:
        print(f"WARN   {name}  [{field}]  {raw}  — {reason}")

    print(f"\n결과: ERROR {len(errors)}건, WARN {len(warnings)}건")
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()
