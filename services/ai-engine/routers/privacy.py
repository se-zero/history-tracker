"""개인정보 보고 대상 조회·결과 반영 — backend의 Jira 개인정보 보고 스케줄러가 호출한다."""

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from graph.privacy import apply_report, list_due_accounts

router = APIRouter()


@router.get("/privacy/jira/accounts")
async def privacy_jira_accounts(due_before: str, limit: int = 90):
    """보고 기한(due_before)이 지난 Jira accountId 목록 — project_id 스코프 없는 앱 전역 조회.

    project_id를 받지 않는 이유와 인증 모델은 graph/privacy.py 모듈 docstring 참고.
    """
    try:
        return await list_due_accounts(due_before, limit)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))


class EraseItem(BaseModel):
    account_id: str
    reason: str  # "closed" | "access_lost"


class RefreshItem(BaseModel):
    account_id: str
    name: str
    email: str | None = None


class ApplyReportRequest(BaseModel):
    reported_at: str
    ok: list[str] = []
    erase: list[EraseItem] = []
    refresh: list[RefreshItem] = []


@router.post("/privacy/jira/accounts/apply")
async def privacy_jira_accounts_apply(req: ApplyReportRequest):
    """보고 결과를 그래프에 반영한다 — project_id 스코프 없는 앱 전역 쓰기.

    ok는 pd_reported_at만 갱신, erase는 alias 개인정보 제거, refresh는 재조회 값으로 갱신한다.
    erase·refresh 후 Actor.name 재계산까지 같은 트랜잭션에서 처리된다 (graph/privacy.py 참고).
    """
    try:
        return await apply_report(
            req.reported_at,
            req.ok,
            [item.model_dump() for item in req.erase],
            [item.model_dump() for item in req.refresh],
        )
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
