import asyncio
import logging
import os
from contextlib import asynccontextmanager

from dotenv import load_dotenv
from fastapi import FastAPI
from pydantic import BaseModel

load_dotenv()

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)

from agent import orchestrator
from graph.builder import close_driver, ensure_vector_indexes, get_driver, make_neo4j_issue_link_store, make_neo4j_reference_store, propagate_thread_discussed_in
from graph.slack_batch_filter import run_slack_llm_filter
from graph.consumer import start_consumer
from graph.event_handler import handle
from graph.issue_linker import build_issue_changeset_links, build_issue_communication_links
from graph.reference_builder import backfill_communication_embeddings, build_reference_edges

logger = logging.getLogger(__name__)


async def _prewarm_project_context() -> None:
    """GITHUB_REPO 환경변수가 설정되어 있으면 시작 시점에 프로젝트 컨텍스트를 캐시한다.
    이후 모든 엔드포인트는 콜드 스타트 race condition 없이 캐시 히트로 동작한다.
    실패해도 서비스는 정상 기동한다 (None이 캐시되므로 추후 재시도 안 함)."""
    repo = os.environ.get("GITHUB_REPO", "")
    if not repo or "/" not in repo:
        logger.info("GITHUB_REPO 미설정 — 프로젝트 컨텍스트 pre-warm 생략")
        return
    owner, repo_name = repo.split("/", 1)
    from graph.project_context import get_project_summary
    summary = await asyncio.to_thread(get_project_summary, owner, repo_name)
    logger.info("프로젝트 컨텍스트 pre-warm 완료: %s/%s loaded=%s", owner, repo_name, summary is not None)


@asynccontextmanager
async def lifespan(app: FastAPI):
    get_driver()  # 연결 검증 겸 초기화
    await ensure_vector_indexes()
    await _prewarm_project_context()
    task = asyncio.create_task(start_consumer())
    try:
        yield
    finally:
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass
        await close_driver()


app = FastAPI(title="History Graph AI Engine", lifespan=lifespan)


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/test/ingest", tags=["test"])
async def test_ingest(event: dict):
    """[테스트 전용] NormalizedEvent를 RabbitMQ 없이 직접 주입한다."""
    await handle(event)
    return {"ok": True}


@app.post("/reference/build")
async def trigger_reference_build():
    """REFERENCE 엣지 배치 생성. 임베딩이 충분히 쌓인 뒤 수동 호출."""
    store = make_neo4j_reference_store()
    created = await build_reference_edges(store)
    return {"created": created}


@app.post("/reference/backfill")
async def trigger_backfill():
    """embedding 없는 Communication 노드 일괄 임베딩 보정."""
    store = make_neo4j_reference_store()
    saved = await backfill_communication_embeddings(store)
    return {"saved": saved}


@app.post("/reference/propagate-threads")
async def trigger_thread_propagation():
    """방안 C — 스레드 전파: DISCUSSED_IN 엣지를 같은 conversation_id 내 전체 메시지로 전파."""
    created = await propagate_thread_discussed_in()
    return {"created": created}


class QueryRequest(BaseModel):
    question: str
    repo: str = ""  # "owner/repo" 형식. 도메인 컨텍스트 주입용. 없으면 컨텍스트 없이 동작.


@app.post("/query")
async def query(req: QueryRequest):
    """자연어 질문을 받아 GraphRAG tool calling으로 답변을 반환한다."""
    project_context = ""
    if req.repo and "/" in req.repo:
        from graph.project_context import get_project_summary
        owner, repo_name = req.repo.split("/", 1)
        project_context = await asyncio.to_thread(get_project_summary, owner, repo_name) or ""

    answer = await orchestrator.run(req.question, project_context)
    return {"answer": answer}


class SlackFilterOptions(BaseModel):
    repo: str = ""  # "owner/repo" 형식, 없으면 기본 컨텍스트 사용


@app.post("/slack/filter")
async def trigger_slack_filter(options: SlackFilterOptions = SlackFilterOptions()):
    """LLM 기반 Slack Communication 배치 필터링.
    슬랙 데이터 수집 완료 후 수동 호출. 스레드 단위 또는 (channel, date) 묶음으로 LLM 판단.
    """
    project_context = ""
    if options.repo and "/" in options.repo:
        from graph.project_context import get_project_summary
        owner, repo_name = options.repo.split("/", 1)
        project_context = await asyncio.to_thread(get_project_summary, owner, repo_name)

    result = await run_slack_llm_filter(project_context)
    return result


class IssueLinkOptions(BaseModel):
    threshold: float = 0.40
    llm_verify: bool = False
    top_k: int = 5
    llm_threshold: float = 0.7
    repo: str = ""  # "owner/repo" 형식. llm_verify=true 일 때 도메인 컨텍스트 주입에 사용


@app.post("/issue-links/build")
async def trigger_issue_links(options: IssueLinkOptions = IssueLinkOptions()):
    """방안 A/D — Issue ↔ ChangeSet, Issue ↔ Communication 엣지 생성.

    llm_verify=false (기본): 방안 A — 임베딩 유사도만으로 판단
    llm_verify=true:         방안 D — 임베딩 후보 선별 후 LLM 검증
    """
    store = make_neo4j_issue_link_store()
    if options.llm_verify:
        from graph.issue_verifier import (
            build_issue_changeset_links_verified,
            build_issue_communication_links_verified,
        )
        project_context = ""
        if options.repo and "/" in options.repo:
            from graph.project_context import get_project_summary
            owner, repo_name = options.repo.split("/", 1)
            project_context = await asyncio.to_thread(get_project_summary, owner, repo_name) or ""

        triggered_by = await build_issue_changeset_links_verified(
            store, options.threshold, options.top_k, options.llm_threshold, project_context,
        )
        discussed_in = await build_issue_communication_links_verified(
            store, options.threshold, options.top_k, options.llm_threshold, project_context,
        )
    else:
        triggered_by = await build_issue_changeset_links(store, threshold=options.threshold)
        discussed_in = await build_issue_communication_links(store, threshold=options.threshold)
    return {"triggered_by": triggered_by, "discussed_in": discussed_in}
