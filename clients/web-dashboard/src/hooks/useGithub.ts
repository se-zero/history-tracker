import { useQueries, useQuery } from "@tanstack/react-query";

import {
  listInstallationRepositories,
  listInstallations,
  listRepositoryBranches,
} from "@/api/github";
import type { GitHubInstallation, GitHubRepository } from "@/types/api";
import { queryKeys } from "./queryKeys";

export function useGithubInstallations() {
  return useQuery({
    queryKey: queryKeys.githubInstallations(),
    queryFn: listInstallations,
  });
}

export interface RepoRow {
  installation: GitHubInstallation;
  repo: GitHubRepository;
}

// installation 목록과 각 installation의 저장소를 묶어 평탄화한 행으로 돌려준다.
// SourcesPage·OnboardingPage가 똑같이 쓰던 installations + useQueries 패턴을 하나로 합쳤다.
export function useGithubRepoRows() {
  const installationsQuery = useGithubInstallations();
  const installations = installationsQuery.data ?? [];

  const repoQueries = useQueries({
    queries: installations.map((inst) => ({
      queryKey: queryKeys.githubRepositories(inst.id),
      queryFn: () => listInstallationRepositories(inst.id),
    })),
  });

  const rows: RepoRow[] = installations.flatMap((inst, idx) => {
    const repos = repoQueries[idx]?.data ?? [];
    return repos.map((repo) => ({ installation: inst, repo }));
  });

  const totalRepos = repoQueries.reduce(
    (sum, q) => sum + (q.data?.length ?? 0),
    0,
  );
  const reposLoading = repoQueries.some((q) => q.isLoading);

  return {
    installationsQuery,
    installations,
    repoQueries,
    rows,
    totalRepos,
    reposLoading,
  };
}

// 저장소 브랜치 목록. 연결 시점에 마운트되는 드롭다운이 lazy 로드한다.
export function useGithubBranches(
  installationId: string,
  owner: string,
  repo: string,
) {
  return useQuery({
    queryKey: queryKeys.githubBranches(installationId, owner, repo),
    queryFn: () => listRepositoryBranches(installationId, owner, repo),
  });
}
