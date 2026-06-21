"""그래프 API — 개요 조회, 프로젝트 그래프 삭제, 후처리(Layer 4) 수동 트리거."""

from fastapi import APIRouter

from graph.builder import delete_project_graph
from graph.overview import get_project_overview
from graph.postprocess import run_postprocess_sequence

router = APIRouter()


@router.get("/graph/overview")
async def graph_overview(project_id: str, limit: int = 200, types: str = ""):
    """프로젝트 그래프 개요 조회 (프론트 그래프 탐색용).

    project_id로 스코프된 최근 content 노드 + 연결 Actor/File을 {nodes, edges}로 반환한다.
    인가는 backend가 담당 — ai-engine은 backend가 넘긴 project_id를 신뢰하는 내부 서비스다.

    types: 쉼표 구분 프론트 type 화이트리스트(예: "commit,pr,jira"). 생략 시 전체.
    """
    type_list = [t for t in (types.split(",") if types else []) if t.strip()] or None
    return await get_project_overview(project_id, limit, type_list)


@router.delete("/graph/projects/{project_id}")
async def delete_project_graph_endpoint(project_id: str):
    """프로젝트의 Neo4j 서브그래프 전체를 삭제한다 (Actor 포함).

    backend의 프로젝트 삭제에서 호출하는 cascade. 인가는 backend가 담당 — ai-engine은
    backend가 넘긴 project_id를 신뢰하는 내부 서비스다. 멱등 — 없는 project_id면 deleted=0.
    """
    deleted = await delete_project_graph(project_id)
    return {"deleted": deleted}


@router.post("/graph/build")
async def trigger_graph_build(verify: bool = False):
    """후처리(Layer 4) 시퀀스를 즉시 1회 실행한다.

    backfill → TRIGGERED_BY/DISCUSSED_IN → REFERENCE → 스레드 전파 순으로
    소스 간 시맨틱 엣지를 구축한다. 평소엔 수집 큐가 잠잠해지면 디바운스 루프
    (postprocess.start_debounce_loop)가 자동 호출하며, 이 엔드포인트는 디바운스를
    기다리지 않는 수동/운영 트리거다 ('그래프 재구축' 버튼의 연결점).
    모든 단계 idempotent — _build_lock으로 디바운스 루프와 직렬화된다.

    verify=false (기본): 방안 A — 임베딩 유사도만 (빠름, LLM 비용 없음).
    verify=true:         방안 D — 시맨틱 엣지 clear 후 LLM 검증으로 재구축
                         (false positive 감소, 호출당 LLM 비용). '정밀 재구축' 버튼용.
    """
    return await run_postprocess_sequence(verify=verify)
