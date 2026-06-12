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
from graph.builder import backfill_pr_jira_keys, backfill_triggered_by_source, clear_semantic_triggered_by, close_driver, ensure_constraints, ensure_vector_indexes, get_driver, make_neo4j_issue_link_store, make_neo4j_reference_store, propagate_thread_discussed_in
from graph.slack_batch_filter import run_slack_llm_filter
from graph.consumer import start_consumer
from graph.event_handler import handle
from graph.overview import get_project_overview
from graph.issue_linker import build_issue_changeset_links, build_issue_communication_links
from graph.reference_builder import backfill_communication_embeddings, build_reference_edges

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
    get_driver()  # 연결 검증 겸 초기화
    await ensure_constraints()
    await ensure_vector_indexes()
    await _prewarm_project_context()
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


@app.get("/graph/overview")
async def graph_overview(project_id: str, limit: int = 200, types: str = ""):
    """프로젝트 그래프 개요 조회 (프론트 그래프 탐색용).

    project_id로 스코프된 최근 content 노드 + 연결 Actor/File을 {nodes, edges}로 반환한다.
    인가는 backend가 담당 — ai-engine은 backend가 넘긴 project_id를 신뢰하는 내부 서비스다.

    types: 쉼표 구분 프론트 type 화이트리스트(예: "commit,pr,jira"). 생략 시 전체.
    """
    type_list = [t for t in (types.split(",") if types else []) if t.strip()] or None
    return await get_project_overview(project_id, limit, type_list)


@app.post("/test/ingest", tags=["test"])
async def test_ingest(event: dict):
    """[테스트 전용] NormalizedEvent를 RabbitMQ 없이 직접 주입한다.

    projectId 필수 — 없는 이벤트는 그래프 격리를 위해 건너뛴다 (event_handler.handle 참고).
    """
    await handle(event)
    return {"ok": True}


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


class QueryRequest(BaseModel):
    question: str
    repo: str = ""  # "owner/repo" 형식. 도메인 컨텍스트 주입용. 없으면 컨텍스트 없이 동작.


@app.post("/query")
async def query(req: QueryRequest):
    """자연어 질문을 받아 GraphRAG tool calling으로 답변을 반환한다.

    응답:
      - answer: markdown 형식 답변 (Structured Output → render).
      - structured: grounded_answer 스키마 dict (summary/evidence/unknown_aspects).
        Structured 호출 실패 시 null — 이때 answer는 LLM의 자유 텍스트 fallback.
    """
    project_context = ""
    if req.repo and "/" in req.repo:
        from graph.project_context import get_project_summary
        owner, repo_name = req.repo.split("/", 1)
        project_context = await asyncio.to_thread(get_project_summary, owner, repo_name) or ""

    answer, structured = await orchestrator.run(req.question, project_context)
    return {"answer": answer, "structured": structured}


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
