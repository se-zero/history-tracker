"""_PartitionedDispatcher의 ChangeSet 프리페치(look-ahead) 단위 테스트 (오프라인).

aio_pika 없이 fake process/prefetch 콜백만으로 디스패처 내부 동작을 검증한다.
async 함수는 pytest-asyncio 없이 asyncio.run()으로 구동한다(tests/unit/test_consumer_retry.py 관행).

핵심 불변식(파티션 FIFO 직렬 유지)과 look-ahead 동시성 노브(lookahead)가 서로 다른 축임을
각각의 테스트로 분리해 검증한다: 프리페치는 미리·동시에 실행돼도 되지만, process 호출 순서는
반드시 submit 순서와 같아야 한다.
"""

import asyncio

from graph.consumer import _PartitionedDispatcher


def test_process_order_preserved_despite_reversed_prefetch_completion():
    """프리페치 완료 시점이 뒤섞여도(역순 sleep) process는 submit 순서대로, 자기 이벤트의 prepared로 호출된다."""
    process_calls = []

    async def process(item, prepared):
        process_calls.append((item, prepared))

    def prefetch(item):
        async def _prepare():
            # 나중에 submit된 항목일수록 먼저 끝나도록 역순 대기 — 완료 순서를 일부러 교란한다.
            await asyncio.sleep((5 - item) * 0.02)
            return f"prepared-{item}"
        return _prepare()

    async def scenario():
        dispatcher = _PartitionedDispatcher(process, max_concurrency=4, prefetch=prefetch, lookahead=5)
        for i in range(5):
            dispatcher.submit("p1", i)
        await dispatcher._queues["p1"].join()
        await dispatcher.close()

    asyncio.run(scenario())

    assert [item for item, _ in process_calls] == [0, 1, 2, 3, 4]
    assert [prepared for _, prepared in process_calls] == [f"prepared-{i}" for i in range(5)]


def test_prefetch_runs_concurrently_while_process_blocked():
    """process가 앞 이벤트에 묶여 있어도 뒤 이벤트들의 프리페치는 동시에 시작된다."""
    prefetch_starts = []
    entered_process = asyncio.Event()
    process_gate = asyncio.Event()

    async def process(item, prepared):
        if item == 0:
            entered_process.set()
            await process_gate.wait()  # item 0 처리를 인위적으로 묶어둔다

    def prefetch(item):
        async def _prepare():
            prefetch_starts.append(item)
            await asyncio.sleep(0.05)
            return f"p-{item}"
        return _prepare()

    async def scenario():
        dispatcher = _PartitionedDispatcher(process, max_concurrency=4, prefetch=prefetch, lookahead=5)
        dispatcher.submit("p1", 0)
        await entered_process.wait()  # item 0의 process가 블록된 시점까지 대기
        dispatcher.submit("p1", 1)
        dispatcher.submit("p1", 2)
        await asyncio.sleep(0.02)  # 1, 2의 프리페치가 시작할 시간을 준다
        concurrent_started = len([i for i in prefetch_starts if i in (1, 2)])
        process_gate.set()
        await dispatcher._queues["p1"].join()
        await dispatcher.close()
        return concurrent_started

    concurrent_started = asyncio.run(scenario())
    assert concurrent_started >= 2


def test_lookahead_limits_concurrent_prefetch():
    """lookahead 노브가 동시 실행 중인 프리페치 수의 전역 상한이다."""
    current = 0
    max_concurrent = 0

    async def process(item, prepared):
        pass

    def prefetch(item):
        async def _prepare():
            nonlocal current, max_concurrent
            current += 1
            max_concurrent = max(max_concurrent, current)
            await asyncio.sleep(0.03)
            current -= 1
            return f"p-{item}"
        return _prepare()

    async def scenario():
        dispatcher = _PartitionedDispatcher(process, max_concurrency=4, prefetch=prefetch, lookahead=2)
        for i in range(5):
            dispatcher.submit("p1", i)
        await dispatcher._queues["p1"].join()
        await dispatcher.close()

    asyncio.run(scenario())
    assert max_concurrent <= 2


def test_non_prefetchable_item_gets_no_task_and_none_prepared():
    """팩토리가 None을 반환하면(비대상) process는 prepared=None을 받고, 프리페치 태스크는 추적되지 않는다."""
    calls = []

    async def process(item, prepared):
        calls.append((item, prepared))

    def prefetch(item):
        return None

    async def scenario():
        dispatcher = _PartitionedDispatcher(process, max_concurrency=4, prefetch=prefetch, lookahead=5)
        dispatcher.submit("p1", "x")
        await dispatcher._queues["p1"].join()
        assert dispatcher._prefetch_tasks == set()
        await dispatcher.close()

    asyncio.run(scenario())
    assert calls == [("x", None)]


def test_prefetch_failure_falls_back_to_none():
    """프리페치 코루틴이 예외를 던져도 process는 죽지 않고 prepared=None으로 정상 호출된다."""
    calls = []

    async def process(item, prepared):
        calls.append((item, prepared))

    def prefetch(item):
        async def _prepare():
            raise RuntimeError("boom")
        return _prepare()

    async def scenario():
        dispatcher = _PartitionedDispatcher(process, max_concurrency=4, prefetch=prefetch, lookahead=5)
        dispatcher.submit("p1", "x")
        await dispatcher._queues["p1"].join()
        await dispatcher.close()

    asyncio.run(scenario())
    assert calls == [("x", None)]


def test_close_cancels_pending_prefetch_tasks():
    """close()는 아직 끝나지 않은 프리페치 태스크를 취소하고 추적 set을 비운다."""
    async def process(item, prepared):
        pass

    def prefetch(item):
        async def _prepare():
            await asyncio.sleep(100)  # 테스트 시간 안에는 절대 안 끝남
            return "never"
        return _prepare()

    async def scenario():
        dispatcher = _PartitionedDispatcher(process, max_concurrency=4, prefetch=prefetch, lookahead=5)
        dispatcher.submit("p1", "x")
        await asyncio.sleep(0.01)  # 프리페치 태스크가 뜰 시간을 준다
        assert len(dispatcher._prefetch_tasks) == 1
        task = next(iter(dispatcher._prefetch_tasks))
        await dispatcher.close()
        assert task.cancelled()
        assert dispatcher._prefetch_tasks == set()

    asyncio.run(scenario())


def test_zero_lookahead_disables_prefetch():
    """lookahead=0이면 프리페치 팩토리 자체가 호출되지 않고, 전 이벤트가 prepared=None으로 처리된다."""
    calls = []
    prefetch_called = []

    async def process(item, prepared):
        calls.append((item, prepared))

    def prefetch(item):
        prefetch_called.append(item)

        async def _prepare():
            return "should-not-run"
        return _prepare()

    async def scenario():
        dispatcher = _PartitionedDispatcher(process, max_concurrency=4, prefetch=prefetch, lookahead=0)
        dispatcher.submit("p1", "x")
        await dispatcher._queues["p1"].join()
        assert dispatcher._prefetch_tasks == set()
        await dispatcher.close()

    asyncio.run(scenario())
    assert calls == [("x", None)]
    assert prefetch_called == []
