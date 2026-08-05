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


async def drop_node_search_index() -> None:
    """옛 통합 검색용 node_search full-text 인덱스를 제거한다. 없으면 무시.

    ⌘K 검색이 대화 검색으로 바뀌며 이 인덱스를 읽던 graph.search가 사라졌다. 남겨두면
    노드를 upsert할 때마다 색인 갱신 비용만 든다. DROP ... IF EXISTS라 매 기동 반복해도
    안전하고 이미 정리된 배포에서는 no-op다 — 모든 배포가 한 번씩 뜨고 나면 이 함수와
    호출부는 지워도 된다.

    제거가 실패해도 색인 비용만 남을 뿐 수집·질의 동작에는 영향이 없으므로 부트를 막지 않는다.
    """
    try:
        async with get_driver().session() as session:
            await session.run("DROP INDEX node_search IF EXISTS")
        logger.info("node_search full-text 인덱스 제거 확인 완료")
    except Exception:
        logger.warning("node_search 인덱스 제거 실패 — 색인 갱신 비용만 남는다", exc_info=True)


# 프로젝트 격리의 핵심 — 모든 도메인 노드는 (project_id, 자연키) 복합 유니크.
# pr_number/path/issue_key 같은 자연키는 프로젝트(레포/워크스페이스)마다 충돌하므로
# project_id 없이 MERGE하면 서로 다른 프로젝트의 데이터가 같은 노드로 병합된다.
_UNIQUE_CONSTRAINTS: list[tuple[str, str, list[str]]] = [
    ("changeset_project_hash",      "ChangeSet",     ["project_id", "hash"]),
    ("pull_request_project_number", "PullRequest",   ["project_id", "pr_number"]),
    ("issue_project_issue_key",      "Issue",         ["project_id", "issue_key"]),
    ("communication_project_url",   "Communication", ["project_id", "url"]),
    ("file_project_path",           "File",          ["project_id", "path"]),
    ("actor_uuid",                  "Actor",         ["uuid"]),
    # ActorAlias: (project_id, source_id) 유니크 — Step 0 alias 조회를 배열 스캔 대신
    # 인덱스로 O(1) 처리하고, 동시 수집 시 같은 alias의 중복 Actor 생성을 MERGE로 막는다.
    ("actor_alias_project_source",  "ActorAlias",    ["project_id", "source_id"]),
    # ActorDecision: 수동 병합·분리 결정 (docs/actor-manual-merge.md)
    ("actor_decision_id",           "ActorDecision", ["decision_id"]),
]

# ActorAlias의 개인정보 조회 키 — 유니크는 아니지만(같은 이름/이메일을 쓰는 alias가 여럿일
# 수 있다) resolve_actor Step 1(pd_email)·Step 2(pd_normalized_name) 조회를 배열 스캔이
# 아닌 인덱스 조회로 만든다. (source, pd_reported_at)은 개인정보 보고 대상을 프로젝트
# 경계 없이 전역으로 훑어야 하므로 의도적으로 project_id를 넣지 않는다.
_RANGE_INDEXES: list[tuple[str, str, list[str]]] = [
    ("actor_alias_pd_normalized_name", "ActorAlias", ["project_id", "pd_normalized_name"]),
    ("actor_alias_pd_email",           "ActorAlias", ["project_id", "pd_email"]),
    ("actor_alias_source_reported",    "ActorAlias", ["source", "pd_reported_at"]),
]


async def ensure_constraints() -> None:
    """(project_id, 자연키) 복합 유니크 제약과 ActorAlias 개인정보 조회용 range 인덱스를 생성한다.
    이미 존재하면 무시.

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
        for name, label, props in _RANGE_INDEXES:
            key = ", ".join(f"n.{p}" for p in props)
            await session.run(
                f"CREATE INDEX {name} IF NOT EXISTS "
                f"FOR (n:{label}) ON ({key})"
            )
    logger.info(
        "프로젝트 스코프 유니크 제약 확인 완료 (%d개), ActorAlias range 인덱스 확인 완료 (%d개)",
        len(_UNIQUE_CONSTRAINTS), len(_RANGE_INDEXES),
    )
