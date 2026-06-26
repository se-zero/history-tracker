"""백필·마이그레이션·정리·프로젝트 삭제 — 일회성/관리성 그래프 배치 작업.

수집 쓰기 경로(graph.writes)와 달리 /migrations·/reference 엔드포인트나
프로젝트 삭제 cascade에서 명시적으로 트리거된다.
"""

import logging
import re

from graph.driver import get_driver
from graph.writes import link_pr_changesets_to_issues

logger = logging.getLogger(__name__)


async def propagate_thread_discussed_in(project_id: str | None = None) -> int:
    """방안 C — 스레드 전파: conversation_id로 묶인 스레드 내 하나의 Communication이
    DISCUSSED_IN을 가지면 같은 스레드의 나머지 Communication에도 전파.

    conversation_id(Slack ts 등)는 프로젝트 간 충돌 가능 — 같은 project_id 안에서만 전파한다.
    project_id를 주면 그 프로젝트 스레드만 전파한다(per-project 빌드).
    """
    query = """
        MATCH (i:Issue)-[:DISCUSSED_IN]->(seed:Communication)
        WHERE seed.conversation_id IS NOT NULL AND seed.conversation_id <> ''
        __PROJECT_FILTER__
        WITH i, seed
        MATCH (other:Communication {project_id: seed.project_id, conversation_id: seed.conversation_id})
        WHERE NOT (i)-[:DISCUSSED_IN]->(other)
        MERGE (i)-[:DISCUSSED_IN]->(other)
        RETURN count(*) AS created
    """.replace("__PROJECT_FILTER__", "AND seed.project_id = $project_id" if project_id else "")
    async with get_driver().session() as session:
        result = await session.run(query, project_id=project_id)
        record = await result.single()
        return record["created"] if record else 0


async def backfill_triggered_by_source() -> dict:
    """기존 TRIGGERED_BY 엣지에 source / confidence 속성을 채우는 일회성 마이그레이션.

    분류 기준:
      - confidence IS NULL          → 텍스트 경로로만 생성된 것 → source='text', confidence=1.0
      - confidence IS NOT NULL      → 시맨틱 경로 산물            → source='semantic'
      - 위 둘 다 끝난 뒤, commit.message에 jira_key 텍스트가 들어있는 시맨틱 엣지는
        실제로는 텍스트 참조 케이스로 봐야 하므로 'text'로 승격 (confidence=1.0)

    모든 절은 idempotent. 재실행해도 안전.
    반환: 단계별 갱신 카운트.
    """
    async with get_driver().session() as session:
        # 1) confidence가 없으면 텍스트 경로로만 생성된 것 → text/1.0
        result = await session.run(
            """
            MATCH ()-[r:TRIGGERED_BY]->()
            WHERE r.source IS NULL AND r.confidence IS NULL
            SET r.source = 'text', r.confidence = 1.0
            RETURN count(r) AS n
            """
        )
        text_backfilled = (await result.single())["n"]

        # 2) confidence 있으면 시맨틱 산물 → source='semantic'
        result = await session.run(
            """
            MATCH ()-[r:TRIGGERED_BY]->()
            WHERE r.source IS NULL AND r.confidence IS NOT NULL
            SET r.source = 'semantic'
            RETURN count(r) AS n
            """
        )
        semantic_backfilled = (await result.single())["n"]

        # 3) commit message에 jira_key가 직접 들어있는 시맨틱 엣지를 텍스트로 승격
        #    (pipeline-worker가 refs.jiraKey 추출에 실패했어도 후속 정정)
        result = await session.run(
            """
            MATCH (c:ChangeSet)-[r:TRIGGERED_BY]->(i:Issue)
            WHERE r.source = 'semantic'
              AND c.message IS NOT NULL
              AND c.message CONTAINS i.jira_key
            SET r.source = 'text', r.confidence = 1.0
            RETURN count(r) AS n
            """
        )
        promoted = (await result.single())["n"]

    logger.info(
        "TRIGGERED_BY source 백필 완료: text=%d, semantic=%d, promoted=%d",
        text_backfilled, semantic_backfilled, promoted,
    )
    return {
        "text_backfilled": text_backfilled,
        "semantic_backfilled": semantic_backfilled,
        "promoted_to_text": promoted,
    }


_JIRA_KEY_PATTERN = re.compile(r"\b([A-Z]{2,}-\d+)\b")


async def backfill_pr_jira_keys() -> dict:
    """기존 PR 노드의 title/body에서 jira_keys를 추출해 pr.jira_keys로 저장하고
    link_pr_changesets_to_issues 전파까지 수행한다.

    배경:
      Phase 2 이후 _handle_pull_request는 PR 이벤트가 들어올 때 refs.jiraKeys를 받아
      pr.jira_keys로 저장하지만, 그 변경 이전에 이미 그래프에 들어와 있던 PR은 속성이
      비어있다. 이 함수는 그런 기존 PR에 한정해 한 번에 후처리한다.

    동작:
      pr.jira_keys가 NULL이거나 빈 PR을 찾아 title + body 텍스트에서 Jira 키를 추출.
      매치가 있으면 pr.jira_keys 설정 후 link_pr_changesets_to_issues로 CONTAINS 커밋에
      텍스트 TRIGGERED_BY 전파.

    Idempotent: jira_keys가 이미 채워진 PR은 건너뜀.

    Returns:
        {"pr_scanned": N, "pr_backfilled": K, "edges_propagated": M}
    """
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (pr:PullRequest)
            WHERE (pr.jira_keys IS NULL OR size(pr.jira_keys) = 0)
              AND pr.project_id IS NOT NULL
            RETURN pr.project_id AS project_id,
                   pr.pr_number  AS pr_number,
                   pr.title      AS title,
                   pr.body       AS body
            """
        )
        prs = await result.data()

    backfilled = 0
    edges_propagated = 0
    for pr in prs:
        text = (pr["title"] or "") + " " + (pr["body"] or "")
        # 중복 제거 + 입력 순서 유지 — pipeline-worker RefsExtractor와 같은 정책
        keys = list(dict.fromkeys(_JIRA_KEY_PATTERN.findall(text)))
        if not keys:
            continue

        project_id = pr["project_id"]
        pr_number = pr["pr_number"]
        async with get_driver().session() as session:
            await session.run(
                """
                MATCH (pr:PullRequest {project_id: $project_id, pr_number: $pr_number})
                SET pr.jira_keys = $keys
                """,
                project_id=project_id,
                pr_number=pr_number,
                keys=keys,
            )
        propagated = await link_pr_changesets_to_issues(project_id, pr_number)
        edges_propagated += propagated
        backfilled += 1
        logger.debug("PR #%s 백필: jira_keys=%s → 전파 %d개", pr_number, keys, propagated)

    logger.info(
        "PR jira_keys 백필 완료: scanned=%d, backfilled=%d, propagated=%d",
        len(prs), backfilled, edges_propagated,
    )
    return {
        "pr_scanned":       len(prs),
        "pr_backfilled":    backfilled,
        "edges_propagated": edges_propagated,
    }


async def backfill_actor_aliases() -> dict:
    """기존 Actor.aliases 배열에서 ActorAlias 인덱스 노드를 백필한다 (A: alias 노드 도입).

    Step 0 alias 조회가 배열 스캔 대신 (ActorAlias)-[:ALIAS_OF]->(Actor) 인덱스를 쓰려면,
    구버전에 만들어진 Actor의 aliases 원소마다 ActorAlias 노드/엣지가 있어야 한다.
    그게 없으면 기존 actor의 이벤트가 Step 0에서 안 잡혀 중복 Actor가 생길 수 있으므로,
    신규 코드 배포 직후(컨슈머 가동 전) 1회 실행해야 한다 — main.lifespan에서 호출한다.

    Idempotent — MERGE 기반이라 매 기동마다 돌려도 이미 연결된 alias는 no-op.

    Returns:
        {"actors_scanned": N, "aliases_linked": M}
    """
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (a:Actor)
            WHERE a.aliases IS NOT NULL AND a.project_id IS NOT NULL
            UNWIND a.aliases AS sid
            MERGE (al:ActorAlias {project_id: a.project_id, source_id: sid})
            MERGE (al)-[:ALIAS_OF]->(a)
            RETURN count(DISTINCT a) AS actors, count(*) AS links
            """
        )
        row = await result.single()
    actors = row["actors"] if row else 0
    links = row["links"] if row else 0
    logger.info("ActorAlias 백필 완료: actors=%d, aliases_linked=%d", actors, links)
    return {"actors_scanned": actors, "aliases_linked": links}


async def clear_semantic_triggered_by(project_id: str | None = None) -> int:
    """source='semantic'인 TRIGGERED_BY 엣지를 일괄 삭제한다.

    용도: 정책(threshold/window/top-1) 변경 후 시맨틱 결과를 깨끗하게 재구축하고 싶을 때.
    텍스트 매칭(source='text')은 보존되므로 명시 참조는 손상되지 않는다.
    project_id를 주면 그 프로젝트 엣지만 삭제한다(per-project 정밀 재구축).

    선행 조건:
      backfill_triggered_by_source가 한 번이라도 실행되어 모든 엣지에 source가 라벨링되어 있어야 한다.
      (라벨이 없으면 이 함수가 그것을 시맨틱으로 간주하지 못해 정리 대상에서 누락된다.)

    Returns:
        삭제된 엣지 수.
    """
    # TRIGGERED_BY는 항상 ChangeSet→Issue라 c:ChangeSet 바인딩은 () 와 동치이며, project_id 스코프를 건다.
    query = """
        MATCH (c:ChangeSet)-[r:TRIGGERED_BY]->()
        WHERE r.source = 'semantic'
        __PROJECT_FILTER__
        DELETE r
    """.replace("__PROJECT_FILTER__", "AND c.project_id = $project_id" if project_id else "")
    async with get_driver().session() as session:
        result = await session.run(query, project_id=project_id)
        summary = await result.consume()
        deleted = summary.counters.relationships_deleted
    logger.info("시맨틱 TRIGGERED_BY 엣지 삭제 완료: %d개", deleted)
    return deleted


async def clear_semantic_discussed_in(project_id: str | None = None) -> int:
    """시맨틱 DISCUSSED_IN(방안 A/D 산물)을 일괄 삭제한다.

    시맨틱 엣지만 r.confidence가 설정되므로 이를 기준으로 구분한다.
    refs 텍스트(link_issue_to_communication)·스레드 전파 엣지는 confidence가 없어 보존된다.
    방안 D(LLM 검증) 재구축 전에 A의 결과를 비워 false positive가 섞이지 않게 하는 용도.
    project_id를 주면 그 프로젝트 엣지만 삭제한다(per-project 정밀 재구축).

    Returns:
        삭제된 엣지 수.
    """
    # DISCUSSED_IN은 항상 Issue→Communication이라 i:Issue 바인딩은 () 와 동치이며, project_id 스코프를 건다.
    query = """
        MATCH (i:Issue)-[r:DISCUSSED_IN]->()
        WHERE r.confidence IS NOT NULL
        __PROJECT_FILTER__
        DELETE r
    """.replace("__PROJECT_FILTER__", "AND i.project_id = $project_id" if project_id else "")
    async with get_driver().session() as session:
        result = await session.run(query, project_id=project_id)
        summary = await result.consume()
        deleted = summary.counters.relationships_deleted
    logger.info("시맨틱 DISCUSSED_IN 엣지 삭제 완료: %d개", deleted)
    return deleted


async def delete_project_graph(project_id: str, batch_size: int = 10_000) -> int:
    """해당 project_id의 모든 노드(Actor 포함)와 관계를 삭제한다.

    프로젝트 삭제 시 backend가 호출하는 cascade. 모든 도메인 노드뿐 아니라 Actor도
    project_id로 스코프되므로(상단 MERGE/CREATE 참고) 프로젝트 서브그래프 전체가 제거되고
    다른 프로젝트는 건드리지 않는다. 멱등 — 없는/빈 project_id면 0 반환.

    수개월 수집된 대형 프로젝트는 수만 노드·수십만 관계를 가질 수 있어, 단일 트랜잭션으로
    DETACH DELETE하면 tx timeout 또는 힙 부족이 발생한다. CALL { } IN TRANSACTIONS로
    배치 커밋해 메모리 상한을 피한다 — 중간 실패해도 멱등 재시도로 나머지를 마저 지운다.

    Returns:
        삭제된 노드 수.
    """
    if not project_id:
        return 0
    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (n {project_id: $project_id})
            CALL (n) { DETACH DELETE n } IN TRANSACTIONS OF $batch_size ROWS
            """,
            project_id=project_id,
            batch_size=batch_size,
        )
        summary = await result.consume()
        deleted = summary.counters.nodes_deleted
    logger.info("프로젝트 그래프 삭제 완료: project=%s, nodes=%d", project_id, deleted)
    return deleted
