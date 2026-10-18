import { GithubMark } from "@/components/brand/BrandMarks";
import { DisconnectIntegration } from "@/components/sources/DisconnectIntegration";
import { GitHubInstallEmptyState } from "@/components/sources/GitHubInstallEmptyState";
import { useGithubInstallations } from "@/hooks/useGithub";
import { useIntegrations } from "@/hooks/useIntegrations";
import { uiText } from "@/i18n/language";
import { formatTimestamp } from "@/lib/format";

// GitHub는 항상 "연동 중" 행 최상단에 고정된다(미설치 상태에도 행 안에서 설치 플로우를 안내한다).
// 저장소 선택 UI는 이 카드가 아니라 온보딩에서 담당한다 — 여기는 표시 전용.
export function GitHubCard({ projectId }: { projectId: string }) {
  const installationsQuery = useGithubInstallations();
  const installations = installationsQuery.data ?? [];
  const connected = installations.length > 0;

  const integrationsQuery = useIntegrations(projectId);
  const githubIntegration = integrationsQuery.data?.find((i) => i.provider === "github");
  const repoConnected = Boolean(githubIntegration);

  const repoFullName = githubIntegration?.metadata?.["repository_full_name"] as
    | string
    | undefined;
  const branch = githubIntegration?.metadata?.["branch"] as string | undefined;
  const repoLabel = repoFullName
    ? `${repoFullName}:${branch}`
    : githubIntegration?.displayName ?? "";

  const lastSyncedAt = githubIntegration?.lastSyncedAt
    ? formatTimestamp(githubIntegration.lastSyncedAt)
    : null;

  return (
    <div className="source-row">
      <div className="source-row-top">
        <div className="source-logo-chip">
          <GithubMark size={20} />
        </div>
        <div className="source-row-main">
          <div className="source-row-name">GitHub</div>
          <div className="source-row-sub">
            {!connected ? (
              uiText("GitHub App을 설치해 시작하세요", "Install the GitHub App to start")
            ) : repoConnected ? (
              <span className="mono">{repoLabel}</span>
            ) : (
              uiText("연결된 저장소가 없습니다", "No repository connected")
            )}
          </div>
        </div>
        <div className="source-row-status">
          {/* 배지는 App 설치가 아니라 "이 프로젝트에 저장소가 연결됐는가"를 말한다 —
              설치만 되고 repo가 없으면 서브라인(연결된 저장소가 없습니다)과 함께 미연결. */}
          <span className={"src-pill " + (connected && repoConnected ? "connected" : "disconnected")}>
            <span className="dot" />
            {installationsQuery.isLoading
              ? uiText("확인 중", "Checking")
              : connected && repoConnected
                ? uiText("연결됨", "Connected")
                : uiText("미연결", "Not connected")}
          </span>
          {lastSyncedAt && (
            <div className="source-row-synced">
              {uiText("마지막 수집", "Last sync")} <span className="mono">{lastSyncedAt}</span>
            </div>
          )}
        </div>
        {/* 저장소가 연결된 경우에만 해제할 게 있다. App 설치 자체는 계정 단위(다른 프로젝트도
            쓴다)라 여기서 건드리지 않는다 — 제거는 GitHub 설정에서 한다. */}
        {repoConnected && (
          <DisconnectIntegration projectId={projectId} provider="github" />
        )}
      </div>

      {installationsQuery.isLoading && (
        <div style={{ padding: "16px 0 0", color: "var(--fg-muted)", fontSize: 13 }}>
          {uiText("GitHub installations 불러오는 중…", "Loading GitHub installations…")}
        </div>
      )}

      {!installationsQuery.isLoading && !connected && (
        <GitHubInstallEmptyState style={{ padding: "16px 0 0" }} />
      )}
    </div>
  );
}
