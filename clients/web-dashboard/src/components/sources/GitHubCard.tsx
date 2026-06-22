import { useState } from "react";
import axios from "axios";
import { useMutation, useQueryClient } from "@tanstack/react-query";

import { BranchSelect } from "@/components/BranchSelect";
import { Icons } from "@/components/Icons";
import { InlineError } from "@/components/ui/InlineError";
import { GITHUB_AUTHORIZE_URL, GITHUB_INSTALL_URL } from "@/api/auth";
import { connectGitHubRepository } from "@/api/integrations";
import { queryKeys } from "@/hooks/queryKeys";
import { useGithubRepoRows } from "@/hooks/useGithub";
import { useDisconnectIntegration, useIntegrations } from "@/hooks/useIntegrations";
import type { GitHubInstallation, GitHubRepository } from "@/types/api";

// 접힌 상태에서 보여줄 리포지토리 수 — Jira/Slack 카드와 높이를 맞추기 위함
const REPO_PREVIEW_COUNT = 4;

export function GitHubCard({ projectId }: { projectId: string }) {
  const { installationsQuery, installations, rows: allRepoRows, totalRepos } =
    useGithubRepoRows();
  const connected = installations.length > 0;

  const [showAllRepos, setShowAllRepos] = useState(false);
  const visibleRepoRows = showAllRepos
    ? allRepoRows
    : allRepoRows.slice(0, REPO_PREVIEW_COUNT);
  const hiddenRepoCount = allRepoRows.length - visibleRepoRows.length;

  const integrationsQuery = useIntegrations(projectId);
  const githubIntegration = integrationsQuery.data?.find((i) => i.provider === "github");
  const connectedRepoId = githubIntegration?.metadata?.["repository_id"] as
    | number
    | undefined;
  const connectedBranch = githubIntegration?.metadata?.["branch"] as
    | string
    | undefined;

  // 연결하려고 선택한 저장소(브랜치 선택 단계)
  const [selectedRepoId, setSelectedRepoId] = useState<number | null>(null);
  const [branch, setBranch] = useState("");

  const queryClient = useQueryClient();
  const connectMutation = useMutation({
    mutationFn: (payload: {
      installation: GitHubInstallation;
      repo: GitHubRepository;
      branch: string;
    }) =>
      connectGitHubRepository(projectId, {
        installationId: payload.installation.id,
        repositoryId: payload.repo.id,
        repositoryFullName: payload.repo.full_name,
        branch: payload.branch,
      }),
    onSuccess: () => {
      setSelectedRepoId(null);
      queryClient.invalidateQueries({ queryKey: queryKeys.integrations(projectId) });
    },
  });

  const startBranchSelect = (repo: GitHubRepository) => {
    setSelectedRepoId(repo.id);
    setBranch(repo.default_branch);
  };

  const connectErrorMessage = connectMutation.isError
    ? axios.isAxiosError(connectMutation.error) && connectMutation.error.response?.status === 409
      ? "이미 이 프로젝트에 연결된 GitHub 저장소가 있어요. 다른 저장소로 바꾸려면 먼저 기존 연동을 해제해 주세요."
      : "연결에 실패했어요. 잠시 후 다시 시도해 주세요."
    : null;

  const disconnectMutation = useDisconnectIntegration(projectId);

  const handleDisconnect = (repoFullName: string) => {
    if (!githubIntegration) return;
    if (!window.confirm(`${repoFullName} 연동을 해제할까요?`)) return;
    disconnectMutation.mutate(githubIntegration.id);
  };

  return (
    <div className="source-card">
      <div className="src-head">
        <div className="src-logo gh">
          <Icons.GitHub size={20} />
        </div>
        <div style={{ flex: 1 }}>
          <h4>GitHub</h4>
          <div className="src-sub">
            {connected
              ? `${installations.map((i) => i.accountLogin).join(", ")} · ${totalRepos} repos`
              : "GitHub App을 설치해 시작하세요"}
          </div>
        </div>
        <span className={"badge " + (connected ? "success" : "")}>
          <span className="dot" />
          {installationsQuery.isLoading
            ? "확인 중"
            : connected
              ? "연결됨"
              : "미연결"}
        </span>
      </div>

      {installationsQuery.isLoading && (
        <div style={{ padding: "20px 0", color: "var(--fg-muted)", fontSize: 13 }}>
          GitHub installations 불러오는 중…
        </div>
      )}

      {!installationsQuery.isLoading && !connected && (
        <div
          style={{
            padding: "20px 0",
            display: "flex",
            flexDirection: "column",
            alignItems: "center",
            gap: 10,
            textAlign: "center",
            color: "var(--fg-muted)",
            fontSize: 13,
          }}
        >
          <span>이 계정으로 GitHub App이 설치된 워크스페이스가 없습니다.</span>
          <a
            className="btn btn-primary"
            href={GITHUB_INSTALL_URL}
            target="_blank"
            rel="noopener noreferrer"
          >
            GitHub App 연결하기
          </a>
          <span style={{ fontSize: 12 }}>
            설치 후 이 페이지로 돌아와{" "}
            <a href={GITHUB_AUTHORIZE_URL}>연결 확인</a>을 눌러주세요.
          </span>
        </div>
      )}

      {connected && (
        <div className="repo-list">
          {visibleRepoRows.map(({ installation: inst, repo: r }) => {
            const isConnected = connectedRepoId === r.id;
            const otherRepoConnected = connectedRepoId !== undefined && !isConnected;
            const isSelected = selectedRepoId === r.id;
            const isPending =
              connectMutation.isPending &&
              connectMutation.variables?.repo.id === r.id;
            return (
              <div key={`${inst.id}-${r.id}`} className="repo-row">
                <span className="repo-name">{r.full_name}</span>
                {isConnected ? (
                  <>
                    <span className="repo-meta">
                      {connectedBranch ? `branch: ${connectedBranch}` : r.visibility}
                    </span>
                    <span style={{ fontSize: 12, color: "var(--success)", marginLeft: 8 }}>
                      연결됨
                    </span>
                    <button
                      className="btn btn-ghost"
                      style={{ padding: "3px 8px", marginLeft: 4, color: "var(--danger)" }}
                      onClick={() => handleDisconnect(r.full_name)}
                      disabled={disconnectMutation.isPending}
                    >
                      {disconnectMutation.isPending ? "해제 중…" : "연결 해제"}
                    </button>
                  </>
                ) : isSelected ? (
                  <>
                    <BranchSelect
                      installationId={inst.id}
                      owner={r.owner}
                      repo={r.name}
                      value={branch}
                      onChange={setBranch}
                      disabled={isPending}
                    />
                    <button
                      className="btn btn-primary"
                      style={{ padding: "3px 8px", marginLeft: 8 }}
                      onClick={() =>
                        connectMutation.mutate({ installation: inst, repo: r, branch })
                      }
                      disabled={isPending || !branch}
                    >
                      {isPending ? "연결 중…" : "연결"}
                    </button>
                    <button
                      className="btn btn-ghost"
                      style={{ padding: "3px 8px", marginLeft: 4 }}
                      onClick={() => setSelectedRepoId(null)}
                      disabled={isPending}
                    >
                      취소
                    </button>
                  </>
                ) : (
                  <>
                    <span className="repo-meta">{r.visibility}</span>
                    <button
                      className="btn btn-ghost"
                      style={{ padding: "3px 8px", marginLeft: 8 }}
                      onClick={() => startBranchSelect(r)}
                      disabled={otherRepoConnected}
                    >
                      {otherRepoConnected ? "다른 저장소 연결됨" : "이 프로젝트에 연결"}
                    </button>
                  </>
                )}
              </div>
            );
          })}
          {allRepoRows.length > REPO_PREVIEW_COUNT && (
            <button
              className="repo-toggle"
              onClick={() => setShowAllRepos((prev) => !prev)}
            >
              {showAllRepos ? "접기" : `${hiddenRepoCount}개 더 보기`}
              <Icons.ChevronDown
                size={12}
                style={{
                  transform: showAllRepos ? "rotate(180deg)" : undefined,
                }}
              />
            </button>
          )}
          {connectErrorMessage && (
            <InlineError style={{ padding: "8px 12px" }}>
              {connectErrorMessage}
            </InlineError>
          )}
          {disconnectMutation.isError && (
            <InlineError style={{ padding: "8px 12px" }}>
              연결 해제에 실패했어요. 잠시 후 다시 시도해 주세요.
            </InlineError>
          )}
        </div>
      )}

      <div style={{ display: "flex", gap: 8, marginTop: "auto" }}>
        <a
          className="btn btn-ghost"
          href={GITHUB_INSTALL_URL}
          target="_blank"
          rel="noopener noreferrer"
        >
          앱 관리
        </a>
        <a className="btn btn-ghost" href={GITHUB_AUTHORIZE_URL}>
          연결 확인
        </a>
      </div>
    </div>
  );
}
