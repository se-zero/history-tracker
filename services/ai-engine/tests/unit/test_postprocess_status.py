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
        postprocess._ever_built.clear()
        postprocess._manual_build_running.clear()
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


class BuildCooldownTest(_ResetStateMixin, unittest.TestCase):
    """디바운스 루프의 쿨다운 판정 — 빌드 기록이 없으면 쿨다운을 적용하지 않는다."""

    def test_no_build_record_has_no_cooldown_on_fresh_boot(self):
        # monotonic(부팅 후 경과 초)이 쿨다운(300s)보다 작아도, 빌드 기록이 없으면
        # "방금 빌드했다"로 오판해 최초 빌드를 미루면 안 된다 (issue #115와 같은 원인)
        self.assertFalse(postprocess._cooldown_active("p1", now=10.0))

    def test_recent_build_is_in_cooldown(self):
        postprocess._last_build_at["p1"] = 1000.0
        self.assertTrue(postprocess._cooldown_active("p1", now=1100.0))

    def test_elapsed_cooldown_is_over(self):
        postprocess._last_build_at["p1"] = 1000.0
        self.assertFalse(
            postprocess._cooldown_active("p1", now=1000.0 + postprocess.MIN_BUILD_INTERVAL_SECONDS)
        )


class GraphActivityTest(_ResetStateMixin, unittest.TestCase):
    """프론트 채팅 게이팅용 get_graph_activity 상태 판정 (idle/collecting/building)."""

    def test_no_activity_is_idle(self):
        # 이벤트도 빌드도 없는 프로젝트
        self.assertEqual(postprocess.get_graph_activity("p1"), "idle")

    def test_no_activity_is_idle_on_freshly_booted_host(self):
        # monotonic은 리눅스에서 부팅 후 경과 시간이라 창(45s)보다 작을 수 있다.
        # 이벤트 기록이 없는 프로젝트는 그 값과 무관하게 idle이어야 한다 (issue #115).
        with patch.object(postprocess.time, "monotonic", return_value=10.0):
            self.assertEqual(postprocess.get_graph_activity("p1"), "idle")

    def test_initial_collection_is_collecting(self):
        # 최초 이벤트 유입, 아직 한 번도 빌드 성공 안 함 → collecting
        postprocess.mark_dirty("p1")
        self.assertNotIn("p1", postprocess._ever_built)
        self.assertEqual(postprocess.get_graph_activity("p1"), "collecting")

    def test_after_first_build_success_is_idle(self):
        # _execute_build 성공이 _ever_built를 세우고, 그 후 (dirty 흔적이 남아도) idle
        async def fake_sequence(project_id, verify):
            return {"triggered_by": 1}

        postprocess.mark_dirty("p1")                       # 최초 수집 흔적
        postprocess.mark_build_running("p1", verify=False)
        with patch.object(postprocess, "run_postprocess_sequence", fake_sequence):
            asyncio.run(postprocess._execute_build("p1", verify=False))

        self.assertIn("p1", postprocess._ever_built)
        self.assertEqual(postprocess.get_graph_activity("p1"), "idle")

    def test_webhook_increment_after_built_stays_idle(self):
        # 첫 빌드 성공 이후 웹훅 이벤트로 dirty가 다시 서도 collecting으로 오인하지 않는다
        postprocess._ever_built.add("p1")
        postprocess.mark_dirty("p1")                       # 웹훅 증분
        self.assertEqual(postprocess.get_graph_activity("p1"), "idle")

    def test_auto_maintenance_build_stays_idle(self):
        # 이미 구축된 프로젝트의 자동 유지보수 빌드(is_build_running=true, 수동 아님) → idle
        postprocess._ever_built.add("p1")
        postprocess.mark_build_running("p1", verify=False)   # 자동 빌드 running
        self.assertNotIn("p1", postprocess._manual_build_running)
        self.assertEqual(postprocess.get_graph_activity("p1"), "idle")

    def test_manual_rebuild_is_building_then_idle(self):
        # 수동 재구축(trigger_build) 실행 중 → building, 성공 후 → idle
        async def fake_sequence(project_id, verify):
            await asyncio.sleep(0)                          # 태스크가 즉시 끝나지 않게 양보
            return {"reference": 1}

        async def scenario():
            with patch.object(postprocess, "run_postprocess_sequence", fake_sequence):
                postprocess.trigger_build("p1", verify=False)
                during = postprocess.get_graph_activity("p1")   # 태스크 실행 전 — 수동 플래그 선다
                await asyncio.gather(*postprocess._build_tasks)  # 백그라운드 완료 대기
                after = postprocess.get_graph_activity("p1")
            return during, after

        during, after = asyncio.run(scenario())
        self.assertEqual(during, "building")
        self.assertEqual(after, "idle")                    # 성공 → _ever_built 서고 _manual_build_running 비워짐


if __name__ == "__main__":
    unittest.main()
