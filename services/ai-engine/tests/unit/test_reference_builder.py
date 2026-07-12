"""REFERENCE 엣지 빌더 단위 테스트 (오프라인 — ReferenceStore mock 주입).

한 커밋의 여러 파일이 같은 메시지에 매칭될 때 confidence가 "마지막에 계산된 값"이 아니라
파일 간 최고 점수로 집계되는지를 중심으로 검증한다. 임계값·시간 윈도우·프로젝트 격리는
집계 변경이 깨뜨리지 않았는지 확인하는 회귀 케이스다.
"""

import asyncio
import unittest
from datetime import datetime, timedelta, timezone

from graph.reference_builder import ReferenceStore, build_reference_edges

NOW = datetime(2026, 7, 1, tzinfo=timezone.utc)


def _modified(changeset_id, file_path, embedding, project_id="p1", occurred_at=NOW):
    return {
        "project_id": project_id,
        "changeset_id": changeset_id,
        "file_path": file_path,
        "diff_summary": f"{file_path} 변경",
        "embedding": embedding,
        "occurred_at": occurred_at,
    }


def _comm(comm_id, embedding, project_id="p1", occurred_at=NOW):
    return {
        "project_id": project_id,
        "id": comm_id,
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
