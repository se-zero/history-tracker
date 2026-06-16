import { api } from "./client";
import type { GraphData } from "@/types/graph";

export async function getProjectGraph(projectId: string): Promise<GraphData> {
  const { data } = await api.get<GraphData>(`/projects/${projectId}/graph`);
  return data;
}
