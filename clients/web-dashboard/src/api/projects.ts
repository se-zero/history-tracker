import { api } from "./client";
import type { Project } from "@/types/api";

export async function listProjects(): Promise<Project[]> {
  const { data } = await api.get<Project[]>("/projects");
  return data;
}

export async function getProject(projectId: string): Promise<Project> {
  const { data } = await api.get<Project>(`/projects/${projectId}`);
  return data;
}

// 드래그로 바뀐 프로젝트 순서를 저장한다. 재정렬된 전체 목록을 돌려받는다.
export async function reorderProjects(orderedIds: string[]): Promise<Project[]> {
  const { data } = await api.patch<Project[]>("/projects/order", { orderedIds });
  return data;
}

export async function createProject(payload: {
  name: string;
  description?: string;
}): Promise<Project> {
  const { data } = await api.post<Project>("/projects", payload);
  return data;
}

export async function updateProject(
  projectId: string,
  payload: { name: string; description?: string },
): Promise<Project> {
  const { data } = await api.put<Project>(`/projects/${projectId}`, payload);
  return data;
}

export async function deleteProject(projectId: string): Promise<void> {
  await api.delete(`/projects/${projectId}`);
}
