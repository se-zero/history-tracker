"""커밋 메시지 임베딩 마이크로배처.

event_handler가 커밋마다 embed_text를 단건 호출하면 커밋 수만큼 OpenAI 임베딩 요청이
나간다. 이 모듈은 짧은 대기창(COALESCE_WINDOW_MS) 동안 도착한 단건 호출들을 모아
embed_batch 1콜로 코얼레싱해 요청 수를 줄인다.

수집 경로(event_handler) 전용이다. embed_batch는 항상 기본 priority(BACKGROUND)로 호출한다 —
질의 경로(tools/executor 등)는 INTERACTIVE로 지연을 최소화해야 하므로 배칭 대기창이 오히려
손해다. 질의 경로는 이 모듈을 거치지 않고 기존 embed_text를 그대로 쓴다.
"""

import asyncio
import os

from graph.embedder import embed_batch

# env 노브 — consumer.py의 관행을 따른다 (INGEST_* 접두사, 하한 클램프).
# 기본 8 = INGEST_CHANGESET_LOOKAHEAD 기본값과 정렬 — 배치에 합류하는 주체가 동시 실행 중인
# prepare들뿐이라 그 상한(lookahead)보다 큰 값은 개수 flush 경로를 사문화시킨다.
COALESCE_MAX = max(1, int(os.environ.get("INGEST_EMBED_COALESCE_MAX", "8")))
COALESCE_WINDOW_MS = max(0, int(os.environ.get("INGEST_EMBED_COALESCE_WINDOW_MS", "50")))

_pending: list[tuple[str, asyncio.Future]] = []
_flush_timer: asyncio.Task | None = None
_loop: asyncio.AbstractEventLoop | None = None
_tasks: set[asyncio.Task] = set()  # flush/타이머 태스크 참조 보관 (GC 방지 — postprocess.py _spawn_tracked 선례)


async def embed_text_batched(text: str) -> list[float]:
    """단일 텍스트를 코얼레싱 배치에 합류시켜 벡터를 받는다.

    embed_text와 동일한 계약을 유지한다: 빈 텍스트나 (배치 내부의) 호출 실패 시
    빈 리스트를 반환하고 예외를 던지지 않는다.
    """
    if not text or not text.strip():
        return []

    # 배칭 비활성 킬스위치: COALESCE_MAX=1이면 대기창·waiter 없이 즉시 단건 호출한다.
    # 프리페치 킬스위치(INGEST_CHANGESET_LOOKAHEAD=0)와 함께 써야 기존 직렬 동작이 완전 복원된다.
    if COALESCE_MAX <= 1:
        vectors = await embed_batch([text])
        return vectors[0] if vectors else []

    loop = _bind_loop()
    future: asyncio.Future = loop.create_future()
    _pending.append((text, future))

    # append → 분기 → 태스크 생성 구간에 await가 없어 단일 이벤트 루프에서 원자적이다.
    # 동시에 여러 호출이 들어와도 각자 이 구간을 끊기지 않고 실행하므로 len(_pending)
    # 판정이 어긋나지 않는다 — graph/postprocess.py _try_start_build와 같은 이유.
    if len(_pending) >= COALESCE_MAX:
        _spawn_tracked(_flush())
    elif _flush_timer is None or _flush_timer.done():
        # done() 확인: 죽은(취소된) 타이머 참조가 남아 있으면 새 타이머가 영영 안 떠
        # 개수 미달 pending이 매달린다.
        _start_timer()

    return await future


def reset() -> None:
    """테스트 전용 — 전역 상태(pending·타이머·루프 바인딩)를 초기화한다."""
    _discard_pending()
    global _loop
    _loop = None


def _bind_loop() -> asyncio.AbstractEventLoop:
    """현재 실행 중인 루프로 재바인딩이 필요한지 확인하고, 필요하면 이전 상태를 버린다.

    이전 루프에서 만든 Future에 다른 루프가 set_result를 호출하면 에러가 난다
    (graph/postprocess.py의 lazy 세마포어와 같은 이유). 테스트가 케이스마다 asyncio.run을
    새로 돌리는 관행이 있어 매 호출에서 루프 동일성을 확인해 자동으로 재바인딩한다.
    """
    global _loop
    current = asyncio.get_running_loop()
    if _loop is not current:
        _discard_pending()
        _loop = current
    return current


def _discard_pending() -> None:
    """대기 중인 pending·타이머 태스크 참조를 버린다 (재바인딩·reset 공용).

    이전 루프가 이미 닫혔을 수 있어 Future/Task를 직접 취소하지 않는다 — 참조만 놓으면
    닫힌 루프가 알아서 정리한다.
    """
    global _flush_timer
    _pending.clear()
    _flush_timer = None
    _tasks.clear()


def _start_timer() -> None:
    global _flush_timer
    _flush_timer = _spawn_tracked(_wait_and_flush())


async def _wait_and_flush() -> None:
    """COALESCE_WINDOW_MS 만큼 대기한 뒤 flush한다.

    대기 중 개수 flush(COALESCE_MAX 도달)가 먼저 일어나 _pending이 이미 비었으면
    _flush()의 가드가 no-op으로 처리한다.
    """
    global _flush_timer
    await asyncio.sleep(COALESCE_WINDOW_MS / 1000)
    _flush_timer = None
    await _flush()


async def _flush() -> None:
    """현재 pending을 스왑해 비우고 embed_batch 1콜로 처리한다.

    스왑(await 없음) 이후 도착하는 호출은 새 배치로 분리된다. embed_batch 호출이
    실패하면 모든 waiter에 빈 벡터로 set_result한다 — embed_text의 never-raise
    계약을 유지하기 위해 set_exception은 쓰지 않는다.
    """
    if not _pending:
        return
    batch, _pending[:] = _pending[:], []

    texts = [text for text, _ in batch]
    try:
        vectors = await embed_batch(texts)
    except Exception:
        vectors = [[] for _ in texts]

    for (_, fut), vector in zip(batch, vectors):
        if vector and not fut.done():  # 취소된 waiter 방어
            fut.set_result(vector)

    # 실패 반경 축소: 콜 하나가 통째로 거절되면(예: 8,192토큰 초과 입력 1건 → 400 → 청크 전체 [])
    # 같이 탄 정상 텍스트까지 전부 결손된다. 빈 벡터로 남은 항목만 1건씩 재시도해
    # 단건 호출 시절의 격리 수준을 복원한다 — 정상 경로 비용 0, 실패 시에만 추가 콜.
    # 재시도는 gather로 동시 실행한다 — API 전면 장애 시 직렬 재시도(각각 SDK 백오프 포함)가
    # 프리페치 슬롯을 오래 붙들어 파티션 워커까지 지연시키는 것을 막는다.
    retries = [
        _retry_single(text, fut)
        for (text, fut), vector in zip(batch, vectors)
        if not vector and not fut.done()
    ]
    if retries:
        await asyncio.gather(*retries)

    # embed_batch가 입력보다 짧게 반환하면(계약 파손) zip이 조용히 멈춰 남은 waiter가
    # 영원히 잠든다 — 워커 정지로 이어지므로 빈 벡터로 마저 깨운다.
    for _, fut in batch:
        if not fut.done():
            fut.set_result([])


async def _retry_single(text: str, fut: asyncio.Future) -> None:
    """배치 실패로 빈 벡터가 된 텍스트 1건을 단독 콜로 재시도해 waiter를 깨운다."""
    try:
        single = await embed_batch([text])
    except Exception:
        single = []
    if not fut.done():
        fut.set_result(single[0] if single else [])


def _spawn_tracked(coro) -> asyncio.Task:
    """코루틴을 백그라운드 태스크로 띄우고 참조를 모듈 전역에 보관한다(완료 시 자동 정리).

    참조를 들고 있지 않으면 asyncio가 실행 중인 태스크를 GC할 수 있다
    (graph/postprocess.py _spawn_tracked와 동일한 이유).
    """
    task = asyncio.create_task(coro)
    _tasks.add(task)
    task.add_done_callback(_tasks.discard)
    return task
