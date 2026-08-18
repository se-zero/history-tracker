"""Neo4j 드라이버 수명주기.

빌더 계층 전체가 공유하는 단일 AsyncDriver 인스턴스를 관리한다.
모든 그래프 모듈은 이 모듈의 get_driver()를 통해서만 세션을 연다 (싱글턴 보장).
"""

import logging
import os
from typing import Optional

from neo4j import AsyncDriver, AsyncGraphDatabase

logger = logging.getLogger(__name__)


_driver: Optional[AsyncDriver] = None


def get_driver() -> AsyncDriver:
    global _driver
    if _driver is None:
        uri = os.environ.get("NEO4J_URI", "bolt://localhost:7687")
        user = os.environ.get("NEO4J_USER", "neo4j")
        password = os.environ.get("NEO4J_PASSWORD", "password1234")
        _driver = AsyncGraphDatabase.driver(uri, auth=(user, password))
        logger.info("Neo4j driver initialized: %s", uri)
    return _driver


async def close_driver() -> None:
    global _driver
    if _driver is not None:
        await _driver.close()
        _driver = None
