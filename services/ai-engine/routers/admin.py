"""운영/마이그레이션 API — 일회성·수동 트리거.

REFERENCE 엣지 빌드, 일회성 마이그레이션, Slack 배치 필터, 이슈 링크 빌드, 테스트 주입.
모두 운영자가 명시적으로 호출하는 비공개 경로 (정기 read 트래픽 아님).
"""

import aio_pika
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from graph.actor_admin import (
    delete_decision,
    list_decisions,
    merge_actors,
    rename_actor,
    split_alias,
    unmerge_actors,
)
from graph.builder import (
    backfill_discussed_in_source,
    backfill_pr_issue_keys,
    backfill_triggered_by_source,
    clear_bulk_document_issue_links,
    clear_reference,
    clear_semantic_described_in,
    clear_semantic_discussed_in,
    clear_semantic_triggered_by,
    make_neo4j_document_link_store,
    make_neo4j_issue_link_store,
    make_neo4j_reference_store,
    propagate_thread_discussed_in,
    verify_actor_name_consistency,
)
from graph.consumer import (
    DLQ_QUEUE,
    EXCHANGE_NAME,
    PARKING_QUEUE,
    RABBITMQ_URL,
    RETRY_ROUTING_KEY,
)
from graph.document_linker import (
    DESCRIBED_IN_THRESHOLD as DESCRIBED_IN_DEFAULT_THRESHOLD,
    DOCUMENT_PRE_BUFFER_DAYS as DOCUMENT_DEFAULT_PRE_DAYS,
    DOCUMENT_REFERENCE_THRESHOLD as DOCUMENT_REFERENCE_DEFAULT_THRESHOLD,
    DOCUMENT_TOP_K as DOCUMENT_DEFAULT_TOP_K,
    build_described_in_document_edges,
    build_document_reference_edges,
)
from graph.event_handler import handle
from graph.issue_linker import (
    DEFAULT_DISCUSSED_IN_MARGIN as DISCUSSED_IN_DEFAULT_MARGIN,
    DISCUSSED_IN_POST_BUFFER_DAYS as DISCUSSED_IN_DEFAULT_POST_DAYS,
    DISCUSSED_IN_PRE_BUFFER_DAYS as DISCUSSED_IN_DEFAULT_PRE_DAYS,
    DISCUSSED_IN_THRESHOLD as DISCUSSED_IN_DEFAULT_THRESHOLD,
    TRIGGERED_BY_MESSAGE_MODE as TRIGGERED_BY_DEFAULT_MESSAGE_MODE,
    TRIGGERED_BY_THRESHOLD as TRIGGERED_BY_DEFAULT_THRESHOLD,
    backfill_issue_embeddings,
    build_issue_changeset_links,
    build_issue_communication_links,
)
from graph.reference_builder import (
    DEFAULT_MESSAGE_MODE as REFERENCE_DEFAULT_MESSAGE_MODE,
    DEFAULT_THRESHOLD as REFERENCE_DEFAULT_THRESHOLD,
    DEFAULT_TOP_K as REFERENCE_DEFAULT_TOP_K,
    backfill_changeset_message_embeddings,
    backfill_communication_embeddings,
    backfill_modified_embeddings,
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
    message_mode: str = REFERENCE_DEFAULT_MESSAGE_MODE,
):
    """REFERENCE 엣지 배치 생성. 임베딩이 충분히 쌓인 뒤 수동 호출.

    threshold:    엣지 생성 최소 유사도.
    top_k:        커밋당 유지할 최대 스레드 수 (fan-out 컷).
    message_mode: 커밋 메시지 임베딩 비교 방식 (off/max/only — reference_builder 참고).
    모두 코드 수정 없이 스윕하기 위한 파라미터다 (측정 루프용 — 확정된 값은
    reference_builder의 상수/기본값에 반영한다).

    LLM 검수까지 포함한 조합은 POST /graph/build?verify=true로 실행한다.
    """
    store = make_neo4j_reference_store()
    created = await build_reference_edges(store, threshold=threshold, top_k=top_k, message_mode=message_mode)
    return {"created": created}


@router.post("/reference/backfill")
async def trigger_backfill(force: bool = False):
    """embedding 없는 Communication 노드 일괄 임베딩 보정.

    force=true면 이미 embedding이 있는 노드까지 덮어쓴다 (임베딩 모델 교체 시 전량 재임베딩).
    응답의 saved != total이면 일부가 임베딩되지 않은 것이다 (백필 4종 공통).
    """
    store = make_neo4j_reference_store()
    return await backfill_communication_embeddings(store, force=force)


@router.post("/reference/propagate-threads")
async def trigger_thread_propagation():
    """스레드 전파 — DISCUSSED_IN 엣지를 같은 conversation_id 내 전체 메시지로 전파."""
    created = await propagate_thread_discussed_in()
    return {"created": created}


@router.post("/migrations/changeset-embeddings")
async def trigger_changeset_embeddings_backfill(project_id: str | None = None, force: bool = False):
    """message는 있는데 embedding이 없는 ChangeSet 노드 일괄 임베딩 보정.

    커밋 메시지 임베딩 도입 이전에 수집된 노드를 이벤트 재주입 없이 채우는 백필.
    project_id를 주면 그 프로젝트만. Idempotent — 이미 채워진 노드는 건너뜀.
    force=true면 이미 채워진 노드까지 덮어쓴다 (임베딩 모델 교체 시 전량 재임베딩).
    응답의 saved != total이면 일부가 임베딩되지 않은 것이다 (백필 4종 공통).
    """
    store = make_neo4j_reference_store(project_id)
    return await backfill_changeset_message_embeddings(store, force=force)


@router.post("/migrations/modified-embeddings")
async def trigger_modified_embeddings_backfill(project_id: str | None = None, force: bool = False):
    """diffSummary는 있는데 embedding이 없는 MODIFIED 엣지 일괄 임베딩 보정.

    저장된 diffSummary를 그대로 임베딩한다 (요약 LLM 재호출 없음).
    project_id를 주면 그 프로젝트만. Idempotent — 이미 채워진 엣지는 건너뜀.
    force=true면 이미 채워진 엣지까지 덮어쓴다 (임베딩 모델 교체 시 전량 재임베딩).
    응답의 saved != total이면 일부가 임베딩되지 않은 것이다 (백필 4종 공통).
    """
    store = make_neo4j_reference_store(project_id)
    return await backfill_modified_embeddings(store, force=force)


@router.post("/migrations/issue-embeddings")
async def trigger_issue_embeddings_backfill(project_id: str | None = None, force: bool = False):
    """embedding이 없는 Issue 노드 일괄 임베딩 보정.

    임베딩 대상은 수집 경로와 같은 "title\\n\\nbody"다.
    project_id를 주면 그 프로젝트만. Idempotent — 이미 채워진 노드는 건너뜀.
    force=true면 이미 채워진 노드까지 덮어쓴다 (임베딩 모델 교체 시 전량 재임베딩).
    응답의 saved != total이면 일부가 임베딩되지 않은 것이다 (백필 4종 공통).
    """
    store = make_neo4j_issue_link_store(project_id)
    return await backfill_issue_embeddings(store, force=force)


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
      3. POST /migrations/pr-issue-keys              (기존 PR에 issue_keys 백필 + 전파)
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
    """시맨틱 REFERENCE 엣지를 삭제한다. project_id를 주면 그 프로젝트만.

    명시 URL 참조(source='text')는 보존하고, source 없는 기존 엣지는 시맨틱으로 간주해 삭제한다.
    임계값을 바꿔 재구축(POST /reference/build?threshold=...)하기 전에 호출한다.
    """
    deleted = await clear_reference(project_id)
    return {"deleted": deleted}


@router.post("/migrations/clear-semantic-described-in")
async def trigger_clear_semantic_described_in(project_id: str | None = None):
    """source='semantic'인 DESCRIBED_IN(Issue→Document) 엣지를 삭제한다. project_id를 주면 그 프로젝트만.

    text(명시 이슈 키/URL 참조) 엣지는 보존된다. 임계값을 바꿔 재구축(POST /document-links/build)하기
    전에 호출한다 — clear-reference는 REFERENCE만 지우고 DESCRIBED_IN은 지우지 않는다.
    """
    deleted = await clear_semantic_described_in(project_id)
    return {"deleted": deleted}


@router.post("/migrations/clear-bulk-document-issue-links")
async def trigger_clear_bulk_document_issue_links(project_id: str | None = None):
    """상한(DOCUMENT_ISSUE_REF_LIMIT)을 넘는 문서의 text DESCRIBED_IN(Issue→Document) 엣지를
    삭제한다. project_id를 주면 그 프로젝트만.

    이건 semantic이 아니라 **text 엣지를 지운다** — graph/event_handler.py의 런타임 가드가
    막는 건 "앞으로 들어오는" 이벤트뿐이라, 가드 도입 이전에 이미 상한을 넘겨 만들어진
    text 링크(색인·QA 문서가 이슈 키를 대량 나열한 경우)는 이 소급 정리로만 지울 수 있다.
    상한 이하인 문서의 링크는 건드리지 않는다.
    """
    return await clear_bulk_document_issue_links(project_id)


@router.post("/migrations/pr-issue-keys")
async def trigger_pr_issue_keys_backfill():
    """기존 PR 노드 title/body에서 issue_keys를 추출해 pr.issue_keys로 저장하고
    그 PR에 묶인 모든 ChangeSet에 text TRIGGERED_BY를 전파한다.

    Phase 2(PR.issue_keys 전파) 변경 이전에 수집된 PR이 응답 단에서 누락되는 문제를 보정.
    Idempotent — pr.issue_keys가 이미 채워진 PR은 건너뜀.
    """
    return await backfill_pr_issue_keys()


@router.post("/migrations/verify-actor-names")
async def trigger_verify_actor_names(project_id: str | None = None):
    """모든 Actor의 name이 alias 기준 기대값과 일치하는지 검증한다. project_id를 주면 그 프로젝트만.

    개인정보 삭제는 "alias 비우기 + Actor.name 재계산"이 한 트랜잭션이어야 하는데, 그 불변식이
    깨져도 에러 없이 조용히 어긋난다 — 이 엔드포인트로 수동 감사한다. 읽기 전용이라 mismatches가
    있어도 직접 고치지 않는다.
    """
    return await verify_actor_name_consistency(project_id)


@router.post("/slack/filter")
async def trigger_slack_filter():
    """LLM 기반 Slack Communication 배치 필터링.
    슬랙 데이터 수집 완료 후 수동 호출. 스레드 단위 또는 (channel, date) 묶음으로 LLM 판단.
    """
    result = await run_slack_llm_filter()
    return result


class IssueLinkOptions(BaseModel):
    # TRIGGERED_BY 시맨틱 매칭 임계값 (기본값 근거는 issue_linker 상수 주석 참고)
    triggered_by_threshold: float = TRIGGERED_BY_DEFAULT_THRESHOLD
    # TRIGGERED_BY 커밋 메시지 임베딩 비교 방식 (off/max/only — issue_linker 참고)
    triggered_by_message_mode: str = TRIGGERED_BY_DEFAULT_MESSAGE_MODE
    # DISCUSSED_IN 시맨틱 매칭 임계값 (기본값 근거는 issue_linker 상수 주석 참고)
    discussed_in_threshold: float = DISCUSSED_IN_DEFAULT_THRESHOLD
    # DISCUSSED_IN fan-out 컷 — 이슈 최고점 스레드와의 허용 점수차
    discussed_in_margin: float = DISCUSSED_IN_DEFAULT_MARGIN
    # DISCUSSED_IN 시간 윈도우 — 이슈 생애(생성~종료) 앞뒤로 며칠까지 후보로 볼지.
    # 코드 수정 없이 스윕하기 위한 파라미터. 확정된 값은 issue_linker 상수에 반영한다.
    discussed_in_pre_days: int = DISCUSSED_IN_DEFAULT_PRE_DAYS
    discussed_in_post_days: int = DISCUSSED_IN_DEFAULT_POST_DAYS


@router.post("/issue-links/build")
async def trigger_issue_links(options: IssueLinkOptions = IssueLinkOptions()):
    """Issue ↔ ChangeSet, Issue ↔ Communication 엣지 생성 (임베딩 유사도만).

    LLM 검수까지 포함한 조합은 POST /graph/build?verify=true로 실행한다.
    """
    store = make_neo4j_issue_link_store()
    triggered_by = await build_issue_changeset_links(
        store,
        threshold=options.triggered_by_threshold,
        message_mode=options.triggered_by_message_mode,
    )
    discussed_in = await build_issue_communication_links(
        store,
        threshold=options.discussed_in_threshold,
        margin=options.discussed_in_margin,
        pre_days=options.discussed_in_pre_days,
        post_days=options.discussed_in_post_days,
    )
    return {"triggered_by": triggered_by, "discussed_in": discussed_in}


class DocumentLinkOptions(BaseModel):
    # REFERENCE(ChangeSet→Document) 시맨틱 매칭 임계값 (기본값 근거는 document_linker 상수 주석 참고)
    reference_threshold: float = DOCUMENT_REFERENCE_DEFAULT_THRESHOLD
    # DESCRIBED_IN(Issue→Document) 시맨틱 매칭 임계값 (기본값 근거는 document_linker 상수 주석 참고)
    described_in_threshold: float = DESCRIBED_IN_DEFAULT_THRESHOLD
    # 문서당 유지할 최대 매칭 수 (fan-out 컷 — 반대편인 ChangeSet/Issue는 열어 둔다). REFERENCE·DESCRIBED_IN 공용.
    top_k: int = DOCUMENT_DEFAULT_TOP_K
    # 문서 시간 윈도우 하한 버퍼(일) — 문서 생성일 이전의 ChangeSet/Issue는 후보에서 제외. 상한은 없다.
    pre_days: int = DOCUMENT_DEFAULT_PRE_DAYS


@router.post("/document-links/build")
async def trigger_document_links(options: DocumentLinkOptions = DocumentLinkOptions()):
    """ChangeSet ↔ Document REFERENCE, Issue ↔ Document DESCRIBED_IN 엣지 생성 (임베딩 유사도만).

    LLM 검수 빌더는 없다 — 자동구축(임베딩만) 경로만 있다(document_linker 모듈 docstring 참고).
    """
    store = make_neo4j_document_link_store()
    reference = await build_document_reference_edges(
        store,
        threshold=options.reference_threshold,
        top_k=options.top_k,
        pre_days=options.pre_days,
    )
    described_in = await build_described_in_document_edges(
        store,
        threshold=options.described_in_threshold,
        top_k=options.top_k,
        pre_days=options.pre_days,
    )
    return {"reference": reference, "described_in": described_in}


class ActorMergeRequest(BaseModel):
    project_id: str
    uuid_a: str  # 합칠 두 노드 중 하나
    uuid_b: str  # 합칠 두 노드 중 나머지 — 어느 쪽이 살아남을지는 활동 엣지 수로 자동 결정
    note: str = ""


class ActorUnmergeRequest(BaseModel):
    project_id: str
    decision_id: str  # kind='same' 병합 결정


class ActorSplitRequest(BaseModel):
    project_id: str
    actor_uuid: str
    source_ids: list[str]  # 분리할 alias 목록 (예: ["GITHUB:se-zero"])


class ActorRenameRequest(BaseModel):
    project_id: str
    actor_uuid: str
    name: str


@router.post("/actors/merge", tags=["actors"])
async def trigger_actor_merge(req: ActorMergeRequest):
    """Actor 수동 병합 — 두 노드를 같은 사람으로 합친다.

    어느 쪽이 살아남을지(canonical)는 활동 엣지가 많은 쪽으로 자동 결정된다 — 표시 이름은
    입력받지 않고 alias 기준으로 재계산된다. merged_from 표식을 남겨 /actors/unmerge로 복원 가능하다.
    설계: docs/actor-manual-merge.md
    """
    try:
        return await merge_actors(req.project_id, req.uuid_a, req.uuid_b, req.note)
    except LookupError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.post("/actors/rename", tags=["actors"])
async def trigger_actor_rename(req: ActorRenameRequest):
    """Actor 표시 이름 변경 — 수동 관리 화면에서 canonical 이름을 정리한다."""
    try:
        return await rename_actor(req.project_id, req.actor_uuid, req.name)
    except LookupError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.post("/actors/unmerge", tags=["actors"])
async def trigger_actor_unmerge(req: ActorUnmergeRequest):
    """수동 병합 취소 — same 결정의 스냅샷으로 정확히 복원하고 distinct 결정을 남긴다."""
    try:
        return await unmerge_actors(req.project_id, req.decision_id)
    except LookupError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.post("/actors/split", tags=["actors"])
async def trigger_actor_split(req: ActorSplitRequest):
    """자동 병합 교정 — Actor에서 alias 일부를 새 Actor로 분리하고 distinct 결정을 남긴다.

    표시 이름은 입력받지 않고 alias 기준으로 재계산된다 — 직접 정하려면 /actors/rename을 쓴다.
    """
    try:
        return await split_alias(req.project_id, req.actor_uuid, req.source_ids)
    except LookupError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.get("/actors/decisions", tags=["actors"])
async def actor_decisions(project_id: str):
    """수동 결정 이력 — 감사 및 unmerge/철회 대상 조회용."""
    return {"decisions": await list_decisions(project_id)}


@router.delete("/actors/decisions/{decision_id}", tags=["actors"])
async def revoke_actor_decision(decision_id: str, project_id: str):
    """distinct 결정 철회 — 자동 파이프라인의 재병합을 다시 허용한다.

    same 결정은 삭제 불가(복원 데이터 보유) — unmerge로만 해소한다.
    """
    deleted = await delete_decision(project_id, decision_id)
    if not deleted:
        raise HTTPException(status_code=404, detail=f"distinct 결정 없음: {decision_id}")
    return {"deleted": deleted}


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
