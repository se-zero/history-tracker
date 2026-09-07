#!/usr/bin/env bash
# infra/scripts/restart-check.sh
# 컨테이너 재시작 감지 (호스트 cron 전용).
#
# 무엇을 보나
#   컨테이너별 `docker inspect`의 RestartCount를 직전 실행 값(상태 파일)과 비교해,
#   오른 컨테이너를 한 메시지로 묶어 Slack Incoming Webhook으로 보낸다.
#
# RestartCount의 의미 (그래서 이 값을 본다)
#   RestartCount는 **재시작 정책이 죽은 컨테이너를 되살릴 때만** 1씩 오른다
#   (OOM kill·크래시·기동 실패 루프). 운영자의 `docker stop/kill`은 정책을 취소해
#   오르지 않고, `docker restart`·`docker start`·컨테이너 재생성(`prod.sh up`)은
#   0으로 되돌린다. 그래서 "아무도 손대지 않았는데 죽었다 살아난" 경우만 알린다.
#   backup.sh의 Neo4j stop/start도 재시작 정책을 거치지 않으므로 알리지 않는다.
#   ai-engine 쪽 알림(DLQ·LLM 실패·잔액 소진)은 앱이 직접 보내며, 이 스크립트는
#   재시작만 맡는다. 한 달 뒤 클라우드 이전 시 관리형 컨테이너의 재시작 알림으로
#   대체하는 **폐기 예정** 스크립트다.
#
# 설정 (환경변수, .env는 읽지 않는다 — backup.sh와 같은 방침)
#   ALERT_SLACK_WEBHOOK_URL     비우면 stderr에 WARN 한 줄 후 기록만 하고 전송하지 않는다
#   RESTART_CHECK_STATE_FILE    기본 $HOME/.history-tracker-restart-check.state
#   RESTART_CHECK_CONTAINERS    공백 구분 컨테이너 목록. 기본은 스택 8개(아래 상수 참고)
#
# 사용법
#   ./restart-check.sh
#
# cron 등록 예시 (5분마다, backup.sh와 같은 사용자로 — 상태 파일이 $HOME 기준이다):
#   */5 * * * * ALERT_SLACK_WEBHOOK_URL=https://hooks.slack.com/services/... /path/to/infra/scripts/restart-check.sh >> /var/log/history-restart-check.log 2>&1
#
# 재현 절차
#   `docker restart`/`docker kill`로는 RestartCount가 오르지 않는다(정책을 거치지 않음).
#   컨테이너 안에서 PID 1에 SIGTERM을 보내야 정책이 개입해 되살린다:
#     docker exec <컨테이너> sh -c 'kill 1'
#   dev 스택(docker-compose.dev.yml)은 재시작 정책이 없어 먼저 정책을 걸어야 한다:
#     docker update --restart unless-stopped <컨테이너>

set -euo pipefail

RESTART_CHECK_CONTAINERS_DEFAULT="history-tracker-postgres history-graph-neo4j history-graph-rabbitmq history-graph-ai-engine history-tracker-backend history-tracker-pipeline-worker history-tracker-web history-tracker-tunnel"

WEBHOOK_URL="${ALERT_SLACK_WEBHOOK_URL:-}"
STATE_FILE="${RESTART_CHECK_STATE_FILE:-$HOME/.history-tracker-restart-check.state}"
CONTAINERS="${RESTART_CHECK_CONTAINERS:-$RESTART_CHECK_CONTAINERS_DEFAULT}"

log() { echo "[$(date -u +%H:%M:%SZ)] $*"; }
die() { echo "ERROR: $*" >&2; exit 1; }

# 상태 파일을 원자적으로 갱신한다. 임시 파일은 반드시 상태 파일과 같은 디렉터리에 만든다 —
# /tmp는 tmpfs(다른 파일시스템)인 경우가 흔해 mv가 복사+삭제로 바뀌어 원자성이 깨진다.
save_state() {
  mkdir -p "$(dirname "$STATE_FILE")"
  local tmp
  tmp="$(mktemp "${STATE_FILE}.XXXXXX")"
  printf '%s' "$new_state" > "$tmp"
  mv "$tmp" "$STATE_FILE"
}

docker info >/dev/null 2>&1 || die "Docker 데몬에 접근할 수 없습니다"

[ -n "$WEBHOOK_URL" ] || echo "WARN: ALERT_SLACK_WEBHOOK_URL이 비어 있어 기록만 하고 전송하지 않습니다" >&2

# 이전 실행의 기준선을 "<container> <count>" 맵으로 읽는다. 상태 파일이 없으면(첫 실행)
# 전부 신규 등장으로 취급해 알림 없이 기록만 한다.
declare -A prev_count=()
if [ -f "$STATE_FILE" ]; then
  while read -r name count; do
    [ -n "$name" ] || continue
    # 손상된 줄(count가 숫자가 아님)은 건너뛴다 — 그대로 두면 뒤의 [ -gt ] 비교에서
    # 오류가 나고 set -e로 스크립트가 죽어 매 주기 재시작 알림이 멈춘다. 이 컨테이너는
    # "첫 등장"으로 취급돼 이번 실행의 값으로 다시 기록된다.
    [[ "$count" =~ ^[0-9]+$ ]] || continue
    prev_count["$name"]="$count"
  done < "$STATE_FILE"
fi

alert_lines=""
new_state=""

for c in $CONTAINERS; do
  info="$(docker inspect "$c" --format '{{.RestartCount}} {{.State.Status}} {{.State.StartedAt}}' 2>/dev/null || true)"
  if [ -z "$info" ]; then
    log "$c 없음 — 건너뜀"
    continue
  fi
  count="${info%% *}"
  rest="${info#* }"
  status="${rest%% *}"
  started_at="${rest#* }"

  new_state="${new_state}${c} ${count}
"

  if [ -z "${prev_count[$c]+x}" ]; then
    continue
  fi
  old_count="${prev_count[$c]}"
  if [ "$count" -gt "$old_count" ]; then
    alert_lines="${alert_lines}• ${c}: RestartCount ${old_count}→${count} (status=${status}, StartedAt=${started_at})
"
  fi
  # 감소는 컨테이너 재생성으로 보고 기준선만 갱신한다 — 알림 없음.
done

if [ -z "$alert_lines" ]; then
  log "재시작 감지 없음"
  save_state
  exit 0
fi

hostname_val="$(hostname)"
message="[${hostname_val}] 컨테이너 재시작 감지 — 재시작 정책이 되살렸습니다. docker logs와 OOM 여부(docker inspect --format '{{.State.OOMKilled}}')를 확인하세요.
${alert_lines}"

log "재시작 감지: 아래 컨테이너"
printf '%s' "$alert_lines"

if [ -z "$WEBHOOK_URL" ]; then
  log "웹훅 미설정 — 전송하지 않고 기록만 합니다"
  save_state
  exit 0
fi

# JSON 이스케이프는 본문에 들어갈 수 있는 큰따옴표·백슬래시만 처리한다
# (컨테이너명·숫자·타임스탬프·고정 문구뿐이라 그 외 제어문자는 나오지 않는다).
escaped="${message//\\/\\\\}"
escaped="${escaped//\"/\\\"}"
escaped="${escaped//$'\n'/\\n}"

# 본문은 명령줄 인자가 아니라 stdin으로 넘긴다 — Windows Git Bash처럼 셸이 네이티브 curl.exe의
# argv를 코드페이지로 변환하는 환경에서는 한글·기호가 깨진다(2026-09-07 실측). stdin은 바이트 그대로 간다.
if ! printf '%s' "{\"text\": \"${escaped}\"}" \
  | curl -sS --fail --max-time 10 -o /dev/null \
      -H 'Content-type: application/json; charset=utf-8' \
      --data-binary @- \
      "$WEBHOOK_URL"; then
  die "Slack 전송 실패 — 상태 파일을 갱신하지 않습니다(다음 주기에 재시도)"
fi

# 전송에 성공했을 때만 기준선을 갱신한다 — 실패 시 다음 주기에 같은 알림이 다시 나가야 한다.
save_state

log "Slack 알림 전송 완료"
