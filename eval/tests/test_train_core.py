"""embedding_finetune/train_core.py 순수 함수와 train.py의 인자·summarize 단위 테스트 (오프라인 — 모델·GPU 불필요).

이 부분이 틀리면 학습은 멀쩡히 돌고(GPU에서 한 시간 넘게) 숫자만 조용히 틀어진다: val까지 변형으로 걸러 변형 간
비교가 무효가 되거나, 같은 앵커의 짝이 둘일 때 하나만 정답으로 쳐 점수가 낮게 나오거나, 유형 평균이 아니라 한 유형이
점수를 좌우하거나, 실패한 변형이 1위로 뽑힌다. 그래서 경계와 방향을 값으로 고정한다.

실행: services/ai-engine/.venv/bin/python -m pytest eval/tests -q
"""

import hashlib
import json
import os
import subprocess
import sys

import pytest

EMBEDDING_FINETUNE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "embedding_finetune")
sys.path.insert(0, EMBEDDING_FINETUNE_DIR)

import train  # noqa: E402
import train_core as tc  # noqa: E402


def pair(type_="change_conversation", anchor="a", positive="p", **extra):
    row = {"id": f"{type_}:{anchor}:{positive}", "type": type_, "anchor": anchor, "positive": positive,
           "has_korean": True, "created_at": "2024-06-01T00:00:00Z"}
    row.update(extra)
    return row


# ─── 상수 ───────────────────────────────────────────────────────────────────────


def test_variant_order_is_the_tie_break_order():
    # 점수가 같으면 앞선 변형이 이긴다 — 데이터를 덜 건드린 쪽(all)이 먼저여야 한다
    assert tc.VARIANTS == ("all", "ko", "pre2025")


def test_types_cover_the_three_edge_pair_types():
    assert tc.TYPES == ("change_conversation", "change_issue", "issue_conversation")
    assert set(tc.EDGE_LABELS) == set(tc.TYPES)


def test_evaluator_options_disable_prompts_with_empty_string_not_none():
    # None이면 ST의 encode_query가 Qwen3의 "Instruct: ..." 지시문을 질의에만 붙여 학습(지시문 없음)과 평가 조건이 어긋난다.
    # 빈 문자열이어야 "지시문 없음"이다 (2026-11-04 Qwen3로 확인: None이면 encode()와 cosine 0.91, ""이면 동일)
    assert tc.EVALUATOR_OPTIONS["query_prompt"] == ""
    assert tc.EVALUATOR_OPTIONS["corpus_prompt"] == ""


def test_evaluator_options_measure_ndcg_at_10_without_writing_files():
    options = tc.EVALUATOR_OPTIONS
    assert options["ndcg_at_k"] == [10]          # ndcg_key가 가정하는 유일한 k — 달라지면 키 조회가 어긋난다
    assert options["write_csv"] is False
    assert options["show_progress_bar"] is False


# ─── filter_variant ────────────────────────────────────────────────────────────


def test_all_keeps_every_row_in_order_and_returns_a_new_list():
    # 한글이 없는 쌍과 날짜가 없는 쌍도 all에는 들어간다 — 다른 변형의 규칙이 새어 들어오면 기준 변형이 아니게 된다
    rows = [pair(anchor="c"), pair(anchor="en", has_korean=False), pair(anchor="new", created_at="2025-06-01T00:00:00Z"),
            pair(anchor="undated", created_at=None)]

    kept = tc.filter_variant(rows, "all")

    assert kept == rows
    kept.append(pair(anchor="x"))
    assert len(rows) == 4   # 호출자의 목록을 건드리지 않는다


def test_ko_keeps_only_rows_with_has_korean_true():
    rows = [pair(anchor="ko", has_korean=True), pair(anchor="en", has_korean=False), pair(anchor="none")]
    del rows[2]["has_korean"]

    kept = tc.filter_variant(rows, "ko")

    assert [r["anchor"] for r in kept] == ["ko"]   # 값이 거짓이거나 키가 없는 쌍은 한국어로 치지 않는다


def test_ko_does_not_treat_truthy_strings_as_korean():
    # "false" 문자열이 참으로 통과하면 영어 쌍이 ko 변형에 섞인다
    assert tc.filter_variant([pair(has_korean="false")], "ko") == []


@pytest.mark.parametrize("created_at, kept", [
    ("2024-12-31T23:59:59Z", True),      # 경계 직전 — 포함
    ("2025-01-01T00:00:00Z", False),     # 경계 — 제외
    ("2025-01-01", False),               # 날짜만 있어도 같은 규칙 (같은 문자열이라 '미만'이 아니다)
    ("2024-12-31", True),
    ("2019-03-01T00:00:00Z", True),
    ("2025-06-01T00:00:00Z", False),
    ("", False),                         # 언제 쓴 글인지 모르면 뺀다
    (None, False),
])
def test_pre2025_boundary_and_missing_dates(created_at, kept):
    row = pair(created_at=created_at)

    assert (tc.filter_variant([row], "pre2025") == [row]) is kept


def test_pre2025_excludes_rows_without_created_at_key():
    row = pair()
    del row["created_at"]

    assert tc.filter_variant([row], "pre2025") == []


@pytest.mark.parametrize("name", ["KO", "", "pre-2025", "none"])
def test_unknown_variant_is_an_error(name):
    with pytest.raises(ValueError):
        tc.filter_variant([pair()], name)


# ─── training_columns_by_type ──────────────────────────────────────────────────


def test_columns_have_only_anchor_and_positive():
    # type·repo 같은 열이 남으면 NO_DUPLICATES 샘플러가 그 값까지 비교해, 같은 저장소 쌍이 한 배치에 하나만 들어간다
    rows = [pair(repo="o/r", split="train", source={"kind": "pr", "number": 1})]

    columns = tc.training_columns_by_type(rows)

    assert set(columns["change_conversation"]) == {"anchor", "positive"}


def test_columns_split_by_type_in_types_order_and_keep_input_order():
    rows = [
        pair("issue_conversation", "i1", "c1"),
        pair("change_conversation", "a1", "p1"),
        pair("issue_conversation", "i2", "c2"),
        pair("change_conversation", "a2", "p2"),
    ]

    columns = tc.training_columns_by_type(rows)

    assert list(columns) == ["change_conversation", "issue_conversation"]   # TYPES 순서, 쌍이 없는 change_issue는 빠진다
    assert columns["change_conversation"] == {"anchor": ["a1", "a2"], "positive": ["p1", "p2"]}
    assert columns["issue_conversation"] == {"anchor": ["i1", "i2"], "positive": ["c1", "c2"]}


def test_columns_keep_anchor_and_positive_aligned_per_row():
    rows = [pair(anchor=f"a{i}", positive=f"p{i}") for i in range(20)]

    columns = tc.training_columns_by_type(rows, limit_per_type=7)["change_conversation"]

    assert all(a[1:] == p[1:] for a, p in zip(columns["anchor"], columns["positive"]))   # a3 ↔ p3


def test_columns_with_no_rows_is_empty():
    assert tc.training_columns_by_type([]) == {}


def test_unknown_type_is_an_error_not_silently_dropped():
    with pytest.raises(ValueError):
        tc.training_columns_by_type([pair("new_type")])
    with pytest.raises(ValueError):
        tc.eval_inputs_by_type([pair("new_type")])


def test_limit_is_per_type_deterministic_and_keeps_original_order():
    rows = [pair("change_conversation", f"a{i:02d}", f"p{i:02d}") for i in range(30)]
    rows += [pair("change_issue", f"b{i:02d}", f"q{i:02d}") for i in range(12)]

    first = tc.training_columns_by_type(rows, limit_per_type=5, seed=42)
    again = tc.training_columns_by_type(rows, limit_per_type=5, seed=42)

    assert first == again
    assert {t: len(c["anchor"]) for t, c in first.items()} == {"change_conversation": 5, "change_issue": 5}
    for column in first.values():
        assert column["anchor"] == sorted(column["anchor"])   # 입력 순서(여기서는 오름차순)를 지킨다


def test_limit_larger_than_the_type_keeps_everything():
    rows = [pair(anchor=f"a{i}", positive=f"p{i}") for i in range(4)]

    assert len(tc.training_columns_by_type(rows, limit_per_type=10)["change_conversation"]["anchor"]) == 4


def test_limit_changes_with_seed():
    rows = [pair(anchor=f"a{i:03d}", positive=f"p{i:03d}") for i in range(200)]

    picks = {tuple(tc.training_columns_by_type(rows, limit_per_type=10, seed=s)["change_conversation"]["anchor"])
             for s in range(5)}

    assert len(picks) > 1   # 시드를 무시하면 항상 같은 표본이다


def test_limit_samples_each_type_independently():
    # 유형이 같은 시드를 공유하면 쌍 수가 같은 두 유형이 같은 자리를 뽑아, 표본이 유형마다 짝을 맞춘 듯 겹친다
    rows = [pair("change_conversation", f"a{i:03d}", f"p{i:03d}") for i in range(100)]
    rows += [pair("change_issue", f"b{i:03d}", f"q{i:03d}") for i in range(100)]

    columns = tc.training_columns_by_type(rows, limit_per_type=10)

    picked_conv = [int(a[1:]) for a in columns["change_conversation"]["anchor"]]
    picked_issue = [int(a[1:]) for a in columns["change_issue"]["anchor"]]
    assert picked_conv != picked_issue


def test_limit_of_one_type_does_not_depend_on_other_types():
    # 유형마다 따로 뽑는다 — 다른 유형의 쌍이 늘거나 줄어도 이 유형의 표본이 달라지면 시험 실행을 재현할 수 없다
    conv = [pair("change_conversation", f"a{i:03d}", f"p{i:03d}") for i in range(100)]
    other = [pair("change_issue", f"b{i:03d}", f"q{i:03d}") for i in range(40)]

    alone = tc.training_columns_by_type(conv, limit_per_type=10)["change_conversation"]
    mixed = tc.training_columns_by_type(other[:7] + conv + other[7:], limit_per_type=10)["change_conversation"]

    assert alone == mixed


# ─── eval_inputs_by_type ───────────────────────────────────────────────────────


def test_same_anchor_becomes_one_query_with_every_positive_relevant():
    rows = [pair(anchor="리뷰가 가리킨 코드", positive="댓글 하나"), pair(anchor="리뷰가 가리킨 코드", positive="댓글 둘")]

    queries, corpus, relevant = tc.eval_inputs_by_type(rows)["change_conversation"]

    assert queries == {"change_conversation-q0": "리뷰가 가리킨 코드"}
    assert corpus == {"change_conversation-d0": "댓글 하나", "change_conversation-d1": "댓글 둘"}
    assert relevant == {"change_conversation-q0": {"change_conversation-d0", "change_conversation-d1"}}


def test_same_positive_becomes_one_corpus_entry_shared_by_queries():
    rows = [pair("change_issue", "PR 제목 하나", "같은 이슈"), pair("change_issue", "PR 제목 둘", "같은 이슈")]

    queries, corpus, relevant = tc.eval_inputs_by_type(rows)["change_issue"]

    assert corpus == {"change_issue-d0": "같은 이슈"}
    assert len(queries) == 2
    assert relevant == {"change_issue-q0": {"change_issue-d0"}, "change_issue-q1": {"change_issue-d0"}}


def test_ids_follow_first_appearance_order():
    rows = [pair(anchor="x", positive="p1"), pair(anchor="y", positive="p2"), pair(anchor="x", positive="p3")]

    queries, corpus, _ = tc.eval_inputs_by_type(rows)["change_conversation"]

    assert list(queries.items()) == [("change_conversation-q0", "x"), ("change_conversation-q1", "y")]
    assert list(corpus.items()) == [("change_conversation-d0", "p1"), ("change_conversation-d1", "p2"),
                                    ("change_conversation-d2", "p3")]


def test_corpus_holds_only_positives_of_the_same_type():
    # 다른 유형의 글이 후보에 섞이면 질문이 쉬워지거나 어려워져 유형 점수를 서로 견줄 수 없다
    rows = [
        pair("change_conversation", "코드", "리뷰 댓글"),
        pair("issue_conversation", "이슈", "이슈 댓글"),
        pair("change_issue", "PR", "이슈 본문"),
    ]

    inputs = tc.eval_inputs_by_type(rows)

    assert set(inputs["change_conversation"][1].values()) == {"리뷰 댓글"}
    assert set(inputs["issue_conversation"][1].values()) == {"이슈 댓글"}
    assert set(inputs["change_issue"][1].values()) == {"이슈 본문"}


def test_same_text_in_two_types_is_not_shared():
    rows = [pair("change_conversation", "a", "같은 글"), pair("issue_conversation", "b", "같은 글")]

    inputs = tc.eval_inputs_by_type(rows)

    assert inputs["change_conversation"][1] == {"change_conversation-d0": "같은 글"}
    assert inputs["issue_conversation"][1] == {"issue_conversation-d0": "같은 글"}


def test_types_without_pairs_are_absent_from_eval_inputs():
    inputs = tc.eval_inputs_by_type([pair("change_issue", "a", "p")])

    assert list(inputs) == ["change_issue"]


def test_eval_inputs_are_consistent_every_query_has_relevant_docs_that_exist():
    rows = [pair(anchor=f"a{i % 4}", positive=f"p{i % 6}") for i in range(30)]

    queries, corpus, relevant = tc.eval_inputs_by_type(rows)["change_conversation"]

    assert set(relevant) == set(queries)               # 정답이 없는 질의는 평가기가 조용히 건너뛴다
    assert all(relevant[q] and relevant[q] <= set(corpus) for q in queries)


def test_identical_duplicate_rows_do_not_double_count():
    rows = [pair(anchor="a", positive="p"), pair(anchor="a", positive="p")]

    queries, corpus, relevant = tc.eval_inputs_by_type(rows)["change_conversation"]

    assert len(queries) == 1 and len(corpus) == 1
    assert relevant["change_conversation-q0"] == {"change_conversation-d0"}


def test_eval_limit_is_deterministic_and_applies_to_pairs_per_type():
    rows = [pair("change_issue", f"a{i:02d}", f"p{i:02d}") for i in range(40)]

    first = tc.eval_inputs_by_type(rows, limit_per_type=6, seed=7)
    again = tc.eval_inputs_by_type(rows, limit_per_type=6, seed=7)

    assert first == again
    queries, corpus, relevant = first["change_issue"]
    assert len(queries) == 6 and len(corpus) == 6
    assert sum(len(docs) for docs in relevant.values()) == 6


# ─── 이름·평균 ──────────────────────────────────────────────────────────────────


def test_names_and_keys_match_what_the_evaluator_emits():
    # InformationRetrievalEvaluator는 지표 키를 "<name>_<유사도>_ndcg@10"으로 만든다 (cosine 모델 기준)
    assert tc.evaluator_name("change_issue") == "val_change_issue"
    assert tc.ndcg_key("change_issue") == "val_change_issue_cosine_ndcg@10"


def test_macro_mean_is_the_plain_average_of_type_scores():
    assert tc.macro_mean([0.2, 0.4, 0.9]) == pytest.approx(0.5)
    assert tc.macro_mean([1.0, 0.0]) == pytest.approx(0.5)


def test_macro_mean_accepts_any_iterable_and_a_single_value():
    assert tc.macro_mean(x for x in (0.25, 0.75)) == pytest.approx(0.5)
    assert tc.macro_mean({"a": 0.3}.values()) == pytest.approx(0.3)


def test_macro_mean_of_nothing_is_an_error():
    with pytest.raises(ValueError):
        tc.macro_mean([])


# ─── 학습 기록 ──────────────────────────────────────────────────────────────────

TYPES2 = ["change_conversation", "change_issue"]


def eval_entry(epoch, step, a, b):
    return {
        "eval_sequential_score": (a + b) / 2,
        f"eval_{tc.ndcg_key('change_conversation')}": a,
        f"eval_{tc.ndcg_key('change_issue')}": b,
        "epoch": epoch, "step": step,
    }


def test_epoch_records_pair_each_eval_with_the_mean_loss_since_the_last_eval():
    history = [
        {"loss": 2.0, "epoch": 0.3, "step": 10},
        {"loss": 1.0, "epoch": 0.6, "step": 20},
        eval_entry(1.0, 30, 0.4, 0.6),
        {"loss": 0.5, "epoch": 1.5, "step": 40},
        eval_entry(2.0, 60, 0.5, 0.7),
        {"train_runtime": 12.0, "train_loss": 1.1, "epoch": 2.0, "step": 60},   # 마지막 요약 줄은 loss가 아니다
    ]

    records = tc.epoch_records(history, TYPES2)

    assert [r["epoch"] for r in records] == [1.0, 2.0]
    assert [r["step"] for r in records] == [30, 60]
    assert [r["train_loss"] for r in records] == [pytest.approx(1.5), pytest.approx(0.5)]
    assert records[0]["by_type"] == {"change_conversation": 0.4, "change_issue": 0.6}
    assert records[1]["macro"] == pytest.approx(0.6)


def test_epoch_records_without_logged_loss_have_none():
    records = tc.epoch_records([eval_entry(1.0, 5, 0.1, 0.3)], TYPES2)

    assert records[0]["train_loss"] is None


def test_epoch_records_round_the_epoch_to_two_decimals():
    # 배치 수가 어긋나면 에폭 끝의 epoch가 0.9993처럼 찍힌다
    assert tc.epoch_records([eval_entry(0.9993, 5, 0.1, 0.3)], TYPES2)[0]["epoch"] == 1.0


def test_best_epoch_is_the_first_epoch_with_the_best_score():
    records = tc.epoch_records([
        eval_entry(1.0, 10, 0.4, 0.4), eval_entry(2.0, 20, 0.6, 0.6), eval_entry(3.0, 30, 0.5, 0.5),
    ], TYPES2)

    assert tc.pick_best_epoch(records, 0.6) == 2
    assert tc.pick_best_epoch(records, None) is None
    assert tc.pick_best_epoch(records, 0.99) is None


def test_best_epoch_prefers_the_earlier_epoch_on_ties():
    records = tc.epoch_records([eval_entry(1.0, 10, 0.5, 0.5), eval_entry(2.0, 20, 0.5, 0.5)], TYPES2)

    assert tc.pick_best_epoch(records, 0.5) == 1


# ─── summarize_run ─────────────────────────────────────────────────────────────


def write_metrics(run_dir, variant, macro, *, base_macro=0.1, status="ok", best_epoch=2):
    by_type = {t: macro for t in tc.TYPES}
    doc = {
        "status": status,
        "variant": variant,
        "n_train": {t: 100 for t in tc.TYPES},
        "base": {"by_type": {t: base_macro for t in tc.TYPES}, "macro": base_macro, "details": {"x": 1.0}},
        "final": {"by_type": by_type, "macro": macro, "details": {"x": 1.0}},
        "best_epoch": best_epoch,
    }
    folder = run_dir / variant
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "metrics.json").write_text(json.dumps(doc), encoding="utf-8")


def write_failed(run_dir, variant, text="OSError: boom\n\nTraceback ..."):
    folder = run_dir / variant
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "FAILED").write_text(text, encoding="utf-8")


def test_summary_picks_the_highest_final_macro(tmp_path):
    write_metrics(tmp_path, "all", 0.50)
    write_metrics(tmp_path, "ko", 0.70)
    write_metrics(tmp_path, "pre2025", 0.60)

    summary = tc.summarize_run(str(tmp_path))

    assert summary["chosen"] == "ko"
    assert summary["variants"]["ko"]["final"]["macro"] == pytest.approx(0.70)
    assert summary["variants"]["ko"]["best_epoch"] == 2
    assert summary["variants"]["ko"]["n_train"] == {t: 100 for t in tc.TYPES}


def test_summary_chooses_by_final_macro_not_by_base(tmp_path):
    write_metrics(tmp_path, "all", 0.50, base_macro=0.45)   # base가 가장 높아도 final이 낮으면 지는 것이다
    write_metrics(tmp_path, "ko", 0.70, base_macro=0.10)

    assert tc.summarize_run(str(tmp_path))["chosen"] == "ko"


def test_summary_tie_goes_to_the_earlier_variant_in_variants_order(tmp_path):
    write_metrics(tmp_path, "all", 0.60)
    write_metrics(tmp_path, "ko", 0.60)
    write_metrics(tmp_path, "pre2025", 0.60)

    assert tc.summarize_run(str(tmp_path))["chosen"] == "all"


def test_summary_tie_between_later_variants_goes_to_ko(tmp_path):
    write_failed(tmp_path, "all")
    write_metrics(tmp_path, "ko", 0.60)
    write_metrics(tmp_path, "pre2025", 0.60)

    assert tc.summarize_run(str(tmp_path))["chosen"] == "ko"


def test_summary_tie_rule_does_not_depend_on_the_order_of_the_variants_argument(tmp_path):
    write_metrics(tmp_path, "all", 0.60)
    write_metrics(tmp_path, "pre2025", 0.60)

    summary = tc.summarize_run(str(tmp_path), variants=("pre2025", "ko", "all"))

    assert summary["chosen"] == "all"


def test_failed_variant_is_never_chosen_even_with_a_higher_looking_metrics_file(tmp_path):
    write_metrics(tmp_path, "all", 0.99)
    write_failed(tmp_path, "all", "RuntimeError: 이전 시도의 metrics.json이 남아 있다\n")
    write_metrics(tmp_path, "ko", 0.40)

    summary = tc.summarize_run(str(tmp_path))

    assert summary["variants"]["all"]["status"] == "failed"
    assert summary["variants"]["all"]["reason"] == "RuntimeError: 이전 시도의 metrics.json이 남아 있다"
    assert summary["chosen"] == "ko"


def test_failed_reason_is_the_first_non_empty_line_truncated(tmp_path):
    write_failed(tmp_path, "all", "\n\n" + "x" * 500 + "\nsecond line")

    reason = tc.summarize_run(str(tmp_path), variants=("all",))["variants"]["all"]["reason"]

    assert reason == "x" * 300


def test_missing_variant_is_marked_missing(tmp_path):
    write_metrics(tmp_path, "ko", 0.60)

    summary = tc.summarize_run(str(tmp_path))

    assert summary["variants"]["all"] == {"status": "missing"}
    assert summary["variants"]["pre2025"] == {"status": "missing"}
    assert summary["chosen"] == "ko"


def test_metrics_with_a_status_other_than_ok_counts_as_failed(tmp_path):
    write_metrics(tmp_path, "all", 0.90, status="running")

    entry = tc.summarize_run(str(tmp_path), variants=("all",))["variants"]["all"]

    assert entry["status"] == "failed"
    assert "running" in entry["reason"]


def test_unreadable_metrics_file_counts_as_failed(tmp_path):
    (tmp_path / "all").mkdir()
    (tmp_path / "all" / "metrics.json").write_text('{"status": "ok", "fina', encoding="utf-8")   # 쓰다 만 파일

    entry = tc.summarize_run(str(tmp_path), variants=("all",))["variants"]["all"]

    assert entry["status"] == "failed"


def test_all_failed_has_no_choice_and_no_base(tmp_path):
    write_failed(tmp_path, "all")
    write_failed(tmp_path, "ko")

    summary = tc.summarize_run(str(tmp_path))

    assert summary["chosen"] is None
    assert summary["base"] is None
    assert summary["variants"]["pre2025"]["status"] == "missing"


def test_base_comes_from_the_first_successful_variant(tmp_path):
    write_failed(tmp_path, "all")
    write_metrics(tmp_path, "ko", 0.60, base_macro=0.31)
    write_metrics(tmp_path, "pre2025", 0.70, base_macro=0.32)

    summary = tc.summarize_run(str(tmp_path))

    assert summary["base"]["macro"] == pytest.approx(0.31)
    assert set(summary["base"]) == {"by_type", "macro"}   # details까지 summary에 싣지 않는다


def test_summary_of_unknown_variant_is_an_error(tmp_path):
    with pytest.raises(ValueError):
        tc.summarize_run(str(tmp_path), variants=("all", "nope"))


def test_format_summary_names_the_choice_failures_and_gain(tmp_path):
    write_metrics(tmp_path, "all", 0.50, base_macro=0.40)
    write_failed(tmp_path, "ko", "CUDA out of memory")

    text = tc.format_summary(tc.summarize_run(str(tmp_path)))

    assert "선택: all" in text and "+0.1000" in text
    assert "ko: 실패 — CUDA out of memory" in text
    assert "pre2025: 결과가 없습니다" in text
    assert "(base)" in text


def test_format_summary_without_a_choice(tmp_path):
    write_failed(tmp_path, "all")

    text = tc.format_summary(tc.summarize_run(str(tmp_path), variants=("all",)))

    assert "선택: 없음" in text


# ─── 파일 ──────────────────────────────────────────────────────────────────────


def test_read_pairs_skips_blank_lines_and_keeps_korean(tmp_path):
    path = tmp_path / "p.jsonl"
    path.write_text('{"anchor": "한글"}\n\n{"anchor": "b"}\n', encoding="utf-8")

    assert tc.read_pairs(str(path)) == [{"anchor": "한글"}, {"anchor": "b"}]


def test_read_pairs_of_missing_file_names_the_path(tmp_path):
    with pytest.raises(FileNotFoundError) as exc:
        tc.read_pairs(str(tmp_path / "nope.jsonl"))

    assert "nope.jsonl" in str(exc.value)


def test_write_json_atomic_replaces_the_file_and_leaves_no_temp(tmp_path):
    path = tmp_path / "metrics.json"
    path.write_text("old", encoding="utf-8")

    tc.write_json_atomic(str(path), {"status": "ok", "메모": "한글"})

    assert json.loads(path.read_text(encoding="utf-8")) == {"status": "ok", "메모": "한글"}
    assert "한글" in path.read_text(encoding="utf-8")   # ensure_ascii=False — 사람이 열어 읽는 파일이다
    assert os.listdir(tmp_path) == ["metrics.json"]


def test_sha256_file_matches_known_digest_and_spans_chunks(tmp_path):
    small = tmp_path / "abc"
    small.write_bytes(b"abc")
    big = tmp_path / "big"
    payload = os.urandom((1 << 20) * 2 + 123)    # 읽기 단위(1MiB)를 넘는 크기
    big.write_bytes(payload)

    assert tc.sha256_file(str(small)) == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    assert tc.sha256_file(str(big)) == hashlib.sha256(payload).hexdigest()


# ─── train.py: 인자 해석과 summarize ────────────────────────────────────────────


def test_train_defaults_match_the_decided_configuration():
    args = train.parse_args(["train", "--variant", "all", "--out", "o"])

    assert (args.epochs, args.batch_size, args.mini_batch_size) == (3, 128, 32)
    assert (args.lr, args.warmup, args.max_seq_length, args.seed) == (2e-5, 0.1, 512, 42)
    assert args.device == "auto"
    assert args.limit_train is None and args.limit_val is None
    assert args.model == tc.DEFAULT_MODEL


def test_revision_defaults_to_the_pinned_hash_only_for_the_default_model():
    default = train.parse_args(["train", "--variant", "all", "--out", "o"])
    other = train.parse_args(["train", "--variant", "all", "--out", "o", "--model", "sentence-transformers/all-MiniLM-L6-v2"])

    assert default.revision == tc.DEFAULT_REVISION
    assert other.revision is None    # Qwen의 해시를 다른 모델에 붙이면 받기가 실패한다


def test_explicit_revision_wins_and_empty_string_means_no_revision():
    explicit = train.parse_args(["train", "--variant", "all", "--out", "o", "--revision", "abc123"])
    empty = train.parse_args(["train", "--variant", "all", "--out", "o", "--revision", ""])

    assert explicit.revision == "abc123"
    assert empty.revision is None    # gpu_run.sh가 REVISION=""일 때 넘기는 값


def test_train_requires_a_known_variant_and_out():
    with pytest.raises(SystemExit):
        train.parse_args(["train", "--out", "o"])
    with pytest.raises(SystemExit):
        train.parse_args(["train", "--variant", "nope", "--out", "o"])
    with pytest.raises(SystemExit):
        train.parse_args(["train", "--variant", "all"])


@pytest.mark.parametrize("flag", ["--limit-train", "--limit-val", "--epochs", "--batch-size", "--mini-batch-size"])
def test_counts_must_be_positive(flag):
    with pytest.raises(SystemExit):
        train.parse_args(["train", "--variant", "all", "--out", "o", flag, "0"])


def test_summarize_writes_summary_json_and_prints_the_table(tmp_path, capsys):
    write_metrics(tmp_path, "all", 0.55)
    write_metrics(tmp_path, "ko", 0.65)

    code = train.main(["summarize", "--run-dir", str(tmp_path), "--variants", "all,ko"])

    assert code == 0
    summary = json.loads((tmp_path / "summary.json").read_text(encoding="utf-8"))
    assert summary["chosen"] == "ko" and list(summary["variants"]) == ["all", "ko"]
    assert "선택: ko" in capsys.readouterr().out


def test_summarize_exits_1_when_no_variant_succeeded(tmp_path, capsys):
    write_failed(tmp_path, "all")

    code = train.main(["summarize", "--run-dir", str(tmp_path), "--variants", "all"])

    assert code == 1
    assert json.loads((tmp_path / "summary.json").read_text(encoding="utf-8"))["chosen"] is None   # gpu_run.sh가 이 값을 읽는다
    assert "선택: 없음" in capsys.readouterr().out


def test_summarize_rejects_unknown_variants_and_missing_run_dir(tmp_path):
    with pytest.raises(SystemExit):
        train.main(["summarize", "--run-dir", str(tmp_path), "--variants", "all,nope"])
    with pytest.raises(SystemExit):
        train.main(["summarize", "--run-dir", str(tmp_path / "nope")])


def test_importing_train_and_summarizing_does_not_load_the_heavy_libraries(tmp_path):
    write_metrics(tmp_path, "all", 0.5)
    code = (
        "import sys; sys.path.insert(0, sys.argv[1]); import train; "
        "train.main(['summarize', '--run-dir', sys.argv[2], '--variants', 'all']); "
        "print(sorted(m for m in ('torch', 'sentence_transformers', 'datasets', 'transformers') if m in sys.modules))"
    )

    result = subprocess.run([sys.executable, "-I", "-c", code, os.path.abspath(EMBEDDING_FINETUNE_DIR), str(tmp_path)],
                            capture_output=True, text=True, encoding="utf-8")

    assert result.returncode == 0, result.stderr
    assert result.stdout.strip().splitlines()[-1] == "[]"
