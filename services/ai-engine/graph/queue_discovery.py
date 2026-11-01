import asyncio
import logging
import re
from urllib.parse import quote, unquote, urlsplit

import httpx
from aio_pika.exceptions import ChannelNotFoundEntity

from graph import ingest_backlog, ingest_tombstones

logger = logging.getLogger(__name__)

DISCOVERY_INTERVAL_SECONDS = 5
MANAGEMENT_PORT = 15672
PAGE_SIZE = 500

# 프로젝트 큐 이름 규칙의 단일 출처. consumer가 이 모듈을 상단에서 import해 re-export한다
# (반대 방향 import는 순환). 릴레이(pipeline-worker OutboxRelay.QUEUE_PREFIX)와 같은 값이어야 한다.
PROJECT_QUEUE_PREFIX = "history.project."


def project_queue_name(project_id: str) -> str:
    return PROJECT_QUEUE_PREFIX + project_id


def management_target(amqp_url: str) -> tuple[str, tuple[str, str], str]:
    """AMQP URL에서 관리 API의 (base URL, (user, password), vhost)를 파생한다. 새 env를 두지 않기 위함."""
    parts = urlsplit(amqp_url)
    user = unquote(parts.username) if parts.username else "guest"
    password = unquote(parts.password) if parts.password else "guest"
    vhost = "/" if parts.path in ("", "/") else unquote(parts.path[1:])
    return f"http://{parts.hostname}:{MANAGEMENT_PORT}", (user, password), vhost


async def fetch_project_queues(client: httpx.AsyncClient, base: str, vhost: str) -> dict[str, int]:
    """관리 API에서 history.project.* 큐의 {이름: messages}를 페이지 끝까지 모은다."""
    result: dict[str, int] = {}
    page = 1
    while True:
        response = await client.get(
            f"{base}/api/queues/{quote(vhost, safe='')}",
            params={
                "page": page,
                "page_size": PAGE_SIZE,
                "name": "^" + re.escape(PROJECT_QUEUE_PREFIX),
                "use_regex": "true",
                "columns": "name,messages",
            },
        )
        response.raise_for_status()
        body = response.json()
        if isinstance(body, dict):
            items = body.get("items", [])
            page_count = body.get("page_count")
        else:
            items = body
            page_count = None
        for item in items:
            result[item["name"]] = int(item.get("messages") or 0)
        # 이중 종료 조건 — page_count가 없거나 어긋나는 버전에서도 무한 루프를 막는다.
        if (page_count is not None and page >= page_count) or len(items) < PAGE_SIZE:
            return result
        page += 1


def diff(known: set[str], found: set[str]) -> tuple[list[str], list[str]]:
    return sorted(found - known), sorted(known - found)


async def tick(state, client: httpx.AsyncClient, base: str, vhost: str) -> None:
    """발견 1회. 연결이 끊겼으면 예외로 start_consumer 재시작 루프에 넘긴다."""
    # consumer가 이 모듈을 상단 import하므로 지연 import
    from graph import consumer

    if not state.is_alive():
        raise ConnectionError("RabbitMQ 연결이 닫힘 — consumer 재시작")

    try:
        found = await fetch_project_queues(client, base, vhost)
    except (httpx.HTTPError, ValueError):
        # 관리 API 일시 장애로 구독을 건드리지 않는다 — 스냅샷도 갱신하지 않고 직전 값을 유지한다.
        logger.warning("관리 API 큐 목록 조회 실패 — 이번 틱 건너뜀", exc_info=True)
        return
    # 관리 API의 name/use_regex 필터는 page 파라미터가 있을 때만 적용된다(3.13.7 실측). 필터가 빠지면
    # DLQ·parking·재시도 큐까지 "새 큐"로 보고 구독해 버리므로, 응답을 접두사로 한 번 더 거른다.
    found = {name: n for name, n in found.items() if name.startswith(PROJECT_QUEUE_PREFIX)}

    ingest_backlog.update_snapshot({name[len(PROJECT_QUEUE_PREFIX):]: n for name, n in found.items()})

    # 구 큐(history.events)도 state.consumers에 있으므로 프로젝트 큐 접두사만 본다.
    known = {n for n in state.consumers if n.startswith(PROJECT_QUEUE_PREFIX)}
    added, removed = diff(known, set(found))

    for name in removed:
        logger.info("프로젝트 큐 사라짐 — 구독 해제: %s", name)
        await state.unsubscribe(name)

    for name in added:
        project_id = name[len(PROJECT_QUEUE_PREFIX):]
        if ingest_tombstones.is_project_blocked(project_id):
            # 프로젝트 삭제 직후 릴레이가 미발행 행으로 큐를 되살린 경우 — 구독하지 않고 지운다.
            logger.info("지운 프로젝트의 고아 큐 — 구독 대신 삭제: project=%s", project_id)
            try:
                await consumer.delete_project_queue(project_id)
            except Exception:
                logger.warning("고아 큐 삭제 실패 — 다음 틱에 재시도: project=%s", project_id, exc_info=True)
            continue
        try:
            queue = await state.channel.declare_queue(name, passive=True)
        except ChannelNotFoundEntity:
            # passive 선언 실패는 브로커가 소비 채널을 닫는다 — 다음 틱의 is_alive가 재시작시킨다.
            logger.warning("목록과 선언 사이에 큐가 사라짐 — 채널이 닫혀 다음 틱에 재시작")
            return
        await state.subscribe(name, queue)
        logger.info("프로젝트 큐 구독: %s, messages=%d", name, found[name])


async def run_forever(state) -> None:
    """발견 틱을 5초마다 돈다. 연결이 끊기면 tick이 예외를 던져 consumer가 통째로 재구성된다."""
    from graph import consumer

    base, auth, vhost = management_target(consumer.RABBITMQ_URL)
    logger.info("프로젝트 큐 발견 시작 (management=%s, vhost=%s)", base, vhost)
    async with httpx.AsyncClient(auth=auth, timeout=5.0) as client:
        while True:
            await tick(state, client, base, vhost)
            await asyncio.sleep(DISCOVERY_INTERVAL_SECONDS)
