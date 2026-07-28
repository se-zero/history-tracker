import { api } from "./client";
import type {
  ConstellationData,
  GraphActivity,
  GraphBuildResult,
  GraphBuildStatus,
  GraphData,
  GraphNode,
  SubgraphData,
} from "@/types/graph";

export async function getProjectGraph(projectId: string): Promise<GraphData> {
  const { data } = await api.get<GraphData>(`/projects/${projectId}/graph`);
  return data;
}

// 성좌 뷰용 조회 — overview와 달리 작업 단위(PR, 없으면 Issue)를 전량 담고 위성만 최신 limit개다.
// 별성이 빠지면 그림 자체가 틀리기 때문에 완전성이 필요한 쪽만 따로 가져온다.
interface ConstellationResponse {
  nodes: GraphData["nodes"];
  edges: GraphData["edges"];
  work_unit_ids: string[];
}

export async function getProjectConstellation(
  projectId: string,
  limit?: number,
): Promise<ConstellationData> {
  const { data } = await api.get<ConstellationResponse>(
    `/projects/${projectId}/graph/constellation`,
    { params: limit ? { limit } : undefined },
  );
  return {
    nodes: data.nodes ?? [],
    edges: data.edges ?? [],
    workUnitIds: data.work_unit_ids ?? [],
  };
}

// 성좌 드릴인 — 작업 단위 하나의 이웃만 가져온다.
// 성좌 뷰는 위성을 최신 N개로 자르므로 오래된 작업은 별성만 있고 위성이 비어 있는데,
// 그 성좌를 열 때 이걸로 채운다.
export async function getWorkUnitNeighborhood(
  projectId: string,
  nodeId: string,
): Promise<GraphData> {
  const { data } = await api.get<GraphData>(
    `/projects/${projectId}/graph/work-unit`,
    { params: { nodeId } },
  );
  return { nodes: data.nodes ?? [], edges: data.edges ?? [] };
}

// 통합 검색 — full-text 인덱스로 프로젝트 그래프 전체에서 노드 검색.
// overview가 최근 활동 top-N만 내리는 것과 달리 전체를 대상으로 하므로,
// 반환된 노드가 그래프 화면에 로드돼 있지 않을 수 있다.
export async function searchGraphNodes(
  projectId: string,
  q: string,
  limit?: number,
): Promise<GraphNode[]> {
  const { data } = await api.get<{ nodes: GraphNode[] }>(
    `/projects/${projectId}/graph/search`,
    { params: limit ? { q, limit } : { q } },
  );
  return data.nodes;
}

// 답변 evidence(도메인 키)로 관련 서브그래프를 조회한다 (대화 화면 그래프 패널용).
// 응답 nodes/edges/seeds는 이미 camelCase 형태라 별도 매핑이 필요 없다.
export async function getMessageSubgraph(
  projectId: string,
  evidence: Array<{ type: string; id: string }>,
): Promise<SubgraphData> {
  const { data } = await api.post<SubgraphData>(
    `/projects/${projectId}/graph/subgraph`,
    { evidence },
  );
  return data;
}

// backend는 snake_case로 응답한다. 컴포넌트는 camelCase만 보도록 여기서 매핑한다.
interface GraphBuildStatusResponse {
  state: GraphBuildStatus["state"];
  verify: boolean | null;
  started_at: string | null;
  result: GraphBuildResult | null;
  error: string | null;
}

function toBuildStatus(raw: GraphBuildStatusResponse): GraphBuildStatus {
  return {
    state: raw.state,
    verify: raw.verify,
    startedAt: raw.started_at,
    result: raw.result,
    error: raw.error,
  };
}

// 소스 간 시맨틱 엣지 재구축을 프로젝트 단위로 트리거한다 (디바운스 자동 빌드를 기다리지 않음).
// 빌드는 백그라운드(202)라 즉시 현재 상태(보통 running)를 반환하고, 완료는 getGraphBuildStatus 폴링으로 확인한다.
// verify=true면 수동 정밀 구축 — 시맨틱 엣지를 비우고 LLM 검수로 재구축(느림·비용).
export async function rebuildProjectGraph(
  projectId: string,
  verify = false,
): Promise<GraphBuildStatus> {
  const { data } = await api.post<GraphBuildStatusResponse>(
    `/projects/${projectId}/graph/build`,
    null,
    { params: { verify } },
  );
  return toBuildStatus(data);
}

// 프로젝트의 현재 빌드 상태 조회 (트리거 후 완료까지 폴링).
export async function getGraphBuildStatus(
  projectId: string,
): Promise<GraphBuildStatus> {
  const { data } = await api.get<GraphBuildStatusResponse>(
    `/projects/${projectId}/graph/build/status`,
  );
  return toBuildStatus(data);
}

// 프로젝트의 그래프 활동 상태 조회 (프론트 채팅 게이팅용). state는 이미 camelCase라 매핑 불필요.
export async function getGraphActivity(
  projectId: string,
): Promise<GraphActivity> {
  const { data } = await api.get<GraphActivity>(
    `/projects/${projectId}/graph/activity`,
  );
  return data;
}
