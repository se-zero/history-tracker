"""REFERENCE 엣지 빌더 단위 테스트 (오프라인 — ReferenceStore mock 주입).

한 커밋의 여러 파일이 같은 메시지에 매칭될 때 confidence가 "마지막에 계산된 값"이 아니라
파일 간 최고 점수로 집계되는지, 그리고 커밋당 상위 k개 스레드만 남는지(fan-out 컷)를 검증한다.
임계값·시간 윈도우·프로젝트 격리는 변경이 깨뜨리지 않았는지 확인하는 회귀 케이스다.
"""

import asyncio
import math
import unittest
from datetime import datetime, timedelta, timezone

from graph.reference_builder import DEFAULT_TOP_K, ReferenceStore, build_reference_edges

NOW = datetime(2026, 7, 1, tzinfo=timezone.utc)


def _vec(sim: float) -> list[float]:
    """기준 벡터 [1,0]과의 코사인 유사도가 정확히 sim인 단위 벡터."""
    return [sim, math.sqrt(1.0 - sim * sim)]


def _modified(changeset_id, file_path, embedding, project_id="p1", occurred_at=NOW):
    return {
        "project_id": project_id,
        "changeset_id": changeset_id,
        "file_path": file_path,
        "diff_summary": f"{file_path} 변경",
        "embedding": embedding,
        "occurred_at": occurred_at,
    }


def _comm(comm_id, embedding, project_id="p1", occurred_at=NOW, conversation_id=None):
    return {
        "project_id": project_id,
        "id": comm_id,
        "conversation_id": conversation_id or comm_id,   # 기본값: 자기 자신이 스레드
        "body": f"{comm_id} 본문",
        "embedding": embedding,
        "occurred_at": occurred_at,
    }


class _FakeStore:
    """ReferenceStore 콜백 대역 — create_reference_edge 호출을 그대로 기록한다."""

    def __init__(self, modified, comms):
        self.modified = modified
        self.comms = comms
        self.created: list[tuple[str, str, str, float]] = []

    def as_store(self) -> ReferenceStore:
        async def fetch_modified():
            return self.modified

        async def fetch_comms():
            return self.comms

        async def create_edge(project_id, changeset_id, comm_id, confidence):
            self.created.append((project_id, changeset_id, comm_id, confidence))

        async def unsupported(*args):
            raise AssertionError("이 테스트에서 호출되지 않아야 한다")

        return ReferenceStore(
            fetch_modified_embeddings=fetch_modified,
            fetch_communication_embeddings=fetch_comms,
            create_reference_edge=create_edge,
            fetch_unembedded_communications=unsupported,
            save_communication_embedding=unsupported,
        )


class ConfidenceAggregationTest(unittest.TestCase):
    """(changeset, communication) 쌍당 엣지 1개 · confidence는 파일 간 max."""

    def test_same_pair_from_multiple_files_writes_max_score_once(self):
        # 같은 커밋의 두 파일이 같은 메시지에 매칭 — 유사도 1.0(정확 일치)과 0.8
        fake = _FakeStore(
            modified=[
                _modified("c1", "a.py", [1.0, 0.0]),
                _modified("c1", "b.py", [0.8, 0.6]),
            ],
            comms=[_comm("m1", [1.0, 0.0])],
        )

        created = asyncio.run(build_reference_edges(fake.as_store(), threshold=0.3))

        self.assertEqual(created, 1)                    # 쌍당 1회 — 파일 수만큼 쓰지 않는다
        self.assertEqual(len(fake.created), 1)
        project_id, changeset_id, comm_id, confidence = fake.created[0]
        self.assertEqual((project_id, changeset_id, comm_id), ("p1", "c1", "m1"))
        self.assertAlmostEqual(confidence, 1.0, places=5)   # 0.8(마지막 값)이 아니라 최고 점수

    def test_lower_scoring_file_listed_last_does_not_overwrite(self):
        # 순서를 뒤집어도 결과가 같아야 한다 — "마지막에 쓴 값"이 남지 않는다는 확인
        fake = _FakeStore(
            modified=[
                _modified("c1", "b.py", [0.8, 0.6]),
                _modified("c1", "a.py", [1.0, 0.0]),
            ],
            comms=[_comm("m1", [1.0, 0.0])],
        )

        asyncio.run(build_reference_edges(fake.as_store(), threshold=0.3))

        self.assertEqual(len(fake.created), 1)
        self.assertAlmostEqual(fake.created[0][3], 1.0, places=5)

    def test_distinct_pairs_each_get_an_edge(self):
        # 커밋·메시지가 다르면 각각 별도 엣지 (집계가 쌍을 뭉개지 않는다)
        fake = _FakeStore(
            modified=[
                _modified("c1", "a.py", [1.0, 0.0]),
                _modified("c2", "b.py", [0.0, 1.0]),
            ],
            comms=[_comm("m1", [1.0, 0.0]), _comm("m2", [0.0, 1.0])],
        )

        created = asyncio.run(build_reference_edges(fake.as_store(), threshold=0.9))

        self.assertEqual(created, 2)
        self.assertEqual(
            {(cs, comm) for _, cs, comm, _ in fake.created},
            {("c1", "m1"), ("c2", "m2")},
        )


class TopKThreadCapTest(unittest.TestCase):
    """커밋당 상위 k개 스레드만 유지 (fan-out 컷).

    자르는 단위가 메시지가 아니라 스레드인 이유: 수다스러운 스레드 하나가 k칸을 독식하면
    다른 스레드의 진짜 배경 논의가 밀려난다. 골든 쌍 조회에서도 진짜 스레드는 커밋 기준
    1~4위(스레드 단위)에 들어와 k=4가 전원을 살린다.
    """

    def test_keeps_only_top_k_threads_per_commit(self):
        # 한 커밋이 5개 스레드에 매칭 — top_k=3이면 상위 3개만 남는다
        fake = _FakeStore(
            modified=[_modified("c1", "a.py", [1.0, 0.0])],
            comms=[
                _comm("m1", _vec(0.90), conversation_id="t1"),
                _comm("m2", _vec(0.80), conversation_id="t2"),
                _comm("m3", _vec(0.70), conversation_id="t3"),
                _comm("m4", _vec(0.60), conversation_id="t4"),
                _comm("m5", _vec(0.50), conversation_id="t5"),
            ],
        )

        created = asyncio.run(build_reference_edges(fake.as_store(), threshold=0.3, top_k=3))

        self.assertEqual(created, 3)
        self.assertEqual({comm for _, _, comm, _ in fake.created}, {"m1", "m2", "m3"})

    def test_chatty_thread_takes_one_slot_not_many(self):
        # t1의 메시지 3개는 슬롯 1개만 차지한다 — top_k=2면 t1 전체 + 차순위 스레드 t2가 산다
        fake = _FakeStore(
            modified=[_modified("c1", "a.py", [1.0, 0.0])],
            comms=[
                _comm("m1a", _vec(0.90), conversation_id="t1"),
                _comm("m1b", _vec(0.85), conversation_id="t1"),
                _comm("m1c", _vec(0.80), conversation_id="t1"),
                _comm("m2", _vec(0.75), conversation_id="t2"),
                _comm("m3", _vec(0.70), conversation_id="t3"),
            ],
        )

        created = asyncio.run(build_reference_edges(fake.as_store(), threshold=0.3, top_k=2))

        self.assertEqual(created, 4)   # t1의 3개 + t2의 1개
        self.assertEqual({comm for _, _, comm, _ in fake.created}, {"m1a", "m1b", "m1c", "m2"})

    def test_thread_rank_uses_best_message_in_thread(self):
        # t2의 최고 메시지(0.95)가 t1의 최고(0.90)보다 높으면 t2가 1위 — 스레드의 대표값은 최고점
        fake = _FakeStore(
            modified=[_modified("c1", "a.py", [1.0, 0.0])],
            comms=[
                _comm("m1", _vec(0.90), conversation_id="t1"),
                _comm("m2a", _vec(0.40), conversation_id="t2"),
                _comm("m2b", _vec(0.95), conversation_id="t2"),
            ],
        )

        created = asyncio.run(build_reference_edges(fake.as_store(), threshold=0.3, top_k=1))

        self.assertEqual(created, 2)   # t2만 살고, t2의 저점 메시지도 함께 남는다
        self.assertEqual({comm for _, _, comm, _ in fake.created}, {"m2a", "m2b"})

    def test_cap_is_per_commit(self):
        # 캡은 커밋별로 독립 — c1이 다 썼다고 c2의 몫이 줄지 않는다
        fake = _FakeStore(
            modified=[
                _modified("c1", "a.py", [1.0, 0.0]),
                _modified("c2", "b.py", [1.0, 0.0]),
            ],
            comms=[
                _comm("m1", _vec(0.90), conversation_id="t1"),
                _comm("m2", _vec(0.80), conversation_id="t2"),
            ],
        )

        created = asyncio.run(build_reference_edges(fake.as_store(), threshold=0.3, top_k=1))

        self.assertEqual(created, 2)
        self.assertEqual({(cs, comm) for _, cs, comm, _ in fake.created}, {("c1", "m1"), ("c2", "m1")})

    def test_default_top_k_is_applied(self):
        # 파라미터를 안 주면 DEFAULT_TOP_K(=4)가 적용된다 — 무제한이 아니다
        fake = _FakeStore(
            modified=[_modified("c1", "a.py", [1.0, 0.0])],
            comms=[_comm(f"m{i}", _vec(0.9 - i * 0.05), conversation_id=f"t{i}") for i in range(6)],
        )

        created = asyncio.run(build_reference_edges(fake.as_store(), threshold=0.3))

        self.assertEqual(DEFAULT_TOP_K, 4)
        self.assertEqual(created, 4)

    def test_missing_conversation_id_falls_back_to_message_as_own_thread(self):
        # conversation_id가 없는 노드(스레드 없는 소스)는 각자 독립 스레드로 센다
        fake = _FakeStore(
            modified=[_modified("c1", "a.py", [1.0, 0.0])],
            comms=[
                {**_comm("m1", _vec(0.90)), "conversation_id": None},
                {**_comm("m2", _vec(0.80)), "conversation_id": None},
            ],
        )

        created = asyncio.run(build_reference_edges(fake.as_store(), threshold=0.3, top_k=1))

        self.assertEqual(created, 1)
        self.assertEqual(fake.created[0][2], "m1")


class FilterRegressionTest(unittest.TestCase):
    """집계 변경이 임계값·시간 윈도우·프로젝트 격리를 깨지 않았는지."""

    def test_below_threshold_is_skipped(self):
        fake = _FakeStore(
            modified=[_modified("c1", "a.py", [0.8, 0.6])],   # sim 0.8
            comms=[_comm("m1", [1.0, 0.0])],
        )

        created = asyncio.run(build_reference_edges(fake.as_store(), threshold=0.9))

        self.assertEqual(created, 0)
        self.assertEqual(fake.created, [])

    def test_outside_time_window_is_skipped(self):
        fake = _FakeStore(
            modified=[_modified("c1", "a.py", [1.0, 0.0])],
            comms=[_comm("m1", [1.0, 0.0], occurred_at=NOW + timedelta(days=6))],  # 윈도우 5일 초과
        )

        created = asyncio.run(build_reference_edges(fake.as_store(), threshold=0.3))

        self.assertEqual(created, 0)

    def test_cross_project_pair_is_not_linked(self):
        fake = _FakeStore(
            modified=[_modified("c1", "a.py", [1.0, 0.0], project_id="p1")],
            comms=[_comm("m1", [1.0, 0.0], project_id="p2")],
        )

        created = asyncio.run(build_reference_edges(fake.as_store(), threshold=0.3))

        self.assertEqual(created, 0)


if __name__ == "__main__":
    unittest.main()
