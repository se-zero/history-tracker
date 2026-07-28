import { api } from "./client";
import type { Integration } from "@/types/api";

export interface ConnectGitHubPayload {
  installationId: string;
  repositoryId: number;
  repositoryFullName: string;
  branch: string;
}

export interface ConnectJiraPayload {
  baseUrl: string;
  projectKey: string;
  email: string;
  apiToken: string;
}

export async function listIntegrations(projectId: string): Promise<Integration[]> {
  const { data } = await api.get<Integration[]>(`/projects/${projectId}/integrations`);
  return data;
}

export async function connectGitHubRepository(
  projectId: string,
  payload: ConnectGitHubPayload,
): Promise<Integration> {
  const { data } = await api.post<Integration>(
    `/projects/${projectId}/integrations/github`,
    {
      installation_id: payload.installationId,
      repository_id: payload.repositoryId,
      repository_full_name: payload.repositoryFullName,
      branch: payload.branch,
    },
  );
  return data;
}

export async function connectJiraProject(
  projectId: string,
  payload: ConnectJiraPayload,
): Promise<Integration> {
  const { data } = await api.post<Integration>(
    `/projects/${projectId}/integrations/jira`,
    {
      base_url: payload.baseUrl,
      project_key: payload.projectKey,
      email: payload.email,
      api_token: payload.apiToken,
    },
  );
  return data;
}

// Slack 동의 화면 URL 조회. 프론트는 이 URL로 window.location.href를 대입해 이동한다 —
// <a href> 최상위 네비게이션에는 axios 인터셉터가 붙이는 JWT가 실리지 않아 이 엔드포인트는 JSON으로 받아야 한다.
export async function getSlackAuthorizeUrl(projectId: string): Promise<string> {
  const { data } = await api.get<{ authorizeUrl: string }>(
    `/projects/${projectId}/integrations/slack/authorize`,
  );
  return data.authorizeUrl;
}
