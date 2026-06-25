"""postprocess per-project 빌드 상태 머신 단위 테스트 (오프라인).

상태 전이(idle→running→succeeded/failed)와 coalesce(같은 프로젝트 중복 트리거),
dirty 표시를 검증한다. 실제 Neo4j 시퀀스(run_postprocess_sequence)는 모킹해
상태 관리 로직만 격리한다.
"""

import asyncio
import unittest
from unittest.mock import patch

from graph import postprocess


class _ResetStateMixin:
    """모듈 전역 상태를 테스트마다 초기화한다 (per-project dict/set 공유 방지).

    세마포어는 None으로 되돌려, 각 asyncio.run이 자기 이벤트 루프에 묶인
    새 세마포어를 lazy 생성하도록 한다 (루프 간 재사용 에러 회피)."""

    def setUp(self):
        postprocess._build_status.clear()
        postprocess._dirty.clear()
        postprocess._last_build_at.clear()
        postprocess._last_event_at.clear()
        postprocess._build_semaphore = None


class BuildStatusTest(_ResetStateMixin, unittest.TestCase):
    def test_unknown_project_is_idle(self):
        self.assertEqual(postprocess.get_build_status("p1")["state"], "idle")
        self.assertFalse(postprocess.is_build_running("p1"))

    def test_mark_running_records_verify_and_started_at(self):
        postprocess.mark_build_running("p1", verify=True)
        status = postprocess.get_build_status("p1")
        self.assertEqual(status["state"], "running")
        self.assertTrue(status["verify"])
        self.assertIsNotNone(status["started_at"])
        self.assertTrue(postprocess.is_build_running("p1"))

    def test_try_start_coalesces_while_running(self):
        self.assertTrue(postprocess._try_start_build("p1", verify=False))   # 최초 시작
        self.assertFalse(postprocess._try_start_build("p1", verify=False))  # 이미 running → coalesce

    def test_different_projects_start_independently(self):
        self.assertTrue(postprocess._try_start_build("p1", verify=False))
        self.assertTrue(postprocess._try_start_build("p2", verify=False))   # 다른 프로젝트는 독립


class MarkDirtyTest(_ResetStateMixin, unittest.TestCase):
    def test_mark_dirty_adds_project(self):
        postprocess.mark_dirty("p1")
        self.assertIn("p1", postprocess._dirty)

    def test_mark_dirty_ignores_empty(self):
        postprocess.mark_dirty("")
        self.assertEqual(postprocess._dirty, set())


class ExecuteBuildTest(_ResetStateMixin, unittest.TestCase):
    def test_success_records_result_and_clears_running(self):
        async def fake_sequence(project_id, verify):
            return {"triggered_by": 3}

        postprocess.mark_build_running("p1", verify=False)
        with patch.object(postprocess, "run_postprocess_sequence", fake_sequence):
            ok = asyncio.run(postprocess._execute_build("p1", verify=False))

        self.assertTrue(ok)
        status = postprocess.get_build_status("p1")
        self.assertEqual(status["state"], "succeeded")
        self.assertEqual(status["result"], {"triggered_by": 3})
        self.assertFalse(postprocess.is_build_running("p1"))
        self.assertIn("p1", postprocess._last_build_at)   # 쿨다운 기준점 기록

    def test_failure_records_error(self):
        async def boom(project_id, verify):
            raise RuntimeError("neo4j down")

        postprocess.mark_build_running("p1", verify=False)
        with patch.object(postprocess, "run_postprocess_sequence", boom):
            ok = asyncio.run(postprocess._execute_build("p1", verify=False))

        self.assertFalse(ok)
        status = postprocess.get_build_status("p1")
        self.assertEqual(status["state"], "failed")
        self.assertIn("neo4j down", status["error"])
        self.assertIn("p1", postprocess._last_build_at)

    def test_failure_with_requeue_readds_to_dirty(self):
        async def boom(project_id, verify):
            raise RuntimeError("x")

        postprocess.mark_build_running("p1", verify=False)
        with patch.object(postprocess, "run_postprocess_sequence", boom):
            asyncio.run(postprocess._execute_build("p1", verify=False, requeue_on_failure=True))
        # 자동 빌드 실패 → 다음 디바운스 주기에 재시도하도록 dirty 복원 (쿨다운은 _last_build_at로 적용)
        self.assertIn("p1", postprocess._dirty)

    def test_failure_without_requeue_does_not_touch_dirty(self):
        async def boom(project_id, verify):
            raise RuntimeError("x")

        postprocess.mark_build_running("p1", verify=False)
        with patch.object(postprocess, "run_postprocess_sequence", boom):
            asyncio.run(postprocess._execute_build("p1", verify=False))  # 기본=수동 경로
        # 수동 빌드 실패는 디바운스가 재시도하지 않는다 (사용자가 failed 보고 재트리거)
        self.assertNotIn("p1", postprocess._dirty)


class TriggerBuildTest(_ResetStateMixin, unittest.TestCase):
    def test_trigger_returns_running_then_completes_in_background(self):
        async def fake_sequence(project_id, verify):
            return {"reference": 1}

        async def scenario():
            with patch.object(postprocess, "run_postprocess_sequence", fake_sequence):
                status = postprocess.trigger_build("p1", verify=False)
                self.assertEqual(status["state"], "running")          # 즉시 running 반환(202용)
                await asyncio.gather(*postprocess._build_tasks)       # 백그라운드 태스크 완료 대기
            return postprocess.get_build_status("p1")

        final = asyncio.run(scenario())
        self.assertEqual(final["state"], "succeeded")
        self.assertEqual(final["result"], {"reference": 1})
        self.assertEqual(postprocess._build_tasks, set())             # 완료 후 참조 정리됨

    def test_trigger_coalesces_when_already_running(self):
        postprocess.mark_build_running("p1", verify=False)            # 이미 빌드 중 가정
        status = postprocess.trigger_build("p1", verify=True)         # verify가 달라도 coalesce
        self.assertEqual(status["state"], "running")
        self.assertEqual(postprocess._build_tasks, set())             # 새 태스크를 만들지 않음


if __name__ == "__main__":
    unittest.main()
