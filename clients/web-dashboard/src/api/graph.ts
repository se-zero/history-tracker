import { api } from "./client";
import type { GraphBuildResult, GraphBuildStatus, GraphData } from "@/types/graph";

export async function getProjectGraph(projectId: string): Promise<GraphData> {
  const { data } = await api.get<GraphData>(`/projects/${projectId}/graph`);
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
// verify=true면 방안 D — 시맨틱 엣지를 비우고 LLM 검증으로 재구축(느림·비용).
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
