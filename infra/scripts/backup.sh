#!/usr/bin/env bash
# infra/scripts/backup.sh
# PostgreSQL·Neo4j 백업 (호스트 로컬).
#
# 무엇을 백업하나
#   postgres  사용자·프로젝트·대화, 암호화된 OAuth 자격증명, checkpoint  → 재생성 불가
#   neo4j     지식 그래프 + 벡터 인덱스                                   → 재생성은 되지만 OpenAI 비용
#   rabbitmq  백업하지 않는다 — 재수집으로 복구되는 일시 상태다
#
# 왜 Neo4j만 중단이 필요한가
#   온라인 백업(neo4j-admin database backup)은 Enterprise 전용이다. Community에서는
#   `neo4j-admin database dump`뿐이고, 이 명령은 실행 중인 서버에 마운트된 DB를 덤프하지
#   못한다("It is not possible to dump a database that is mounted in a running Neo4j server").
#   그래서 stop → dump → start 순서이며, 실측 중단 시간은 10초 안팎이다(671MiB 기준).
#   **Neo4j를 내린 채로 죽는 것이 이 스크립트의 최악 실패**라 trap으로 재기동을 보장한다.
#
# 덤프를 표준출력으로 받아 호스트가 파일을 쓴다(--to-stdout). 컨테이너에 백업 디렉터리를
# bind mount하면 컨테이너 uid가 호스트 디렉터리에 쓸 수 있어야 해서 권한 문제가 생긴다.
#
# 설정 (환경변수)
#   BACKUP_DIR             기본 $HOME/history-tracker-backups
#   BACKUP_RETENTION_DAYS  기본 14
#
# 사용법
#   ./backup.sh
#   BACKUP_DIR=/mnt/backup ./backup.sh
#
# cron 등록 예시 (매일 04:00) — 실패를 알 수 있게 출력을 로그로 남긴다:
#   0 4 * * * /path/to/infra/scripts/backup.sh >> /var/log/history-backup.log 2>&1

set -euo pipefail

# 컨테이너 이름은 compose의 container_name으로 고정돼 있어 compose 프로젝트 이름과 무관하다.
PG_CONTAINER="history-tracker-postgres"
NEO4J_CONTAINER="history-graph-neo4j"
PG_USER="history_tracker"
PG_DB="history_tracker"
NEO4J_DB="neo4j"

BACKUP_DIR="${BACKUP_DIR:-$HOME/history-tracker-backups}"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-14}"
TS="$(date -u +%Y%m%dT%H%M%SZ)"

log() { echo "[$(date -u +%H:%M:%SZ)] $*"; }
die() { echo "ERROR: $*" >&2; exit 1; }

# ── 사전 점검 ────────────────────────────────────────────────────
# 백업 도중에 알게 되는 것보다 시작 전에 막는 편이 낫다. 특히 Neo4j를 내린 뒤
# 디스크가 없어 실패하면 중단 시간만 길어진다.
for c in "$PG_CONTAINER" "$NEO4J_CONTAINER"; do
  docker inspect "$c" >/dev/null 2>&1 || die "컨테이너 $c 를 찾을 수 없습니다. 스택이 떠 있나요?"
done
mkdir -p "$BACKUP_DIR" || die "$BACKUP_DIR 를 만들 수 없습니다"
[ -w "$BACKUP_DIR" ] || die "$BACKUP_DIR 에 쓸 수 없습니다"

# 볼륨·이미지는 실행 중인 컨테이너에서 읽는다 — 볼륨 이름에는 compose 프로젝트 접두사가
# 붙고(예: docker_neo4j_data), 이미지 태그는 나중에 올라갈 수 있어 하드코딩하면 어긋난다.
NEO4J_VOLUME="$(docker inspect "$NEO4J_CONTAINER" \
  --format '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Name}}{{end}}{{end}}')"
NEO4J_IMAGE="$(docker inspect "$NEO4J_CONTAINER" --format '{{.Config.Image}}')"
[ -n "$NEO4J_VOLUME" ] || die "Neo4j /data 볼륨을 찾을 수 없습니다"

PG_OUT="$BACKUP_DIR/pg-$TS.dump"
NEO4J_OUT="$BACKUP_DIR/neo4j-$TS.dump"

# 덤프는 `.part`로 쓰고 **성공했을 때만** 최종 이름으로 바꾼다.
# 곧바로 최종 이름에 쓰면, 디스크가 차거나 도중에 죽었을 때 잘린 파일이 정상 백업과
# 이름·크기로 구분되지 않는다 — 재해 상황에서 최신 파일을 집어드는데 그게 반쪽인 상황.
# `.dump.part`는 보존 정리의 `*.dump` 글롭에도 걸리지 않는다.
PG_PART="$PG_OUT.part"
NEO4J_PART="$NEO4J_OUT.part"

# 정리 트랩은 **첫 덤프를 시작하기 전에** 건다. pg_dump 뒤에 걸면 그 단계가 실패했을 때
# `pg-<ts>.dump.part`가 그대로 남아, "미완성 산출물을 남기지 않는다"는 이 스크립트의
# 원칙이 첫 구간에서만 깨진다.
neo4j_was_stopped=0
cleanup() {
  # 미완성 산출물을 남기지 않는다. 성공 경로에서는 이미 mv 되어 없다.
  rm -f "$PG_PART" "$NEO4J_PART"
  if [ "$neo4j_was_stopped" = "1" ]; then
    neo4j_was_stopped=0   # EXIT 트랩과 신호 트랩이 겹쳐도 한 번만 돌게 한다
    log "Neo4j 재기동..."
    docker start "$NEO4J_CONTAINER" >/dev/null || echo "ERROR: Neo4j 재기동 실패 — 수동 확인 필요" >&2
  fi
}
# 신호 트랩은 정리 후 **반드시 종료해야 한다.** 핸들러가 그냥 복귀하면 bash가 중단을
# 삼키고 스크립트가 계속 돈다 — Ctrl-C를 눌렀는데 백업이 끝까지 진행되는 상태가 된다
# (실측으로 확인하고 고친 결함이다).
trap cleanup EXIT
trap 'cleanup; exit 130' INT
trap 'cleanup; exit 143' TERM

log "백업 시작 → $BACKUP_DIR"

# ── 1) PostgreSQL (무중단) ───────────────────────────────────────
log "PostgreSQL 덤프..."
docker exec "$PG_CONTAINER" pg_dump -Fc -U "$PG_USER" "$PG_DB" > "$PG_PART"
mv "$PG_PART" "$PG_OUT"
log "  $(basename "$PG_OUT") ($(du -h "$PG_OUT" | cut -f1))"

# ── 2) Neo4j (중단 필요) ─────────────────────────────────────────
# 이 시점부터 Neo4j가 내려가 있다. 어떤 경로로 빠져나가든 반드시 다시 올린다.
log "Neo4j 중단 (덤프 동안 그래프 조회가 멈춥니다)..."
docker stop "$NEO4J_CONTAINER" >/dev/null
neo4j_was_stopped=1

log "Neo4j 덤프..."
# neo4j-admin은 진행률을 stderr로 파일 수만큼(수백 줄) 뿜는다. cron 로그가 그걸로 덮이면
# 정작 봐야 할 오류가 묻히므로, stderr를 모아 뒀다가 **실패했을 때만** 보여준다.
neo4j_err="$(mktemp)"
if ! docker run --rm -v "$NEO4J_VOLUME:/data" "$NEO4J_IMAGE" \
     neo4j-admin database dump "$NEO4J_DB" --to-stdout > "$NEO4J_PART" 2>"$neo4j_err"; then
  echo "--- neo4j-admin 출력 ---" >&2
  cat "$neo4j_err" >&2
  rm -f "$neo4j_err"
  die "Neo4j 덤프 실패"
fi
rm -f "$neo4j_err"
mv "$NEO4J_PART" "$NEO4J_OUT"
log "  $(basename "$NEO4J_OUT") ($(du -h "$NEO4J_OUT" | cut -f1))"

cleanup
trap - EXIT INT TERM

# ── 3) 보존 기간 정리 ────────────────────────────────────────────
# 이번 백업이 성공한 뒤에만 지운다 — 실패했는데 옛 백업까지 지우면 아무것도 안 남는다.
# `.part`도 함께 지운다: SIGKILL처럼 trap이 못 도는 경우에 남을 수 있다.
log "보존 기간(${RETENTION_DAYS}일) 초과분 정리..."
find "$BACKUP_DIR" -maxdepth 1 -type f \
  \( -name 'pg-*.dump' -o -name 'neo4j-*.dump' -o -name '*.dump.part' \) \
  -mtime +"$RETENTION_DAYS" -print -delete

log "백업 완료"
