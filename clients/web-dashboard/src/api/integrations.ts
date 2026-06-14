import { api } from "./client";
import type { Integration } from "@/types/api";

export interface ConnectGitHubPayload {
  installationId: string;
  repositoryId: number;
  repositoryFullName: string;
}

export interface ConnectJiraPayload {
  baseUrl: string;
  projectKey: string;
  email: string;
  apiToken: string;
}

export interface ConnectSlackPayload {
  token: string;
}

export async function listIntegrations(projectId: string): Promise<Integration[]> {
  const { data } = await api.get<Integration[]>(`/projects/${projectId}/integrations`);
  return data;
}

export async function disconnectIntegration(
  projectId: string,
  integrationId: string,
): Promise<void> {
  await api.delete(`/projects/${projectId}/integrations/${integrationId}`);
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

export async function connectSlackWorkspace(
  projectId: string,
  payload: ConnectSlackPayload,
): Promise<Integration> {
  const { data } = await api.post<Integration>(
    `/projects/${projectId}/integrations/slack`,
    { token: payload.token },
  );
  return data;
}
