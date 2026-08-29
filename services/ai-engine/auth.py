"""내부 서비스 인증 — backend/pipeline-worker와 공유하는 X-Internal-Service-Token 규약.

ai-engine 라우터에는 인가가 전혀 없어 도달 가능한 누구든 그래프 삭제·DLQ 재투입·Actor 병합을
호출할 수 있었다. backend `InternalServiceAuthenticationFilter`와 같은 헤더·비교 방식을 그대로 쓴다.

라우터 단위(`include_router(dependencies=[Depends(verify_internal_token)])`)로 적용하는 이유:
`tests/unit/`의 일부 테스트(`test_query_debug.py`, `test_privacy_accounts.py` 등)는 TestClient를
거치지 않고 핸들러 함수를 직접 await한다 — 핸들러 파라미터로 토큰을 받으면 그 시그니처가 바뀌어
그 테스트들이 전부 깨진다. `dependencies=`는 라우팅 계층에서만 검증하고 핸들러 시그니처를
건드리지 않으므로 두 경로(HTTP 요청 / 단위 테스트 직접 호출) 모두 무사하다.
"""

import hmac
import os

from fastapi import Header, HTTPException

HEADER_NAME = "X-Internal-Service-Token"


def verify_internal_token(x_internal_service_token: str = Header(default="")) -> None:
    """요청 헤더의 내부 서비스 토큰을 timing-safe하게 검증한다.

    import 시점이 아니라 호출 시점에 os.environ을 읽는다 — 모듈 최상단에서 읽으면
    오프라인 import(테스트 포함)가 환경변수 존재를 강제받는다.
    """
    expected = os.environ.get("INTERNAL_SERVICE_TOKEN", "")
    # bytes로 비교한다 — compare_digest에 str을 넘기면 ASCII 전용이라 비ASCII 헤더가 오면
    # TypeError로 터져 401이 아니라 500이 나간다(스택 트레이스까지 노출된다).
    if not expected or not hmac.compare_digest(
        expected.encode("utf-8"), x_internal_service_token.encode("utf-8")
    ):
        raise HTTPException(status_code=401, detail="Invalid or missing internal service token")


def ensure_token_configured() -> None:
    """토큰 미설정 시 기동을 막는다 (lifespan에서 호출 — import 시점 강제가 아니라 fail-fast).

    설정 누락 상태로 기동하면 verify_internal_token이 빈 문자열끼리 비교로 항상 실패해
    "인증이 걸려 있는데 아무도 못 들어온다"는 조용한 장애가 되므로, 기동 자체를 막는다.
    """
    if not os.environ.get("INTERNAL_SERVICE_TOKEN"):
        raise RuntimeError("INTERNAL_SERVICE_TOKEN must be configured.")
