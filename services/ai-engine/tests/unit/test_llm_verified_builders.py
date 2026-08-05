"""LLM 검수 빌더 단위 테스트 (오프라인 — store·LLM mock).

비교 측정에서 엣지 타입별로 채택된 방식만 남아 있고, 그 경계를 코드로 고정한다.

  TRIGGERED_BY · 추천형  임베딩이 이슈당 top-k를 추천하고 LLM이 최종 선택 — 엣지 추가 가능
  DISCUSSED_IN · 필터형  임베딩이 확정한 쌍만 검수 — 추가 없이 걸러내기만
  REFERENCE    · 필터형  위와 동일. recall ≤ 임베딩 전용이 구조적으로 보장된다
"""

import math
import unittest
from datetime import datetime, timezone
from unittest.mock import AsyncMock, patch

from graph import llm_judge, reference_verifier
from graph.reference_builder import ReferenceStore

NOW = datetime(2026, 7, 1, tzinfo=timezone.utc)


def _vec(sim: float) -> list[float]:
    """기준 벡터 [1,0]과의 코사인 유사도가 정확히 sim인 단위 벡터."""
    return [sim, math.sqrt(1.0 - sim * sim)]


def _modified(changeset_id, file_path, embedding, project_id="p1", occurred_at=NOW):
    return {
        "project_id": project_id, "changeset_id": changeset_id, "file_path": file_path,
        "diff_summary": f"{file_path} 변경", "embedding": embedding, "occurred_at": occurred_at,
    }


def _message_row(changeset_id, embedding, message, project_id="p1", occurred_at=NOW):
    return {
        "project_id": project_id, "changeset_id": changeset_id, "message": message,
        "embedding": embedding, "occurred_at": occurred_at,
    }


def _comm(comm_id, embedding, project_id="p1", occurred_at=NOW, conversation_id=None):
    return {
        "project_id": project_id, "id": comm_id, "conversation_id": conversation_id or comm_id,
        "body": f"{comm_id} 본문", "embedding": embedding, "occurred_at": occurred_at,
    }


class _FakeStore:
    def __init__(self, modified, comms, messages=None):
        self.modified, self.comms, self.messages = modified, comms, messages or []
        self.created: list[tuple[str, str, str, float]] = []

    def as_store(self):
        async def _created(pid, cs, comm, conf):
            self.created.append((pid, cs, comm, conf))
        return ReferenceStore(
            fetch_modified_embeddings=AsyncMock(return_value=self.modified),
            fetch_communication_embeddings=AsyncMock(return_value=self.comms),
            create_reference_edge=_created,
            fetch_unembedded_communications=AsyncMock(return_value=[]),
            save_communication_embedding=AsyncMock(),
            fetch_unembedded_changeset_messages=AsyncMock(return_value=[]),
            save_changeset_message_embedding=AsyncMock(),
            fetch_changeset_message_embeddings=AsyncMock(return_value=self.messages),
            fetch_unembedded_modified_edges=AsyncMock(return_value=[]),
            save_modified_embedding=AsyncMock(),
        )


def _judge(returns):
    """judge_pair 대역 — 호출 순서대로 returns를 돌려주고 호출 인자를 기록한다."""
    return AsyncMock(side_effect=returns)


class ReferenceFilteredTest(unittest.IsolatedAsyncioTestCase):
    """REFERENCE 필터형 — 임베딩이 확정한 쌍만 검수한다. 추가는 못 하고 걸러내기만 한다."""

    async def test_llm_input_includes_commit_message(self):
        """LLM이 커밋 메시지를 diff 요약과 함께 봐야 한다."""
        store = _FakeStore(
            [_modified("c1", "a.py", _vec(0.95))],
            [_comm("m1", _vec(1.0))],
            [_message_row("c1", _vec(0.95), "fix: 세션 만료 처리")],
        )
        judge = _judge([0.9])
        with patch.object(reference_verifier, "judge_pair", judge):
            await reference_verifier.build_reference_edges_filtered(
                store.as_store(), threshold=0.39, llm_threshold=0.7, message_mode="max",
            )
        commit_text = judge.await_args.args[1]
        self.assertIn("fix: 세션 만료 처리", commit_text)
        self.assertIn("a.py 변경", commit_text)

    async def test_does_not_judge_pairs_embedding_never_selected(self):
        """임계값(0.39) 아래 쌍은 판정 대상에조차 오르지 않아야 한다 (recall ≤ 임베딩 전용 보장)."""
        store = _FakeStore(
            [_modified("c1", "a.py", _vec(0.34)), _modified("c2", "b.py", _vec(0.95))],
            [_comm("m1", _vec(1.0))],
        )
        judge = _judge([0.9])
        with patch.object(reference_verifier, "judge_pair", judge):
            created = await reference_verifier.build_reference_edges_filtered(
                store.as_store(), threshold=0.39, llm_threshold=0.7, message_mode="off",
            )
        self.assertEqual(judge.await_count, 1)          # c2만 판정 (c1은 임베딩이 안 고름)
        self.assertEqual(created, 1)
        self.assertEqual(store.created[0][1], "c2")

    async def test_removes_pair_when_llm_rejects(self):
        store = _FakeStore([_modified("c1", "a.py", _vec(0.95))], [_comm("m1", _vec(1.0))])
        with patch.object(reference_verifier, "judge_pair", _judge([0.3])):
            created = await reference_verifier.build_reference_edges_filtered(
                store.as_store(), threshold=0.39, llm_threshold=0.7, message_mode="off",
            )
        self.assertEqual(created, 0)

    async def test_keeps_pair_when_judgement_skipped(self):
        """일시 오류로 판정 못 한 엣지는 남긴다 — 장애가 골든 엣지를 지우면 안 된다."""
        store = _FakeStore([_modified("c1", "a.py", _vec(0.95))], [_comm("m1", _vec(1.0))])
        with patch.object(reference_verifier, "judge_pair", _judge([None])):
            created = await reference_verifier.build_reference_edges_filtered(
                store.as_store(), threshold=0.39, llm_threshold=0.7, message_mode="off",
            )
        self.assertEqual(created, 1)
        # 판정 못 했으므로 코사인 값을 유지한다 (LLM 값으로 덮어쓸 근거가 없다)
        self.assertAlmostEqual(store.created[0][3], 0.95, places=5)


def _issue(issue_key, embedding, project_id="p1", occurred_at=NOW):
    return {
        "project_id": project_id, "id": issue_key, "title": f"{issue_key} 제목",
        "body": f"{issue_key} 본문", "embedding": embedding, "occurred_at": occurred_at,
        "created_at": occurred_at, "closed_at": None, "status": "진행 중",
    }


class _FakeIssueStore:
    def __init__(self, issues, modified, comms=None, messages=None):
        self.issues, self.modified = issues, modified
        self.comms, self.messages = comms or [], messages or []
        self.triggered: list[tuple[str, str, str, float]] = []
        self.discussed: list[tuple[str, str, str, float]] = []

    def as_store(self):
        from graph.issue_linker import IssueLinkStore

        async def _tb(pid, cs, key, conf):
            self.triggered.append((pid, cs, key, conf))

        async def _di(pid, key, comm, confidence):
            self.discussed.append((pid, key, comm, confidence))

        return IssueLinkStore(
            fetch_issue_embeddings=AsyncMock(return_value=self.issues),
            fetch_modified_embeddings=AsyncMock(return_value=self.modified),
            fetch_communication_embeddings=AsyncMock(return_value=self.comms),
            create_triggered_by_edge=_tb,
            create_discussed_in_edge=_di,
            fetch_changeset_message_embeddings=AsyncMock(return_value=self.messages),
            fetch_unembedded_issues=AsyncMock(return_value=[]),
            save_issue_embedding=AsyncMock(),
        )


class TriggeredByVerifiedTest(unittest.IsolatedAsyncioTestCase):
    """TRIGGERED_BY 추천형 — 이슈당 top-k를 LLM이 판정하고 커밋당 top-1만 남긴다."""

    async def test_commit_judged_once_under_max_injection(self):
        """max 주입 시 한 커밋의 diff 행·메시지 행이 top-5 슬롯을 둘 다 먹으면 안 된다.

        커밋 단위로 집계해서 한 번만 판정해야 '이슈당 top-5 커밋'이라는 정의가 유지된다.
        """
        from graph import issue_verifier
        store = _FakeIssueStore(
            [_issue("HT-1", _vec(1.0))],
            [_modified("c1", "a.py", _vec(0.35))],
            messages=[_message_row("c1", _vec(0.40), "fix: 세션 만료 처리")],
        )
        judge = _judge([0.9])
        with patch.object(issue_verifier, "judge_pair", judge):
            created = await issue_verifier.build_issue_changeset_links_verified(
                store.as_store(), threshold=0.30, top_k=5, llm_threshold=0.7, message_mode="max",
            )
        self.assertEqual(judge.await_count, 1)
        self.assertEqual(created, 1)
        commit_text = judge.await_args.args[3]
        self.assertIn("fix: 세션 만료 처리", commit_text)
        self.assertIn("a.py 변경", commit_text)

    async def test_keeps_top1_commit_when_two_issues_approved(self):
        """커밋당 top-1 — 두 이슈가 모두 통과하면 LLM confidence가 높은 쪽만 남는다."""
        from graph import issue_verifier
        store = _FakeIssueStore(
            [_issue("HT-1", _vec(1.0)), _issue("HT-2", _vec(0.99))],
            [_modified("c1", "a.py", _vec(0.9))],
        )
        with patch.object(issue_verifier, "judge_pair", _judge([0.75, 0.95])):
            created = await issue_verifier.build_issue_changeset_links_verified(
                store.as_store(), threshold=0.30, top_k=5, llm_threshold=0.7, message_mode="off",
            )
        self.assertEqual(created, 1)
        self.assertEqual(store.triggered[0][2], "HT-2")
        self.assertEqual(store.triggered[0][3], 0.95)


class DiscussedInFilteredTest(unittest.IsolatedAsyncioTestCase):
    """DISCUSSED_IN 필터형 — 임베딩이 확정한 쌍만 검수한다 (추가 없음)."""

    async def test_only_judges_embedding_selected_pairs(self):
        from graph import issue_verifier
        store = _FakeIssueStore(
            [_issue("HT-1", _vec(1.0))], [],
            comms=[_comm("m1", _vec(0.9)), _comm("m2", _vec(0.2))],
        )
        judge = _judge([0.9])
        with patch.object(issue_verifier, "judge_pair", judge):
            created = await issue_verifier.build_issue_communication_links_filtered(
                store.as_store(), threshold=0.40, llm_threshold=0.7,
            )
        self.assertEqual(judge.await_count, 1)   # m2는 임계값 미달로 임베딩이 안 고름
        self.assertEqual(created, 1)

    async def test_removes_pair_when_llm_rejects(self):
        from graph import issue_verifier
        store = _FakeIssueStore([_issue("HT-1", _vec(1.0))], [], comms=[_comm("m1", _vec(0.9))])
        with patch.object(issue_verifier, "judge_pair", _judge([0.3])):
            created = await issue_verifier.build_issue_communication_links_filtered(
                store.as_store(), threshold=0.40, llm_threshold=0.7,
            )
        self.assertEqual(created, 0)
        self.assertEqual(store.discussed, [])

    async def test_keeps_pair_when_judgement_skipped(self):
        """일시 오류로 판정 못 한 엣지는 남긴다 — 장애가 골든 엣지를 지우면 안 된다."""
        from graph import issue_verifier
        store = _FakeIssueStore([_issue("HT-1", _vec(1.0))], [], comms=[_comm("m1", _vec(0.9))])
        with patch.object(issue_verifier, "judge_pair", _judge([None])):
            created = await issue_verifier.build_issue_communication_links_filtered(
                store.as_store(), threshold=0.40, llm_threshold=0.7,
            )
        self.assertEqual(created, 1)
        # 판정 못 했으므로 코사인 값을 유지한다 (LLM 값으로 덮어쓸 근거가 없다)
        self.assertAlmostEqual(store.discussed[0][3], 0.9, places=5)


if __name__ == "__main__":
    unittest.main()
