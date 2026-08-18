"""embed_batcher 마이크로배칭 단위 테스트 (오프라인 — embed_batch 모킹).

코얼레싱(개수/타임아웃 flush), 빈 텍스트, 실패 폴백, 이벤트 루프 재바인딩을 검증한다.
pytest-asyncio가 없어 asyncio.run()으로 async 시나리오를 구동한다
(tests/unit/test_consumer_retry.py, test_postprocess_status.py 관행).
"""

import asyncio
from unittest import mock

from graph import embed_batcher


def test_coalesces_concurrent_calls_into_single_batch(monkeypatch):
    embed_batcher.reset()
    monkeypatch.setattr(embed_batcher, "COALESCE_WINDOW_MS", 30)  # 넉넉한 대기창

    async def scenario():
        return await asyncio.gather(
            embed_batcher.embed_text_batched("a"),
            embed_batcher.embed_text_batched("bb"),
            embed_batcher.embed_text_batched("ccc"),
        )

    fake = mock.AsyncMock(return_value=[[0.1], [0.2], [0.3]])
    with mock.patch("graph.embed_batcher.embed_batch", new=fake):
        results = asyncio.run(scenario())

    fake.assert_awaited_once()
    assert fake.await_args.args[0] == ["a", "bb", "ccc"]
    assert results == [[0.1], [0.2], [0.3]]  # 각 호출자가 자기 순서의 벡터를 받는다


def test_count_flush_does_not_wait_for_timer(monkeypatch):
    embed_batcher.reset()
    monkeypatch.setattr(embed_batcher, "COALESCE_MAX", 2)
    # 타이머로는 시간 안에 끝날 수 없는 값으로 설정 — 개수 flush로만 통과 가능하게 한다.
    monkeypatch.setattr(embed_batcher, "COALESCE_WINDOW_MS", 5000)

    async def scenario():
        return await asyncio.wait_for(
            asyncio.gather(
                embed_batcher.embed_text_batched("a"),
                embed_batcher.embed_text_batched("b"),
            ),
            timeout=1.0,
        )

    fake = mock.AsyncMock(return_value=[[1.0], [2.0]])
    with mock.patch("graph.embed_batcher.embed_batch", new=fake):
        results = asyncio.run(scenario())

    fake.assert_awaited_once()
    assert results == [[1.0], [2.0]]


def test_single_call_flushes_after_timeout(monkeypatch):
    embed_batcher.reset()
    monkeypatch.setattr(embed_batcher, "COALESCE_WINDOW_MS", 30)

    fake = mock.AsyncMock(return_value=[[9.0]])
    with mock.patch("graph.embed_batcher.embed_batch", new=fake):
        result = asyncio.run(embed_batcher.embed_text_batched("solo"))

    fake.assert_awaited_once()
    assert fake.await_args.args[0] == ["solo"]
    assert result == [9.0]


def test_empty_text_returns_immediately_without_batching():
    embed_batcher.reset()

    fake = mock.AsyncMock(return_value=[])
    with mock.patch("graph.embed_batcher.embed_batch", new=fake):
        result = asyncio.run(embed_batcher.embed_text_batched("   "))

    assert result == []
    fake.assert_not_awaited()


def test_batch_failure_resolves_all_waiters_to_empty(monkeypatch):
    embed_batcher.reset()
    monkeypatch.setattr(embed_batcher, "COALESCE_MAX", 2)

    async def scenario():
        return await asyncio.gather(
            embed_batcher.embed_text_batched("a"),
            embed_batcher.embed_text_batched("b"),
        )

    fake = mock.AsyncMock(side_effect=RuntimeError("openai down"))
    with mock.patch("graph.embed_batcher.embed_batch", new=fake):
        results = asyncio.run(scenario())

    assert results == [[], []]  # 예외가 전파되지 않고 빈 벡터로 폴백


def test_timer_waking_after_count_flush_is_noop(monkeypatch):
    """개수 flush가 먼저 배치를 비운 뒤 타이머가 뒤늦게 깨어나는 시퀀스 — _flush의 빈
    pending 가드가 no-op으로 처리해야 한다(추가 embed_batch 호출·에러 없음)."""
    embed_batcher.reset()
    monkeypatch.setattr(embed_batcher, "COALESCE_MAX", 2)
    monkeypatch.setattr(embed_batcher, "COALESCE_WINDOW_MS", 30)

    async def scenario():
        results = await asyncio.gather(
            embed_batcher.embed_text_batched("a"),  # 첫 호출이 타이머를 띄운다
            embed_batcher.embed_text_batched("b"),  # 개수 도달 — 즉시 flush
        )
        await asyncio.sleep(0.06)  # 타이머가 빈 pending으로 깨어날 때까지 루프 안에서 대기
        return results

    fake = mock.AsyncMock(return_value=[[1.0], [2.0]])
    with mock.patch("graph.embed_batcher.embed_batch", new=fake):
        results = asyncio.run(scenario())

    fake.assert_awaited_once()  # 타이머 flush는 no-op — 추가 호출이 없어야 한다
    assert results == [[1.0], [2.0]]


def test_loop_isolation_rebinds_automatically():
    embed_batcher.reset()

    fake1 = mock.AsyncMock(return_value=[[1.0]])
    with mock.patch("graph.embed_batcher.embed_batch", new=fake1):
        result1 = asyncio.run(embed_batcher.embed_text_batched("first"))

    # reset() 없이 새 asyncio.run — 루프가 바뀐 것을 자동 감지해 재바인딩해야 한다.
    fake2 = mock.AsyncMock(return_value=[[2.0]])
    with mock.patch("graph.embed_batcher.embed_batch", new=fake2):
        result2 = asyncio.run(embed_batcher.embed_text_batched("second"))

    assert result1 == [1.0]
    assert result2 == [2.0]
