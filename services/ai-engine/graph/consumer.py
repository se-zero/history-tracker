import asyncio
import functools
import json
import logging
import os
from dataclasses import dataclass, field
from typing import Awaitable, Callable

import aio_pika

import alerts
from graph import ingest_backlog, queue_discovery
from graph.event_handler import handle, is_prefetchable_changeset, prepare_changeset, should_drop_tombstoned
from graph.postprocess import mark_dirty
# 큐 이름 규칙의 단일 출처는 queue_discovery — 여기서는 re-export한다(라우터·테스트가 consumer에서 import).
from graph.queue_discovery import PROJECT_QUEUE_PREFIX, project_queue_name  # noqa: F401

logger = logging.getLogger(__name__)

RABBITMQ_URL  = os.environ.get("RABBITMQ_URL", "amqp://guest:guest@localhost/")


def mask_amqp_url(url: str) -> str:
    """AMQP URL의 자격증명을 가려 로그에 남길 수 있는 형태로 만든다.

    비밀번호가 URL 안에 들어 있어(amqp://user:password@host/) 그대로 찍으면 원문이
    로그 파일에 남는다. 사용자명은 어느 계정으로 붙는지 봐야 진단이 되므로 남기고,
    비밀번호만 가린다. 자격증명이 없는 URL은 그대로 둔다.
    """
    scheme, sep, rest = url.partition("://")
    if not sep or "@" not in rest:
        return url
    creds, _, host = rest.rpartition("@")
    user, has_pw, _ = creds.partition(":")
    return f"{scheme}://{user}{':***' if has_pw else ''}@{host}"

# 수집 동시성. project 단위로 파티셔닝해 project 내부는 직렬(순서·노드 경합·Actor race 보호),
# project 간은 이 값까지 동시 처리한다. 기본 4 — 선결조건(rate_limiter의 OpenAI 페이싱,
# Actor 멱등화 ActorAlias, 이벤트당 fan-out 축소 #2/#6)이 모두 충족돼 활성화됐다.
# OpenAI 호출은 rate_limiter가 응답 헤더로 학습한 모델별 한도로 페이싱하고, BACKGROUND 게이트
# (OPENAI_BACKGROUND_CONCURRENCY)가 동시 요청 수를 묶으므로 올려도 한도를 넘지 않는다.
INGEST_MAX_CONCURRENCY = max(1, int(os.environ.get("INGEST_MAX_CONCURRENCY", "4")))
# ChangeSet look-ahead 깊이. 같은 파티션(project) 안에서 도착 순서대로 최대 이 개수만큼
# 뒤 이벤트의 LLM 준비 단계(파일 diff 요약 + 임베딩)를 앞 이벤트의 Neo4j 쓰기와 동시에 미리 실행한다.
# 쓰기(_process) 자체는 여전히 도착 순서 직렬 — 준비만 앞당긴다. 0 = 프리페치 비활성(킬스위치).
INGEST_CHANGESET_LOOKAHEAD = max(0, int(os.environ.get("INGEST_CHANGESET_LOOKAHEAD", "8")))
# RabbitMQ prefetch(미ack 상한) = 백프레셔. 큐당 9 = 쓰기 1 + look-ahead 8, 채널 전체 36 = 동시성 4 × 큐당.
# 큐당 상한(global_=False)이 한 프로젝트 큐의 독점을 막고, 채널 상한(global_=True)이 전체 미ack를 묶는다.
# 트레이드오프: 재시작 시 최대 채널 상한(INGEST_PREFETCH)만큼 재배달될 수 있다. 쓰기가 전부 MERGE라 멱등이라
# 그래프는 안전하고 LLM 준비 비용만 재발생한다(재계산일 뿐 데이터 손상 없음).
# 미설정 시 파생한다. 빈 문자열도 미설정 취급(or 폴백) — compose가 빈 값을 넘겨 파생 규칙을 살릴 수 있게 한다.
INGEST_PREFETCH_PER_QUEUE = max(1, int(os.environ.get("INGEST_PREFETCH_PER_QUEUE") or (1 + INGEST_CHANGESET_LOOKAHEAD)))
INGEST_PREFETCH = max(1, int(os.environ.get("INGEST_PREFETCH") or (INGEST_MAX_CONCURRENCY * INGEST_PREFETCH_PER_QUEUE)))

# 처리 실패 재시도/보관 큐. handle() 실패 시 consumer가 재시도 횟수를 헤더로 관리하며 직접 재발행한다
# (imperative). 재시도는 "원래 큐로" 돌아가야 하므로 fanout 익스체인지 + TTL 큐 + DLX(기본 익스체인지)를 쓴다:
# 재발행 때 라우팅 키에 원래 큐 이름을 적으면 fanout은 키를 무시하고 RETRY_QUEUE에 넣고,
# TTL 만료 후 DLX가 메시지 자신의 키(원래 큐 이름)로 원래 큐에 꽂는다.
RETRY_EXCHANGE = "history.retry"            # fanout
RETRY_QUEUE   = "history.retry"             # x-message-ttl + x-dead-letter-exchange "" → 원래 큐로 복귀
DLQ_QUEUE     = "history.dlq"               # 재시도 소진분 파킹 (replay 대상)
PARKING_QUEUE = "history.parking"           # malformed(JSON 파싱 실패) 전용 inspect 큐 (replay 미대상)
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
    - ChangeSet의 LLM 준비 단계(prepare)는 look-ahead로 미리 실행하되, 쓰기(_process)는
      여전히 파티션 순서 그대로 직렬 처리한다 — 준비만 앞당길 뿐 쓰기 순서는 바뀌지 않는다.
    - aio_pika에 직접 의존하지 않는다(process 콜백만 받음) — 오프라인 단위 테스트 가능.
    """

    def __init__(
        self,
        process: Callable[[object, object], Awaitable[None]],
        max_concurrency: int,
        *,
        prefetch: Callable[[object], object] | None = None,
        lookahead: int = 0,
    ) -> None:
        self._process = process
        self._sem = asyncio.Semaphore(max_concurrency)
        self._queues: dict[str, asyncio.Queue] = {}
        self._workers: dict[str, asyncio.Task] = {}
        self._prefetch = prefetch
        # look-ahead 노브 = 동시 실행 중인 prepare 수의 전역 상한(파티션 무관).
        self._prefetch_sem = asyncio.Semaphore(max(1, lookahead))
        self._lookahead = lookahead
        self._prefetch_tasks: set[asyncio.Task] = set()
        self._active: set[str] = set()  # 지금 처리 중인 파티션(워커당 최대 1건)

    def submit(self, key: str, item: object) -> None:
        """파티션 키의 큐에 작업을 넣는다. 해당 워커가 없으면 생성한다.
        put_nowait라 블로킹하지 않는다 — 백프레셔는 RabbitMQ prefetch가 담당."""
        queue = self._queues.get(key)
        if queue is None:
            queue = asyncio.Queue()
            self._queues[key] = queue
            self._workers[key] = asyncio.create_task(self._run_worker(key, queue))
        task = self._spawn_prefetch(item)
        queue.put_nowait((item, task))

    def _spawn_prefetch(self, item: object) -> asyncio.Task | None:
        """프리페치 대상이면 준비 코루틴을 백그라운드 태스크로 띄운다. 대상이 아니면 None.

        팩토리 호출 전에 비활성(lookahead<=0 또는 prefetch 미설정) 여부를 먼저 걸러
        미await 코루틴 경고를 방지한다.
        """
        if self._prefetch is None or self._lookahead <= 0:
            return None
        coro = self._prefetch(item)
        if coro is None:
            return None
        task = asyncio.create_task(self._gated(coro))
        self._prefetch_tasks.add(task)
        task.add_done_callback(self._prefetch_tasks.discard)
        return task

    async def _gated(self, coro):
        async with self._prefetch_sem:
            return await coro

    async def _run_worker(self, key: str, queue: asyncio.Queue) -> None:
        """한 파티션의 큐를 순서대로 비운다(직렬). 처리 실패는 격리하고 워커는 유지한다."""
        while True:
            item, prefetch_task = await queue.get()
            self._active.add(key)
            try:
                # 프리페치 결과 수거는 _sem(쓰기 동시성) 밖에서 한다 — LLM 준비 대기가
                # 쓰기 슬롯을 잠식하면 look-ahead의 이득(쓰기와 준비의 동시 진행)이 사라진다.
                prepared = None
                if prefetch_task is not None:
                    try:
                        prepared = await prefetch_task
                    except asyncio.CancelledError:
                        raise
                    except Exception:
                        logger.exception("프리페치 실패 — 인라인 재계산으로 폴백 (partition=%s)", key)
                async with self._sem:
                    await self._process(item, prepared)
            except asyncio.CancelledError:
                raise
            except Exception:
                # _process_event가 실패를 retry/dlq로 재발행하고 ack까지 마치므로 보통 여기 안 온다.
                # 여기 도달 = 재발행까지 실패한 경우 → 원본은 unacked로 남아 브로커가 재배달한다(유실 방지).
                # 삼켜서 한 메시지 실패가 워커(파티션) 전체를 죽이지 않게 한다.
                logger.exception("이벤트 처리/재발행 실패 — 메시지 건너뜀, 재배달 대기 (partition=%s)", key)
            finally:
                self._active.discard(key)
                queue.task_done()

    def pending(self, key: str) -> int:
        """해당 프로젝트의 대기 + 처리 중(0/1) 수. 디바운스 유휴 판정용."""
        return (q.qsize() if (q := self._queues.get(key)) else 0) + (1 if key in self._active else 0)

    async def close(self) -> None:
        """모든 워커·프리페치 태스크를 취소한다. 큐에 남아 미ack된 메시지는 RabbitMQ가 재배달한다(유실 없음)."""
        for task in self._workers.values():
            task.cancel()
        for task in self._workers.values():
            try:
                await task
            except asyncio.CancelledError:
                pass
        self._queues.clear()
        self._workers.clear()

        for task in list(self._prefetch_tasks):  # cancel 완료 콜백(discard)의 set 변형과 순회 분리
            task.cancel()
        for task in list(self._prefetch_tasks):
            try:
                await task
            except asyncio.CancelledError:
                pass
            except Exception:
                # 예외로 방금 끝난 태스크는 done callback(discard)이 아직 안 돌아 set에 남아 있을 수
                # 있다 — 그 예외가 close() 밖으로 새 종료 사유를 덮지 않게 여기서 삼키고 기록만 한다.
                logger.exception("프리페치 태스크가 예외로 종료된 채 남아 있었음 — 종료 계속")
        self._prefetch_tasks.clear()


@dataclass
class _ConsumerState:
    """한 번의 연결 수명 동안의 소비 상태. 끊기면 통째로 버리고 _run_consumer가 다시 만든다."""

    connection: aio_pika.abc.AbstractConnection
    channel: aio_pika.abc.AbstractChannel
    publish_channel: aio_pika.abc.AbstractChannel
    retry_exchange: aio_pika.abc.AbstractExchange
    dispatcher: _PartitionedDispatcher
    queues: dict[str, aio_pika.abc.AbstractQueue] = field(default_factory=dict)
    consumers: dict[str, str] = field(default_factory=dict)  # 큐 이름 → consumer tag

    async def subscribe(self, name: str, queue: aio_pika.abc.AbstractQueue) -> None:
        tag = await queue.consume(functools.partial(_on_message, state=self, origin_queue=name))
        self.queues[name] = queue
        self.consumers[name] = tag

    async def unsubscribe(self, name: str) -> None:
        queue = self.queues.pop(name, None)
        tag = self.consumers.pop(name, None)
        if queue is None or tag is None:
            return
        try:
            await queue.cancel(tag)
        except Exception:
            # 서버가 지운 큐는 이미 소비자가 취소돼 있어 cancel이 실패한다 — 정상 경로다.
            logger.debug("consumer 취소 실패 (queue=%s)", name, exc_info=True)

    def is_alive(self) -> bool:
        # 발행 채널도 본다 — 비-robust 연결이라 한 번 닫히면 되살아나지 않고, 그 상태로는 재시도·DLQ·parking
        # 발행이 전부 실패해 미ack 재배달만 반복되다 prefetch가 차서 조용히 멈춘다. 통째 재구성 대상이다.
        return not (
            self.connection.is_closed or self.channel.is_closed or self.publish_channel.is_closed
        )


# 현재 연결의 상태. 큐 삭제·발견 코드가 consumer를 import하지 않고도 구독을 바꾸도록 노출한다.
_state: _ConsumerState | None = None


async def _wait_partition_drained(dispatcher, project_id: str, timeout: float = 10.0) -> bool:
    """파티션의 대기·처리 중이 0이 될 때까지 0.1초 간격으로 기다린다. 시간 안에 비면 True."""
    deadline = asyncio.get_running_loop().time() + timeout
    while dispatcher.pending(project_id) > 0:
        if asyncio.get_running_loop().time() >= deadline:
            return False
        await asyncio.sleep(0.1)
    return True


async def delete_project_queue(project_id: str) -> bool:
    """프로젝트 큐를 지운다. 구독 취소 → 파티션 비움 대기 → 별도 연결에서 queue_delete.

    지웠으면 True, 이미 없었으면 False. 소비 채널에서 지우지 않는 이유: 이미 받아 둔 미ack
    메시지의 ack가 "unknown delivery tag"로, 큐가 없을 때의 404가 각각 소비 채널을 닫기 때문이다.
    """
    name = project_queue_name(project_id)
    state = _state
    if state is not None:
        await state.unsubscribe(name)  # 구독 중이 아니면 no-op
        # tombstone이 먼저 기록돼 남은 메시지가 즉시 ack되므로 보통 바로 빈다.
        if not await _wait_partition_drained(state.dispatcher, project_id):
            # 그래도 삭제는 진행한다 — 남은 메시지는 tombstone이라 ack만 하고 버려지는데, 큐가 먼저
            # 사라지면 그 ack가 소비 채널을 닫아 재시작 한 번이 일어날 뿐 데이터는 안전하다.
            logger.warning("파티션이 비기 전에 큐 삭제 진행 (project=%s)", project_id)
    connection = await aio_pika.connect_robust(RABBITMQ_URL)  # /dlq/stats와 같은 일회성 연결
    async with connection:
        channel = await connection.channel()
        try:
            await channel.queue_delete(name)
            return True
        except aio_pika.exceptions.ChannelNotFoundEntity:
            return False


async def start_consumer() -> None:
    """RabbitMQ에 연결하고 프로젝트별 큐(history.project.<id>)를 소비한다.

    예외 발생 시 5초 후 자동 재시작 — 연결·QoS·토폴로지·구독을 통째로 다시 만든다.
    실패 유형을 구분해 처리:
    - JSON 파싱 오류(영구 실패): ack 후 버림 — 재시도해도 동일한 오류
    - 처리 오류(일시 실패): retry 큐로 재발행 후 ack, 소진되면 DLQ
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


async def _apply_qos(channel: aio_pika.abc.AbstractChannel) -> None:
    # RabbitMQ에서 global_=False는 소비자(큐)별 상한, global_=True는 채널 전체 상한이다.
    # 순서가 중요하다 — 소비자별 qos(global=false)를 받으면 브로커가 채널 상한을 해제하므로
    # (RabbitMQ 3.13.7 실측: 채널 36 → 소비자별 9 순서로 걸면 채널 값이 0이 된다),
    # 공식 문서 예시대로 소비자별을 먼저, 채널 전체를 나중에 건다. 채널 전체 qos(global_qos)는
    # 2021년부터 deprecated(경고 로그 1줄)이지만 제거 버전은 없다.
    await channel.set_qos(prefetch_count=INGEST_PREFETCH_PER_QUEUE, global_=False)
    await channel.set_qos(prefetch_count=INGEST_PREFETCH, global_=True)


async def _run_consumer() -> None:
    global _state
    logger.info("RabbitMQ 연결 시도: %s", mask_amqp_url(RABBITMQ_URL))
    # connect_robust가 아니다 — robust 채널은 재연결 때 마지막 set_qos만 global_ 없이 재적용하고
    # 서버가 지운 큐를 재선언하며 서버발 소비자 취소를 알리지 않는다. 끊기면 예외를 던져
    # start_consumer 재시작 루프가 통째로 재구성하게 한다.
    connection = await aio_pika.connect(RABBITMQ_URL)

    async with connection:
        channel = await connection.channel()
        await _apply_qos(channel)

        # 재발행(retry/dlq/parking) 전용 채널 — 소비 채널과 분리한다.
        # AMQP는 채널 오류 시 그 채널 전체가 닫히므로, 발행 오류가 소비 루프를 중단시키지 않게 격리한다.
        publish_channel = await connection.channel()

        # 신규 재시도 경로: 원래 큐 이름을 라우팅 키로 실어 TTL 뒤 원래 큐로 복귀시킨다.
        retry_exchange = await publish_channel.declare_exchange(
            RETRY_EXCHANGE, aio_pika.ExchangeType.FANOUT, durable=True
        )
        retry_queue = await channel.declare_queue(RETRY_QUEUE, durable=True, arguments={
            "x-message-ttl": RETRY_DELAY_MS,
            "x-dead-letter-exchange": "",
        })
        await retry_queue.bind(retry_exchange)

        await channel.declare_queue(DLQ_QUEUE, durable=True)
        await channel.declare_queue(PARKING_QUEUE, durable=True)

        dispatcher = _PartitionedDispatcher(
            lambda item, prepared: _process_event(item, prepared, publish_channel, retry_exchange),
            INGEST_MAX_CONCURRENCY,
            prefetch=_changeset_prefetch,
            lookahead=INGEST_CHANGESET_LOOKAHEAD,
        )
        ingest_backlog.set_pending_provider(dispatcher.pending)
        state = _ConsumerState(
            connection=connection,
            channel=channel,
            publish_channel=publish_channel,
            retry_exchange=retry_exchange,
            dispatcher=dispatcher,
        )
        _state = state
        try:
            logger.info(
                "RabbitMQ consumer 준비 완료 (concurrency=%d, prefetch_per_queue=%d, "
                "prefetch_channel=%d, retry_max=%d, retry_delay_ms=%d)",
                INGEST_MAX_CONCURRENCY, INGEST_PREFETCH_PER_QUEUE, INGEST_PREFETCH,
                RETRY_MAX, RETRY_DELAY_MS,
            )
            await queue_discovery.run_forever(state)
        finally:
            # 구독을 먼저 끊는다 — 디스패처를 닫은 뒤에도 메시지가 들어오면 빈 디스패처에 새 워커가 생겨
            # 연결이 닫힌 뒤 영원히 대기하는 유령 태스크가 남는다. unsubscribe는 cancel 실패(연결이 이미
            # 죽은 경우)를 삼키므로 어떤 종료 경로에서도 안전하다.
            for name in list(state.consumers):
                await state.unsubscribe(name)
            ingest_backlog.set_pending_provider(None)
            _state = None
            await dispatcher.close()


async def _on_message(
    message: aio_pika.abc.AbstractIncomingMessage,
    *,
    state: _ConsumerState,
    origin_queue: str,
) -> None:
    try:
        await _route_message(message, state.dispatcher, state.publish_channel, origin_queue)
    except Exception:
        # consume 콜백의 예외는 aio-pika가 로그만 남기고 삼킨다. 미ack로 두면 재배달되므로 기록만 한다.
        logger.exception("메시지 라우팅 실패 — 미ack, 재배달 대기 (queue=%s)", origin_queue)


async def _route_message(
    message: aio_pika.abc.AbstractIncomingMessage,
    dispatcher: _PartitionedDispatcher,
    publish_channel: aio_pika.abc.AbstractChannel,
    origin_queue: str,
) -> None:
    """메시지를 파싱해 project 파티션으로 라우팅한다.

    JSON 파싱 실패(malformed·영구 오류)는 워커로 보내지 않고 parking 큐로 보낸 뒤 ack 한다 —
    재시도·replay가 무의미하므로 DLQ가 아닌 inspect 전용 parking 큐에 원본을 보존한다.
    정상 메시지는 (message, event, origin_queue)를 project_id 파티션 큐에 넣는다(순서 보존, 논블로킹).
    origin_queue는 실패 재시도 때 원래 큐로 돌려보내기 위해 들고 간다.
    """
    try:
        event = json.loads(message.body.decode())
    except (json.JSONDecodeError, UnicodeDecodeError):
        logger.error(
            "JSON 파싱 실패 — parking 큐로 이동 (queue=%s, routing_key=%s, body=%r)",
            origin_queue, message.routing_key, message.body[:200],
        )
        await _publish(publish_channel, PARKING_QUEUE, message.body, {"x-parse-error": "1"})
        alerts.record_parking(message.routing_key)
        await message.ack()
        return

    # project_id가 파티션 키 — 같은 프로젝트는 한 워커에서 도착 순서대로 직렬 처리.
    # projectId 없는 이벤트는 handle()이 어차피 건너뛴다(빈 키 파티션으로 라우팅).
    project_id = event.get("projectId") or ""
    dispatcher.submit(project_id, (message, event, origin_queue))


def _changeset_prefetch(item: object):
    """디스패처가 submit 시점에 호출 — 프리페치 대상이면 prepare_changeset 코루틴 객체를 반환한다.

    코루틴 객체만 반환하고 await하지 않는다 — _PartitionedDispatcher가 태스크로 감싸 실행한다.
    """
    event = item[1]  # type: ignore[index]
    if is_prefetchable_changeset(event):
        return prepare_changeset(event)
    return None


async def _process_event(
    item: object,
    prepared: object,
    publish_channel: aio_pika.abc.AbstractChannel,
    retry_exchange: aio_pika.abc.AbstractExchange,
) -> None:
    """워커가 호출 — 메시지 하나를 그래프에 반영한다.

    prepared: 같은 파티션에서 look-ahead로 미리 계산된 ChangeSet 준비 결과(없으면 None →
    handle이 인라인으로 재계산). 실패 라우팅 시(_handle_failure) prepared는 그냥 버려진다 —
    재배달된 메시지는 재소비 시 새로 프리페치되므로 재계산은 의도된 동작이다.

    성공: 후처리 디바운스 타이머 갱신 후 ack.
    tombstone: 끊긴 연결·지운 프로젝트의 이벤트는 처리하지 않고 ack만 한다 — 재시도·DLQ로 보내면
          소용없이 맴돌고, 디바운스 타이머도 건드리지 않는다(지운 프로젝트에 후처리 빌드가 돌거나
          최초 수집중 상태가 되살아나지 않게).
    실패: _handle_failure가 retry/dlq로 재발행하고 원본을 ack 한다.
          재발행까지 실패하면 예외를 raise → 원본 unacked로 남아 브로커가 재배달한다(유실 방지).
    """
    message, event, origin_queue = item  # type: ignore[misc]
    if should_drop_tombstoned(event):
        await message.ack()
        return
    try:
        await handle(event, prepared=prepared)
        # 처리 성공 — 해당 프로젝트의 후처리(시맨틱 링크) 디바운스 타이머 갱신.
        # 그 프로젝트 큐가 잠잠해지면 start_debounce_loop가 Layer 4 시퀀스를 1회 실행한다.
        # projectId 없는 이벤트는 handle에서 이미 건너뛰며, mark_dirty도 빈 값을 무시한다.
        mark_dirty(event.get("projectId") or "")
        await message.ack()
    except Exception:
        await _handle_failure(message, event, publish_channel, retry_exchange, origin_queue)


def _retry_count(message: aio_pika.abc.AbstractIncomingMessage) -> int:
    """지금까지의 재시도 횟수. 우리가 심는 x-retry-count 헤더로 관리(브로커 x-death 파싱 불필요)."""
    return int((message.headers or {}).get("x-retry-count", 0))


async def _handle_failure(
    message: aio_pika.abc.AbstractIncomingMessage,
    event: dict,
    publish_channel: aio_pika.abc.AbstractChannel,
    retry_exchange: aio_pika.abc.AbstractExchange,
    origin_queue: str,
) -> None:
    """처리 실패 메시지를 재시도 또는 DLQ로 라우팅하고 원본을 ack 한다.

    n < RETRY_MAX  → retry 익스체인지로 재발행(x-retry-count=n+1, 라우팅 키=원래 큐). TTL 만료 후 원래 큐로 돌아와 재소비.
    n >= RETRY_MAX → DLQ_QUEUE로 파킹.
    재발행(_publish)이 실패하면 ack하지 않고 예외를 전파 → 원본 unacked → 브로커 재배달(유실 방지).
    """
    n = _retry_count(message)
    project_id = event.get("projectId") or ""
    if n < RETRY_MAX:
        # fanout은 라우팅 키를 무시하고 RETRY_QUEUE에 넣고, 키는 메시지에 남아 DLX가 원래 큐로 되돌린다.
        await retry_exchange.publish(
            aio_pika.Message(body=message.body, headers={"x-retry-count": n + 1},
                             delivery_mode=aio_pika.DeliveryMode.PERSISTENT),
            routing_key=origin_queue,
        )
        logger.warning("처리 실패 — 재시도 %d/%d 예약 (delay=%dms, project=%s, queue=%s)",
                       n + 1, RETRY_MAX, RETRY_DELAY_MS, project_id, origin_queue)
    else:
        await _publish(publish_channel, DLQ_QUEUE, message.body, {"x-retry-count": n})
        logger.error("처리 실패 — 재시도 %d회 초과, DLQ 파킹 (project=%s, queue=%s)", RETRY_MAX, project_id, origin_queue)
        alerts.record_dlq_parked(project_id)
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
