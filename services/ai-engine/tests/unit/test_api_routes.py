"""API 라우트 계약 가드 — main.app에 등록된 (method, path) 집합이 변하지 않았는지 검증.

Phase 3에서 main.py의 엔드포인트를 routers/ 패키지로 분리했다. 이 테스트는 그 분리(및
이후 리팩토링)가 공개 HTTP 계약을 바꾸지 않았음을 보장한다. 의도적으로 라우트를
추가/변경/삭제할 때만 EXPECTED_ROUTES를 함께 갱신한다.
"""

from fastapi.routing import APIRoute

import main

EXPECTED_ROUTES = {
    ("DELETE", "/graph/projects/{project_id}"),
    ("GET", "/graph/overview"),
    ("GET", "/health"),
    ("POST", "/graph/build"),
    ("POST", "/issue-links/build"),
    ("POST", "/migrations/clear-semantic-triggered-by"),
    ("POST", "/migrations/pr-jira-keys"),
    ("POST", "/migrations/triggered-by-source"),
    ("POST", "/query"),
    ("POST", "/query/summary"),
    ("POST", "/reference/backfill"),
    ("POST", "/reference/build"),
    ("POST", "/reference/propagate-threads"),
    ("POST", "/slack/filter"),
    ("POST", "/test/ingest"),
}


def _actual_routes() -> set[tuple[str, str]]:
    return {
        (method, r.path)
        for r in main.app.routes
        if isinstance(r, APIRoute)
        for method in r.methods
    }


def test_route_contract_unchanged():
    assert _actual_routes() == EXPECTED_ROUTES
