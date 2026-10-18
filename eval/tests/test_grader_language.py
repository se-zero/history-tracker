"""grader.py 답변 언어 채점·짝 비교 순수 함수 단위 테스트 (오프라인 — Neo4j·LLM 불필요).

답변 언어는 summary + unknown_aspects의 한글 토큰 비율로 판정한다(docs/answer-language.md §4-4) —
≥0.4 ko, ≤0.15 en, 그 사이는 mixed(실패). 근거 인용(evidence[*].quote)은 원문 그대로라 보지 않고,
따옴표 안의 글(이슈 제목 인용)도 원문 언어라 빼고 센다.

실행: services/ai-engine/.venv/Scripts/python.exe -m pytest eval/tests
"""

import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

from grader import aggregate, answer_language, decided_language, pair_deltas


def _structured(summary, unknown_aspects=(), evidence=()):
    return {"summary": summary, "evidence": list(evidence), "unknown_aspects": list(unknown_aspects)}


# ─── answer_language ──────────────────────────────────────────────────────────


def test_korean_answer_with_identifiers_is_ko():
    s = _structured(
        "HT-26 '그래프 생성' 작업은 수집한 이벤트를 Neo4j 노드로 만드는 파이프라인을 추가했어요. "
        "PR #12의 커밋 9ef0504에서 event_handler.py가 처음 생겼어요.",
        ["배포 일정은 기록에서 확인되지 않았어요."],
    )
    language, ratio = answer_language(s)
    assert language == "ko"
    assert ratio >= 0.4


def test_english_answer_quoting_korean_titles_is_en():
    # 이슈 제목은 원문(한국어) 그대로 따옴표로 인용된다 — 소유격 아포스트로피(HT-26's)에 걸리지 않아야 한다
    s = _structured(
        "HT-26's '그래프 생성' task added the pipeline that turns collected events into Neo4j nodes. "
        "It was linked to HT-102 \"글로벌 검색 추가\" and HT-94 “ai-engine 최적화”, which were tied at 13 "
        "linked messages. 서준수 opened PR #12 on 2026-04-03.",
        ["The deployment date could not be confirmed in the records."],
    )
    language, ratio = answer_language(s)
    assert language == "en"
    assert ratio <= 0.15


def test_korean_answer_with_english_offer_tail_is_mixed():
    s = _structured(
        "HT-69에서 질의응답을 멀티턴으로 바꾼 이유는 후속 질문의 맥락을 잇기 위해서예요. "
        "This change also added a running summary for long conversations and kept the last five turns as history.",
    )
    language, _ = answer_language(s)
    assert language == "mixed"


def test_evidence_quotes_are_not_counted():
    # 원문 인용은 한국어여도 답변 언어 판정에 들어가지 않는다
    s = _structured(
        "The DLQ was introduced so that failed events are retried and parked instead of being dropped.",
        evidence=[{"type": "issue", "id": "HT-100", "quote": "실패한 이벤트가 조용히 버려져서 재시도 큐와 DLQ를 도입한다"}],
    )
    assert answer_language(s)[0] == "en"


def test_contraction_is_not_a_quote():
    s = _structured("It doesn't say why. The issue wasn't linked to any PR, and the reason isn't recorded.")
    assert answer_language(s)[0] == "en"


def test_empty_or_identifier_only_answer_has_no_language():
    # 숫자가 섞인 토큰(이슈 키·해시·날짜)은 어느 언어의 단어로도 세지 않는다
    assert answer_language(_structured("", [])) == (None, None)
    assert answer_language(_structured("HT-12 #31 9ef0504 2026-04-03", [])) == (None, None)


def test_korean_token_with_digits_still_counts_as_korean():
    language, _ = answer_language(_structured("2026년 4월에 HT-63을 열었어요."))
    assert language == "ko"


def test_structured_null_has_no_language():
    assert answer_language(None) == (None, None)


# ─── decided_language ─────────────────────────────────────────────────────────


def test_decided_language_prefers_debug_record():
    response = {"structured": {"language": "ko"}, "debug": {"answer_language": {"language": "en", "source": "router"}}}
    assert decided_language(response) == ("en", "router")


def test_decided_language_falls_back_to_structured():
    assert decided_language({"structured": {"language": "en"}, "debug": {}}) == ("en", None)


def test_decided_language_unknown_for_old_engine():
    # 이 작업 이전 엔진의 응답에는 판정 기록이 없다
    assert decided_language({"structured": {"summary": "s"}, "debug": {}}) == (None, None)
    assert decided_language(None) == (None, None)


# ─── aggregate 언어 카운터 ─────────────────────────────────────────────────────


def _run(language, decided, structured_null=False, **metrics):
    return {
        "structured_null": structured_null, "contamination": [], "format_violations": [],
        "answer_language": language, "answer_language_decided": decided, **metrics,
    }


def _case(case_id, runs, answer_language_=None, paired_with=None, **means):
    case = {"case_id": case_id, "runs": runs, "mean": {"recall": None, "precision": None,
            "hallucination_rate": None, "fact_accuracy": None, "rule_pass_rate": None, **means}}
    if answer_language_:
        case["answer_language"] = answer_language_
    if paired_with:
        case["paired_with"] = paired_with
    return case


def test_language_mismatch_counts_runs_against_expected_language():
    cases = [
        _case("case-03", [_run("ko", "ko"), _run("ko", "ko"), _run("mixed", "ko")]),            # 기대 ko(기본)
        _case("case-03-en", [_run("en", "en"), _run("ko", "ko"), _run(None, "en", structured_null=True)],
              answer_language_="en", paired_with="case-03"),
    ]
    agg = aggregate(cases)
    # mixed 1건 + 영어 케이스의 ko 답 1건. structured=null은 언어 판정 대상이 아니다(runs_structured_null로 따로 센다)
    assert agg["runs_answer_language_mismatch"] == 2
    # 서버가 정한 언어가 기대와 다른 런 — 영어 케이스에서 ko로 정한 1건
    assert agg["runs_language_decision_mismatch"] == 1
    assert agg["runs_language_decision_unknown"] == 0


def test_answer_without_countable_words_is_unknown_not_mismatch():
    # 식별자뿐인 답(셀 단어가 없음)은 어느 언어로 썼는지 알 수 없다 — 불일치가 아니라 unknown으로 따로 센다.
    # structured=null 런은 답이 없어 여기에도 넣지 않는다(runs_structured_null로 센다)
    cases = [_case("case-03", [_run(None, "ko"), _run("ko", "ko"), _run(None, "ko", structured_null=True)])]
    agg = aggregate(cases)
    assert agg["runs_answer_language_mismatch"] == 0
    assert agg["runs_answer_language_unknown"] == 1


def test_old_engine_decisions_are_unknown_not_mismatch():
    cases = [_case("case-03", [_run("ko", None), _run("ko", None)])]
    agg = aggregate(cases)
    assert agg["runs_language_decision_mismatch"] == 0
    assert agg["runs_language_decision_unknown"] == 2


# ─── pair_deltas ──────────────────────────────────────────────────────────────


def test_pair_deltas_are_english_minus_korean():
    cases = [
        _case("case-03", [], recall=0.8, fact_accuracy=1.0),
        _case("case-03-en", [], answer_language_="en", paired_with="case-03", recall=0.6, fact_accuracy=0.75),
        _case("case-45", [], recall=1.0, fact_accuracy=None),
        _case("case-45-en", [], answer_language_="en", paired_with="case-45", recall=0.5, fact_accuracy=1.0),
    ]
    pairs = pair_deltas(cases)
    assert [(p["case_id"], p["paired_with"]) for p in pairs] == [("case-03-en", "case-03"), ("case-45-en", "case-45")]
    assert pairs[0]["deltas"]["recall"] == -0.2
    assert pairs[0]["deltas"]["fact_accuracy"] == -0.25
    assert pairs[1]["deltas"]["fact_accuracy"] is None          # 한쪽이 None이면 차이도 None

    agg = aggregate(cases)
    assert agg["pairs"] == 2
    assert agg["pair_recall_delta_mean"] == -0.35               # (-0.2 + -0.5) / 2
    assert agg["pair_fact_accuracy_delta_mean"] == -0.25        # None은 평균에서 뺀다


def test_pair_without_its_partner_in_the_run_is_skipped():
    # --cases로 영어판만 돌린 런 — 짝이 없으면 차이를 낼 수 없다
    cases = [_case("case-03-en", [], answer_language_="en", paired_with="case-03", recall=0.6)]
    assert pair_deltas(cases) == []
    agg = aggregate(cases)
    assert agg["pairs"] == 0
    assert agg["pair_recall_delta_mean"] is None
