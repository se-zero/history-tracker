"""slack_filter_eval.py 순수 함수 단위 테스트 (오프라인 — 네트워크·파일 I/O 불필요).

다수결·판정 비교·혼동행렬은 측정 하네스의 계측기다. 여기서 방향(동률 처리, 양성=보존)이나
경계가 틀리면 프롬프트 변경의 개선/악화 판정 자체가 조용히 뒤집힌다.

실행: services/ai-engine/.venv/Scripts/python.exe -m pytest eval/tests
"""

import os
import sys
from datetime import datetime

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

from slack_filter_eval import (  # noqa: E402
    confusion,
    find_flips,
    flip_analysis,
    majority_verdict,
    material_from_snapshot,
    metrics,
    thread_context,
    unstable_urls,
)


# ─── majority_verdict — 다수결·짝수 동률 보존 ──────────────────────────────────


def test_majority_verdict_odd_runs_uses_majority_vote():
    runs = [{"a": True}, {"a": True}, {"a": False}]
    assert majority_verdict(runs) == {"a": True}


def test_majority_verdict_even_tie_keeps():
    # 동률(1보존/1제거)이면 보존(True) — 과삭제보다 과보존이 안전한 기본값
    runs = [{"a": True}, {"a": False}]
    assert majority_verdict(runs) == {"a": True}


# ─── unstable_urls — 런 간 판정이 갈린 url 추출 ────────────────────────────────


def test_unstable_urls_extracts_only_disagreeing_urls():
    runs = [{"a": True, "b": True}, {"a": True, "b": False}]
    assert unstable_urls(runs) == ["b"]


def test_unstable_urls_single_run_is_empty():
    assert unstable_urls([{"a": True}]) == []


# ─── find_flips — base/new majority 차이 ───────────────────────────────────────


def test_find_flips_only_common_urls_that_differ():
    base = {"a": True, "b": False, "c": True}
    new = {"a": True, "b": True, "d": False}
    # c/d는 한쪽에만 있어 비교 대상이 아니고, a는 같으므로 제외 — b만 뒤집힘
    assert find_flips(base, new) == ["b"]


# ─── confusion / metrics — 혼동행렬(보존=양성)과 네 지표 ───────────────────────


def test_confusion_and_metrics_hand_computed():
    labels = {"a": "keep", "b": "keep", "c": "keep", "d": "remove", "e": "remove"}
    # e는 이 결과 파일에 판정이 없음 (skipped)
    verdicts = {"a": True, "b": True, "c": False, "d": False}

    cm = confusion(labels, verdicts)
    assert cm == {"tp": 2, "fp": 0, "fn": 1, "tn": 1, "skipped": 1}

    m = metrics(cm)
    assert m["accuracy"] == 0.75
    assert m["precision"] == 1.0
    assert round(m["recall"], 4) == 0.6667
    assert m["specificity"] == 1.0


def test_metrics_handles_zero_denominator_as_none():
    cm = {"tp": 0, "fp": 0, "fn": 0, "tn": 0, "skipped": 0}
    m = metrics(cm)
    assert m == {"accuracy": None, "precision": None, "recall": None, "specificity": None}


# ─── flip_analysis — 개선/악화 분류 ────────────────────────────────────────────


def test_flip_analysis_classifies_improved_and_regressed():
    labels = {"a": "keep", "b": "keep", "c": "remove", "d": "remove", "e": "keep"}
    base = {"a": True, "b": False, "c": True, "d": False, "e": True}
    new = {"a": True, "b": True, "c": False, "d": False, "e": False}

    result = flip_analysis(labels, base, new)
    # b: remove→keep(정답) 개선 / c: keep→remove(정답) 개선 / e: keep→remove(오답) 악화
    assert result == {"improved": ["b", "c"], "regressed": ["e"]}


# ─── thread_context — 앞뒤 k건, 경계에서 잘림 ──────────────────────────────────


def test_thread_context_trims_at_boundary():
    messages = [
        {"url": "u1", "conversation_id": "c1", "occurred_at": datetime(2026, 7, 5, 0, 0), "body": "b1"},
        {"url": "u2", "conversation_id": "c1", "occurred_at": datetime(2026, 7, 5, 0, 1), "body": "b2"},
        {"url": "u3", "conversation_id": "c1", "occurred_at": datetime(2026, 7, 5, 0, 2), "body": "b3"},
        {"url": "u4", "conversation_id": "c1", "occurred_at": datetime(2026, 7, 5, 0, 3), "body": "b4"},
    ]
    # u2는 앞에 1건뿐이라 k=2를 요청해도 뒤로만 2건 채워지고 앞은 1건에서 잘린다
    ctx = thread_context(messages, "u2", k=2)
    assert ctx == ["[-1] b1", "[+0] b2", "[+1] b3", "[+2] b4"]


def test_thread_context_ignores_other_conversations():
    messages = [
        {"url": "u1", "conversation_id": "c1", "occurred_at": datetime(2026, 7, 5, 0, 0), "body": "b1"},
        {"url": "u2", "conversation_id": "c2", "occurred_at": datetime(2026, 7, 5, 0, 1), "body": "other"},
    ]
    ctx = thread_context(messages, "u1", k=2)
    assert ctx == ["[+0] b1"]


# ─── material_from_snapshot — 스냅샷 jsonl → project_profile material 모양 변환 ─────


def test_material_from_snapshot_builds_expected_shape_and_recency_order():
    records = [
        {
            "nodeType": "PullRequest",
            "occurredAt": "2026-07-05T10:00:00Z",
            "properties": {"url": "https://github.com/acme/payflow/pull/1", "title": "PR 최신"},
        },
        {
            "nodeType": "PullRequest",
            "occurredAt": "2026-07-04T10:00:00Z",
            "properties": {"url": "https://github.com/acme/payflow/pull/2", "title": "PR 과거"},
        },
        {
            "nodeType": "Issue",
            "occurredAt": "2026-07-05T08:00:00Z",
            "properties": {"title": "버그 수정", "issue_type": "bug"},
        },
        {
            "nodeType": "ChangeSet",
            "occurredAt": "2026-07-05T09:00:00Z",
            "properties": {
                "message": "fix: 타임아웃\n부연설명",
                "files": [{"path": "services/ai-engine/graph/a.py"}],
            },
        },
        {
            "nodeType": "ChangeSet",
            "occurredAt": "2026-07-03T09:00:00Z",
            "properties": {
                "message": "feat: 결제 재시도",
                "files": [
                    {"path": "services/ai-engine/graph/b.py"},
                    {"path": "services/backend/src/Foo.java"},
                ],
            },
        },
        {
            "nodeType": "Document",
            "occurredAt": "2026-07-05T07:00:00Z",
            "properties": {"title": "아키텍처 문서"},
        },
    ]

    material = material_from_snapshot(records)

    # 최신순(occurredAt DESC) 정렬 확인
    assert material["pr_titles"] == ["PR 최신", "PR 과거"]
    assert material["commit_messages"] == ["fix: 타임아웃", "feat: 결제 재시도"]

    assert material["repo_names"] == ["acme/payflow"]
    assert material["issue_titles"] == ["[bug] 버그 수정"]
    assert material["top_dirs"] == ["services/ai-engine", "services/backend"]
    assert material["document_titles"] == ["아키텍처 문서"]


def test_material_from_snapshot_mixes_latest_and_oldest_half_when_over_limit():
    # PR 한도(20)보다 많은 24건 — 최신 10건 + 최초 10건 순서로 20건이 남아야 한다
    records = [
        {
            "nodeType": "PullRequest",
            "occurredAt": f"2026-07-{i:02d}T00:00:00Z",
            "properties": {
                "url": f"https://github.com/acme/payflow/pull/{i}",
                "title": f"PR-{i:02d}",
            },
        }
        for i in range(1, 25)
    ]

    material = material_from_snapshot(records)

    expected = [f"PR-{i:02d}" for i in range(24, 14, -1)] + [f"PR-{i:02d}" for i in range(1, 11)]
    assert material["pr_titles"] == expected
