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

from graph.builder import close_driver, get_driver
from graph.consumer import start_consumer

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
