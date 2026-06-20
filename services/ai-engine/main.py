import asyncio
import logging
import os
from contextlib import asynccontextmanager

from dotenv import load_dotenv
from fastapi import FastAPI
from pydantic import BaseModel

load_dotenv()

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)

from agent import orchestrator
from graph.builder import backfill_pr_jira_keys, backfill_triggered_by_source, clear_semantic_triggered_by, close_driver, delete_project_graph, ensure_constraints, ensure_vector_indexes, get_driver, make_neo4j_issue_link_store, make_neo4j_reference_store, propagate_thread_discussed_in
from graph.slack_batch_filter import run_slack_llm_filter
from graph.consumer import start_consumer
from graph.event_handler import handle
from graph.postprocess import run_postprocess_sequence, start_debounce_loop
from graph.overview import get_project_overview
from graph.issue_linker import build_issue_changeset_links, build_issue_communication_links
from graph.reference_builder import backfill_communication_embeddings, build_reference_edges
from query_models import QueryRequest, SummaryRequest

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
    summary = await asyncio.to_thread(get_project_summary, owner, repo_name)
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


@app.get("/health")
def health():
    return {"status": "ok"}


@app.get("/graph/overview")
async def graph_overview(project_id: str, limit: int = 200, types: str = ""):
    """프로젝트 그래프 개요 조회 (프론트 그래프 탐색용).

    project_id로 스코프된 최근 content 노드 + 연결 Actor/File을 {nodes, edges}로 반환한다.
    인가는 backend가 담당 — ai-engine은 backend가 넘긴 project_id를 신뢰하는 내부 서비스다.

    types: 쉼표 구분 프론트 type 화이트리스트(예: "commit,pr,jira"). 생략 시 전체.
    """
    type_list = [t for t in (types.split(",") if types else []) if t.strip()] or None
    return await get_project_overview(project_id, limit, type_list)


@app.delete("/graph/projects/{project_id}")
async def delete_project_graph_endpoint(project_id: str):
    """프로젝트의 Neo4j 서브그래프 전체를 삭제한다 (Actor 포함).

    backend의 프로젝트 삭제에서 호출하는 cascade. 인가는 backend가 담당 — ai-engine은
    backend가 넘긴 project_id를 신뢰하는 내부 서비스다. 멱등 — 없는 project_id면 deleted=0.
    """
    deleted = await delete_project_graph(project_id)
    return {"deleted": deleted}


@app.post("/test/ingest", tags=["test"])
async def test_ingest(event: dict):
    """[테스트 전용] NormalizedEvent를 RabbitMQ 없이 직접 주입한다.

    projectId 필수 — 없는 이벤트는 그래프 격리를 위해 건너뛴다 (event_handler.handle 참고).
    """
    await handle(event)
    return {"ok": True}


@app.post("/graph/build")
async def trigger_graph_build():
    """후처리(Layer 4) 시퀀스를 즉시 1회 실행한다.

    backfill → TRIGGERED_BY/DISCUSSED_IN → REFERENCE → 스레드 전파 순으로
    소스 간 시맨틱 엣지를 구축한다. 평소엔 수집 큐가 잠잠해지면 디바운스 루프
    (postprocess.start_debounce_loop)가 자동 호출하며, 이 엔드포인트는 디바운스를
    기다리지 않는 수동/운영 트리거다 (향후 프론트 '그래프 재구축' 버튼의 연결점).
    모든 단계 idempotent — _build_lock으로 디바운스 루프와 직렬화된다.
    """
    return await run_postprocess_sequence()


@app.post("/reference/build")
async def trigger_reference_build():
    """REFERENCE 엣지 배치 생성. 임베딩이 충분히 쌓인 뒤 수동 호출."""
    store = make_neo4j_reference_store()
    created = await build_reference_edges(store)
    return {"created": created}


@app.post("/reference/backfill")
async def trigger_backfill():
    """embedding 없는 Communication 노드 일괄 임베딩 보정."""
    store = make_neo4j_reference_store()
    saved = await backfill_communication_embeddings(store)
    return {"saved": saved}


@app.post("/reference/propagate-threads")
async def trigger_thread_propagation():
    """방안 C — 스레드 전파: DISCUSSED_IN 엣지를 같은 conversation_id 내 전체 메시지로 전파."""
    created = await propagate_thread_discussed_in()
    return {"created": created}


@app.post("/migrations/triggered-by-source")
async def trigger_triggered_by_source_backfill():
    """기존 TRIGGERED_BY 엣지에 source(text/semantic) / confidence 속성을 채우는 일회성 마이그레이션.

    이후 모든 쿼리는 r.source와 r.confidence를 기준으로 노이즈 엣지를 필터링하게 된다.
    Idempotent — 재실행해도 안전.
    """
    return await backfill_triggered_by_source()


@app.post("/migrations/clear-semantic-triggered-by")
async def trigger_clear_semantic_triggered_by():
    """source='semantic'인 TRIGGERED_BY 엣지를 모두 삭제한다.

    threshold/window/top-1 정책이 변경된 뒤 깨끗한 그래프에서 시맨틱 링크를 재구축하고 싶을 때 사용.
    텍스트(refs/PR 전파) 엣지는 보존되어 명시 참조는 손상되지 않는다.

    실행 순서 권장:
      1. POST /migrations/triggered-by-source       (모든 엣지에 source 라벨 보장)
      2. POST /migrations/clear-semantic-triggered-by  (시맨틱만 정리)
      3. POST /migrations/pr-jira-keys              (기존 PR에 jira_keys 백필 + 전파)
      4. POST /issue-links/build                     (새 정책으로 시맨틱 재구축)
    """
    deleted = await clear_semantic_triggered_by()
    return {"deleted": deleted}


@app.post("/migrations/pr-jira-keys")
async def trigger_pr_jira_keys_backfill():
    """기존 PR 노드 title/body에서 jira_keys를 추출해 pr.jira_keys로 저장하고
    그 PR에 묶인 모든 ChangeSet에 text TRIGGERED_BY를 전파한다.

    Phase 2(PR.jira_keys 전파) 변경 이전에 수집된 PR이 응답 단에서 누락되는 문제를 보정.
    Idempotent — pr.jira_keys가 이미 채워진 PR은 건너뜀.
    """
    return await backfill_pr_jira_keys()


@app.post("/query")
async def query(req: QueryRequest):
    """자연어 질문을 받아 GraphRAG tool calling으로 답변을 반환한다.

    project_id로 모든 그래프 쿼리가 스코프된다 — 없으면 어떤 프로젝트 노드에도 매칭되지
    않아 빈 답변이 된다 (안전한 degradation, 크로스 프로젝트 누출 없음).

    응답:
      - answer: markdown 형식 답변 (Structured Output → render).
      - structured: grounded_answer 스키마 dict (summary/evidence/unknown_aspects).
        Structured 호출 실패 시 null — 이때 answer는 LLM의 자유 텍스트 fallback.
    """
    if not req.project_id:
        logger.warning("/query에 project_id 없음 — 그래프 조회가 비어 있게 됩니다.")

    project_context = ""
    if req.repo and "/" in req.repo:
        from graph.project_context import get_project_summary
        owner, repo_name = req.repo.split("/", 1)
        project_context = await asyncio.to_thread(get_project_summary, owner, repo_name) or ""

    history = [message.model_dump() for message in req.history]
    prior_evidence = [evidence.model_dump() for evidence in req.prior_evidence]
    answer, structured = await orchestrator.run(
        req.question,
        project_context,
        project_id=req.project_id,
        history=history,
        prior_evidence=prior_evidence,
        running_summary=req.running_summary,
    )
    return {"answer": answer, "structured": structured}


@app.post("/query/summary")
async def summarize_query_history(req: SummaryRequest):
    """기존 누적 요약에 새 대화 턴을 병합해 갱신한다."""
    history = [message.model_dump() for message in req.history]
    summary = await orchestrator.summarize_history(req.running_summary, history)
    return {"summary": summary}


class SlackFilterOptions(BaseModel):
    repo: str = ""  # "owner/repo" 형식, 없으면 기본 컨텍스트 사용


@app.post("/slack/filter")
async def trigger_slack_filter(options: SlackFilterOptions = SlackFilterOptions()):
    """LLM 기반 Slack Communication 배치 필터링.
    슬랙 데이터 수집 완료 후 수동 호출. 스레드 단위 또는 (channel, date) 묶음으로 LLM 판단.
    """
    project_context = ""
    if options.repo and "/" in options.repo:
        from graph.project_context import get_project_summary
        owner, repo_name = options.repo.split("/", 1)
        project_context = await asyncio.to_thread(get_project_summary, owner, repo_name)

    result = await run_slack_llm_filter(project_context)
    return result


class IssueLinkOptions(BaseModel):
    # TRIGGERED_BY 시맨틱 매칭 임계값 (정밀도 우선 — 0.55 권장)
    triggered_by_threshold: float = 0.55
    # DISCUSSED_IN 시맨틱 매칭 임계값 (스레드 보존은 쿼리 단에서 처리하므로 기존값 유지)
    discussed_in_threshold: float = 0.40
    llm_verify: bool = False
    top_k: int = 5
    llm_threshold: float = 0.7
    repo: str = ""  # "owner/repo" 형식. llm_verify=true 일 때 도메인 컨텍스트 주입에 사용


@app.post("/issue-links/build")
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
            project_context = await asyncio.to_thread(get_project_summary, owner, repo_name) or ""

        triggered_by = await build_issue_changeset_links_verified(
            store, options.triggered_by_threshold, options.top_k, options.llm_threshold, project_context,
        )
        discussed_in = await build_issue_communication_links_verified(
            store, options.discussed_in_threshold, options.top_k, options.llm_threshold, project_context,
        )
    else:
        triggered_by = await build_issue_changeset_links(store, threshold=options.triggered_by_threshold)
        discussed_in = await build_issue_communication_links(store, threshold=options.discussed_in_threshold)
    return {"triggered_by": triggered_by, "discussed_in": discussed_in}
