"""precheck_retrieval.py 단위 테스트 (오프라인 — 합성 벡터와 임시 골든 파일, Neo4j·OpenAI·서버 불필요).

검색 근거 회수 지표는 임베딩 후보를 가르는 2차 지표라, 순위 계산의 방향이나 스레드 집계가 틀리면
후보 순서가 소리 없이 뒤집힌다. 그래프 조회(fetch_rows)와 임베딩 호출만 대체하고 나머지는 실제 경로를 탄다.

실행: services/ai-engine/.venv/Scripts/python.exe -m pytest eval/tests
"""

import argparse
import json
import os
import sys

import numpy as np
import pytest
import yaml

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

import precheck_retrieval as pr  # noqa: E402


def _ev(id_type, value, aliases=()):
    return {"type": id_type, "id": value, "ids": [value, *aliases], "raw": f"{id_type}:{value}"}


# ─── 골든 파싱 ──────────────────────────────────────────────────────────────────


def test_parse_evidence_accepts_strings_and_alias_objects():
    assert pr.parse_evidence("issue:HT-3") == _ev("issue", "HT-3")

    parsed = pr.parse_evidence({"id": "message:1778586053.322069", "aliases": ["message:1778590761.356869"]})
    assert parsed == _ev("message", "1778586053.322069", ["1778590761.356869"])

    # aliases 키가 있어도 비어 있을 수 있다
    assert pr.parse_evidence({"id": "issue:HT-9", "aliases": None})["ids"] == ["HT-9"]


def test_parse_evidence_ignores_commits_and_pull_requests():
    assert pr.parse_evidence("commit:abcdef1") is None
    assert pr.parse_evidence("pull_request:#4") is None


def test_parse_evidence_rejects_malformed_ids():
    with pytest.raises(ValueError):
        pr.parse_evidence("HT-3")


def _write_case(directory, name, case_id, question, expected):
    doc = {"id": case_id, "question": question, "expected_evidence_ids": expected}
    (directory / f"{name}.yaml").write_text(yaml.safe_dump(doc, allow_unicode=True), encoding="utf-8")


def test_load_cases_keeps_only_issue_and_message_evidence(tmp_path):
    _write_case(tmp_path, "case-01", "case-01", "HT-1이 뭐야?",
                ["issue:HT-1", "commit:abcdef1", "message:111.222", "pull_request:#4"])
    _write_case(tmp_path, "case-02", "case-02", "커밋만 있는 케이스", ["commit:abcdef1", "pull_request:#4"])
    _write_case(tmp_path, "case-03-en", "case-03-en", "What is HT-1?",
                [{"id": "message:333.444", "aliases": ["message:555.666"]}])
    (tmp_path / "notes.yaml").write_text("id: not-a-case\n", encoding="utf-8")     # case-*.yaml이 아니면 읽지 않는다

    cases, skipped = pr.load_cases(str(tmp_path))

    assert [c["id"] for c in cases] == ["case-01", "case-03-en"]
    assert skipped == 1                                                               # case-02
    assert cases[0]["question"] == "HT-1이 뭐야?"
    assert cases[0]["evidence"] == [_ev("issue", "HT-1"), _ev("message", "111.222")]
    assert cases[1]["evidence"] == [_ev("message", "333.444", ["555.666"])]


def test_real_golden_set_parses_without_dropping_any_case_silently():
    # 레포의 골든을 그대로 읽는다 — id 형식이 어긋난 케이스가 있으면 ValueError로 여기서 드러난다
    golden_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "golden")

    cases, skipped = pr.load_cases(golden_dir)

    assert cases and all(c["question"] and c["evidence"] for c in cases)
    assert len(cases) + skipped == len([f for f in os.listdir(golden_dir) if f.startswith("case-")])


# ─── 코퍼스 구성 ────────────────────────────────────────────────────────────────


def test_build_corpus_drops_unusable_rows_and_uses_the_ingestion_issue_text():
    comm_rows = [
        {"conversation_id": "T1", "body": "첫 메시지"},
        {"conversation_id": "T1", "body": "   "},            # 본문이 공백
        {"conversation_id": None, "body": "스레드 id 없음"},   # 어느 근거 id에도 대응할 수 없다
        {"conversation_id": "T2", "body": None},
        {"conversation_id": "T2", "body": "두 번째 스레드"},
    ]
    issue_rows = [
        {"issue_key": "HT-1", "title": "제목", "body": "본문"},
        {"issue_key": "HT-2", "title": None, "body": None},   # 임베딩할 글이 없다
        {"issue_key": "HT-3", "title": "제목만", "body": None},
    ]

    corpus = pr.build_corpus(comm_rows, issue_rows)

    assert corpus["conversation_ids"] == ["T1", "T2"]
    assert corpus["bodies"] == ["첫 메시지", "두 번째 스레드"]
    assert corpus["issue_keys"] == ["HT-1", "HT-3"]
    # 수집 경로(event_handler)와 같은 텍스트 — precheck의 fetch_texts와도 같다
    assert corpus["issue_texts"] == ["제목\n\n본문", "제목만\n\n"]
    assert corpus["dropped"] == {"no_conversation_id": 1, "empty_body": 2, "empty_issue": 1}


# ─── 순위 계산 ──────────────────────────────────────────────────────────────────


def test_thread_max_scores_takes_the_best_message_per_thread():
    scores = pr.thread_max_scores([0.2, 0.9, 0.5, 0.4], ["t1", "t1", "t2", "t3"])

    assert scores == {"t1": 0.9, "t2": 0.5, "t3": 0.4}


def test_threads_are_ranked_by_best_message_not_by_average():
    query = np.array([1.0, 0.0])
    # t_good: 메시지 3개 중 하나만 질문과 같은 방향 — 평균(0.33)은 낮지만 최댓값은 1.0
    # t_mid : 메시지 3개가 모두 45도 — 평균도 최댓값도 0.71. 평균으로 집계하면 순서가 뒤집힌다.
    matrix = np.array([[1.0, 0.0], [0.0, 1.0], [0.0, 1.0], [1.0, 1.0], [1.0, 1.0], [1.0, 1.0]])
    keys = ["t_good"] * 3 + ["t_mid"] * 3

    assert pr.rank_by_cosine(query, matrix, keys) == ["t_good", "t_mid"]


def test_ranking_uses_cosine_not_raw_dot_product():
    query = np.array([1.0, 0.0])
    # a는 크기가 작아도 방향이 같고(코사인 1.0), b는 크기가 커도 45도다(코사인 0.71, 내적은 5)
    matrix = np.array([[0.1, 0.0], [5.0, 5.0]])

    assert pr.rank_by_cosine(query, matrix, ["a", "b"]) == ["a", "b"]


def test_ties_are_broken_by_id_so_the_ranking_is_reproducible():
    query = np.array([0.0, 1.0])
    matrix = np.array([[1.0, 0.0], [1.0, 0.0], [0.0, 1.0]])    # 앞의 둘은 질문과 직교(코사인 0.0)

    assert pr.rank_by_cosine(query, matrix, ["T9", "T1", "T5"]) == ["T5", "T1", "T9"]


def test_ranking_an_empty_corpus_returns_nothing():
    assert pr.rank_by_cosine(np.array([1.0, 0.0]), np.zeros((0, 2)), []) == []


def test_best_rank_matches_the_id_or_any_alias_and_takes_the_best_one():
    ranked = ["t9", "t5", "t2", "t7"]

    assert pr.best_rank(ranked, ["t2"]) == 3
    assert pr.best_rank(ranked, ["t7", "t5"]) == 2       # 별칭 중 더 앞선 쪽
    assert pr.best_rank(ranked, ["nope"]) is None


# ─── 지표 ───────────────────────────────────────────────────────────────────────


def test_recall_at_k_counts_evidence_ids_and_keeps_missing_ones_in_the_denominator():
    ranks = [1, 5, 6, 20, 21, None]       # 5위·20위는 각각 @5·@20 안쪽(경계 포함), 21위·그래프에 없는 근거는 바깥

    assert pr.recall_at_k(ranks, 5) == pytest.approx(2 / 6)
    assert pr.recall_at_k(ranks, 20) == pytest.approx(4 / 6)
    assert pr.recall_at_k([], 5) is None


def test_mrr_uses_the_best_rank_per_case_and_zero_when_nothing_is_found():
    ranks_by_case = [[3, 10], [None, None], [1]]

    # 케이스 1은 가장 앞선 정답(3위)만 본다 → 1/3, 케이스 2는 못 찾았으니 0, 케이스 3은 1
    assert pr.mean_reciprocal_rank(ranks_by_case) == pytest.approx((1 / 3 + 0 + 1) / 3)
    assert pr.mean_reciprocal_rank([]) is None


def test_summarize_reports_each_type_separately():
    rows = [
        {"case": "c1", "type": "issue", "evidence": "issue:HT-1", "rank": 1},
        {"case": "c1", "type": "issue", "evidence": "issue:HT-2", "rank": 7},
        {"case": "c2", "type": "issue", "evidence": "issue:HT-3", "rank": None},
        {"case": "c2", "type": "message", "evidence": "message:1.1", "rank": 4},
        {"case": "c3", "type": "message", "evidence": "message:2.2", "rank": 30},
    ]

    summary = pr.summarize(rows, [5, 20])

    issue, message = summary["issue"], summary["message"]
    assert (issue["n_evidence"], issue["n_cases"], issue["n_unresolved"]) == (3, 2, 1)
    assert issue["recall"]["5"] == pytest.approx(1 / 3) and issue["recall"]["20"] == pytest.approx(2 / 3)
    assert issue["mrr"] == pytest.approx((1.0 + 0.0) / 2)        # c1 → 1위, c2 → 못 찾음
    assert (message["n_evidence"], message["n_cases"], message["n_unresolved"]) == (2, 2, 0)
    assert message["recall"]["5"] == pytest.approx(1 / 2) and message["recall"]["20"] == pytest.approx(1 / 2)
    assert message["mrr"] == pytest.approx((1 / 4 + 1 / 30) / 2)


def test_summarize_omits_types_without_evidence():
    rows = [{"case": "c1", "type": "issue", "evidence": "issue:HT-1", "rank": 2}]

    assert list(pr.summarize(rows, [5])) == ["issue"]


# ─── 합성 벡터로 처음부터 끝까지 ────────────────────────────────────────────────


E1, E2, E3 = np.eye(3)


def test_evaluate_ranks_evidence_with_synthetic_vectors():
    comm = np.array([E1, E2, E3, 0.5 * E1 + E3])     # 스레드 T1, T2, T3(메시지 둘)
    conv_ids = ["T1", "T2", "T3", "T3"]
    issues = np.array([E1, E2, E3])
    issue_keys = ["HT-1", "HT-2", "HT-3"]
    cases = [{"id": "c1", "question": "q", "evidence": [
        _ev("issue", "HT-2"), _ev("message", "T1"), _ev("message", "T9"), _ev("message", "T3", ["T2"])]}]
    questions = np.array([E2])                         # 질문은 e2 방향 — HT-2와 T2가 1위

    rows = pr.evaluate(cases, questions, comm, conv_ids, issues, issue_keys)

    ranks = {r["evidence"]: r["rank"] for r in rows}
    assert ranks["issue:HT-2"] == 1
    assert ranks["message:T1"] == 2          # T1·T3은 코사인 0.0 동점이라 id순 (T1 → T3)
    assert ranks["message:T9"] is None       # 그래프에 없는 근거 — 순위 없음
    assert ranks["message:T3"] == 1          # 별칭 T2가 1위라 별칭 쪽 순위를 쓴다
    assert all(r["case"] == "c1" for r in rows)


# ─── CLI ────────────────────────────────────────────────────────────────────────


def test_parse_ks_sorts_dedupes_and_rejects_bad_values():
    assert pr.parse_ks("20, 5,5") == [5, 20]
    for bad in ("0", "a,b", "", "-3"):
        with pytest.raises(argparse.ArgumentTypeError):
            pr.parse_ks(bad)


def test_default_out_path_names_the_labels_only_when_only_is_given():
    default = pr.default_out_path(pr.select_configs(None), only_given=False)
    one = pr.default_out_path(pr.select_configs("bge-m3"), only_given=True)

    assert os.path.basename(default) == "precheck-retrieval.json"
    assert os.path.basename(one) == "precheck-retrieval-bge-m3.json"


class _Ctx:
    """open_driver()가 돌려주는 드라이버·세션 자리 — with 문만 통과시킨다."""

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def session(self):
        return self


def test_main_runs_the_pipeline_and_writes_ranks_and_summary(tmp_path, monkeypatch, capsys):
    _write_case(tmp_path, "case-01", "case-01", "q1", ["issue:HT-1", "message:T1"])
    _write_case(tmp_path, "case-02", "case-02", "q2", ["issue:HT-1", "message:T9"])    # T9는 그래프에 없다
    vectors = {"q1": E1, "q2": E2, "본문1": E1, "본문2": E2, "이슈1\n\n내용1": E1, "이슈2\n\n내용2": E2}

    monkeypatch.setattr(pr, "open_driver", lambda: _Ctx())
    monkeypatch.setattr(pr, "detect_project_id", lambda session: "pid")
    monkeypatch.setattr(pr, "fetch_rows", lambda session, pid: (
        [{"conversation_id": "T1", "body": "본문1"}, {"conversation_id": "T2", "body": "본문2"}],
        [{"issue_key": "HT-1", "title": "이슈1", "body": "내용1"}, {"issue_key": "HT-2", "title": "이슈2", "body": "내용2"}]))
    monkeypatch.setattr(pr, "ensure_openai_key", lambda: None)
    monkeypatch.setattr(pr, "make_client", lambda cfg: object())
    monkeypatch.setattr(pr, "embed_all", lambda client, texts, model, dims: [vectors[t].tolist() for t in texts])
    out = tmp_path / "retrieval.json"

    pr.main(["--only", "3-large@1536", "--golden-dir", str(tmp_path), "--k", "1,2", "--out", str(out)])

    printed = capsys.readouterr().out
    assert "| 3-large@1536 | issue |" in printed and "| 3-large@1536 | message |" in printed
    doc = json.loads(out.read_text(encoding="utf-8"))
    assert doc["project_id"] == "pid" and doc["ks"] == [1, 2]
    assert doc["corpus"]["threads"] == 2 and doc["corpus"]["issues"] == 2
    config = doc["configs"]["3-large@1536"]
    ranks = {(r["case"], r["evidence"]): r["rank"] for r in config["ranks"]}
    assert ranks == {("case-01", "issue:HT-1"): 1, ("case-01", "message:T1"): 1,
                     ("case-02", "issue:HT-1"): 2, ("case-02", "message:T9"): None}
    issue, message = config["summary"]["issue"], config["summary"]["message"]
    assert issue["recall"] == {"1": 0.5, "2": 1.0} and issue["mrr"] == pytest.approx(0.75)
    assert message["recall"] == {"1": 0.5, "2": 0.5} and message["n_unresolved"] == 1
    assert message["mrr"] == pytest.approx(0.5)


def test_main_exits_when_the_golden_dir_has_no_usable_case(tmp_path):
    _write_case(tmp_path, "case-01", "case-01", "q", ["commit:abcdef1"])

    with pytest.raises(SystemExit):
        pr.main(["--golden-dir", str(tmp_path), "--out", str(tmp_path / "x.json")])
