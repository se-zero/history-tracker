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
