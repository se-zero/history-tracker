"""edge_eval.grade_recall 순수 로직 단위 테스트 (오프라인 — Neo4j 불필요).

recall 채점기도 측정 계측기라, hit/miss 분류·타입별 집계·무효 쌍 분리의 조용한 버그가
모든 recall 숫자를 소리 없이 오염시킨다. 그래프 질의 edge_exists()를 합성 함수로 대체해
DB 없이 채점 경로를 고정한다.

실행: services/ai-engine/.venv/Scripts/python.exe -m pytest eval/tests
"""

import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

import edge_eval


# dst에 'HIT'가 들어간 쌍만 그래프에 존재(conf 0.42)한다고 본뜬 합성 응답.
PAIRS = [
    {"edge_type": "REFERENCE", "src": {"id": "aaaaaaa"}, "dst": {"id": "HIT1"}},    # hit
    {"edge_type": "REFERENCE", "src": {"id": "bbbbbbb"}, "dst": {"id": "miss1"}},   # miss
    {"edge_type": "DISCUSSED_IN", "src": {"id": "HT-1"}, "dst": {"id": "HIT2"}},    # hit
    {"edge_type": "TRIGGERED_BY", "src": {"id": "ccccccc"}, "dst": {"id": "HT-9"}}, # miss
    {"edge_type": "BOGUS", "src": {"id": "x"}, "dst": {"id": "y"}},                 # 무효: 미지원 타입
    {"edge_type": "REFERENCE", "src": {"id": None}, "dst": {"id": "HIT3"}},         # 무효: src id 누락
]


def _fake_exists(session, pid, edge_type, src, dst):
    return 0.42 if "HIT" in (dst or "") else None


def _grade(monkeypatch, pairs=PAIRS):
    monkeypatch.setattr(edge_eval, "edge_exists", _fake_exists)
    return edge_eval.grade_recall(session=None, pid="p", pairs=pairs)


def test_overall_and_by_type(monkeypatch):
    rec = _grade(monkeypatch)
    # 유효 쌍 4개 중 2개 hit → 0.5. 무효 2개(미지원 타입·id 누락)는 분모에서 제외.
    assert rec["overall"] == {"recall": 0.5, "hits": 2, "misses": 2, "total": 4}
    assert rec["by_type"]["REFERENCE"]["recall"] == 0.5
    assert rec["by_type"]["DISCUSSED_IN"]["recall"] == 1.0
    assert rec["by_type"]["TRIGGERED_BY"]["recall"] == 0.0


def test_miss_list_kept_as_improvement_target(monkeypatch):
    rec = _grade(monkeypatch)
    # precision과 달리 miss는 버리지 않고 목록으로 남긴다 (연결됐어야 하는데 없음 = 개선 타깃).
    assert len(rec["miss_list"]) == 2
    assert any("bbbbbbb->miss1" in m for m in rec["miss_list"])
    assert any("ccccccc->HT-9" in m for m in rec["miss_list"])


def test_invalid_pairs_excluded_from_denominator(monkeypatch):
    rec = _grade(monkeypatch)
    assert len(rec["invalid"]) == 2          # 미지원 edge_type + src id 누락
    assert rec["pairs_total"] == 6           # 원본 개수는 그대로 보존
    assert rec["overall"]["total"] == 4      # 분모는 유효 쌍만


def test_empty_pairs_recall_is_none_not_zero_division(monkeypatch):
    rec = _grade(monkeypatch, pairs=[])
    assert rec["overall"]["recall"] is None  # 분모 0 → None (ZeroDivision 방지)
    assert rec["overall"]["total"] == 0
