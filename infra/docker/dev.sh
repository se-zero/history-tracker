#!/usr/bin/env bash
# infra/docker/dev.sh
# docker compose --profile app <args> wrapper.
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
exec docker compose --profile app "$@"
