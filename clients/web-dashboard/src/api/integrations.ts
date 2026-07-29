import { api } from "./client";
import type { Integration, JiraProject, JiraSite } from "@/types/api";

export interface ConnectGitHubPayload {
  installationId: string;
  repositoryId: number;
  repositoryFullName: string;
  branch: string;
}

export interface CompleteJiraProjectPayload {
  cloudId: string;
  siteName: string;
  projectKey: string;
  projectName: string;
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

// Slack 동의 화면 URL 조회. 프론트는 이 URL로 window.location.href를 대입해 이동한다 —
// <a href> 최상위 네비게이션에는 axios 인터셉터가 붙이는 JWT가 실리지 않아 이 엔드포인트는 JSON으로 받아야 한다.
export async function getSlackAuthorizeUrl(projectId: string): Promise<string> {
  const { data } = await api.get<{ authorizeUrl: string }>(
    `/projects/${projectId}/integrations/slack/authorize`,
  );
  return data.authorizeUrl;
}

// Jira 동의 화면 URL 조회 — Slack과 동일한 이유로 JSON 응답을 받아 window.location으로 이동한다.
export async function getJiraAuthorizeUrl(projectId: string): Promise<string> {
  const { data } = await api.get<{ authorizeUrl: string }>(
    `/projects/${projectId}/integrations/jira/authorize`,
  );
  return data.authorizeUrl;
}

export async function listJiraSites(projectId: string): Promise<JiraSite[]> {
  const { data } = await api.get<JiraSite[]>(
    `/projects/${projectId}/integrations/jira/sites`,
  );
  return data;
}

export async function listJiraProjects(
  projectId: string,
  cloudId: string,
): Promise<JiraProject[]> {
  const { data } = await api.get<JiraProject[]>(
    `/projects/${projectId}/integrations/jira/projects`,
    { params: { cloudId } },
  );
  return data;
}

export async function completeJiraProject(
  projectId: string,
  payload: CompleteJiraProjectPayload,
): Promise<Integration> {
  const { data } = await api.post<Integration>(
    `/projects/${projectId}/integrations/jira/project`,
    {
      cloud_id: payload.cloudId,
      site_name: payload.siteName,
      project_key: payload.projectKey,
      project_name: payload.projectName,
    },
  );
  return data;
}
