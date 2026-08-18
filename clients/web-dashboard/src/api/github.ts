import { api } from "./client";
import type { GitHubInstallation, GitHubRepository } from "@/types/api";

export async function listInstallations(): Promise<GitHubInstallation[]> {
  const { data } = await api.get<GitHubInstallation[]>("/github/installations");
  return data;
}

export async function listInstallationRepositories(
  installationId: string,
): Promise<GitHubRepository[]> {
  const { data } = await api.get<GitHubRepository[]>(
    `/github/installations/${installationId}/repositories`,
  );
  return data;
}

export async function listRepositoryBranches(
  installationId: string,
  owner: string,
  repo: string,
): Promise<string[]> {
  const { data } = await api.get<string[]>(
    `/github/installations/${installationId}/repositories/${owner}/${repo}/branches`,
  );
  return data;
}
