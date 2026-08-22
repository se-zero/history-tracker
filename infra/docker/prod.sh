#!/usr/bin/env bash
# infra/docker/prod.sh
# 프로덕션용 docker compose wrapper (base + prod 오버라이드 + --profile app).
#
# dev.sh와의 차이:
#   - 외부에 열리는 포트가 0이다. 웹은 127.0.0.1에만 바인딩하고 공개는 Cloudflare Tunnel이
#     맡는다. 인증 없는 /api/v1/collect·/api/v1/raw는 pipeline-worker 포트를 닫아 막는다
#     — docs/deployment.md 참고.
#   - 컨테이너별 메모리 상한, JVM·Neo4j 힙, 재시작 정책, 로그 로테이션이 적용된다.
#   - cloudflared(터널)가 함께 뜬다. .env의 TUNNEL_TOKEN이 필요하다.
#
# 배포 전 .env에 실제 값을 채워야 한다(시크릿·OAuth 콜백 URL·TUNNEL_TOKEN). 목록은
# .env.example과 docs/deployment.md의 체크리스트를 본다.
#
# 사용법:
#   ./prod.sh up -d --build
#   ./prod.sh down
#   ./prod.sh logs -f backend
#   ./prod.sh ps
#
#   ./prod.sh --no-tunnel up -d     터널 없이 기동(도메인·토큰 확보 전 점검용).
#                                   이 상태에서는 서버 자신만 http://localhost 로 접근할 수 있다.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ ! -f "$SCRIPT_DIR/.env" ]; then
  echo "⚠️  $SCRIPT_DIR/.env 가 없습니다." >&2
  echo "    cp .env.example .env 후 값을 채워 주세요." >&2
  exit 1
fi

cd "$SCRIPT_DIR"

# 터널은 별도 프로필이라 --no-tunnel로 뺄 수 있다. 도메인·토큰을 확보하기 전에
# 자원 상한·포트 폐쇄 같은 것을 점검하려면 이 경로가 필요하다.
PROFILES=(--profile app --profile tunnel)
if [ "${1:-}" = "--no-tunnel" ]; then
  shift
  PROFILES=(--profile app)
  echo "ℹ️  Cloudflare Tunnel 없이 기동합니다 — 외부에서 접근할 수 없습니다(127.0.0.1만)." >&2
fi

exec docker compose -f docker-compose.yml -f docker-compose.prod.yml "${PROFILES[@]}" "$@"
