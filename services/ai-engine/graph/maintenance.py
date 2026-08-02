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
    """스레드 전파 — conversation_id로 묶인 스레드 내 하나의 Communication이
    DISCUSSED_IN을 가지면 같은 스레드의 나머지 Communication에도 전파.

    conversation_id(Slack ts 등)는 프로젝트 간 충돌 가능 — 같은 project_id 안에서만 전파한다.
    project_id를 주면 그 프로젝트 스레드만 전파한다(per-project 빌드).

    복사본에는 source='propagated' 표식을 남긴다 — 표식이 없으면 clear가 시맨틱 원본만 지우고
    복사본은 스레드에 그대로 남아, 다음 빌드의 전파가 지워진 원본까지 되살린다(오탐이 불사신이 됨).
    """
    query = """
        MATCH (i:Issue)-[:DISCUSSED_IN]->(seed:Communication)
        WHERE seed.conversation_id IS NOT NULL AND seed.conversation_id <> ''
        __PROJECT_FILTER__
        WITH i, seed
        MATCH (other:Communication {project_id: seed.project_id, conversation_id: seed.conversation_id})
        WHERE NOT (i)-[:DISCUSSED_IN]->(other)
        MERGE (i)-[r:DISCUSSED_IN]->(other)
        SET r.source = 'propagated'
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


async def backfill_discussed_in_source() -> dict:
    """기존 DISCUSSED_IN 엣지에 source 표식을 채우는 일회성 마이그레이션.

    DISCUSSED_IN은 세 경로로 생긴다 — 텍스트 참조(link_issue_to_communication),
    시맨틱 매칭(issue_linker/issue_verifier), 스레드 전파(propagate_thread_discussed_in).
    표식이 도입되기 전 엣지는 셋을 구분할 수 없어 clear가 시맨틱만 골라 지울 수 없다.

    분류 기준 (backfill_triggered_by_source 선례와 동일한 순서):
      - confidence IS NULL + 메시지 본문에 jira_key 포함 → source='text'   (명시적 참조)
      - confidence IS NULL + 그 외                        → source='propagated' (스레드 복사본)
      - confidence IS NOT NULL                            → source='semantic'
      - 위가 끝난 뒤, 본문에 jira_key가 들어있는 시맨틱 엣지는 실제로는 텍스트 참조인데
        가드 없던 시맨틱 빌더가 confidence를 덮어쓴 것이므로 'text'로 승격하고 confidence를 제거한다
        (남겨두면 clear가 시맨틱으로 오인해 삭제한다).

    모든 절은 idempotent. 재실행해도 안전.
    반환: 단계별 갱신 카운트.
    """
    async with get_driver().session() as session:
        # 1) confidence 없음 + 본문이 jira_key를 직접 언급 → 텍스트 참조
        result = await session.run(
            """
            MATCH (i:Issue)-[r:DISCUSSED_IN]->(comm:Communication)
            WHERE r.source IS NULL AND r.confidence IS NULL
              AND comm.body IS NOT NULL AND comm.body CONTAINS i.jira_key
            SET r.source = 'text'
            RETURN count(r) AS n
            """
        )
        text_backfilled = (await result.single())["n"]

        # 2) confidence 없음 + 언급도 없음 → 스레드 전파 복사본
        result = await session.run(
            """
            MATCH ()-[r:DISCUSSED_IN]->()
            WHERE r.source IS NULL AND r.confidence IS NULL
            SET r.source = 'propagated'
            RETURN count(r) AS n
            """
        )
        propagated_backfilled = (await result.single())["n"]

        # 3) confidence 있음 → 시맨틱 산물
        result = await session.run(
            """
            MATCH ()-[r:DISCUSSED_IN]->()
            WHERE r.source IS NULL AND r.confidence IS NOT NULL
            SET r.source = 'semantic'
            RETURN count(r) AS n
            """
        )
        semantic_backfilled = (await result.single())["n"]

        # 4) 본문에 jira_key가 있는 시맨틱 엣지를 텍스트로 승격 (confidence 오염 복구)
        result = await session.run(
            """
            MATCH (i:Issue)-[r:DISCUSSED_IN]->(comm:Communication)
            WHERE r.source = 'semantic'
              AND comm.body IS NOT NULL
              AND comm.body CONTAINS i.jira_key
            SET r.source = 'text'
            REMOVE r.confidence
            RETURN count(r) AS n
            """
        )
        promoted = (await result.single())["n"]

    logger.info(
        "DISCUSSED_IN source 백필 완료: text=%d, propagated=%d, semantic=%d, promoted=%d",
        text_backfilled, propagated_backfilled, semantic_backfilled, promoted,
    )
    return {
        "text_backfilled":       text_backfilled,
        "propagated_backfilled": propagated_backfilled,
        "semantic_backfilled":   semantic_backfilled,
        "promoted_to_text":      promoted,
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
    """시맨틱 DISCUSSED_IN(자동구축·수동 정밀 구축 산물)과 그 스레드 전파 복사본을 일괄 삭제한다.

    전파 복사본(source='propagated')도 함께 지운다 — 시맨틱 원본만 지우면 복사본이 스레드에
    남고, 다음 빌드의 재전파가 지워진 원본까지 복원해 오탐이 사라지지 않는다. 재구축 시퀀스
    마지막에 propagate_thread_discussed_in이 다시 돌아 정상 전파는 복구되므로 손실이 없다.

    source='text'(메시지가 jira_key를 직접 언급)는 어떤 경우에도 보존한다 — 수집 시점에만
    생기는 엣지라 지우면 복구되지 않는다. confidence 조건은 표식 도입 이전(백필 미실행)
    그래프에서도 시맨틱을 잡기 위한 fallback이다.

    용도: 정책(threshold/window/top-k) 변경 후 시맨틱 결과를 깨끗하게 재구축하고 싶을 때,
    그리고 수동 정밀 구축(LLM 검수) 전에 자동구축의 false positive를 비울 때.
    project_id를 주면 그 프로젝트 엣지만 삭제한다(per-project 정밀 재구축).

    Returns:
        삭제된 엣지 수.
    """
    # DISCUSSED_IN은 항상 Issue→Communication이라 i:Issue 바인딩은 () 와 동치이며, project_id 스코프를 건다.
    query = """
        MATCH (i:Issue)-[r:DISCUSSED_IN]->()
        WHERE (r.source IN ['semantic', 'propagated'] OR r.confidence IS NOT NULL)
          AND coalesce(r.source, '') <> 'text'
        __PROJECT_FILTER__
        DELETE r
    """.replace("__PROJECT_FILTER__", "AND i.project_id = $project_id" if project_id else "")
    async with get_driver().session() as session:
        result = await session.run(query, project_id=project_id)
        summary = await result.consume()
        deleted = summary.counters.relationships_deleted
    logger.info("시맨틱 DISCUSSED_IN 엣지 삭제 완료: %d개", deleted)
    return deleted


async def clear_reference(project_id: str | None = None) -> int:
    """REFERENCE 엣지를 일괄 삭제한다.

    REFERENCE는 텍스트 참조 경로가 없어 전부 시맨틱(임베딩 유사도) 산물이므로 조건 없이 지운다.
    임계값·top-k 정책을 바꾼 뒤 깨끗한 그래프에서 build_reference_edges를 다시 돌리기 위한 지우개.
    project_id를 주면 그 프로젝트 엣지만 삭제한다(per-project 재구축).

    Returns:
        삭제된 엣지 수.
    """
    # REFERENCE는 항상 ChangeSet→Communication이라 c:ChangeSet 바인딩으로 project_id 스코프를 건다.
    query = """
        MATCH (c:ChangeSet)-[r:REFERENCE]->()
        __PROJECT_FILTER__
        DELETE r
    """.replace("__PROJECT_FILTER__", "WHERE c.project_id = $project_id" if project_id else "")
    async with get_driver().session() as session:
        result = await session.run(query, project_id=project_id)
        summary = await result.consume()
        deleted = summary.counters.relationships_deleted
    logger.info("REFERENCE 엣지 삭제 완료: %d개", deleted)
    return deleted


async def delete_project_source_graph(
    project_id: str, source: str, batch_size: int = 10_000
) -> dict:
    """한 소스(GITHUB|SLACK|JIRA)에서 수집한 노드만 삭제한다. 연동 해제 cascade.

    delete_project_graph와 달리 프로젝트는 남기고 그 소스의 흔적만 지운다. 소스가 겹치는
    구조 때문에 단순히 `source` 속성으로 한 번 지우고 끝낼 수 없어 네 단계로 나눈다.

    1. 도메인 노드 — `source` 속성으로 스코프된다(Issue=JIRA, PullRequest/ChangeSet=GITHUB,
       Communication=SLACK|GITHUB). Communication이 두 소스 공용이라 라벨이 아니라 속성으로
       걸러야 한다.
    2. 고아 File — File은 `{project_id, path}`뿐이라 source가 없다(스키마상 GitHub 전용
       파생 노드). ChangeSet이 사라지면 MODIFIED가 끊긴 채 남으므로 여기서 정리한다.
    3. Actor alias — Actor는 소스를 가로지른다(aliases=["GITHUB:x", "SLACK:y"]). 지우는
       소스의 alias만 배열에서 빼고 ActorAlias 인덱스 노드를 삭제한 뒤, 남은 alias가 없는
       Actor만 삭제한다. 그러지 않으면 Slack 해제가 GitHub 이력까지 끊는다.
    4. ActorDecision — 수동 병합·분리 기록 중 삭제된 alias만 참조하는 것은 대상이 사라져
       무의미하므로 제거한다. 한쪽이라도 남아 있으면 보존한다(재수집 후 다시 적용돼야 한다).

    한계: Actor.emails는 어느 소스에서 얻었는지 기록하지 않아, 살아남은 Actor의 이메일은
    그대로 둔다. 소스별 출처를 남기려면 스키마 변경이 필요하다.

    대형 프로젝트의 tx timeout을 피하려고 delete_project_graph와 같은 배치 커밋을 쓴다.
    멱등 — 이미 지워진 소스를 다시 호출하면 전부 0이다.

    Returns:
        단계별 삭제 수 {"nodes", "files", "actors", "decisions"}.
    """
    if not project_id or not source:
        return {"nodes": 0, "files": 0, "actors": 0, "decisions": 0}

    normalized_source = source.upper()
    alias_prefix = f"{normalized_source}:"

    async with get_driver().session() as session:
        result = await session.run(
            """
            MATCH (n {project_id: $project_id, source: $source})
            CALL (n) { DETACH DELETE n } IN TRANSACTIONS OF $batch_size ROWS
            """,
            project_id=project_id,
            source=normalized_source,
            batch_size=batch_size,
        )
        nodes = (await result.consume()).counters.nodes_deleted

        # ChangeSet이 사라져 아무 커밋도 건드리지 않게 된 File
        result = await session.run(
            """
            MATCH (f:File {project_id: $project_id})
            WHERE NOT (f)<-[:MODIFIED]-()
            CALL (f) { DETACH DELETE f } IN TRANSACTIONS OF $batch_size ROWS
            """,
            project_id=project_id,
            batch_size=batch_size,
        )
        files = (await result.consume()).counters.nodes_deleted

        # ActorAlias 인덱스 노드 — Step 0 조회(actor_store.find_actor_by_alias)가 이걸 탄다
        await session.run(
            """
            MATCH (al:ActorAlias {project_id: $project_id})
            WHERE al.source_id STARTS WITH $alias_prefix
            DETACH DELETE al
            """,
            project_id=project_id,
            alias_prefix=alias_prefix,
        )

        # 이 소스 전용 Actor(가진 alias가 전부 해당 소스)는 삭제한다. any()가 앞에 있어야
        # alias가 비어 있던 기존 이상 데이터까지 쓸어가지 않는다 — all()은 빈 배열에 참이다.
        result = await session.run(
            """
            MATCH (a:Actor {project_id: $project_id})
            WHERE any(alias IN coalesce(a.aliases, []) WHERE alias STARTS WITH $alias_prefix)
              AND all(alias IN coalesce(a.aliases, []) WHERE alias STARTS WITH $alias_prefix)
            CALL (a) { DETACH DELETE a } IN TRANSACTIONS OF $batch_size ROWS
            """,
            project_id=project_id,
            alias_prefix=alias_prefix,
            batch_size=batch_size,
        )
        actors = (await result.consume()).counters.nodes_deleted

        # 살아남은 Actor(다른 소스 alias 보유)는 배열에서 해당 소스 alias만 뺀다
        await session.run(
            """
            MATCH (a:Actor {project_id: $project_id})
            WHERE any(alias IN coalesce(a.aliases, []) WHERE alias STARTS WITH $alias_prefix)
            SET a.aliases = [alias IN a.aliases WHERE NOT alias STARTS WITH $alias_prefix]
            """,
            project_id=project_id,
            alias_prefix=alias_prefix,
        )

        # 양쪽 모두 삭제된 alias만 가리키는 결정은 적용 대상이 없다
        result = await session.run(
            """
            MATCH (d:ActorDecision {project_id: $project_id})
            WITH d,
                 [alias IN coalesce(d.aliases_a, [])
                  WHERE NOT alias STARTS WITH $alias_prefix] AS remaining_a,
                 [alias IN coalesce(d.aliases_b, [])
                  WHERE NOT alias STARTS WITH $alias_prefix] AS remaining_b
            WHERE size(remaining_a) = 0 OR size(remaining_b) = 0
            DETACH DELETE d
            """,
            project_id=project_id,
            alias_prefix=alias_prefix,
        )
        decisions = (await result.consume()).counters.nodes_deleted

    logger.info(
        "소스 그래프 삭제 완료: project=%s, source=%s, nodes=%d, files=%d, actors=%d, decisions=%d",
        project_id,
        normalized_source,
        nodes,
        files,
        actors,
        decisions,
    )
    return {
        "nodes": nodes,
        "files": files,
        "actors": actors,
        "decisions": decisions,
    }


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
