import asyncio
import logging
from contextlib import asynccontextmanager

from dotenv import load_dotenv
from fastapi import FastAPI

load_dotenv()

"""
로그 확인용
logging.basicConfig(
    level=logging.DEBUG,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
"""

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


@app.post("/issue-links/build")
async def trigger_issue_links():
    """방안 A — 임베딩 유사도로 Issue ↔ ChangeSet, Issue ↔ Communication 엣지 생성."""
    store = make_neo4j_issue_link_store()
    triggered_by = await build_issue_changeset_links(store)
    discussed_in = await build_issue_communication_links(store)
    return {"triggered_by": triggered_by, "discussed_in": discussed_in}
