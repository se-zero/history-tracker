"""질의 API — 자연어 질문 응답 + 대화 요약 (공개 read 경로)."""

import logging

from fastapi import APIRouter, HTTPException
from openai import APIStatusError

from agent import orchestrator
from openai_client import Priority
from query_models import QueryRequest, SummaryRequest

logger = logging.getLogger(__name__)

router = APIRouter()


@router.post("/query")
async def query(req: QueryRequest):
    """자연어 질문을 받아 GraphRAG tool calling으로 답변을 반환한다.

    project_id로 모든 그래프 쿼리가 스코프된다 — 없으면 어떤 프로젝트 노드에도 매칭되지
    않아 빈 답변이 된다 (안전한 degradation, 크로스 프로젝트 누출 없음).

    응답:
      - answer: markdown 형식 답변 (Structured Output → render).
      - structured: grounded_answer 스키마 dict (summary/evidence/unknown_aspects).
        Structured 호출 실패 시 null — 이때 answer는 LLM의 자유 텍스트 fallback.
      - debug: include_debug=true일 때만 — 토큰 usage 합산 + 도구 호출 트랜스크립트
        (eval 러너용 관측 데이터, 답변 생성에는 영향 없음).
    """
    if not req.project_id:
        logger.warning("/query에 project_id 없음 — 그래프 조회가 비어 있게 됩니다.")

    project_context = ""
    if req.repo and "/" in req.repo:
        from graph.project_context import get_project_summary
        owner, repo_name = req.repo.split("/", 1)
        project_context = await get_project_summary(owner, repo_name, priority=Priority.INTERACTIVE) or ""

    history = [message.model_dump() for message in req.history]
    prior_evidence = [evidence.model_dump() for evidence in req.prior_evidence]
    focus_evidence = [evidence.model_dump() for evidence in req.focus_evidence]
    debug: dict | None = {} if req.include_debug else None
    try:
        answer, structured = await orchestrator.run(
            req.question,
            project_context,
            project_id=req.project_id,
            history=history,
            prior_evidence=prior_evidence,
            running_summary=req.running_summary,
            focus_evidence=focus_evidence,
            debug=debug,
        )
    except APIStatusError as exc:
        # orchestrator가 전파한 회복 불가 클라이언트 오류(4xx) — 200+빈 답변으로 위장하지
        # 않고 명시적 오류로 반환한다 (모델명·파라미터 설정 오류의 조기 발견).
        logger.error("LLM 클라이언트 오류로 질의 실패 (status=%s): %s", exc.status_code, exc)
        raise HTTPException(
            status_code=502,
            detail=f"LLM 호출이 설정 오류로 실패했습니다 (upstream {exc.status_code}). 모델·파라미터 설정을 확인하세요.",
        ) from exc
    body = {"answer": answer, "structured": structured}
    if debug is not None:
        body["debug"] = debug
    return body


@router.get("/query/config")
async def query_config():
    """질의 경로에 실제 적용 중인 설정을 반환한다 (eval 러너의 meta.json 실측 기록용).

    env를 다시 읽지 않고 각 모듈이 import 시점에 확정한 값을 그대로 노출한다 —
    "환경변수는 바꿨는데 재기동을 안 한" 상태에서도 실제 동작 값이 기록되게.
    수동 라벨(--model-label)과 실제 설정이 어긋난 무효 런을 방지한다
    (실례: QUERY_REASONING_EFFORT=low 사고 — docs/query-followups.md 2번).
    """
    from agent.orchestrator import _MAX_ITERATIONS, _MODEL, _REASONING_EFFORT
    from tools.queries._common import _MIN_CONFIDENCE
    from tools.queries.files import _CONTEXT_CAP, _DETAIL_BUDGET, _DETAIL_K_MAX, _MAX_COMMITS

    return {
        "query_model": _MODEL,
        "reasoning_effort": _REASONING_EFFORT,  # 빈 문자열 = 파라미터 미전송(API 기본값)
        "max_iterations": _MAX_ITERATIONS,
        "tools_min_confidence": _MIN_CONFIDENCE,
        "file_history": {
            "detail_budget": _DETAIL_BUDGET,
            "detail_max": _DETAIL_K_MAX,
            "context_cap": _CONTEXT_CAP,
            "max_commits": _MAX_COMMITS,
        },
    }


@router.post("/query/summary")
async def summarize_query_history(req: SummaryRequest):
    """기존 누적 요약에 새 대화 턴을 병합해 갱신한다."""
    history = [message.model_dump() for message in req.history]
    try:
        summary = await orchestrator.summarize_history(req.running_summary, history)
    except APIStatusError as exc:
        logger.error("LLM 클라이언트 오류로 요약 실패 (status=%s): %s", exc.status_code, exc)
        raise HTTPException(
            status_code=502,
            detail=f"LLM 호출이 설정 오류로 실패했습니다 (upstream {exc.status_code}). 모델·파라미터 설정을 확인하세요.",
        ) from exc
    return {"summary": summary}
