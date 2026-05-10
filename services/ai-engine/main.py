import asyncio
import logging
from contextlib import asynccontextmanager

from dotenv import load_dotenv
from fastapi import FastAPI
from pydantic import BaseModel

load_dotenv()

"""
로그 확인용
logging.basicConfig(
    level=logging.DEBUG,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
"""

from agent import orchestrator
from graph.builder import close_driver, get_driver, make_neo4j_issue_link_store, make_neo4j_reference_store, propagate_thread_discussed_in
from graph.consumer import start_consumer
from graph.event_handler import handle
from graph.issue_linker import build_issue_changeset_links, build_issue_communication_links
from graph.reference_builder import backfill_communication_embeddings, build_reference_edges

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    get_driver()  # 연결 검증 겸 초기화
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


@app.post("/query")
async def query(req: QueryRequest):
    """자연어 질문을 받아 GraphRAG tool calling으로 답변을 반환한다."""
    answer = await orchestrator.run(req.question)
    return {"answer": answer}


class IssueLinkOptions(BaseModel):
    threshold: float = 0.40
    llm_verify: bool = False
    top_k: int = 5
    llm_threshold: float = 0.7


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
        triggered_by = await build_issue_changeset_links_verified(
            store, options.threshold, options.top_k, options.llm_threshold
        )
        discussed_in = await build_issue_communication_links_verified(
            store, options.threshold, options.top_k, options.llm_threshold
        )
    else:
        triggered_by = await build_issue_changeset_links(store, threshold=options.threshold)
        discussed_in = await build_issue_communication_links(store, threshold=options.threshold)
    return {"triggered_by": triggered_by, "discussed_in": discussed_in}
