"""운영자 알림 — Slack Incoming Webhook으로 다섯 종류의 사건을 던지고 잊는(fire-and-forget) 방식으로 알린다.

다섯 종류(kind):
  - openai_quota: OpenAI 잔액·쿼터 소진(insufficient_quota) — 즉시 알림
  - openai_unrecoverable: 재시도로 회복 안 되는 4xx(400/401/403/404/422) — 즉시 알림
  - openai_transient: 일시 오류(429 일반·5xx·연결·타임아웃) — 10분 창에 5건 이상일 때 알림
  - dlq_parked: DLQ 파킹(재시도 소진) — 즉시 알림
  - parking: malformed 이벤트 parking(JSON 파싱 실패) — 즉시 알림

억제: 같은 종류는 SUPPRESS_SECONDS(1시간)에 한 번만 보낸다. 억제된 동안의 발생 건수는
누적해뒀다가 다음 전송 본문에 "N건 추가 발생"으로 실어 보낸다(사라지지 않고 다음 알림에 흡수).

원칙:
  - 본 처리(수집·질의)를 절대 막거나 깨지 않는다 — record_*는 동기이고 raise하지 않으며,
    실제 전송은 asyncio 태스크로 던지고 잊는다. 실행 중인 루프가 없으면(동기 호출·테스트)
    카운트만 하고 전송은 건너뛴다.
  - 상태는 in-process 전역이다 — 프로세스가 재시작하면 카운터·억제 상태가 0으로 리셋된다.
    다중 인스턴스 배포 시에도 인스턴스별로 격리된다(수평 확장은 이 서비스의 다른 in-process
    상태와 같은 제약, docs 참고).
  - 웹훅 URL은 호출 시점에 환경변수(ALERT_SLACK_WEBHOOK_URL)에서 읽는다 — import 시점에
    읽거나 클라이언트를 만들지 않는다(오프라인 import 유지).
  - 감지는 OpenAI 호출의 단일 관문(openai_client.py)과 consumer의 파킹 지점, 이 두 곳에서만
    한다 — 호출부 하나하나에 알림 코드를 심지 않는다.
"""

import asyncio
import logging
import os
import time
from collections import deque
from datetime import datetime, timezone

import httpx

logger = logging.getLogger(__name__)

SERVICE_NAME = "ai-engine"
SUPPRESS_SECONDS = 3600.0
TRANSIENT_THRESHOLD = 5
TRANSIENT_WINDOW_SECONDS = 600.0
SEND_TIMEOUT_SECONDS = 5.0
DETAIL_MAX_CHARS = 200
# openai_client 게이트웨이가 이 상태 코드들을 회복 불가로 분류한다. agent/orchestrator.py·
# graph/llm_judge.py도 같은 정책을 쓰므로 이 상수를 그대로 import해 세 벌을 한 벌로 유지한다.
UNRECOVERABLE_STATUS = frozenset({400, 401, 403, 404, 422})

KIND_QUOTA = "openai_quota"
KIND_UNRECOVERABLE = "openai_unrecoverable"
KIND_TRANSIENT = "openai_transient"
KIND_DLQ = "dlq_parked"
KIND_PARKING = "parking"
KINDS = (KIND_QUOTA, KIND_UNRECOVERABLE, KIND_TRANSIENT, KIND_DLQ, KIND_PARKING)

_counters: dict[str, int] = {}          # kind -> 누적 발생 건수
_suppressed: dict[str, int] = {}        # kind -> 억제 창 안에서 안 보낸 건수(다음 전송에 흡수)
_last_alert_at: dict[str, float] = {}   # kind -> 마지막 전송 monotonic 시각
_transient_window: deque = deque()      # openai_transient 발생 시각(monotonic) 슬라이딩 윈도
_quota_exhausted_at: str | None = None  # 쿼터 소진 최초 감지 시각(UTC ISO8601)
_send_tasks: set = set()                # 띄운 전송 태스크 참조 보관(GC 방지 — postprocess.py 선례)
_alerts_sent = 0
_send_failures = 0
_url_missing_logged = False


def classify_openai_failure(exc: Exception) -> str:
    """openai_client 게이트웨이에서 잡은 예외를 세 종류 중 하나로 분류한다."""
    status = getattr(exc, "status_code", None)
    code = getattr(exc, "code", None)
    type_ = getattr(exc, "type", None)
    if status == 429 and "insufficient_quota" in (code, type_):
        return KIND_QUOTA
    if status in UNRECOVERABLE_STATUS:
        return KIND_UNRECOVERABLE
    return KIND_TRANSIENT


def record_openai_failure(exc: Exception, *, caller: str, model: str | None = None) -> str:
    """OpenAI 게이트웨이 실패를 기록한다. 절대 raise하지 않는다 — 관문의 예외 전파를 막으면 안 된다.

    kind를 먼저 계산해 try 밖에서 반환값으로 확보한다 — 이후 카운트·알림 단계가 예외를 던져도
    호출부(테스트 포함)가 분류 결과는 항상 받을 수 있게 한다.
    """
    kind = classify_openai_failure(exc)
    try:
        _record_openai_failure(kind, exc, caller, model)
    except Exception:
        logger.exception("알림 기록 실패 (무시)")
    return kind


def _record_openai_failure(kind: str, exc: Exception, caller: str, model: str | None) -> None:
    _counters[kind] = _counters.get(kind, 0) + 1
    now = time.monotonic()
    target = f"{caller}({model})" if model else caller

    if kind == KIND_QUOTA:
        global _quota_exhausted_at
        if _quota_exhausted_at is None:
            _quota_exhausted_at = datetime.now(timezone.utc).isoformat()
        text = (
            f"OpenAI 잔액·쿼터 소진(insufficient_quota) — {target} 호출 실패. "
            f"전 사용자의 수집·질의가 멈춥니다. OpenAI 결제 잔액을 확인하세요."
        )
        _maybe_alert(kind, now, text)
    elif kind == KIND_UNRECOVERABLE:
        text = f"OpenAI 회복 불가 오류 — {target} {_describe(exc)}"
        _maybe_alert(kind, now, text)
    else:
        _transient_window.append(now)
        _prune_transient(now)
        if len(_transient_window) >= TRANSIENT_THRESHOLD:
            window_minutes = int(TRANSIENT_WINDOW_SECONDS // 60)
            text = (
                f"OpenAI 일시 오류 {TRANSIENT_THRESHOLD}회/{window_minutes}분 — "
                f"최근: {target} {_describe(exc)}"
            )
            if _maybe_alert(kind, now, text):
                _transient_window.clear()


def record_openai_success() -> None:
    """OpenAI 게이트웨이 성공 — 쿼터 소진 플래그만 내린다.

    복구 알림은 보내지 않는다(성공/실패가 반복되는 플래핑 상황에서 알림이 계속 울리는 걸 방지).
    """
    global _quota_exhausted_at
    _quota_exhausted_at = None


def record_dlq_parked(project_id: str) -> None:
    """재시도 소진 이벤트가 DLQ에 파킹됐을 때 기록한다. 절대 raise하지 않는다."""
    try:
        _counters[KIND_DLQ] = _counters.get(KIND_DLQ, 0) + 1
        text = (
            f"DLQ 파킹 — 재시도 소진 이벤트 (project={project_id}). "
            f"GET /dlq/stats로 개수 확인, 원인 해소 후 POST /dlq/replay"
        )
        _maybe_alert(KIND_DLQ, time.monotonic(), text)
    except Exception:
        logger.exception("알림 기록 실패 (무시)")


def record_parking(routing_key: str) -> None:
    """malformed(JSON 파싱 실패) 이벤트가 parking 큐로 갔을 때 기록한다. 절대 raise하지 않는다."""
    try:
        _counters[KIND_PARKING] = _counters.get(KIND_PARKING, 0) + 1
        text = (
            f"malformed 이벤트 parking — JSON 파싱 실패 (routing_key={routing_key}). "
            f"history.events.parking 큐를 수동 확인하세요"
        )
        _maybe_alert(KIND_PARKING, time.monotonic(), text)
    except Exception:
        logger.exception("알림 기록 실패 (무시)")


def snapshot() -> dict:
    """/health 노출용 스냅샷. 무인증 엔드포인트라 URL·예외 본문은 절대 싣지 않는다."""
    now = time.monotonic()
    # transient_in_window는 마지막 기록 이후 조회 시점까지 시간이 흘렀을 수 있어
    # 여기서도 가지치기해야 창을 벗어난 옛 항목을 세지 않는다.
    _prune_transient(now)
    return {
        "webhook_configured": bool(_webhook_url()),
        "counters": {kind: _counters.get(kind, 0) for kind in KINDS},
        "suppressed": {kind: _suppressed.get(kind, 0) for kind in KINDS},
        "transient_in_window": len(_transient_window),
        "quota_exhausted_at": _quota_exhausted_at,
        "last_alert_age_seconds": {kind: int(now - ts) for kind, ts in _last_alert_at.items()},
        "alerts_sent": _alerts_sent,
        "send_failures": _send_failures,
    }


def reset() -> None:
    """테스트 전용 — 전역 상태를 초기화한다."""
    global _quota_exhausted_at, _alerts_sent, _send_failures, _url_missing_logged
    _counters.clear()
    _suppressed.clear()
    _last_alert_at.clear()
    _transient_window.clear()
    _quota_exhausted_at = None
    _send_tasks.clear()
    _alerts_sent = 0
    _send_failures = 0
    _url_missing_logged = False


async def drain() -> None:
    """테스트 전용 — 띄운 전송 태스크가 끝날 때까지 기다린다."""
    if _send_tasks:
        await asyncio.gather(*list(_send_tasks), return_exceptions=True)


def _maybe_alert(kind: str, now: float, text: str) -> bool:
    """억제 판정. 억제 창 밖이면 전송하고 True, 억제 중이면 카운트만 하고 False를 반환한다."""
    last = _last_alert_at.get(kind)
    if last is not None and now - last < SUPPRESS_SECONDS:
        _suppressed[kind] = _suppressed.get(kind, 0) + 1
        return False

    held = _suppressed.pop(kind, 0)
    if held:
        text = f"{text} (지난 {int(SUPPRESS_SECONDS // 60)}분간 같은 종류 {held}건 추가 발생)"
    _last_alert_at[kind] = now
    _dispatch(kind, text)
    return True


def _dispatch(kind: str, text: str) -> None:
    """테스트가 바꿔 끼우는 봉합선. 항상 로그를 남기고, 가능하면 Slack 전송 태스크를 띄운다."""
    message = f"[ai-engine] {text}"
    logger.error("ALERT(%s): %s", kind, message)

    url = _webhook_url()
    if not url:
        global _url_missing_logged
        if not _url_missing_logged:
            logger.warning("ALERT_SLACK_WEBHOOK_URL 미설정 — 알림을 로그로만 남깁니다")
            _url_missing_logged = True
        return

    try:
        loop = asyncio.get_running_loop()
    except RuntimeError:
        # 실행 중인 루프가 없음(동기 호출·테스트) — 카운트는 이미 끝났으니 전송만 건너뛴다.
        return

    task = loop.create_task(_post(url, message))
    _send_tasks.add(task)
    task.add_done_callback(_send_tasks.discard)


async def _post(url: str, message: str) -> None:
    global _alerts_sent, _send_failures
    try:
        async with httpx.AsyncClient(timeout=SEND_TIMEOUT_SECONDS) as client:
            resp = await client.post(url, json={"text": message})
            resp.raise_for_status()
        _alerts_sent += 1
    except Exception as exc:
        # str(exc)는 httpx 예외 문자열에 웹훅 URL이 그대로 들어가 로그에 시크릿을 남긴다.
        # 무인증 엔드포인트(/health)에도 안 실으니 여기 로그도 예외 타입·상태 코드만 남긴다.
        _send_failures += 1
        status = getattr(getattr(exc, "response", None), "status_code", None)
        logger.warning("Slack 알림 전송 실패 (무시): %s status=%s", type(exc).__name__, status)


def _webhook_url() -> str:
    """호출 시점에 읽는다 — import 시점 환경변수 읽기 금지 규칙(오프라인 import 유지)."""
    return os.environ.get("ALERT_SLACK_WEBHOOK_URL", "").strip()


def _prune_transient(now: float) -> None:
    cutoff = now - TRANSIENT_WINDOW_SECONDS
    while _transient_window and _transient_window[0] < cutoff:
        _transient_window.popleft()


def _describe(exc: Exception) -> str:
    status = getattr(exc, "status_code", None)
    code = getattr(exc, "code", None) or getattr(exc, "type", None)
    detail = f"HTTP {status} {code}: {exc}" if status is not None else str(exc)
    return detail[:DETAIL_MAX_CHARS]
