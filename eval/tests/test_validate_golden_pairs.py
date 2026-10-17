"""validate_golden.py 짝 검사(check_pairs) 단위 테스트 (오프라인 — 그래프 없이 파일만 본다).

영어 짝 케이스(case-NN-en)는 질문만 영어로 바꾸고 기대 근거·사실·규칙은 원본(한국어판)과 같아야 한다
(docs/answer-language.md §4-4). 짝이 원본과 어긋나면 짝별 차이가 언어가 아니라 기대값 차이를 재게 되고,
id를 복사하다 겹치면 grader가 한쪽 케이스를 조용히 덮어쓴다.

실행: services/ai-engine/.venv/Scripts/python.exe -m pytest eval/tests
"""

import copy
import glob
import os
import sys

import yaml

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

from validate_golden import GOLDEN_DIR, check_pairs

KO = {
    "id": "case-03",
    "question": "HT-26 '그래프 생성' 작업의 배경과 변경 내용을 알려줘",
    "focus_evidence": [],
    "expected_evidence_ids": ["issue:HT-26", "commit:9ef0504"],
    "acceptable_evidence_ids": ["pull_request:#12"],
    "expected_facts": ["HT-26은 그래프 생성 파이프라인을 추가했다"],
    "expected_absent": {"evidence_ids": [], "facts": []},
    "rule_checks": ["summary에 도구 이름이 없다"],
}


def _english(**overrides):
    case = {
        **copy.deepcopy(KO),
        "id": "case-03-en",
        "question": "Tell me the background of the HT-26 '그래프 생성' task and what it changed.",
        "paired_with": "case-03",
        "answer_language": "en",
    }
    case.update(overrides)
    return case


def _errors(*named_cases):
    return check_pairs(dict(named_cases))


def test_valid_pair_has_no_errors():
    assert _errors(("case-03", KO), ("case-03-en", _english())) == []


def test_cases_without_pair_fields_are_korean_and_valid():
    assert _errors(("case-03", KO)) == []


def test_unknown_answer_language_is_error():
    errors = _errors(("case-03", KO), ("case-03-en", _english(answer_language="english")))
    assert [(e[0], e[1]) for e in errors] == [("case-03-en", "answer_language")]


def test_missing_partner_is_error():
    errors = _errors(("case-03-en", _english()))
    assert [(e[0], e[1]) for e in errors] == [("case-03-en", "paired_with")]


def test_pair_must_differ_in_language_from_partner():
    errors = _errors(("case-03", KO), ("case-03-en", _english(answer_language="ko")))
    assert [(e[0], e[1]) for e in errors] == [("case-03-en", "answer_language")]


def test_pair_must_point_at_the_original_not_another_pair():
    second = _english(id="case-03-en2", paired_with="case-03-en")
    errors = _errors(("case-03", KO), ("case-03-en", _english()), ("case-03-en2", second))
    assert ("case-03-en2", "paired_with") in [(e[0], e[1]) for e in errors]


def test_expectations_must_match_the_partner_field_by_field():
    drifted = _english(expected_facts=["HT-26은 다른 일을 했다"], rule_checks=[])
    errors = _errors(("case-03", KO), ("case-03-en", drifted))
    assert sorted(e[1] for e in errors) == ["expected_facts", "rule_checks"]


def test_focus_evidence_must_match_too():
    errors = _errors(("case-03", KO), ("case-03-en", _english(focus_evidence=[{"type": "issue", "id": "HT-26"}])))
    assert [e[1] for e in errors] == ["focus_evidence"]


def test_id_must_match_file_name():
    # 짝 파일을 복사하다 id를 안 바꾸면 grader가 원본 케이스를 덮어쓴다
    errors = _errors(("case-03", KO), ("case-03-en", _english(id="case-03")))
    assert ("case-03-en", "id") in [(e[0], e[1]) for e in errors]


def test_real_golden_files_pass_pair_checks():
    # validate_golden.py는 Neo4j에 붙어야 결과를 낸다 — 원본 케이스를 고치고 영어 짝을 안 고친 것을 그래프 없이 여기서 잡는다
    cases = {}
    for path in sorted(glob.glob(os.path.join(GOLDEN_DIR, "case-*.yaml"))):
        with open(path, encoding="utf-8") as f:
            cases[os.path.splitext(os.path.basename(path))[0]] = yaml.safe_load(f)
    assert any(case.get("paired_with") for case in cases.values()), "영어 짝 케이스를 하나도 읽지 못했다"
    assert check_pairs(cases) == []
