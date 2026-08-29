import asyncio
import logging
from contextlib import asynccontextmanager

from dotenv import load_dotenv
from fastapi import Depends, FastAPI

load_dotenv()

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)

from auth import ensure_token_configured, verify_internal_token
from graph.builder import (
    backfill_actor_aliases,
    close_driver,
    drop_legacy_issue_constraint,
    drop_node_search_index,
    ensure_constraints,
    ensure_vector_indexes,
    get_driver,
)
from graph.consumer import start_consumer
from graph.postprocess import start_debounce_loop
from routers.admin import router as admin_router
from routers.graph import router as graph_router
from routers.privacy import router as privacy_router
from routers.query import router as query_router

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    ensure_token_configured()

    async def _init_neo4j_with_retry(max_retries: int = 10, retry_interval: float = 1.0) -> None:
        """Neo4j 초기화를 재시도하며 수행 (health check보다 견고함)."""
        for attempt in range(1, max_retries + 1):
            try:
                get_driver()  # 연결 검증 겸 초기화
                # Issue MERGE 키가 (project_id, issue_key)에서 (project_id, source, external_id)로
                # 바뀌어 구 제약이 남아 있으면 새 제약 생성이 충돌한다 — 반드시 먼저 제거한다.
                await drop_legacy_issue_constraint()
                await ensure_constraints()
                await ensure_vector_indexes()
                # 옛 통합 검색이 쓰던 full-text 인덱스 제거 (읽는 코드가 없어 색인 비용만 남는다).
                await drop_node_search_index()
                # A: 구버전 Actor의 aliases 배열을 ActorAlias 인덱스 노드로 백필한다.
                # 컨슈머 가동 전에 끝내야 기존 actor의 이벤트가 Step 0에서 잡혀 중복 생성을 막는다.
                # Idempotent라 매 기동마다 안전(이미 연결된 alias는 no-op).
                await backfill_actor_aliases()
                logger.info("Neo4j 초기화 완료 (시도 %d/%d)", attempt, max_retries)
                return
            except Exception as e:
                if attempt < max_retries:
                    logger.warning("Neo4j 초기화 실패 (시도 %d/%d), %d초 후 재시도: %s", attempt, max_retries, retry_interval, e)
                    await asyncio.sleep(retry_interval)
                else:
                    logger.error("Neo4j 초기화 실패 (최대 재시도 횟수 초과)")
                    raise

    await _init_neo4j_with_retry()
    tasks = [
        asyncio.create_task(start_consumer()),
        asyncio.create_task(start_debounce_loop()),
    ]
    try:
        yield
    finally:
        for task in tasks:
            task.cancel()
        for task in tasks:
            try:
                await task
            except asyncio.CancelledError:
                pass
        await close_driver()


# docs_url/redoc_url/openapi_url을 끈다 — 이 세 엔드포인트는 라우터 밖이라
# include_router의 dependencies(verify_internal_token)가 걸리지 않는다. 켜두면
# /openapi.json 하나로 admin·migration 엔드포인트 목록과 파라미터가 익명에 노출된다.
app = FastAPI(
    title="History Graph AI Engine",
    lifespan=lifespan,
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
)

app.include_router(query_router, dependencies=[Depends(verify_internal_token)])
app.include_router(graph_router, dependencies=[Depends(verify_internal_token)])
app.include_router(admin_router, dependencies=[Depends(verify_internal_token)])
app.include_router(privacy_router, dependencies=[Depends(verify_internal_token)])


@app.get("/health")
def health():
    return {"status": "ok"}
