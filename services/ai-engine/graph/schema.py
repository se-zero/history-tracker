"""Neo4j 스키마 부트스트랩 — 벡터/full-text 인덱스와 (project_id, 자연키) 복합 유니크 제약."""

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


async def ensure_fulltext_index() -> None:
    """통합 검색용 node_search full-text 인덱스를 생성한다. 이미 존재하면 무시.

    analyzer 'cjk': 한글을 bigram으로 토큰화해 substring처럼 매치되게 한다
    (기본 standard analyzer는 공백 단위라 한글 부분 일치가 안 됨). 영문은 소문자화 토큰.
    검색 기능이 붙기 전 버전의 Neo4j/설정에서 생성이 실패해도 수집·질의는 무관하므로
    부트를 막지 않는다 — 이 경우 /graph/search만 실패한다.

    ActorAlias.pd_name을 색인 대상에 추가한다 — 표시 이름(Actor.name)이 GitHub 기준으로
    고정돼도 Jira/Slack 이름으로 검색이 되게 하기 위함(graph.search가 ALIAS_OF로 소유
    Actor를 되찾는다). IF NOT EXISTS라 기존에 이미 만들어진 DB에는 반영되지 않는다 —
    기존 인덱스 갱신은 이후 백필 단계(migrations)에서 처리한다.
    """
    try:
        async with get_driver().session() as session:
            await session.run(
                """
                CREATE FULLTEXT INDEX node_search IF NOT EXISTS
                FOR (n:ChangeSet|PullRequest|Issue|Communication|Actor|ActorAlias|File)
                ON EACH [n.title, n.message, n.body, n.name, n.aliases, n.path, n.jira_key, n.pd_name]
                OPTIONS { indexConfig: { `fulltext.analyzer`: 'cjk' } }
                """
            )
        logger.info("full-text 인덱스 확인 완료 (node_search)")
    except Exception:
        logger.warning("full-text 인덱스 생성 실패 — /graph/search 비활성", exc_info=True)


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
