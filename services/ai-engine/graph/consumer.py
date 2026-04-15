import asyncio
import json
import logging
import os

import aio_pika

from graph.event_handler import handle

logger = logging.getLogger(__name__)

RABBITMQ_URL  = os.environ.get("RABBITMQ_URL", "amqp://guest:guest@localhost/")
EXCHANGE_NAME = "history.exchange"
QUEUE_NAME    = "history.events"


async def start_consumer() -> None:
    """RabbitMQ에 연결하고 history.events 큐를 소비한다.

    connect_robust를 사용해 연결 끊김 시 자동 재연결.
    실패 유형을 구분해 처리:
    - JSON 파싱 오류(영구 실패): ack 후 버림 — 재시도해도 동일한 오류
    - 처리 오류(일시 실패): 예외를 raise → aio-pika가 nack(requeue=False) 처리
    """
    logger.info("RabbitMQ 연결 시도: %s", RABBITMQ_URL)
    connection = await aio_pika.connect_robust(RABBITMQ_URL)

    async with connection:
        channel = await connection.channel()
        await channel.set_qos(prefetch_count=1)

        # pipeline-worker의 RabbitMqConfig와 동일한 설정으로 선언 (멱등)
        # consumer가 먼저 뜨는 경우에도 안전하게 동작하기 위해 선언 유지
        exchange = await channel.declare_exchange(
            EXCHANGE_NAME, aio_pika.ExchangeType.TOPIC, durable=True
        )
        queue = await channel.declare_queue(QUEUE_NAME, durable=True)
        # 바인딩은 pipeline-worker가 이미 event.# 로 선언 — 별도 추가 불필요
        # 단, consumer 선행 기동 시에도 메시지를 받을 수 있도록 동일 패턴 유지
        await queue.bind(exchange, routing_key="event.#")

        logger.info("RabbitMQ consumer 준비 완료 (queue=%s)", QUEUE_NAME)

        async with queue.iterator() as q:
            async for message in q:
                await _process_message(message)


async def _process_message(message: aio_pika.abc.AbstractIncomingMessage) -> None:
    # JSON 파싱: 영구 실패 → ack 후 버림
    try:
        event = json.loads(message.body.decode())
    except (json.JSONDecodeError, UnicodeDecodeError):
        logger.error(
            "JSON 파싱 실패 — 메시지 버림 (routing_key=%s, body=%r)",
            message.routing_key, message.body[:200],
        )
        await message.ack()
        return

    # 이벤트 처리: 일시 실패는 예외를 그대로 raise → aio-pika가 nack 처리
    async with message.process(requeue=False):
        await handle(event)
