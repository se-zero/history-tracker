"""run_postprocess_sequence의 빌더 선택과 clear 순서 단위 테스트 (오프라인).

verify=False(디바운스 자동 재구축)는 임베딩 유사도 전용 경로를 그대로 쓰고,
verify=True(수동 정밀 재구축)는 타입별 채택 조합을 쓴다:
TRIGGERED_BY=추천형(*_verified), DISCUSSED_IN=필터형(*_filtered), REFERENCE=필터형.

REFERENCE 필터형은 "LLM이 거른 쌍을 만들지 않는" 방식이라 삭제 동작이 없다.
clear_reference가 REFERENCE 빌더보다 **먼저** 돌지 않으면 이전 자동 빌드가 만든
엣지가 그대로 남아 필터가 아무 효과도 못 낸다 — 호출 여부만이 아니라 순서까지 고정한다.
"""

import asyncio
import unittest
from contextlib import ExitStack
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch


class _CallLog:
    """빌드 단계 호출을 순서대로 기록하는 대역 팩토리."""

    def __init__(self):
        self.calls: list[str] = []

    def step(self, name: str, result=0):
        async def _step(*args, **kwargs):
            self.calls.append(name)
            return result
        return _step


def _fake_store():
    """postprocess가 메모이즈 wrapper를 대입하므로 속성 대입이 가능한 대역이어야 한다."""
    return SimpleNamespace(
        fetch_issue_embeddings=AsyncMock(return_value=[]),
        fetch_communication_embeddings=AsyncMock(return_value=[]),
    )


def _run_sequence(verify: bool) -> list[str]:
    """모든 단계를 대역으로 바꾼 뒤 시퀀스를 1회 돌리고 호출 순서를 반환한다.

    run_postprocess_sequence가 빌더를 함수 안에서 lazy import하므로 모듈 속성 패치로 격리된다.
    """
    from graph import postprocess

    log = _CallLog()
    with ExitStack() as stack:
        def step(target: str, name: str):
            stack.enter_context(patch(target, log.step(name)))

        stack.enter_context(
            patch("graph.builder.make_neo4j_reference_store", lambda project_id=None: _fake_store())
        )
        stack.enter_context(
            patch("graph.builder.make_neo4j_issue_link_store", lambda project_id=None: _fake_store())
        )
        stack.enter_context(
            patch(
                "graph.slack_batch_filter.run_slack_llm_filter",
                AsyncMock(return_value={"kept": 0, "deleted": 0}),
            )
        )

        step("graph.builder.clear_semantic_triggered_by", "clear_triggered_by")
        step("graph.builder.clear_semantic_discussed_in", "clear_discussed_in")
        step("graph.builder.clear_reference", "clear_reference")
        step("graph.builder.propagate_thread_discussed_in", "propagate")
        step("graph.reference_builder.backfill_communication_embeddings", "backfill")

        # 임베딩 전용(자동 재구축) 빌더
        step("graph.issue_linker.build_issue_changeset_links", "tb_embedding")
        step("graph.issue_linker.build_issue_communication_links", "di_embedding")
        step("graph.reference_builder.build_reference_edges", "ref_embedding")

        # LLM 개입(정밀 재구축) 빌더 — 채택된 것과 미채택된 것을 함께 감시한다
        step("graph.issue_verifier.build_issue_changeset_links_verified", "tb_verified")
        step("graph.issue_verifier.build_issue_communication_links_filtered", "di_filtered")
        step("graph.reference_verifier.build_reference_edges_filtered", "ref_filtered")

        asyncio.run(postprocess.run_postprocess_sequence("p1", verify=verify))

    return log.calls


class AutoRebuildSequenceTest(unittest.TestCase):
    """verify=False — 자동 재구축은 임베딩 유사도만 쓰고 아무것도 지우지 않는다."""

    def setUp(self):
        self.calls = _run_sequence(verify=False)

    def test_uses_embedding_only_builders(self):
        self.assertIn("tb_embedding", self.calls)
        self.assertIn("di_embedding", self.calls)
        self.assertIn("ref_embedding", self.calls)

    def test_does_not_use_llm_builders(self):
        for name in ("tb_verified", "di_filtered", "ref_filtered"):
            self.assertNotIn(name, self.calls)

    def test_clears_nothing(self):
        # 자동 경로가 엣지를 지우면 웹훅 증분마다 그래프가 흔들린다
        for name in ("clear_triggered_by", "clear_discussed_in", "clear_reference"):
            self.assertNotIn(name, self.calls)


class VerifiedRebuildSequenceTest(unittest.TestCase):
    """verify=True — 타입별 채택 조합(TB 추천형 / DI 필터형 / REF 필터형)."""

    def setUp(self):
        self.calls = _run_sequence(verify=True)

    def test_uses_adopted_builder_per_edge_type(self):
        self.assertIn("tb_verified", self.calls)
        self.assertIn("di_filtered", self.calls)
        self.assertIn("ref_filtered", self.calls)

    def test_does_not_use_embedding_only_builders(self):
        for name in ("tb_embedding", "di_embedding", "ref_embedding"):
            self.assertNotIn(name, self.calls)

    def test_clears_all_three_edge_types(self):
        for name in ("clear_triggered_by", "clear_discussed_in", "clear_reference"):
            self.assertIn(name, self.calls)

    def test_clear_reference_runs_before_reference_builder(self):
        # 필터형은 "만들지 않는" 방식이라 선행 clear가 없으면 이전 엣지가 남아 필터가 무력화된다
        self.assertLess(
            self.calls.index("clear_reference"),
            self.calls.index("ref_filtered"),
        )

    def test_clears_run_before_their_builders(self):
        self.assertLess(self.calls.index("clear_triggered_by"), self.calls.index("tb_verified"))
        self.assertLess(self.calls.index("clear_discussed_in"), self.calls.index("di_filtered"))


if __name__ == "__main__":
    unittest.main()
