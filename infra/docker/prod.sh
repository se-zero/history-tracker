#!/usr/bin/env bash
# infra/docker/prod.sh
# 프로덕션용 docker compose wrapper (base + prod 오버라이드 + --profile app).
#
# dev.sh와의 차이는 오버라이드 파일 하나뿐이다:
#   - 호스트에 열리는 포트가 웹(80) 하나뿐이다. 인증 없는 /api/v1/collect·/api/v1/raw는
#     pipeline-worker 포트를 닫는 것으로 막는다 — docs/deployment.md 참고.
#   - 컨테이너별 메모리 상한, JVM·Neo4j 힙, 재시작 정책, 로그 로테이션이 적용된다.
#
# 배포 전 .env에 실제 값을 채워야 한다(시크릿·OAuth 콜백 URL). 목록은 .env.example과
# docs/deployment.md의 체크리스트를 본다.
#
# 사용법:
#   ./prod.sh up -d --build
#   ./prod.sh down
#   ./prod.sh logs -f backend
#   ./prod.sh ps

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ ! -f "$SCRIPT_DIR/.env" ]; then
  echo "⚠️  $SCRIPT_DIR/.env 가 없습니다." >&2
  echo "    cp .env.example .env 후 값을 채워 주세요." >&2
  exit 1
fi

cd "$SCRIPT_DIR"
exec docker compose -f docker-compose.yml -f docker-compose.prod.yml --profile app "$@"
