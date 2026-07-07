"""grader.py 기계 채점 순수 함수 단위 테스트 (오프라인 — Neo4j·LLM 불필요).

채점기는 측정 계측기라 여기서의 조용한 버그(prefix 매치 방향, 오염 판정 등)가
모든 recall/precision 숫자를 소리 없이 오염시킨다. 합성 골든 케이스 + 합성
structured 응답으로 정규화·매치·채점 경로를 고정한다.

실행: services/ai-engine/.venv/Scripts/python.exe -m pytest eval/tests
"""

import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

from grader import _matches, _normalize, _parse_golden_id, grade_mechanical


# ─── 합성 골든 케이스 (HT-100 DLQ를 본뜬 형태) ────────────────────────────────

CASE = {
    "id": "case-test",
    "question": "q",
    "expected_evidence_ids": [
        "issue:HT-100",
        "commit:caa50b3",
        {"id": "message:1778586053.322069", "aliases": ["message:1778590761.356869"]},
    ],
    "acceptable_evidence_ids": ["pull_request:#52"],
    "expected_absent": {"evidence_ids": ["message:1782286987.165039"], "facts": []},
}


def _structured(evidence):
    return {"summary": "s", "evidence": evidence, "unknown_aspects": []}


def _ev(type_, id_):
    return {"type": type_, "id": id_, "occurredAt": "", "event_meaning": "", "author": None, "quote": ""}


# ─── _normalize / _matches ────────────────────────────────────────────────────


def test_normalize_strips_pr_hash_and_lowers_commit():
    assert _normalize("pull_request", "#18") == "18"
    assert _normalize("pull_request", "18") == "18"
    assert _normalize("commit", "CAA50B3") == "caa50b3"
    assert _normalize("message", "1778586053.322069") == "1778586053.322069"
    assert _normalize("issue", "HT-100") == "HT-100"


def test_matches_commit_prefix_both_directions():
    golden = _parse_golden_id("commit:caa50b3")
    assert _matches("commit", golden, "caa50b3")                                  # 동일 7자
    assert _matches("commit", golden, "caa50b3" + "0" * 33)                       # 인용이 전체 해시
    golden_full = {"type": "commit", "value": "caa50b3" + "0" * 33, "aliases": [], "raw": ""}
    assert _matches("commit", golden_full, "caa50b3")                             # 인용이 더 짧은 prefix
    assert not _matches("commit", golden, "caa50b4")


def test_matches_pr_ignores_hash_prefix():
    golden = _parse_golden_id("pull_request:#52")
    assert _matches("pull_request", golden, "52")
    assert _matches("pull_request", golden, "#52")
    assert not _matches("pull_request", golden, "#5")


def test_matches_message_via_aliases():
    golden = _parse_golden_id(
        {"id": "message:1778586053.322069", "aliases": ["message:1778590761.356869"]}
    )
    assert _matches("message", golden, "1778586053.322069")   # 루트 ts
    assert _matches("message", golden, "1778590761.356869")   # 리플라이 ts alias
    assert not _matches("message", golden, "9999999999.000000")


# ─── grade_mechanical — recall / precision / 오염 / 포맷 위반 ─────────────────


def test_full_recall_and_precision():
    structured = _structured([
        _ev("issue", "HT-100"),
        _ev("commit", "caa50b3"),
        _ev("message", "1778586053.322069"),
        _ev("pull_request", "#52"),           # acceptable — precision에 인정, recall 분모 아님
    ])
    r = grade_mechanical(CASE, structured)
    assert r["recall"] == 1.0
    assert r["precision"] == 1.0
    assert r["expected_missing"] == []
    assert r["contamination"] == []
    assert r["format_violations"] == []


def test_partial_recall_reports_missing():
    structured = _structured([_ev("issue", "HT-100")])
    r = grade_mechanical(CASE, structured)
    assert r["recall"] == 1 / 3
    assert set(r["expected_missing"]) == {"commit:caa50b3", "message:1778586053.322069"}


def test_precision_penalizes_unlisted_evidence():
    structured = _structured([
        _ev("issue", "HT-100"),
        _ev("issue", "HT-999"),               # 골든셋에 없는 근거
    ])
    r = grade_mechanical(CASE, structured)
    assert r["precision"] == 0.5


def test_contamination_detects_absent_ids():
    structured = _structured([_ev("message", "1782286987.165039")])
    r = grade_mechanical(CASE, structured)
    assert r["contamination"] == ["message:1782286987.165039"]
    assert r["precision"] == 0.0              # 오염 근거는 allowed에도 없음


def test_format_violations_counted_but_recall_still_matches():
    # 포맷 위반(전체 해시, # 없는 PR)은 별도 집계하되 recall/precision 매치는 정규화로 살린다
    structured = _structured([
        _ev("commit", "caa50b3" + "0" * 33),  # 전체 해시 — 앞 7자 규칙 위반
        _ev("pull_request", "52"),            # '#' 누락
    ])
    r = grade_mechanical(CASE, structured)
    assert r["recall"] == 1 / 3               # commit은 prefix 매치로 히트
    assert r["precision"] == 1.0              # 둘 다 expected/acceptable로 인정
    assert len(r["format_violations"]) == 2


def test_alias_citation_counts_for_recall():
    structured = _structured([_ev("message", "1778590761.356869")])  # 리플라이 ts 인용
    r = grade_mechanical(CASE, structured)
    assert r["recall"] == 1 / 3
    assert "message:1778586053.322069" not in r["expected_missing"]


def test_type_mismatch_does_not_match():
    structured = _structured([_ev("issue", "caa50b3")])  # 값은 커밋인데 type이 issue
    r = grade_mechanical(CASE, structured)
    assert r["recall"] == 0.0
    assert r["precision"] == 0.0


def test_structured_null_flags_and_skips_rates():
    r = grade_mechanical(CASE, None)
    assert r["structured_null"] is True
    assert r["recall"] is None
    assert r["precision"] is None
    assert set(r["expected_missing"]) == {
        "issue:HT-100", "commit:caa50b3", "message:1778586053.322069",
    }


def test_empty_expected_gives_none_recall():
    case = {**CASE, "expected_evidence_ids": [], "acceptable_evidence_ids": []}
    r = grade_mechanical(case, _structured([]))
    assert r["recall"] is None                # 환각 케이스(case-21류) — recall 분모 없음
    assert r["precision"] is None             # 인용 0건 — precision 분모 없음
