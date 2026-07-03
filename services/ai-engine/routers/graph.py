"""그래프 API — 개요·검색·관련 서브그래프 조회, 프로젝트 그래프 삭제, 후처리(Layer 4) 수동 트리거."""

from fastapi import APIRouter
from pydantic import BaseModel, Field

from graph.builder import delete_project_graph
from graph.overview import get_evidence_subgraph, get_project_overview
from graph.postprocess import get_build_status, trigger_build
from graph.search import DEFAULT_LIMIT as SEARCH_DEFAULT_LIMIT
from graph.search import search_nodes

router = APIRouter()


# 답변 evidence 1건 — 도메인 키 참조. type은 lenient(str)로 받아 미지 타입은 resolve에서 무시.
class EvidenceRef(BaseModel):
    type: str = ""
    id: str = ""


class SubgraphRequest(BaseModel):
    project_id: str = ""
    evidence: list[EvidenceRef] = Field(default_factory=list)


@router.get("/graph/overview")
async def graph_overview(project_id: str, limit: int = 200, types: str = ""):
    """프로젝트 그래프 개요 조회 (프론트 그래프 탐색용).

    project_id로 스코프된 최근 content 노드 + 연결 Actor/File을 {nodes, edges}로 반환한다.
    인가는 backend가 담당 — ai-engine은 backend가 넘긴 project_id를 신뢰하는 내부 서비스다.

    types: 쉼표 구분 프론트 type 화이트리스트(예: "commit,pr,jira"). 생략 시 전체.
    """
    type_list = [t for t in (types.split(",") if types else []) if t.strip()] or None
    return await get_project_overview(project_id, limit, type_list)


@router.get("/graph/search")
async def graph_search(project_id: str, q: str, limit: int = SEARCH_DEFAULT_LIMIT):
    """그래프 노드 키워드 검색 (프론트 통합 검색용).

    overview의 최근 top-N 제한과 무관하게 full-text 인덱스로 프로젝트 그래프 전체를
    검색해, overview와 동일한 GraphNode shape + score를 {nodes}로 반환한다.
    인가는 backend가 담당 — ai-engine은 backend가 넘긴 project_id를 신뢰하는 내부 서비스다.
    """
    return {"nodes": await search_nodes(project_id, q, limit)}


@router.post("/graph/subgraph")
async def graph_subgraph(req: SubgraphRequest):
    """답변 evidence(도메인 키)로 관련 서브그래프를 조회한다 (대화 화면 그래프 패널용).

    evidence가 가리키는 노드(commit/PR/issue/message) + 1홉 이웃을 project_id 스코프로
    {nodes, edges, seeds}로 반환한다. seeds는 입력 evidence 순서의 노드 id(인용 카드 매핑용).
    인가는 backend가 담당 — ai-engine은 backend가 넘긴 project_id를 신뢰하는 내부 서비스다.
    """
    evidence = [e.model_dump() for e in req.evidence]
    return await get_evidence_subgraph(req.project_id, evidence)


@router.delete("/graph/projects/{project_id}")
async def delete_project_graph_endpoint(project_id: str):
    """프로젝트의 Neo4j 서브그래프 전체를 삭제한다 (Actor 포함).

    backend의 프로젝트 삭제에서 호출하는 cascade. 인가는 backend가 담당 — ai-engine은
    backend가 넘긴 project_id를 신뢰하는 내부 서비스다. 멱등 — 없는 project_id면 deleted=0.
    """
    deleted = await delete_project_graph(project_id)
    return {"deleted": deleted}


@router.post("/graph/build", status_code=202)
async def trigger_graph_build(project_id: str, verify: bool = False):
    """해당 프로젝트의 후처리(Layer 4) 빌드를 백그라운드로 시작하고 즉시 202를 반환한다.

    빌드는 길게는 수 분 걸리므로 동기로 블로킹하지 않는다. 백그라운드 태스크로 실행하고
    현재 상태(state=running/...)를 반환한다 — 진행 상황은 GET /graph/build/status로 폴링한다.
    같은 프로젝트가 이미 빌드 중이면 새로 시작하지 않고 진행 중 상태를 반환한다(coalesce).
    인가는 backend가 담당 — ai-engine은 backend가 넘긴 project_id를 신뢰하는 내부 서비스다.

    verify=false (기본): 방안 A — 임베딩 유사도만 (빠름, LLM 비용 없음).
    verify=true:         방안 D — 시맨틱 엣지 clear 후 LLM 검증으로 재구축
                         (false positive 감소, 호출당 LLM 비용). '정밀 재구축' 버튼용.
    """
    return trigger_build(project_id, verify)


@router.get("/graph/build/status")
async def graph_build_status(project_id: str):
    """프로젝트의 현재 빌드 상태를 반환한다 (POST /graph/build 후 폴링용).

    state: idle(빌드 이력 없음) | running | succeeded | failed.
    succeeded면 result에 단계별 카운트, failed면 error에 사유가 담긴다.
    """
    return get_build_status(project_id)
