"""evidence → 서브그래프 변환의 순수 헬퍼 단위 테스트 (오프라인).

Neo4j 없이 도는 부분만 검증한다 — evidence를 타입별 키로 그룹핑하는
`_group_evidence_keys`와, 조회된 노드 행을 evidence 순서대로 elementId에
정렬하는 `_resolve_seed_ids`. 실제 Cypher resolve는 live Neo4j(integration) 영역.
"""

from graph.overview import (
    _group_evidence_keys,
    _normalize_evidence,
    _resolve_seed_ids,
)


def test_normalize_evidence_rules():
    # 정상 — type별 정규화된 (type, key)
    assert _normalize_evidence({"type": "commit", "id": "abc1234"}) == ("commit", "abc1234")
    assert _normalize_evidence({"type": "pull_request", "id": "#42"}) == ("pull_request", "42")
    assert _normalize_evidence({"type": "pull_request", "id": "99"}) == ("pull_request", "99")
    assert _normalize_evidence({"type": "issue", "id": " HT-37 "}) == ("issue", "HT-37")
    assert _normalize_evidence({"type": "message", "id": "1700000000.123"}) == ("message", "1700000000.123")
    # 무효 — None (group/resolve가 동일 규칙을 공유하므로 drift 불가)
    assert _normalize_evidence({"type": "commit", "id": "  "}) is None
    assert _normalize_evidence({"type": "pull_request", "id": "#notanum"}) is None
    assert _normalize_evidence({"type": "weird", "id": "x"}) is None


def test_group_evidence_keys_buckets_by_type():
    evidence = [
        {"type": "commit", "id": "abc1234"},
        {"type": "pull_request", "id": "#42"},
        {"type": "pull_request", "id": "99"},
        {"type": "issue", "id": "HT-37"},
        {"type": "message", "id": "1700000000.123"},
    ]
    keys = _group_evidence_keys(evidence)
    assert keys["commit_prefixes"] == ["abc1234"]
    assert keys["pr_numbers"] == [42, 99]
    assert keys["jira_keys"] == ["HT-37"]
    assert keys["conv_ids"] == ["1700000000.123"]


def test_group_evidence_keys_skips_blank_and_unknown():
    evidence = [
        {"type": "commit", "id": "  "},
        {"type": "pull_request", "id": "#notanum"},
        {"type": "weird", "id": "x"},
        {"type": "issue", "id": ""},
    ]
    keys = _group_evidence_keys(evidence)
    assert keys == {
        "commit_prefixes": [],
        "pr_numbers": [],
        "jira_keys": [],
        "conv_ids": [],
    }


def test_resolve_seed_ids_aligns_to_evidence_order():
    rows = [
        {"id": "n1", "label": "ChangeSet", "hash": "abc1234def"},
        {"id": "n2", "label": "PullRequest", "pr_number": 42},
        {"id": "n3", "label": "Issue", "jira_key": "HT-37"},
        {"id": "n4", "label": "Communication", "conversation_id": "1700000000.123"},
        {"id": "n5", "label": "Actor", "name": "neighbor (무시)"},
    ]
    evidence = [
        {"type": "issue", "id": "HT-37"},
        {"type": "commit", "id": "abc1234"},
        {"type": "message", "id": "1700000000.123"},
        {"type": "pull_request", "id": "#42"},
    ]
    assert _resolve_seed_ids(evidence, rows) == ["n3", "n1", "n4", "n2"]


def test_resolve_seed_ids_unresolved_is_none():
    rows = [{"id": "n1", "label": "ChangeSet", "hash": "abc1234def"}]
    evidence = [
        {"type": "issue", "id": "HT-99"},
        {"type": "commit", "id": "abc1234"},
    ]
    assert _resolve_seed_ids(evidence, rows) == [None, "n1"]
