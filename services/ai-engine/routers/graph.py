"""그래프 API — 개요·관련 서브그래프 조회, 프로젝트 그래프 삭제, 후처리(Layer 4) 수동 트리거."""

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from graph.actor_admin import get_actor_detail, list_actors
from graph.builder import delete_project_graph, delete_project_source_graph
from graph.overview import (
    WORK_UNITS_DEFAULT_LIMIT,
    get_evidence_subgraph,
    get_project_overview,
    get_work_unit_neighborhood,
    get_work_units_view,
)
from graph.postprocess import get_build_status, get_graph_activity, trigger_build

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


@router.get("/graph/work-units")
async def graph_work_units(project_id: str, limit: int = WORK_UNITS_DEFAULT_LIMIT):
    """작업 단위 뷰용 그래프 조회 (프론트 작업 단위 화면용).

    overview와 달리 작업 단위(PullRequest, 없으면 Issue)를 전량 가져오고 구성 노드만 최신
    limit개로 자른다 — 작업 단위 노드가 빠지면 그림 자체가 틀리기 때문. {nodes, edges,
    work_unit_ids}를 반환한다.
    인가는 backend가 담당 — ai-engine은 backend가 넘긴 project_id를 신뢰하는 내부 서비스다.
    """
    return await get_work_units_view(project_id, limit)


@router.get("/graph/work-unit/neighbors")
async def graph_work_unit_neighbors(project_id: str, node_id: str):
    """작업 단위 하나의 이웃 조회 (작업 단위 드릴인용).

    작업 단위 뷰는 구성 노드를 최신 N개로 자르므로 오래된 작업은 작업 단위 노드만 있고
    구성 노드가 비어 있다. 그 작업 단위를 열 때 이 조회로 해당 작업의 이웃만 채운다.
    {nodes, edges} 반환.
    """
    return await get_work_unit_neighborhood(project_id, node_id)


@router.post("/graph/subgraph")
async def graph_subgraph(req: SubgraphRequest):
    """답변 evidence(도메인 키)로 관련 서브그래프를 조회한다 (대화 화면 그래프 패널용).

    evidence가 가리키는 노드(commit/PR/issue/document/message) + 1홉 이웃을 project_id 스코프로
    {nodes, edges, seeds}로 반환한다. seeds는 입력 evidence 순서의 노드 id(인용 카드 매핑용).
    인가는 backend가 담당 — ai-engine은 backend가 넘긴 project_id를 신뢰하는 내부 서비스다.
    """
    evidence = [e.model_dump() for e in req.evidence]
    return await get_evidence_subgraph(req.project_id, evidence)


@router.get("/graph/actors")
async def graph_actors(project_id: str):
    """프로젝트 Actor 목록 + 활동 수 (액터 관리 UI용).

    "이 둘이 같은 사람인가" 판단 재료인 소스별 이름(source_names)만 내려준다. 이메일·계정ID는
    개인정보라 목록에 넣지 않는다 — 병합·분리 폼을 열 때 GET /graph/actors/{actor_uuid}로 조회한다.
    인가는 backend가 담당 — ai-engine은 backend가 넘긴 project_id를 신뢰하는 내부 서비스다.
    """
    return {"actors": await list_actors(project_id)}


@router.get("/graph/actors/{actor_uuid}")
async def graph_actor_detail(actor_uuid: str, project_id: str):
    """Actor 상세 조회 (병합·분리 폼용) — 이메일·계정ID를 포함한다.

    목록(/graph/actors)과 달리 여기서만 개인정보를 노출한다.
    인가는 backend가 담당 — ai-engine은 backend가 넘긴 project_id를 신뢰하는 내부 서비스다.
    """
    try:
        return await get_actor_detail(project_id, actor_uuid)
    except LookupError as e:
        raise HTTPException(status_code=404, detail=str(e))


@router.delete("/graph/projects/{project_id}")
async def delete_project_graph_endpoint(project_id: str):
    """프로젝트의 Neo4j 서브그래프 전체를 삭제한다 (Actor 포함).

    backend의 프로젝트 삭제에서 호출하는 cascade. 인가는 backend가 담당 — ai-engine은
    backend가 넘긴 project_id를 신뢰하는 내부 서비스다. 멱등 — 없는 project_id면 deleted=0.
    """
    deleted = await delete_project_graph(project_id)
    return {"deleted": deleted}


@router.delete("/graph/projects/{project_id}/sources/{source}")
async def delete_project_source_graph_endpoint(project_id: str, source: str):
    """한 소스에서 수집한 노드만 삭제한다 (GITHUB|SLACK|JIRA).

    backend의 연동 해제에서 호출하는 cascade. 프로젝트와 다른 소스의 노드는 남는다.
    인가는 backend가 담당 — 프로젝트 삭제와 같은 내부 서비스 신뢰 모델이다.
    멱등 — 이미 지워진 소스를 다시 호출하면 전부 0이다.
    """
    return await delete_project_source_graph(project_id, source)


@router.post("/graph/build", status_code=202)
async def trigger_graph_build(project_id: str, verify: bool = False):
    """해당 프로젝트의 후처리(Layer 4) 빌드를 백그라운드로 시작하고 즉시 202를 반환한다.

    빌드는 길게는 수 분 걸리므로 동기로 블로킹하지 않는다. 백그라운드 태스크로 실행하고
    현재 상태(state=running/...)를 반환한다 — 진행 상황은 GET /graph/build/status로 폴링한다.
    같은 프로젝트가 이미 빌드 중이면 새로 시작하지 않고 진행 중 상태를 반환한다(coalesce).
    인가는 backend가 담당 — ai-engine은 backend가 넘긴 project_id를 신뢰하는 내부 서비스다.

    verify=false (기본): 임베딩 유사도만 (빠름, LLM 비용 없음).
    verify=true:         시맨틱 엣지(TRIGGERED_BY·DISCUSSED_IN·REFERENCE)만 clear한 뒤
                         LLM이 개입하는 빌더로 재구축한다. 엣지 타입별로 채택 방식이 다르다 —
                         TRIGGERED_BY는 추천형(후보를 넓게 잡고 LLM이 최종 선택),
                         DISCUSSED_IN·REFERENCE는 필터형(임베딩이 확정한 쌍만 LLM이 검수).
                         text 참조는 보존된다. false positive 감소, 호출당 LLM 비용. '정밀 재구축' 버튼용.
    """
    return trigger_build(project_id, verify)


@router.get("/graph/build/status")
async def graph_build_status(project_id: str):
    """프로젝트의 현재 빌드 상태를 반환한다 (POST /graph/build 후 폴링용).

    state: idle(빌드 이력 없음) | running | succeeded | failed.
    succeeded면 result에 단계별 카운트, failed면 error에 사유가 담긴다.
    """
    return get_build_status(project_id)


@router.get("/graph/activity")
async def graph_activity(project_id: str):
    """프로젝트의 그래프 활동 상태를 반환한다 (프론트 채팅 게이팅용, 읽기 전용).

    state: idle | collecting(최초 수집중) | building(수동 재구축중).
    프론트는 idle이 아니면 질문을 막는다. 판정 로직은 postprocess.get_graph_activity 참고.
    build/status와 별개 신호다 — 재구축 버튼 UI 계약은 이 라우트가 건드리지 않는다.
    """
    return {"state": get_graph_activity(project_id)}
