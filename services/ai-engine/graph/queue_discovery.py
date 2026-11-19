import asyncio
import logging
import os
import re
from urllib.parse import quote, unquote, urlsplit

import httpx
from aio_pika.exceptions import ChannelNotFoundEntity

from graph import ingest_backlog, ingest_tombstones

logger = logging.getLogger(__name__)

DISCOVERY_INTERVAL_SECONDS = 5
MANAGEMENT_PORT = 15672      # 평문 관리 API(amqp:// — 로컬·홈서버)
MANAGEMENT_TLS_PORT = 15671  # TLS 관리 API(amqps:// — Amazon MQ는 443·15671만 연다)
PAGE_SIZE = 500

# 프로젝트 큐 이름 규칙의 단일 출처. consumer가 이 모듈을 상단에서 import해 re-export한다
# (반대 방향 import는 순환). 릴레이(pipeline-worker OutboxRelay.QUEUE_PREFIX)와 같은 값이어야 한다.
PROJECT_QUEUE_PREFIX = "history.project."


def project_queue_name(project_id: str) -> str:
    return PROJECT_QUEUE_PREFIX + project_id


class _ManagementRequestLogFilter(logging.Filter):
    """httpx가 요청마다 INFO로 남기는 'HTTP Request: …' 줄 가운데 RabbitMQ 관리 API로 간 것만 떨어뜨린다.

    5초 폴링이라 하루 약 17,000줄이 쌓여 운영 로그를 덮는다. OpenAI 호출 줄(같은 httpx 로거)은
    429 확인에 쓰므로 남긴다. record.args 위치 대신 포맷된 메시지를 보는 것은 httpx 버전이
    바뀌어도 깨지지 않게 하기 위함이다.
    """

    def __init__(self, management_base: str) -> None:
        super().__init__()
        self.management_base = management_base

    def filter(self, record: logging.LogRecord) -> bool:
        return (self.management_base + "/") not in record.getMessage()


def install_httpx_filter(management_base: str) -> None:
    """httpx 로거에 관리 API 필터를 한 번만 붙인다 — consumer 재시작마다 run_forever가 다시 돌아도 중복으로 쌓이지 않는다."""
    httpx_logger = logging.getLogger("httpx")
    if any(
        isinstance(f, _ManagementRequestLogFilter) and f.management_base == management_base
        for f in httpx_logger.filters
    ):
        return
    httpx_logger.addFilter(_ManagementRequestLogFilter(management_base))


def _normalize_management_override(raw: str) -> str:
    """덮어쓰기 주소를 httpx가 요청 줄에 찍는 모양의 base로 정규화한다. 무효면 ValueError(고정 문구)를 던진다.

    로그 필터가 base + "/" 문자열 일치로 동작하므로 httpx 자신의 URL 정규화(기본 포트·호스트 대소문자)를
    그대로 쓴다. 예외 문구에는 입력 값을 넣지 않는다 — 사용자 정보에 든 비밀번호가 로그로 새면 안 된다.
    """
    try:
        url = httpx.URL(raw)
    except httpx.InvalidURL:
        # httpx의 예외 문구는 입력 조각(포트 문자열 등)을 담는다 — 연쇄하지 않는다
        raise ValueError("주소 형식 오류(포트 등)") from None
    if url.scheme not in ("http", "https"):
        raise ValueError("스킴이 http/https가 아님")
    if not url.host:
        raise ValueError("호스트 없음")
    if url.userinfo:
        # 인증은 AMQP URL에서만 가져온다. httpx는 요청 줄에 사용자 정보를 비밀번호까지 그대로 찍는다.
        raise ValueError("사용자 정보 포함")
    if url.query or url.fragment:
        # base 뒤에 /api/queues/...를 이어 붙이므로 쿼리·프래그먼트가 끼면 주소가 깨져 매 틱 실패한다
        raise ValueError("쿼리·프래그먼트 포함")
    return str(url).rstrip("/")


def management_target(amqp_url: str, override: str | None = None) -> tuple[str, tuple[str, str], str]:
    """관리 API의 (base URL, (user, password), vhost)를 정한다. 인증·vhost는 항상 AMQP URL에서 가져온다.

    base는 override(RABBITMQ_MANAGEMENT_URL)가 유효하면 그것을, 비었으면 AMQP 스킴으로 파생한다
    (amqp → http://host:15672, amqps → https://host:15671). 관리 포트는 AMQP 포트와 무관하게 고정이다.
    무효한 override는 예외 대신 ERROR 로그 후 파생 주소를 쓴다 — httpx.InvalidURL은 tick이 잡지 않아
    그대로 두면 consumer가 5초마다 재시작한다.
    """
    parts = urlsplit(amqp_url)
    user = unquote(parts.username) if parts.username else "guest"
    password = unquote(parts.password) if parts.password else "guest"
    vhost = "/" if parts.path in ("", "/") else unquote(parts.path[1:])
    if parts.scheme == "amqps":
        base = f"https://{parts.hostname}:{MANAGEMENT_TLS_PORT}"
    else:
        base = f"http://{parts.hostname}:{MANAGEMENT_PORT}"
    override = (override or "").strip()  # 빈 값(compose의 ${VAR:-})은 미설정과 같다
    if override:
        try:
            base = _normalize_management_override(override)
        except ValueError as exc:
            logger.error("RABBITMQ_MANAGEMENT_URL이 무효하다(%s) — AMQP URL에서 파생한 주소를 쓴다: %s", exc, base)
    return base, (user, password), vhost


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

    known = set(state.consumers)
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

    base, auth, vhost = management_target(consumer.RABBITMQ_URL, os.environ.get("RABBITMQ_MANAGEMENT_URL"))
    install_httpx_filter(base)
    logger.info("프로젝트 큐 발견 시작 (management=%s, vhost=%s)", base, vhost)
    async with httpx.AsyncClient(auth=auth, timeout=5.0) as client:
        while True:
            await tick(state, client, base, vhost)
            await asyncio.sleep(DISCOVERY_INTERVAL_SECONDS)
