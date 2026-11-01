"""_debounce_tick의 유휴 조건(디바운스 경과 + 큐 백로그 0 + 파티션 대기 0) 단위 테스트."""

import time
import unittest
from unittest.mock import patch

from graph import ingest_backlog, postprocess


class DebounceTickTest(unittest.TestCase):
    def setUp(self):
        postprocess._build_status.clear()
        postprocess._dirty.clear()
        postprocess._last_build_at.clear()
        postprocess._last_event_at.clear()
        postprocess._ever_built.clear()
        postprocess._manual_build_running.clear()
        postprocess._build_semaphore = None
        ingest_backlog.reset()
        self.addCleanup(ingest_backlog.reset)
        # 30초 이상 지난 이벤트로 dirty 표시
        postprocess._dirty.add("p1")
        postprocess._last_event_at["p1"] = time.monotonic() - postprocess.DEBOUNCE_SECONDS - 1
        self.started = []
        p1 = patch.object(postprocess, "_try_start_build", side_effect=lambda pid, verify: self.started.append(pid) or True)
        # _execute_build는 async 함수라 side_effect로 막으면 patch가 AsyncMock을 만들어 호출 결과가
        # 코루틴이 되고(_spawn_tracked도 Mock이라 await 안 됨) "never awaited" 경고가 난다 — plain 함수로 교체
        p2 = patch.object(postprocess, "_execute_build", new=lambda *a, **k: None)
        p3 = patch.object(postprocess, "_spawn_tracked")
        for p in (p1, p2, p3):
            p.start()
            self.addCleanup(p.stop)

    def test_queue_backlog_blocks_build(self):
        ingest_backlog.update_snapshot({"p1": 5})
        postprocess._debounce_tick(time.monotonic())
        self.assertEqual(self.started, [])
        self.assertIn("p1", postprocess._dirty)

    def test_dispatcher_pending_blocks_build(self):
        ingest_backlog.update_snapshot({"p1": 0})
        ingest_backlog.set_pending_provider(lambda pid: 2)
        postprocess._debounce_tick(time.monotonic())
        self.assertEqual(self.started, [])
        self.assertIn("p1", postprocess._dirty)

    def test_drained_starts_build_and_clears_dirty(self):
        ingest_backlog.update_snapshot({"p1": 0})
        ingest_backlog.set_pending_provider(lambda pid: 0)
        postprocess._debounce_tick(time.monotonic())
        self.assertEqual(self.started, ["p1"])
        self.assertNotIn("p1", postprocess._dirty)

    def test_debounce_not_elapsed_blocks_even_if_drained(self):
        postprocess._last_event_at["p1"] = time.monotonic()
        postprocess._debounce_tick(time.monotonic())
        self.assertEqual(self.started, [])
        self.assertIn("p1", postprocess._dirty)
