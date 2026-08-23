#!/usr/bin/env bash
# infra/scripts/restore.sh
# backup.sh가 만든 덤프를 되돌린다. **기존 데이터를 덮어쓴다.**
#
# 인자 없이 실행하면 아무것도 하지 않고 사용법만 출력한다 — 실수로 운영 DB를 날리는
# 경로를 만들지 않기 위해서다. 복원할 파일을 반드시 명시해야 한다.
#
# 왜 앱 컨테이너를 내리나
#   backend·pipeline-worker가 커넥션을 물고 있으면 pg_restore --clean이 객체를 지우지
#   못한다. Neo4j도 실행 중인 서버에 마운트된 DB는 교체할 수 없다(dump와 같은 제약).
#
# 사용법
#   ./restore.sh --pg <파일> [--neo4j <파일>] [--yes]
#   ./restore.sh --neo4j <파일>
#
#   --yes  확인 프롬프트를 건너뛴다 (스크립트에서 호출할 때)
#
# 복구 후에는 로그인·그래프 조회까지 실제로 확인한다. 복원해 본 적 없는 백업은 백업이 아니다.

set -euo pipefail

PG_CONTAINER="history-tracker-postgres"
NEO4J_CONTAINER="history-graph-neo4j"
APP_CONTAINERS=("history-tracker-backend" "history-tracker-pipeline-worker" "history-graph-ai-engine")
PG_USER="history_tracker"
PG_DB="history_tracker"
NEO4J_DB="neo4j"

PG_FILE=""
NEO4J_FILE=""
ASSUME_YES=0

usage() {
  cat >&2 <<'EOF'
backup.sh가 만든 덤프를 되돌립니다. 기존 데이터를 덮어씁니다.

사용법:
  restore.sh --pg <파일> [--neo4j <파일>] [--yes]
  restore.sh --neo4j <파일> [--yes]

  --pg <파일>     PostgreSQL 덤프 (pg-<타임스탬프>.dump)
  --neo4j <파일>  Neo4j 덤프 (neo4j-<타임스탬프>.dump)
  --yes           확인 프롬프트를 건너뜁니다

복구 후에는 로그인과 그래프 조회가 되는지 실제로 확인하세요.
EOF
  exit 1
}

log() { echo "[$(date -u +%H:%M:%SZ)] $*"; }
die() { echo "ERROR: $*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --pg)    PG_FILE="${2:-}";    shift 2 ;;
    --neo4j) NEO4J_FILE="${2:-}"; shift 2 ;;
    --yes)   ASSUME_YES=1;        shift ;;
    -h|--help) usage ;;
    *) die "알 수 없는 인자: $1" ;;
  esac
done

# 인자가 하나도 없으면 사용법만 출력하고 끝낸다 (기본 동작이 파괴적이면 안 된다).
[ -n "$PG_FILE" ] || [ -n "$NEO4J_FILE" ] || usage
[ -z "$PG_FILE" ]    || [ -f "$PG_FILE" ]    || die "파일을 찾을 수 없습니다: $PG_FILE"
[ -z "$NEO4J_FILE" ] || [ -f "$NEO4J_FILE" ] || die "파일을 찾을 수 없습니다: $NEO4J_FILE"

echo "다음을 복원합니다 — 대상의 기존 데이터는 사라집니다:"
[ -n "$PG_FILE" ]    && echo "  PostgreSQL($PG_DB)  ← $PG_FILE"
[ -n "$NEO4J_FILE" ] && echo "  Neo4j($NEO4J_DB)     ← $NEO4J_FILE"
if [ "$ASSUME_YES" != "1" ]; then
  printf "계속하려면 'yes'를 입력하세요: "
  read -r answer
  [ "$answer" = "yes" ] || die "취소했습니다"
fi

# ── 앱 중단 ──────────────────────────────────────────────────────
# 복원 중 앱이 붙어 있으면 커넥션 때문에 --clean이 실패하고, 반쯤 복원된 DB에
# 쓰기가 섞일 수도 있다. 어떤 경로로 끝나든 다시 올린다.
stopped_apps=()
restart_apps() {
  [ ${#stopped_apps[@]} -eq 0 ] && return 0
  log "재기동..."
  docker start "${stopped_apps[@]}" >/dev/null || echo "ERROR: 재기동 실패 — 수동 확인 필요" >&2
  stopped_apps=()   # EXIT 트랩과 신호 트랩이 겹쳐도 한 번만 돌게 한다
}
# 신호 트랩은 정리 후 **반드시 종료해야 한다.** 핸들러가 그냥 복귀하면 bash가 중단을
# 삼켜서, 사용자가 Ctrl-C로 멈췄다고 생각한 뒤에도 덮어쓰기가 끝까지 진행된다.
trap restart_apps EXIT
trap 'restart_apps; exit 130' INT
trap 'restart_apps; exit 143' TERM

log "앱 중단..."
for c in "${APP_CONTAINERS[@]}"; do
  if [ "$(docker inspect -f '{{.State.Running}}' "$c" 2>/dev/null)" = "true" ]; then
    docker stop "$c" >/dev/null
    stopped_apps+=("$c")
  fi
done

# ── PostgreSQL ───────────────────────────────────────────────────
if [ -n "$PG_FILE" ]; then
  log "PostgreSQL 복원..."
  # --clean --if-exists: 기존 객체를 지우고 새로 만든다. 없는 객체 DROP은 무시.
  docker exec -i "$PG_CONTAINER" \
    pg_restore --clean --if-exists --no-owner -U "$PG_USER" -d "$PG_DB" < "$PG_FILE"
  log "  완료"
fi

# ── Neo4j ────────────────────────────────────────────────────────
if [ -n "$NEO4J_FILE" ]; then
  # Neo4j 관련 조회는 이 분기 안에서 한다 — --pg만 복구하는 경우에 Neo4j가 없거나
  # 꺼져 있어도 실패하지 않아야 한다.
  NEO4J_VOLUME="$(docker inspect "$NEO4J_CONTAINER" \
    --format '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Name}}{{end}}{{end}}')"
  NEO4J_IMAGE="$(docker inspect "$NEO4J_CONTAINER" --format '{{.Config.Image}}')"
  [ -n "$NEO4J_VOLUME" ] || die "Neo4j /data 볼륨을 찾을 수 없습니다"

  log "Neo4j 중단..."
  docker stop "$NEO4J_CONTAINER" >/dev/null
  stopped_apps+=("$NEO4J_CONTAINER")

  log "Neo4j 복원..."
  docker run --rm -i -v "$NEO4J_VOLUME:/data" "$NEO4J_IMAGE" \
    neo4j-admin database load "$NEO4J_DB" --from-stdin --overwrite-destination < "$NEO4J_FILE"
  log "  완료"
fi

restart_apps
stopped_apps=()
trap - EXIT INT TERM

log "복원 완료 — 로그인과 그래프 조회가 되는지 확인하세요"
