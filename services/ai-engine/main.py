import asyncio
import logging
import os
from contextlib import asynccontextmanager

from dotenv import load_dotenv
from fastapi import FastAPI

load_dotenv()

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)

from graph.builder import (
    backfill_actor_aliases,
    close_driver,
    ensure_constraints,
    ensure_fulltext_index,
    ensure_vector_indexes,
    get_driver,
)
from graph.consumer import start_consumer
from graph.postprocess import start_debounce_loop
from routers.admin import router as admin_router
from routers.graph import router as graph_router
from routers.query import router as query_router

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
    summary = await get_project_summary(owner, repo_name)
    logger.info("프로젝트 컨텍스트 pre-warm 완료: %s/%s loaded=%s", owner, repo_name, summary is not None)


@asynccontextmanager
async def lifespan(app: FastAPI):
    async def _init_neo4j_with_retry(max_retries: int = 10, retry_interval: float = 1.0) -> None:
        """Neo4j 초기화를 재시도하며 수행 (health check보다 견고함)."""
        for attempt in range(1, max_retries + 1):
            try:
                get_driver()  # 연결 검증 겸 초기화
                await ensure_constraints()
                await ensure_vector_indexes()
                await ensure_fulltext_index()
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
    await _prewarm_project_context()
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


app = FastAPI(title="History Graph AI Engine", lifespan=lifespan)

app.include_router(query_router)
app.include_router(graph_router)
app.include_router(admin_router)


@app.get("/health")
def health():
    return {"status": "ok"}
