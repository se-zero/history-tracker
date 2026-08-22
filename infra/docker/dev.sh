#!/usr/bin/env bash
# infra/docker/dev.sh
# 로컬 개발용 docker compose wrapper (base + dev 오버라이드 + --profile app).
#
# 포트는 base가 아니라 docker-compose.dev.yml이 소유한다 — 이 스크립트를 거치지 않고
# `docker compose up`을 직접 치면 어떤 포트도 열리지 않는다. 이유는 base 파일 상단 주석 참고.
# 프로덕션 기동은 ./prod.sh 를 쓴다.
#
# 환경변수는 같은 디렉토리의 `.env` 파일에서 자동으로 로드됩니다 (docker compose 표준).
# `.env`는 `.env.example`을 복사해 만드세요:
#   cp .env.example .env
#
# 사용법:
#   ./dev.sh up -d --build
#   ./dev.sh down
#   ./dev.sh logs -f backend
#   ./dev.sh ps

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ ! -f "$SCRIPT_DIR/.env" ]; then
  echo "⚠️  $SCRIPT_DIR/.env 가 없습니다." >&2
  echo "    cp .env.example .env 후 값을 채워 주세요." >&2
  exit 1
fi

cd "$SCRIPT_DIR"
exec docker compose -f docker-compose.yml -f docker-compose.dev.yml --profile app "$@"
