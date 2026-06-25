"""Neo4j 스키마 부트스트랩 — 벡터 인덱스와 (project_id, 자연키) 복합 유니크 제약."""

import logging

from graph.driver import get_driver

logger = logging.getLogger(__name__)


async def ensure_vector_indexes() -> None:
    """comm_embedding, issue_embedding 벡터 인덱스를 생성한다. 이미 존재하면 무시."""
    async with get_driver().session() as session:
        await session.run(
            """
            CREATE VECTOR INDEX comm_embedding IF NOT EXISTS
            FOR (c:Communication) ON (c.embedding)
            OPTIONS { indexConfig: {
                `vector.dimensions`: 1536,
                `vector.similarity_function`: 'cosine'
            }}
            """
        )
        await session.run(
            """
            CREATE VECTOR INDEX issue_embedding IF NOT EXISTS
            FOR (i:Issue) ON (i.embedding)
            OPTIONS { indexConfig: {
                `vector.dimensions`: 1536,
                `vector.similarity_function`: 'cosine'
            }}
            """
        )
    logger.info("벡터 인덱스 확인 완료 (comm_embedding, issue_embedding)")


# 프로젝트 격리의 핵심 — 모든 도메인 노드는 (project_id, 자연키) 복합 유니크.
# pr_number/path/jira_key 같은 자연키는 프로젝트(레포/워크스페이스)마다 충돌하므로
# project_id 없이 MERGE하면 서로 다른 프로젝트의 데이터가 같은 노드로 병합된다.
_UNIQUE_CONSTRAINTS: list[tuple[str, str, list[str]]] = [
    ("changeset_project_hash",      "ChangeSet",     ["project_id", "hash"]),
    ("pull_request_project_number", "PullRequest",   ["project_id", "pr_number"]),
    ("issue_project_jira_key",      "Issue",         ["project_id", "jira_key"]),
    ("communication_project_url",   "Communication", ["project_id", "url"]),
    ("file_project_path",           "File",          ["project_id", "path"]),
    ("actor_uuid",                  "Actor",         ["uuid"]),
    # ActorAlias: (project_id, source_id) 유니크 — Step 0 alias 조회를 배열 스캔 대신
    # 인덱스로 O(1) 처리하고, 동시 수집 시 같은 alias의 중복 Actor 생성을 MERGE로 막는다.
    ("actor_alias_project_source",  "ActorAlias",    ["project_id", "source_id"]),
]


async def ensure_constraints() -> None:
    """(project_id, 자연키) 복합 유니크 제약을 생성한다. 이미 존재하면 무시.

    제약 생성이 실패하는 환경(에디션/버전 차이)에서는 동일 키 조합의 range 인덱스로
    폴백한다 — MERGE 패턴 자체가 복합 키를 쓰므로 단일 컨슈머 환경에서는
    제약 없이도 중복이 생기지 않고, 인덱스만으로도 조회 성능은 확보된다.
    """
    async with get_driver().session() as session:
        for name, label, props in _UNIQUE_CONSTRAINTS:
            key = ", ".join(f"n.{p}" for p in props)
            try:
                await session.run(
                    f"CREATE CONSTRAINT {name} IF NOT EXISTS "
                    f"FOR (n:{label}) REQUIRE ({key}) IS UNIQUE"
                )
            except Exception:
                logger.warning("유니크 제약 생성 실패 — range 인덱스로 폴백: %s", name, exc_info=True)
                await session.run(
                    f"CREATE INDEX {name}_idx IF NOT EXISTS "
                    f"FOR (n:{label}) ON ({key})"
                )
    logger.info("프로젝트 스코프 유니크 제약 확인 완료 (%d개)", len(_UNIQUE_CONSTRAINTS))
