#!/usr/bin/env bash
# infra/docker/dev.sh
# setenv.sh를 자동 source한 뒤 docker compose --profile app <args> 를 실행.
#
# 사용법:
#   ./dev.sh up -d --build
#   ./dev.sh down
#   ./dev.sh logs -f backend
#   ./dev.sh ps

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SETENV="$SCRIPT_DIR/../../services/backend/setenv.sh"

if [ ! -f "$SETENV" ]; then
  echo "❌ $SETENV 가 없습니다." >&2
  echo "   services/backend/setenv.sh 를 만들고 GITHUB_* 환경변수를 export 하세요." >&2
  exit 1
fi

# shellcheck disable=SC1090
source "$SETENV"

cd "$SCRIPT_DIR"
exec docker compose --profile app "$@"
