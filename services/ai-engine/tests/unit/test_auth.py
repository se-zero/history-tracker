"""내부 서비스 토큰 검증 — 보안 경계라 단위 테스트로 못 박는다.

라우터 적용 방식(`include_router(dependencies=[...])`)은 test_api_routes.py가 경로 집합만 보므로
여기서 검증 함수 자체의 동작을 확인한다.
"""

import os

import pytest
from fastapi import HTTPException

from auth import ensure_token_configured, verify_internal_token


@pytest.fixture
def configured_token(monkeypatch):
    monkeypatch.setenv("INTERNAL_SERVICE_TOKEN", "shared-secret")
    return "shared-secret"


def test_일치하는_토큰은_통과한다(configured_token):
    assert verify_internal_token(configured_token) is None


def test_토큰이_다르면_401(configured_token):
    with pytest.raises(HTTPException) as exc:
        verify_internal_token("wrong-secret")
    assert exc.value.status_code == 401


def test_헤더가_없으면_401(configured_token):
    # Header(default="")라 미전송 시 빈 문자열이 들어온다
    with pytest.raises(HTTPException) as exc:
        verify_internal_token("")
    assert exc.value.status_code == 401


def test_비ASCII_헤더도_500이_아니라_401(configured_token):
    """compare_digest에 str을 넘기면 ASCII 전용이라 TypeError가 나 500이 된다.

    bytes로 비교하는 구현이라야 이 케이스가 401로 끝난다 — 회귀 방지.
    """
    with pytest.raises(HTTPException) as exc:
        verify_internal_token("토큰")
    assert exc.value.status_code == 401


def test_서버에_토큰이_설정돼_있지_않으면_무조건_401(monkeypatch):
    """설정 누락 상태에서 빈 헤더가 통과해버리면 인증이 없는 것과 같다."""
    monkeypatch.delenv("INTERNAL_SERVICE_TOKEN", raising=False)
    with pytest.raises(HTTPException) as exc:
        verify_internal_token("")
    assert exc.value.status_code == 401


def test_기동_가드는_토큰이_있으면_통과한다(configured_token):
    assert ensure_token_configured() is None


def test_기동_가드는_토큰이_없으면_기동을_막는다(monkeypatch):
    monkeypatch.delenv("INTERNAL_SERVICE_TOKEN", raising=False)
    with pytest.raises(RuntimeError):
        ensure_token_configured()


def test_토큰은_import가_아니라_호출_시점에_읽는다(monkeypatch):
    """모듈 최상단에서 읽으면 오프라인 import가 환경변수를 강제받는다(CLAUDE.md 규칙)."""
    monkeypatch.setenv("INTERNAL_SERVICE_TOKEN", "first")
    assert verify_internal_token("first") is None

    monkeypatch.setenv("INTERNAL_SERVICE_TOKEN", "second")
    assert verify_internal_token("second") is None
    with pytest.raises(HTTPException):
        verify_internal_token("first")
