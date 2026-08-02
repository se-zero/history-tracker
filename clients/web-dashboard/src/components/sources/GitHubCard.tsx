import { GithubMark } from "@/components/brand/BrandMarks";
import { GITHUB_AUTHORIZE_URL, GITHUB_INSTALL_URL } from "@/api/auth";
import { useGithubInstallations } from "@/hooks/useGithub";
import { useIntegrations } from "@/hooks/useIntegrations";
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
              "GitHub App을 설치해 시작하세요"
            ) : repoConnected ? (
              <span className="mono">{repoLabel}</span>
            ) : (
              "연결된 저장소가 없습니다"
            )}
          </div>
        </div>
        <div className="source-row-status">
          {/* 배지는 App 설치가 아니라 "이 프로젝트에 저장소가 연결됐는가"를 말한다 —
              설치만 되고 repo가 없으면 서브라인(연결된 저장소가 없습니다)과 함께 미연결. */}
          <span className={"src-pill " + (connected && repoConnected ? "connected" : "disconnected")}>
            <span className="dot" />
            {installationsQuery.isLoading ? "확인 중" : connected && repoConnected ? "연결됨" : "미연결"}
          </span>
          {lastSyncedAt && (
            <div className="source-row-synced">
              마지막 수집 <span className="mono">{lastSyncedAt}</span>
            </div>
          )}
        </div>
      </div>

      {installationsQuery.isLoading && (
        <div style={{ padding: "16px 0 0", color: "var(--fg-muted)", fontSize: 13 }}>
          GitHub installations 불러오는 중…
        </div>
      )}

      {!installationsQuery.isLoading && !connected && (
        <div
          style={{
            padding: "16px 0 0",
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
    </div>
  );
}
