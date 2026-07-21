"""REFERENCE 엣지 빌더 단위 테스트 (오프라인 — ReferenceStore mock 주입).

한 커밋의 여러 파일이 같은 메시지에 매칭될 때 confidence가 "마지막에 계산된 값"이 아니라
파일 간 최고 점수로 집계되는지, 그리고 커밋당 상위 k개 스레드만 남는지(fan-out 컷)를 검증한다.
임계값·시간 윈도우·프로젝트 격리는 변경이 깨뜨리지 않았는지 확인하는 회귀 케이스다.
"""

import asyncio
import math
import unittest
from datetime import datetime, timedelta, timezone
from unittest import mock

from graph.reference_builder import (
    DEFAULT_MESSAGE_MODE,
    DEFAULT_THRESHOLD,
    DEFAULT_TOP_K,
    ReferenceStore,
    backfill_changeset_message_embeddings,
    backfill_communication_embeddings,
    backfill_modified_embeddings,
    build_reference_edges,
)

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


def _message_row(changeset_id, embedding, project_id="p1", occurred_at=NOW):
    """커밋 메시지 임베딩 행 — 파일(diff) 행과 달리 커밋당 1개다."""
    return {
        "project_id": project_id,
        "changeset_id": changeset_id,
        "embedding": embedding,
        "occurred_at": occurred_at,
    }


class _FakeStore:
    """ReferenceStore 콜백 대역 — create_reference_edge 호출을 그대로 기록한다."""

    def __init__(self, modified, comms, messages=None):
        self.modified = modified
        self.comms = comms
        self.messages = messages or []
        self.created: list[tuple[str, str, str, float]] = []
        self.fetched_modified = False
        self.fetched_messages = False

    def as_store(self) -> ReferenceStore:
        async def fetch_modified():
            self.fetched_modified = True
            return self.modified

        async def fetch_comms():
            return self.comms

        async def fetch_messages():
            self.fetched_messages = True
            return self.messages

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
            fetch_unembedded_changeset_messages=unsupported,
            save_changeset_message_embedding=unsupported,
            fetch_changeset_message_embeddings=fetch_messages,
            fetch_unembedded_modified_edges=unsupported,
            save_modified_embedding=unsupported,
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
        # 파라미터를 안 주면 DEFAULT_TOP_K(=5)가 적용된다 — 무제한이 아니다
        fake = _FakeStore(
            modified=[_modified("c1", "a.py", [1.0, 0.0])],
            comms=[_comm(f"m{i}", _vec(0.9 - i * 0.05), conversation_id=f"t{i}") for i in range(6)],
        )

        created = asyncio.run(build_reference_edges(fake.as_store(), threshold=0.3))

        self.assertEqual(DEFAULT_TOP_K, 5)
        self.assertEqual(created, 5)

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


class MessageEmbeddingModeTest(unittest.TestCase):
    """message_mode — 커밋 메시지 임베딩을 비교 대상에 넣는 두 설계(max/only)와 현행(off).

    max: 메시지 행이 파일 행들과 같은 (changeset, comm) 쌍으로 합류 — 기존 쌍별 max 집계가
         "diff와 메시지 중 최고점"을 자연스럽게 만든다.
    only: 파일(diff) 행을 아예 쓰지 않고 메시지 행만 비교한다.
    """

    def test_off_mode_does_not_fetch_message_embeddings(self):
        # off는 메시지 임베딩 도입 전 동작 그대로 — 조회조차 하지 않는다 (측정 재현용)
        fake = _FakeStore(
            modified=[_modified("c1", "a.py", [1.0, 0.0])],
            comms=[_comm("m1", [1.0, 0.0])],
            messages=[_message_row("c1", [1.0, 0.0])],
        )

        created = asyncio.run(
            build_reference_edges(fake.as_store(), threshold=0.3, message_mode="off")
        )

        self.assertFalse(fake.fetched_messages)
        self.assertEqual(created, 1)

    def test_max_mode_takes_best_of_diff_and_message(self):
        fake = _FakeStore(
            modified=[_modified("c1", "a.py", _vec(0.5))],
            comms=[_comm("m1", [1.0, 0.0])],
            messages=[_message_row("c1", _vec(0.9))],
        )

        created = asyncio.run(
            build_reference_edges(fake.as_store(), threshold=0.3, message_mode="max")
        )

        self.assertEqual(created, 1)   # 쌍당 1개 — 메시지 행이 별도 엣지를 만들지 않는다
        self.assertAlmostEqual(fake.created[0][3], 0.9, places=5)

    def test_max_mode_diff_wins_when_higher(self):
        fake = _FakeStore(
            modified=[_modified("c1", "a.py", _vec(0.9))],
            comms=[_comm("m1", [1.0, 0.0])],
            messages=[_message_row("c1", _vec(0.5))],
        )

        asyncio.run(build_reference_edges(fake.as_store(), threshold=0.3, message_mode="max"))

        self.assertEqual(len(fake.created), 1)
        self.assertAlmostEqual(fake.created[0][3], 0.9, places=5)

    def test_max_mode_covers_commits_without_diff_embeddings(self):
        # 파일이 전부 스킵돼 MODIFIED 임베딩이 없는 커밋도 메시지로는 후보가 된다 (후보 풀 확장)
        fake = _FakeStore(
            modified=[],
            comms=[_comm("m1", [1.0, 0.0])],
            messages=[_message_row("c2", _vec(0.9))],
        )

        created = asyncio.run(
            build_reference_edges(fake.as_store(), threshold=0.3, message_mode="max")
        )

        self.assertEqual(created, 1)
        self.assertEqual(fake.created[0][1], "c2")

    def test_only_mode_ignores_diff_embeddings(self):
        # diff가 아무리 높아도 only 모드에선 메시지 점수로만 판단 — diff는 fetch조차 하지 않는다
        fake = _FakeStore(
            modified=[_modified("c1", "a.py", _vec(0.95))],
            comms=[_comm("m1", [1.0, 0.0])],
            messages=[_message_row("c1", _vec(0.4))],
        )

        created = asyncio.run(
            build_reference_edges(fake.as_store(), threshold=0.5, message_mode="only")
        )

        self.assertEqual(created, 0)
        self.assertFalse(fake.fetched_modified)

    def test_only_mode_message_above_threshold_creates_edge(self):
        fake = _FakeStore(
            modified=[],
            comms=[_comm("m1", [1.0, 0.0])],
            messages=[_message_row("c1", _vec(0.9))],
        )

        created = asyncio.run(
            build_reference_edges(fake.as_store(), threshold=0.5, message_mode="only")
        )

        self.assertEqual(created, 1)
        self.assertAlmostEqual(fake.created[0][3], 0.9, places=5)

    def test_message_row_outside_time_window_is_skipped(self):
        # 메시지 행에도 기존 ±5일 윈도우가 그대로 적용된다
        fake = _FakeStore(
            modified=[],
            comms=[_comm("m1", [1.0, 0.0], occurred_at=NOW + timedelta(days=6))],
            messages=[_message_row("c1", [1.0, 0.0])],
        )

        created = asyncio.run(
            build_reference_edges(fake.as_store(), threshold=0.3, message_mode="only")
        )

        self.assertEqual(created, 0)

    def test_invalid_mode_raises(self):
        fake = _FakeStore(modified=[], comms=[], messages=[])

        with self.assertRaises(ValueError):
            asyncio.run(build_reference_edges(fake.as_store(), message_mode="diff"))


class AdoptedDefaultsTest(unittest.TestCase):
    """기본값 = 채택 운영점 (max · top_k 5 · threshold 0.39).

    postprocess 자동 빌드는 인자 없이 호출하므로, 측정으로 확정한 구성과 코드 기본값이
    같아야 프로덕션이 측정과 동일하게 돈다 — 그 일치를 박는 계약 테스트.
    """

    def test_default_constants_are_adopted_operating_point(self):
        self.assertEqual(DEFAULT_MESSAGE_MODE, "max")
        self.assertEqual(DEFAULT_TOP_K, 5)
        self.assertAlmostEqual(DEFAULT_THRESHOLD, 0.39)

    def test_default_call_uses_message_rows(self):
        # diff(0.2)는 임계값 밑, 메시지(0.9)는 위 — 인자 없는 기본 호출이 메시지 행으로 엣지를 만든다
        fake = _FakeStore(
            modified=[_modified("c1", "a.py", _vec(0.2))],
            comms=[_comm("m1", [1.0, 0.0])],
            messages=[_message_row("c1", _vec(0.9))],
        )

        created = asyncio.run(build_reference_edges(fake.as_store()))

        self.assertTrue(fake.fetched_messages)
        self.assertEqual(created, 1)
        self.assertAlmostEqual(fake.created[0][3], 0.9, places=5)

    def test_default_threshold_cuts_between_030_and_039(self):
        # 0.30~0.39 구간은 재조정 측정에서 노이즈만 남았다 — 새 기본 임계값이 잘라야 한다
        fake = _FakeStore(
            modified=[],
            comms=[_comm("m1", [1.0, 0.0])],
            messages=[_message_row("c1", _vec(0.35))],
        )

        created = asyncio.run(build_reference_edges(fake.as_store()))

        self.assertEqual(created, 0)


class ChangesetMessageBackfillTest(unittest.TestCase):
    """backfill_changeset_message_embeddings — message는 있는데 embedding이 없는 ChangeSet 보정.

    communication 백필과 같은 계약: 빈 벡터(임베딩 실패)는 저장하지 않고,
    보정 대상이 없으면 임베딩 API를 호출하지 않는다.
    반환은 {"saved", "total"} — saved != total이면 완주하지 못한 것이다.
    """

    def _store(self, nodes):
        saved: list[tuple[str, str, list[float]]] = []

        async def fetch_unembedded(force):
            return nodes

        async def save(project_id, changeset_id, embedding):
            saved.append((project_id, changeset_id, embedding))

        async def unsupported(*args):
            raise AssertionError("이 테스트에서 호출되지 않아야 한다")

        store = ReferenceStore(
            fetch_modified_embeddings=unsupported,
            fetch_communication_embeddings=unsupported,
            create_reference_edge=unsupported,
            fetch_unembedded_communications=unsupported,
            save_communication_embedding=unsupported,
            fetch_unembedded_changeset_messages=fetch_unembedded,
            save_changeset_message_embedding=save,
            fetch_changeset_message_embeddings=unsupported,
            fetch_unembedded_modified_edges=unsupported,
            save_modified_embedding=unsupported,
        )
        return store, saved

    def test_embeds_and_saves_missing_message_embeddings(self):
        store, saved = self._store([
            {"project_id": "p1", "id": "c1", "message": "feat: 로그인 수정"},
            {"project_id": "p1", "id": "c2", "message": "docs: 문서 최신화"},
        ])

        with mock.patch(
            "graph.reference_builder.embed_batch",
            new=mock.AsyncMock(return_value=[[0.1, 0.2], [0.3, 0.4]]),
        ) as embed:
            result = asyncio.run(backfill_changeset_message_embeddings(store))

        embed.assert_awaited_once_with(["feat: 로그인 수정", "docs: 문서 최신화"])
        self.assertEqual(result, {"saved": 2, "total": 2})
        self.assertEqual(saved, [("p1", "c1", [0.1, 0.2]), ("p1", "c2", [0.3, 0.4])])

    def test_partial_embedding_failure_shows_as_saved_below_total(self):
        # 배치 중 일부만 임베딩이 실패(빈 벡터)하면 그 노드만 건너뛴다.
        # 신·구 모델 벡터가 같은 1536차원이라 섞여도 조용히 계산되므로,
        # 완주하지 못했다는 사실은 saved < total로만 드러난다.
        store, saved = self._store([
            {"project_id": "p1", "id": "c1", "message": "feat: A"},
            {"project_id": "p1", "id": "c2", "message": "feat: B"},
        ])

        with mock.patch(
            "graph.reference_builder.embed_batch",
            new=mock.AsyncMock(return_value=[[0.5, 0.5], []]),
        ):
            result = asyncio.run(backfill_changeset_message_embeddings(store))

        self.assertEqual(result, {"saved": 1, "total": 2})
        self.assertEqual(saved, [("p1", "c1", [0.5, 0.5])])

    def test_noop_when_nothing_to_backfill(self):
        store, saved = self._store([])

        with mock.patch("graph.reference_builder.embed_batch", new=mock.AsyncMock()) as embed:
            result = asyncio.run(backfill_changeset_message_embeddings(store))

        embed.assert_not_awaited()
        self.assertEqual(result, {"saved": 0, "total": 0})
        self.assertEqual(saved, [])


class ModifiedEdgeBackfillTest(unittest.TestCase):
    """backfill_modified_embeddings — diffSummary는 있는데 embedding이 없는 MODIFIED 엣지 보정.

    임베딩 대상 텍스트는 수집 경로(event_handler)가 저장한 diffSummary 그대로여야 한다 —
    다르면 수집으로 들어온 엣지와 백필로 채운 엣지의 벡터가 서로 다른 기준이 된다.
    """

    def _store(self, edges):
        saved: list[tuple[str, str, str, list[float]]] = []

        async def fetch_unembedded(force):
            return edges

        async def save(project_id, changeset_id, file_path, embedding):
            saved.append((project_id, changeset_id, file_path, embedding))

        async def unsupported(*args):
            raise AssertionError("이 테스트에서 호출되지 않아야 한다")

        store = ReferenceStore(
            fetch_modified_embeddings=unsupported,
            fetch_communication_embeddings=unsupported,
            create_reference_edge=unsupported,
            fetch_unembedded_communications=unsupported,
            save_communication_embedding=unsupported,
            fetch_unembedded_changeset_messages=unsupported,
            save_changeset_message_embedding=unsupported,
            fetch_changeset_message_embeddings=unsupported,
            fetch_unembedded_modified_edges=fetch_unembedded,
            save_modified_embedding=save,
        )
        return store, saved

    def test_embeds_diff_summary_and_saves_per_edge(self):
        # 엣지 식별자는 (changeset, file_path) 쌍 — 같은 커밋의 파일마다 별도 벡터가 저장된다
        store, saved = self._store([
            {"project_id": "p1", "changeset_id": "c1", "file_path": "a.py", "diff_summary": "로그인 검증 추가"},
            {"project_id": "p1", "changeset_id": "c1", "file_path": "b.py", "diff_summary": "테스트 보강"},
        ])

        with mock.patch(
            "graph.reference_builder.embed_batch",
            new=mock.AsyncMock(return_value=[[0.1, 0.2], [0.3, 0.4]]),
        ) as embed:
            result = asyncio.run(backfill_modified_embeddings(store))

        embed.assert_awaited_once_with(["로그인 검증 추가", "테스트 보강"])
        self.assertEqual(result, {"saved": 2, "total": 2})
        self.assertEqual(
            saved,
            [("p1", "c1", "a.py", [0.1, 0.2]), ("p1", "c1", "b.py", [0.3, 0.4])],
        )

    def test_partial_embedding_failure_shows_as_saved_below_total(self):
        store, saved = self._store([
            {"project_id": "p1", "changeset_id": "c1", "file_path": "a.py", "diff_summary": "A"},
            {"project_id": "p1", "changeset_id": "c2", "file_path": "b.py", "diff_summary": "B"},
        ])

        with mock.patch(
            "graph.reference_builder.embed_batch",
            new=mock.AsyncMock(return_value=[[0.5, 0.5], []]),
        ):
            result = asyncio.run(backfill_modified_embeddings(store))

        self.assertEqual(result, {"saved": 1, "total": 2})
        self.assertEqual(saved, [("p1", "c1", "a.py", [0.5, 0.5])])

    def test_noop_when_nothing_to_backfill(self):
        store, saved = self._store([])

        with mock.patch("graph.reference_builder.embed_batch", new=mock.AsyncMock()) as embed:
            result = asyncio.run(backfill_modified_embeddings(store))

        embed.assert_not_awaited()
        self.assertEqual(result, {"saved": 0, "total": 0})
        self.assertEqual(saved, [])


class CommunicationBackfillTest(unittest.TestCase):
    """backfill_communication_embeddings — 나머지 3종과 같은 {"saved", "total"} 계약."""

    def _store(self, nodes):
        saved: list[tuple[str, str, list[float]]] = []

        async def fetch_unembedded(force):
            return nodes

        async def save(project_id, comm_id, embedding):
            saved.append((project_id, comm_id, embedding))

        async def unsupported(*args):
            raise AssertionError("이 테스트에서 호출되지 않아야 한다")

        store = ReferenceStore(
            fetch_modified_embeddings=unsupported,
            fetch_communication_embeddings=unsupported,
            create_reference_edge=unsupported,
            fetch_unembedded_communications=fetch_unembedded,
            save_communication_embedding=save,
            fetch_unembedded_changeset_messages=unsupported,
            save_changeset_message_embedding=unsupported,
            fetch_changeset_message_embeddings=unsupported,
            fetch_unembedded_modified_edges=unsupported,
            save_modified_embedding=unsupported,
        )
        return store, saved

    def test_embeds_body_and_reports_full_completion(self):
        store, saved = self._store([
            {"project_id": "p1", "id": "m1", "body": "배포 언제 하나요"},
            {"project_id": "p1", "id": "m2", "body": "내일 오전"},
        ])

        with mock.patch(
            "graph.reference_builder.embed_batch",
            new=mock.AsyncMock(return_value=[[0.1, 0.2], [0.3, 0.4]]),
        ) as embed:
            result = asyncio.run(backfill_communication_embeddings(store))

        embed.assert_awaited_once_with(["배포 언제 하나요", "내일 오전"])
        self.assertEqual(result, {"saved": 2, "total": 2})
        self.assertEqual(saved, [("p1", "m1", [0.1, 0.2]), ("p1", "m2", [0.3, 0.4])])

    def test_partial_embedding_failure_shows_as_saved_below_total(self):
        store, saved = self._store([
            {"project_id": "p1", "id": "m1", "body": "A"},
            {"project_id": "p1", "id": "m2", "body": "B"},
        ])

        with mock.patch(
            "graph.reference_builder.embed_batch",
            new=mock.AsyncMock(return_value=[[0.5, 0.5], []]),
        ):
            result = asyncio.run(backfill_communication_embeddings(store))

        self.assertEqual(result, {"saved": 1, "total": 2})
        self.assertEqual(saved, [("p1", "m1", [0.5, 0.5])])


class ForceReembedTest(unittest.TestCase):
    """force — 이미 embedding이 있는 노드/엣지까지 덮어쓰는 전량 재임베딩.

    임베딩 모델을 바꾸면 구 모델 벡터가 신 모델 벡터와 비교 불가라 전량 교체가 필요하다.
    force 플래그가 fetch까지 그대로 전달되는지(=대상 선정이 바뀌는지)를 박는다.
    기본값은 False — 제품의 기존 동작(수집 중 누락분만 보정)을 바꾸지 않는다.
    """

    def _store(self, field, seen):
        async def fetch(force):
            seen.append(force)
            return []

        async def unsupported(*args):
            raise AssertionError("이 테스트에서 호출되지 않아야 한다")

        fields = {
            "fetch_modified_embeddings": unsupported,
            "fetch_communication_embeddings": unsupported,
            "create_reference_edge": unsupported,
            "fetch_unembedded_communications": unsupported,
            "save_communication_embedding": unsupported,
            "fetch_unembedded_changeset_messages": unsupported,
            "save_changeset_message_embedding": unsupported,
            "fetch_changeset_message_embeddings": unsupported,
            "fetch_unembedded_modified_edges": unsupported,
            "save_modified_embedding": unsupported,
        }
        fields[field] = fetch
        return ReferenceStore(**fields)

    def _run(self, backfill, field, force):
        seen: list[bool] = []
        store = self._store(field, seen)
        if force is None:
            asyncio.run(backfill(store))
        else:
            asyncio.run(backfill(store, force=force))
        return seen

    def test_defaults_to_gap_filling(self):
        for backfill, field in (
            (backfill_communication_embeddings, "fetch_unembedded_communications"),
            (backfill_changeset_message_embeddings, "fetch_unembedded_changeset_messages"),
            (backfill_modified_embeddings, "fetch_unembedded_modified_edges"),
        ):
            with self.subTest(backfill=backfill.__name__):
                self.assertEqual(self._run(backfill, field, None), [False])

    def test_force_is_forwarded_to_fetch(self):
        for backfill, field in (
            (backfill_communication_embeddings, "fetch_unembedded_communications"),
            (backfill_changeset_message_embeddings, "fetch_unembedded_changeset_messages"),
            (backfill_modified_embeddings, "fetch_unembedded_modified_edges"),
        ):
            with self.subTest(backfill=backfill.__name__):
                self.assertEqual(self._run(backfill, field, True), [True])


if __name__ == "__main__":
    unittest.main()
