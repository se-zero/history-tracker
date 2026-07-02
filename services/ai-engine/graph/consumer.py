import asyncio
import json
import logging
import os
from typing import Awaitable, Callable

import aio_pika

from graph.event_handler import handle
from graph.postprocess import mark_dirty

logger = logging.getLogger(__name__)

RABBITMQ_URL  = os.environ.get("RABBITMQ_URL", "amqp://guest:guest@localhost/")
EXCHANGE_NAME = "history.exchange"
QUEUE_NAME    = "history.events"

# 수집 동시성. project 단위로 파티셔닝해 project 내부는 직렬(순서·노드 경합·Actor race 보호),
# project 간은 이 값까지 동시 처리한다. 기본 4 — 선결조건(rate_limiter의 OpenAI 페이싱,
# Actor 멱등화 ActorAlias, 이벤트당 fan-out 축소 #2/#6)이 모두 충족돼 활성화됐다.
# OpenAI 호출은 rate_limiter가 RPM·TPM으로 페이싱하므로 올려도 Tier 한도를 넘지 않는다.
INGEST_MAX_CONCURRENCY = max(1, int(os.environ.get("INGEST_MAX_CONCURRENCY", "4")))
# RabbitMQ prefetch(미ack 상한) = 백프레셔. 동시성만큼은 받아둬야 워커가 놀지 않는다.
# 미설정 시 동시성과 동일하게 둔다.
INGEST_PREFETCH = max(1, int(os.environ.get("INGEST_PREFETCH", str(INGEST_MAX_CONCURRENCY))))

# 처리 실패 재시도/보관 큐. handle() 실패 시 consumer가 재시도 횟수를 헤더로 관리하며 직접 재발행한다
# (imperative). 메인 큐(history.events)는 변경하지 않는다 — 신규 큐만 추가하며 consumer만 선언·사용한다.
RETRY_QUEUE   = "history.events.retry"      # x-message-ttl 만료 후 메인으로 되돌아가는 지연 재시도 큐
DLQ_QUEUE     = "history.events.dlq"         # 재시도 소진분 파킹 (replay 대상)
PARKING_QUEUE = "history.events.parking"     # malformed(JSON 파싱 실패) 전용 inspect 큐 (replay 미대상)
RETRY_ROUTING_KEY = "event.retry"            # retry 만료 시 메인 exchange로 되돌리는 라우팅 키(event.# 매칭)
# 고정 간격 재시도. 기본 3분 × 20회 ≈ 1시간 창 — Neo4j 재기동·짧은 배포·OpenAI 일시 장애를
# 무인 복구하고, 그보다 긴 장애만 DLQ로 넘겨 사람이 보게 한다. 수집은 배경 작업이라 분 단위 지연은 무해.
RETRY_MAX      = max(0, int(os.environ.get("INGEST_RETRY_MAX", "20")))
RETRY_DELAY_MS = max(0, int(os.environ.get("INGEST_RETRY_DELAY_MS", "180000")))


class _PartitionedDispatcher:
    """project별 FIFO 직렬 + project 간 제한 동시성 디스패처.

    - 파티션 키(project_id)마다 asyncio.Queue 1개 + 워커 코루틴 1개를 둔다.
      같은 project의 메시지는 도착 순서대로 한 번에 하나씩만 처리된다
      (PR→commit 순서 의존, 같은 노드 동시 쓰기, Actor 생성 race를 직렬로 차단).
    - 전역 세마포어로 project 간 동시 처리 수를 INGEST_MAX_CONCURRENCY로 제한한다.
    - aio_pika에 직접 의존하지 않는다(process 콜백만 받음) — 오프라인 단위 테스트 가능.
    """

    def __init__(self, process: Callable[[object], Awaitable[None]], max_concurrency: int) -> None:
        self._process = process
        self._sem = asyncio.Semaphore(max_concurrency)
        self._queues: dict[str, asyncio.Queue] = {}
        self._workers: dict[str, asyncio.Task] = {}

    def submit(self, key: str, item: object) -> None:
        """파티션 키의 큐에 작업을 넣는다. 해당 워커가 없으면 생성한다.
        put_nowait라 블로킹하지 않는다 — 백프레셔는 RabbitMQ prefetch가 담당."""
        queue = self._queues.get(key)
        if queue is None:
            queue = asyncio.Queue()
            self._queues[key] = queue
            self._workers[key] = asyncio.create_task(self._run_worker(key, queue))
        queue.put_nowait(item)

    async def _run_worker(self, key: str, queue: asyncio.Queue) -> None:
        """한 파티션의 큐를 순서대로 비운다(직렬). 처리 실패는 격리하고 워커는 유지한다."""
        while True:
            item = await queue.get()
            try:
                async with self._sem:
                    await self._process(item)
            except asyncio.CancelledError:
                raise
            except Exception:
                # _process_event가 실패를 retry/dlq로 재발행하고 ack까지 마치므로 보통 여기 안 온다.
                # 여기 도달 = 재발행까지 실패한 경우 → 원본은 unacked로 남아 브로커가 재배달한다(유실 방지).
                # 삼켜서 한 메시지 실패가 워커(파티션) 전체를 죽이지 않게 한다.
                logger.exception("이벤트 처리/재발행 실패 — 메시지 건너뜀, 재배달 대기 (partition=%s)", key)
            finally:
                queue.task_done()

    async def close(self) -> None:
        """모든 워커를 취소한다. 큐에 남아 미ack된 메시지는 RabbitMQ가 재배달한다(유실 없음)."""
        for task in self._workers.values():
            task.cancel()
        for task in self._workers.values():
            try:
                await task
            except asyncio.CancelledError:
                pass
        self._queues.clear()
        self._workers.clear()


async def start_consumer() -> None:
    """RabbitMQ에 연결하고 history.events 큐를 소비한다.

    예외 발생 시 5초 후 자동 재시작.
    실패 유형을 구분해 처리:
    - JSON 파싱 오류(영구 실패): ack 후 버림 — 재시도해도 동일한 오류
    - 처리 오류(일시 실패): 예외를 raise → aio-pika가 nack(requeue=False) 처리
    """
    while True:
        try:
            await _run_consumer()
        except asyncio.CancelledError:
            logger.info("RabbitMQ consumer 종료")
            raise
        except Exception:
            logger.exception("RabbitMQ consumer 오류 — 5초 후 재시작")
            await asyncio.sleep(5)


async def _run_consumer() -> None:
    logger.info("RabbitMQ 연결 시도: %s", RABBITMQ_URL)
    connection = await aio_pika.connect_robust(RABBITMQ_URL)

    async with connection:
        channel = await connection.channel()
        await channel.set_qos(prefetch_count=INGEST_PREFETCH)

        # 재발행(retry/dlq/parking) 전용 채널 — 소비 채널과 분리한다.
        # AMQP는 채널 오류 시 그 채널 전체가 닫히므로, 발행 오류가 소비 루프를 중단시키지 않게 격리한다.
        publish_channel = await connection.channel()

        # pipeline-worker의 RabbitMqConfig와 동일한 설정으로 선언 (멱등)
        # consumer가 먼저 뜨는 경우에도 안전하게 동작하기 위해 선언 유지
        exchange = await channel.declare_exchange(
            EXCHANGE_NAME, aio_pika.ExchangeType.TOPIC, durable=True
        )
        queue = await channel.declare_queue(QUEUE_NAME, durable=True)
        # 바인딩은 pipeline-worker가 이미 event.# 로 선언 — 별도 추가 불필요
        # 단, consumer 선행 기동 시에도 메시지를 받을 수 있도록 동일 패턴 유지
        await queue.bind(exchange, routing_key="event.#")

        # 재시도/DLQ/parking 큐 선언 (consumer만 사용, 멱등). 메인 큐는 건드리지 않는다.
        # retry 큐: TTL 만료 시 메인 exchange로 event.retry(⊂ event.#) 라우팅해 되돌린다.
        await channel.declare_queue(RETRY_QUEUE, durable=True, arguments={
            "x-message-ttl": RETRY_DELAY_MS,
            "x-dead-letter-exchange": EXCHANGE_NAME,
            "x-dead-letter-routing-key": RETRY_ROUTING_KEY,
        })
        await channel.declare_queue(DLQ_QUEUE, durable=True)
        await channel.declare_queue(PARKING_QUEUE, durable=True)

        logger.info(
            "RabbitMQ consumer 준비 완료 (queue=%s, concurrency=%d, prefetch=%d, retry_max=%d, retry_delay_ms=%d)",
            QUEUE_NAME, INGEST_MAX_CONCURRENCY, INGEST_PREFETCH, RETRY_MAX, RETRY_DELAY_MS,
        )

        dispatcher = _PartitionedDispatcher(
            lambda item: _process_event(item, publish_channel), INGEST_MAX_CONCURRENCY
        )
        try:
            async with queue.iterator() as q:
                async for message in q:
                    await _route_message(message, dispatcher, publish_channel)
        finally:
            await dispatcher.close()


async def _route_message(
    message: aio_pika.abc.AbstractIncomingMessage,
    dispatcher: _PartitionedDispatcher,
    publish_channel: aio_pika.abc.AbstractChannel,
) -> None:
    """메시지를 파싱해 project 파티션으로 라우팅한다.

    JSON 파싱 실패(malformed·영구 오류)는 워커로 보내지 않고 parking 큐로 보낸 뒤 ack 한다 —
    재시도·replay가 무의미하므로 DLQ가 아닌 inspect 전용 parking 큐에 원본을 보존한다.
    정상 메시지는 (message, event)를 project_id 파티션 큐에 넣는다(순서 보존, 논블로킹).
    """
    try:
        event = json.loads(message.body.decode())
    except (json.JSONDecodeError, UnicodeDecodeError):
        logger.error(
            "JSON 파싱 실패 — parking 큐로 이동 (routing_key=%s, body=%r)",
            message.routing_key, message.body[:200],
        )
        await _publish(publish_channel, PARKING_QUEUE, message.body, {"x-parse-error": "1"})
        await message.ack()
        return

    # project_id가 파티션 키 — 같은 프로젝트는 한 워커에서 도착 순서대로 직렬 처리.
    # projectId 없는 이벤트는 handle()이 어차피 건너뛴다(빈 키 파티션으로 라우팅).
    project_id = event.get("projectId") or ""
    dispatcher.submit(project_id, (message, event))


async def _process_event(item: object, publish_channel: aio_pika.abc.AbstractChannel) -> None:
    """워커가 호출 — 메시지 하나를 그래프에 반영한다.

    성공: 후처리 디바운스 타이머 갱신 후 ack.
    실패: _handle_failure가 retry/dlq로 재발행하고 원본을 ack 한다.
          재발행까지 실패하면 예외를 raise → 원본 unacked로 남아 브로커가 재배달한다(유실 방지).
    """
    message, event = item  # type: ignore[misc]
    try:
        await handle(event)
        # 처리 성공 — 해당 프로젝트의 후처리(시맨틱 링크) 디바운스 타이머 갱신.
        # 그 프로젝트 큐가 잠잠해지면 start_debounce_loop가 Layer 4 시퀀스를 1회 실행한다.
        # projectId 없는 이벤트는 handle에서 이미 건너뛰며, mark_dirty도 빈 값을 무시한다.
        mark_dirty(event.get("projectId") or "")
        await message.ack()
    except Exception:
        await _handle_failure(message, event, publish_channel)


def _retry_count(message: aio_pika.abc.AbstractIncomingMessage) -> int:
    """지금까지의 재시도 횟수. 우리가 심는 x-retry-count 헤더로 관리(브로커 x-death 파싱 불필요)."""
    return int((message.headers or {}).get("x-retry-count", 0))


async def _handle_failure(
    message: aio_pika.abc.AbstractIncomingMessage,
    event: dict,
    publish_channel: aio_pika.abc.AbstractChannel,
) -> None:
    """처리 실패 메시지를 재시도 또는 DLQ로 라우팅하고 원본을 ack 한다.

    n < RETRY_MAX  → RETRY_QUEUE로 재발행(x-retry-count=n+1). TTL 만료 후 메인으로 돌아와 재소비.
    n >= RETRY_MAX → DLQ_QUEUE로 파킹.
    재발행(_publish)이 실패하면 ack하지 않고 예외를 전파 → 원본 unacked → 브로커 재배달(유실 방지).
    """
    n = _retry_count(message)
    project_id = event.get("projectId") or ""
    if n < RETRY_MAX:
        await _publish(publish_channel, RETRY_QUEUE, message.body, {"x-retry-count": n + 1})
        logger.warning("처리 실패 — 재시도 %d/%d 예약 (delay=%dms, project=%s)",
                       n + 1, RETRY_MAX, RETRY_DELAY_MS, project_id)
    else:
        await _publish(publish_channel, DLQ_QUEUE, message.body, {"x-retry-count": n})
        logger.error("처리 실패 — 재시도 %d회 초과, DLQ 파킹 (project=%s)", RETRY_MAX, project_id)
    await message.ack()


async def _publish(
    publish_channel: aio_pika.abc.AbstractChannel,
    queue: str,
    body: bytes,
    headers: dict,
) -> None:
    """default exchange로 특정 큐에 직접 발행한다(persistent). retry/dlq/parking 공용."""
    await publish_channel.default_exchange.publish(
        aio_pika.Message(body=body, headers=headers,
                         delivery_mode=aio_pika.DeliveryMode.PERSISTENT),
        routing_key=queue,
    )
