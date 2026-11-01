import { GithubMark } from "@/components/brand/BrandMarks";
import { DisconnectIntegration } from "@/components/sources/DisconnectIntegration";
import { GitHubInstallEmptyState } from "@/components/sources/GitHubInstallEmptyState";
import { GitHubRepoPicker } from "@/components/sources/GitHubRepoPicker";
import { useGithubInstallations } from "@/hooks/useGithub";
import { useConnectGitHubRepository, useIntegrations } from "@/hooks/useIntegrations";
import { uiText } from "@/i18n/language";
import { formatTimestamp } from "@/lib/format";

// GitHub는 항상 "연동 중" 행 최상단에 고정된다(미설치 상태에도 행 안에서 설치 플로우를 안내한다).
// 이 프로젝트에 저장소가 없으면(연동 해제 직후 포함) 온보딩과 같은 선택 화면을 카드 아래에 띄워 이
// 프로젝트에 바로 연결한다 — 온보딩은 프로젝트를 새로 만들 때만 거치므로, 해제한 프로젝트가 GitHub를
// 다시 붙일 곳은 이 카드뿐이다.
export function GitHubCard({ projectId }: { projectId: string }) {
  const installationsQuery = useGithubInstallations();
  const installations = installationsQuery.data ?? [];
  const connected = installations.length > 0;

  const integrationsQuery = useIntegrations(projectId);
  const githubIntegration = integrationsQuery.data?.find((i) => i.provider === "github");
  const repoConnected = Boolean(githubIntegration);

  const connectMutation = useConnectGitHubRepository(projectId);

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

      {/* 연동 목록이 오기 전에는 저장소가 이미 연결됐는지 알 수 없다 — 그때 띄우면 연결된 프로젝트에서
          선택 화면이 잠깐 보이고, 그 사이 "연결"을 누르면 409가 난다. 한 번 받은 뒤에는 60초 폴링이 실패해도
          (isSuccess가 false가 돼도) 받아 둔 목록으로 판단한다 — 고르던 저장소·브랜치가 초기화되지 않게.
          설치 로딩·설치 없음도 picker가 그린다. 위쪽 여백은 이 div가 모든 단계에 한 번만 준다 — 빈 상태에
          따로 주면 겹쳐 32px가 된다. */}
      {integrationsQuery.data !== undefined && !repoConnected && (
        <div style={{ padding: "16px 0 0" }}>
          <GitHubRepoPicker
            onConnect={({ installation, repo, branch }) =>
              connectMutation.mutate({
                installationId: installation.id,
                repositoryId: repo.id,
                repositoryFullName: repo.full_name,
                branch,
              })
            }
            connectingRepoId={
              connectMutation.isPending ? (connectMutation.variables?.repositoryId ?? null) : null
            }
            connectError={connectMutation.error}
            fallbackErrorMessage={uiText(
              "저장소를 연결하지 못했어요. 잠시 후 다시 시도해 주세요.",
              "Couldn't connect the repository. Try again in a moment.",
            )}
            triggerClassName="btn btn-ghost"
          />
        </div>
      )}

      {/* 저장소는 연결돼 있는데 이 계정에 보이는 설치가 없으면(조직 접근을 잃었거나 설치 목록 조회 실패)
          다시 설치하거나 "연결 확인"으로 목록을 새로 받는 길을 남긴다. */}
      {repoConnected && !installationsQuery.isLoading && !connected && (
        <GitHubInstallEmptyState style={{ padding: "16px 0 0" }} />
      )}
    </div>
  );
}
