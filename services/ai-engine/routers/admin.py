"""운영/마이그레이션 API — 일회성·수동 트리거.

REFERENCE 엣지 빌드, 일회성 마이그레이션, Slack 배치 필터, 이슈 링크 빌드, 테스트 주입.
모두 운영자가 명시적으로 호출하는 비공개 경로 (정기 read 트래픽 아님).
"""

import aio_pika
from fastapi import APIRouter
from pydantic import BaseModel

from graph.builder import (
    backfill_discussed_in_source,
    backfill_pr_jira_keys,
    backfill_triggered_by_source,
    clear_reference,
    clear_semantic_discussed_in,
    clear_semantic_triggered_by,
    make_neo4j_issue_link_store,
    make_neo4j_reference_store,
    propagate_thread_discussed_in,
)
from graph.consumer import (
    DLQ_QUEUE,
    EXCHANGE_NAME,
    PARKING_QUEUE,
    RABBITMQ_URL,
    RETRY_ROUTING_KEY,
)
from graph.event_handler import handle
from graph.issue_linker import (
    DEFAULT_DISCUSSED_IN_MARGIN as DISCUSSED_IN_DEFAULT_MARGIN,
    build_issue_changeset_links,
    build_issue_communication_links,
)
from graph.reference_builder import (
    DEFAULT_THRESHOLD as REFERENCE_DEFAULT_THRESHOLD,
    DEFAULT_TOP_K as REFERENCE_DEFAULT_TOP_K,
    backfill_communication_embeddings,
    build_reference_edges,
)
from graph.slack_batch_filter import run_slack_llm_filter

router = APIRouter()


@router.post("/test/ingest", tags=["test"])
async def test_ingest(event: dict):
    """[테스트 전용] NormalizedEvent를 RabbitMQ 없이 직접 주입한다.

    projectId 필수 — 없는 이벤트는 그래프 격리를 위해 건너뛴다 (event_handler.handle 참고).
    """
    await handle(event)
    return {"ok": True}


@router.post("/reference/build")
async def trigger_reference_build(
    threshold: float = REFERENCE_DEFAULT_THRESHOLD,
    top_k: int = REFERENCE_DEFAULT_TOP_K,
):
    """REFERENCE 엣지 배치 생성. 임베딩이 충분히 쌓인 뒤 수동 호출.

    threshold: 엣지 생성 최소 유사도.
    top_k:     커밋당 유지할 최대 스레드 수 (fan-out 컷).
    둘 다 코드 수정 없이 스윕하기 위한 파라미터다 (측정 루프용 — 확정된 값은
    reference_builder의 DEFAULT_THRESHOLD·DEFAULT_TOP_K에 반영한다).
    """
    store = make_neo4j_reference_store()
    created = await build_reference_edges(store, threshold=threshold, top_k=top_k)
    return {"created": created}


@router.post("/reference/backfill")
async def trigger_backfill():
    """embedding 없는 Communication 노드 일괄 임베딩 보정."""
    store = make_neo4j_reference_store()
    saved = await backfill_communication_embeddings(store)
    return {"saved": saved}


@router.post("/reference/propagate-threads")
async def trigger_thread_propagation():
    """방안 C — 스레드 전파: DISCUSSED_IN 엣지를 같은 conversation_id 내 전체 메시지로 전파."""
    created = await propagate_thread_discussed_in()
    return {"created": created}


@router.post("/migrations/triggered-by-source")
async def trigger_triggered_by_source_backfill():
    """기존 TRIGGERED_BY 엣지에 source(text/semantic) / confidence 속성을 채우는 일회성 마이그레이션.

    이후 모든 쿼리는 r.source와 r.confidence를 기준으로 노이즈 엣지를 필터링하게 된다.
    Idempotent — 재실행해도 안전.
    """
    return await backfill_triggered_by_source()


@router.post("/migrations/discussed-in-source")
async def trigger_discussed_in_source_backfill():
    """기존 DISCUSSED_IN 엣지에 source(text/semantic/propagated) 표식을 채우는 일회성 마이그레이션.

    표식이 없으면 clear가 시맨틱·전파 엣지만 골라 지울 수 없다(텍스트까지 지우거나, 전파
    복사본이 남아 오탐이 되살아난다). clear-semantic-discussed-in보다 먼저 1회 실행한다.
    Idempotent — 재실행해도 안전.
    """
    return await backfill_discussed_in_source()


@router.post("/migrations/clear-semantic-triggered-by")
async def trigger_clear_semantic_triggered_by(project_id: str | None = None):
    """source='semantic'인 TRIGGERED_BY 엣지를 삭제한다. project_id를 주면 그 프로젝트만.

    threshold/window/top-1 정책이 변경된 뒤 깨끗한 그래프에서 시맨틱 링크를 재구축하고 싶을 때 사용.
    텍스트(refs/PR 전파) 엣지는 보존되어 명시 참조는 손상되지 않는다.

    실행 순서 권장:
      1. POST /migrations/triggered-by-source       (모든 엣지에 source 라벨 보장)
      2. POST /migrations/clear-semantic-triggered-by  (시맨틱만 정리)
      3. POST /migrations/pr-jira-keys              (기존 PR에 jira_keys 백필 + 전파)
      4. POST /issue-links/build                     (새 정책으로 시맨틱 재구축)
    """
    deleted = await clear_semantic_triggered_by(project_id)
    return {"deleted": deleted}


@router.post("/migrations/clear-semantic-discussed-in")
async def trigger_clear_semantic_discussed_in(project_id: str | None = None):
    """시맨틱 DISCUSSED_IN과 그 스레드 전파 복사본을 삭제한다. project_id를 주면 그 프로젝트만.

    텍스트(source='text') 엣지는 보존된다. 선행 조건: /migrations/discussed-in-source가
    한 번이라도 실행돼 모든 엣지에 source가 라벨링되어 있어야 전파 복사본까지 정리된다.
    """
    deleted = await clear_semantic_discussed_in(project_id)
    return {"deleted": deleted}


@router.post("/migrations/clear-reference")
async def trigger_clear_reference(project_id: str | None = None):
    """REFERENCE 엣지를 삭제한다. project_id를 주면 그 프로젝트만.

    REFERENCE는 텍스트 경로가 없어 전부 시맨틱 산물이라 조건 없이 전량 삭제한다.
    임계값을 바꿔 재구축(POST /reference/build?threshold=...)하기 전에 호출한다.
    """
    deleted = await clear_reference(project_id)
    return {"deleted": deleted}


@router.post("/migrations/pr-jira-keys")
async def trigger_pr_jira_keys_backfill():
    """기존 PR 노드 title/body에서 jira_keys를 추출해 pr.jira_keys로 저장하고
    그 PR에 묶인 모든 ChangeSet에 text TRIGGERED_BY를 전파한다.

    Phase 2(PR.jira_keys 전파) 변경 이전에 수집된 PR이 응답 단에서 누락되는 문제를 보정.
    Idempotent — pr.jira_keys가 이미 채워진 PR은 건너뜀.
    """
    return await backfill_pr_jira_keys()


class SlackFilterOptions(BaseModel):
    repo: str = ""  # "owner/repo" 형식, 없으면 기본 컨텍스트 사용


@router.post("/slack/filter")
async def trigger_slack_filter(options: SlackFilterOptions = SlackFilterOptions()):
    """LLM 기반 Slack Communication 배치 필터링.
    슬랙 데이터 수집 완료 후 수동 호출. 스레드 단위 또는 (channel, date) 묶음으로 LLM 판단.
    """
    project_context = ""
    if options.repo and "/" in options.repo:
        from graph.project_context import get_project_summary
        owner, repo_name = options.repo.split("/", 1)
        project_context = await get_project_summary(owner, repo_name)

    result = await run_slack_llm_filter(project_context)
    return result


class IssueLinkOptions(BaseModel):
    # TRIGGERED_BY 시맨틱 매칭 임계값 (정밀도 우선 — 0.55 권장)
    triggered_by_threshold: float = 0.55
    # DISCUSSED_IN 시맨틱 매칭 임계값 (스레드 보존은 쿼리 단에서 처리하므로 기존값 유지)
    discussed_in_threshold: float = 0.40
    # DISCUSSED_IN fan-out 컷 — 이슈 최고점 스레드와의 허용 점수차 (방안 A 전용)
    discussed_in_margin: float = DISCUSSED_IN_DEFAULT_MARGIN
    llm_verify: bool = False
    top_k: int = 5
    llm_threshold: float = 0.7
    repo: str = ""  # "owner/repo" 형식. llm_verify=true 일 때 도메인 컨텍스트 주입에 사용


@router.post("/issue-links/build")
async def trigger_issue_links(options: IssueLinkOptions = IssueLinkOptions()):
    """방안 A/D — Issue ↔ ChangeSet, Issue ↔ Communication 엣지 생성.

    llm_verify=false (기본): 방안 A — 임베딩 유사도만으로 판단
    llm_verify=true:         방안 D — 임베딩 후보 선별 후 LLM 검증
    """
    store = make_neo4j_issue_link_store()
    if options.llm_verify:
        from graph.issue_verifier import (
            build_issue_changeset_links_verified,
            build_issue_communication_links_verified,
        )
        project_context = ""
        if options.repo and "/" in options.repo:
            from graph.project_context import get_project_summary
            owner, repo_name = options.repo.split("/", 1)
            project_context = await get_project_summary(owner, repo_name) or ""

        triggered_by = await build_issue_changeset_links_verified(
            store, options.triggered_by_threshold, options.top_k, options.llm_threshold, project_context,
        )
        discussed_in = await build_issue_communication_links_verified(
            store, options.discussed_in_threshold, options.top_k, options.llm_threshold, project_context,
        )
    else:
        triggered_by = await build_issue_changeset_links(store, threshold=options.triggered_by_threshold)
        discussed_in = await build_issue_communication_links(
            store, threshold=options.discussed_in_threshold, margin=options.discussed_in_margin,
        )
    return {"triggered_by": triggered_by, "discussed_in": discussed_in}


class DlqReplayOptions(BaseModel):
    max_messages: int = 100  # 한 번에 재투입할 최대 건수


@router.get("/dlq/stats", tags=["dlq"])
async def dlq_stats():
    """DLQ와 parking 큐에 쌓인 메시지 수를 반환한다(운영 점검용).

    parking(malformed)을 함께 노출해 손상 메시지 유입을 바로 알아챌 수 있게 한다.
    """
    connection = await aio_pika.connect_robust(RABBITMQ_URL)
    async with connection:
        channel = await connection.channel()
        dlq = await channel.declare_queue(DLQ_QUEUE, durable=True, passive=True)
        parking = await channel.declare_queue(PARKING_QUEUE, durable=True, passive=True)
        return {
            "dlq": dlq.declaration_result.message_count,
            "parking": parking.declaration_result.message_count,
        }


@router.post("/dlq/replay", tags=["dlq"])
async def dlq_replay(options: DlqReplayOptions = DlqReplayOptions()):
    """DLQ에 파킹된 메시지를 최대 max_messages건 꺼내 정상 파이프라인으로 재투입한다.

    장애(예: Neo4j 다운) 해소 후 호출. history.exchange에 event.retry(⊂ event.#)로 재발행하며
    x-retry-count를 리셋(미설정)해 재시도 예산을 새로 준다.
    parking 큐(malformed)는 재투입해도 다시 실패하므로 **건드리지 않는다**.
    각 메시지는 재발행 성공 시에만 ack(=DLQ에서 제거)하고, 실패하면 requeue돼 DLQ에 남는다.
    """
    connection = await aio_pika.connect_robust(RABBITMQ_URL)
    async with connection:
        channel = await connection.channel()
        exchange = await channel.declare_exchange(
            EXCHANGE_NAME, aio_pika.ExchangeType.TOPIC, durable=True
        )
        dlq = await channel.declare_queue(DLQ_QUEUE, durable=True, passive=True)

        replayed = 0
        while replayed < options.max_messages:
            message = await dlq.get(fail=False)
            if message is None:
                break
            async with message.process(requeue=True):
                await exchange.publish(
                    aio_pika.Message(
                        body=message.body,
                        delivery_mode=aio_pika.DeliveryMode.PERSISTENT,
                    ),
                    routing_key=RETRY_ROUTING_KEY,
                )
            replayed += 1
    return {"replayed": replayed}
