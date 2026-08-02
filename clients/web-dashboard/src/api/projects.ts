import { api } from "./client";
import type { ConnectGitHubPayload } from "./integrations";
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

// github을 함께 넘기면 backend가 프로젝트와 GitHub 연동을 한 트랜잭션으로 만든다 —
// 둘 중 하나만 남는 상태가 없다(온보딩에서 쓴다).
export async function createProject(payload: {
  name: string;
  description?: string;
  github?: ConnectGitHubPayload;
}): Promise<Project> {
  const { data } = await api.post<Project>("/projects", {
    name: payload.name,
    description: payload.description,
    github: payload.github && {
      installation_id: payload.github.installationId,
      repository_id: payload.github.repositoryId,
      repository_full_name: payload.github.repositoryFullName,
      branch: payload.github.branch,
    },
  });
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
