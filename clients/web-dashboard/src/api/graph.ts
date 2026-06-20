import { api } from "./client";
import type { GraphBuildResult, GraphData } from "@/types/graph";

export async function getProjectGraph(projectId: string): Promise<GraphData> {
  const { data } = await api.get<GraphData>(`/projects/${projectId}/graph`);
  return data;
}

// 소스 간 시맨틱 엣지 재구축 트리거. 수집이 끝났지만 아직 연결이 안 된 그래프를
// 사용자가 직접 합치는 용도 (디바운스 자동 빌드를 기다리지 않음).
export async function rebuildProjectGraph(
  projectId: string,
): Promise<GraphBuildResult> {
  const { data } = await api.post<GraphBuildResult>(
    `/projects/${projectId}/graph/build`,
  );
  return data;
}
