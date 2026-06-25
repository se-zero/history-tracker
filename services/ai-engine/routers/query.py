"""질의 API — 자연어 질문 응답 + 대화 요약 (공개 read 경로)."""

import logging

from fastapi import APIRouter

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
    answer, structured = await orchestrator.run(
        req.question,
        project_context,
        project_id=req.project_id,
        history=history,
        prior_evidence=prior_evidence,
        running_summary=req.running_summary,
    )
    return {"answer": answer, "structured": structured}


@router.post("/query/summary")
async def summarize_query_history(req: SummaryRequest):
    """기존 누적 요약에 새 대화 턴을 병합해 갱신한다."""
    history = [message.model_dump() for message in req.history]
    summary = await orchestrator.summarize_history(req.running_summary, history)
    return {"summary": summary}
