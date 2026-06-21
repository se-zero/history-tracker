import { useState } from "react";
import axios from "axios";
import { useMutation, useQueryClient } from "@tanstack/react-query";

import { BranchSelect } from "@/components/BranchSelect";
import { Icons } from "@/components/Icons";
import { Field } from "@/components/ui/Field";
import { InlineError } from "@/components/ui/InlineError";
import { MonoChip } from "@/components/ui/MonoChip";
import { GITHUB_AUTHORIZE_URL, GITHUB_INSTALL_URL } from "@/api/auth";
import {
  connectGitHubRepository,
  connectJiraProject,
  connectSlackWorkspace,
  type ConnectJiraPayload,
  type ConnectSlackPayload,
} from "@/api/integrations";
import { queryKeys } from "@/hooks/queryKeys";
import { useGithubRepoRows } from "@/hooks/useGithub";
import {
  useDisconnectIntegration,
  useIntegrations,
} from "@/hooks/useIntegrations";
import { formatDateTime } from "@/lib/format";
import type { GitHubInstallation, GitHubRepository, Project } from "@/types/api";

// 접힌 상태에서 보여줄 리포지토리 수 — Jira/Slack 카드와 높이를 맞추기 위함
const REPO_PREVIEW_COUNT = 4;

const PROVIDER_LABELS: Record<string, string> = {
  github: "GitHub",
  jira: "Jira",
  slack: "Slack",
};

function formatSyncedAt(iso: string | null): string {
  return iso ? formatDateTime(iso) : "아직 수집 전";
}

export function SourcesPage({ project }: { project: Project }) {
  return (
    <div className="sources-page">
      <h1 className="page-title">데이터 소스</h1>
      <p className="page-sub">
        <MonoChip>{project.name}</MonoChip>{" "}
        · 코드와 의사결정의 원본이 모이는 곳. GitHub은 필수, Jira와 Slack은 선택.
      </p>

      <div className="source-grid">
        <GitHubCard projectId={project.id} />
        <JiraCard projectId={project.id} />
        <SlackCard projectId={project.id} />
      </div>

      <IngestStatus projectId={project.id} />
    </div>
  );
}

// =========================================================
// 수집 진행상황 — 연동별 마지막 수집 시각
// =========================================================

function IngestStatus({ projectId }: { projectId: string }) {
  // 백그라운드 수집이 진행되는 동안 마지막 수집 시각을 1분마다 갱신
  const integrationsQuery = useIntegrations(projectId, { refetchInterval: 60000 });
  const integrations = integrationsQuery.data ?? [];

  return (
    <div className="ingest-card">
      <div className="ingest-head">
        <Icons.Sparkle size={14} className="muted" />
        <h4>수집 진행상황</h4>
        {integrations.length > 0 && (
          <span className="muted" style={{ fontSize: 12 }}>
            · {integrations.length}개 연동
          </span>
        )}
      </div>

      {integrationsQuery.isLoading && (
        <div style={{ padding: "12px 0", color: "var(--fg-muted)", fontSize: 13 }}>
          불러오는 중…
        </div>
      )}

      {!integrationsQuery.isLoading && integrations.length === 0 && (
        <div style={{ padding: "12px 0", color: "var(--fg-muted)", fontSize: 13 }}>
          연결된 데이터 소스가 없습니다.
        </div>
      )}

      {integrations.map((integration) => (
        <div key={integration.id} className="ingest-row">
          <div className="ingest-source">
            {PROVIDER_LABELS[integration.provider] ?? integration.provider}
            {integration.displayName ? ` · ${integration.displayName}` : ""}
          </div>
          <div className="ingest-count">{formatSyncedAt(integration.lastSyncedAt)}</div>
        </div>
      ))}
    </div>
  );
}

// =========================================================
// GitHub
// =========================================================

function GitHubCard({ projectId }: { projectId: string }) {
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

// =========================================================
// Jira
// =========================================================

function JiraCard({ projectId }: { projectId: string }) {
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState<ConnectJiraPayload>({
    baseUrl: "",
    projectKey: "",
    email: "",
    apiToken: "",
  });
  const queryClient = useQueryClient();
  // 연결 상태는 로컬 state가 아니라 서버 연동 목록에서 도출 — 새로고침해도 유지된다.
  const integrationsQuery = useIntegrations(projectId);
  const jiraIntegration = integrationsQuery.data?.find((i) => i.provider === "jira");
  const connected = Boolean(jiraIntegration);
  const connectedName = jiraIntegration?.displayName ?? "Jira";

  const mutation = useMutation({
    mutationFn: () => connectJiraProject(projectId, form),
    onSuccess: () => {
      setOpen(false);
      setForm({ baseUrl: "", projectKey: "", email: "", apiToken: "" });
      queryClient.invalidateQueries({ queryKey: queryKeys.integrations(projectId) });
    },
  });

  const disconnectMutation = useDisconnectIntegration(projectId);

  const handleDisconnect = () => {
    if (!jiraIntegration) return;
    if (!window.confirm(`Jira(${connectedName}) 연동을 해제할까요?`)) return;
    disconnectMutation.mutate(jiraIntegration.id);
  };

  return (
    <div className="source-card">
      <div className="src-head">
        <div className="src-logo jira">J</div>
        <div style={{ flex: 1 }}>
          <h4>Jira</h4>
          <div className="src-sub">{connected ? connectedName : "선택 사항"}</div>
        </div>
        <span className={"badge " + (connected ? "success" : "")}>
          <span className="dot" />
          {connected ? "연결됨" : "미연결"}
        </span>
      </div>

      {!connected && !open && (
        <div
          style={{
            padding: "20px 0",
            textAlign: "center",
            color: "var(--fg-muted)",
            fontSize: 13,
          }}
        >
          Jira 티켓을 그래프에 포함하려면 연결하세요.
        </div>
      )}

      {open && (
        <div className="connect-form">
          <Field label="Base URL">
            <input
              placeholder="https://acme.atlassian.net"
              value={form.baseUrl}
              onChange={(e) => setForm({ ...form, baseUrl: e.target.value })}
            />
          </Field>
          <Field label="프로젝트 키">
            <input
              placeholder="AUTH"
              value={form.projectKey}
              onChange={(e) =>
                setForm({ ...form, projectKey: e.target.value.toUpperCase() })
              }
            />
          </Field>
          <Field label="계정 이메일">
            <input
              placeholder="you@acme.com"
              value={form.email}
              onChange={(e) => setForm({ ...form, email: e.target.value })}
            />
          </Field>
          <Field label="API Token" hint="Atlassian → 계정 설정 → API 토큰에서 발급">
            <input
              type="password"
              value={form.apiToken}
              onChange={(e) => setForm({ ...form, apiToken: e.target.value })}
            />
          </Field>
          {mutation.isError && (
            <InlineError>연결에 실패했어요. 입력 값을 확인해 주세요.</InlineError>
          )}
        </div>
      )}

      {disconnectMutation.isError && (
        <InlineError>연결 해제에 실패했어요. 잠시 후 다시 시도해 주세요.</InlineError>
      )}

      <div style={{ display: "flex", gap: 8, marginTop: "auto" }}>
        <button
          className={"btn " + (connected ? "" : "btn-primary")}
          style={{ flex: 1 }}
          onClick={() => {
            if (open) mutation.mutate();
            else setOpen(true);
          }}
          disabled={mutation.isPending}
        >
          {mutation.isPending ? "연결 중…" : open ? "연결" : connected ? "재연결" : "Jira 연결"}
        </button>
        {open ? (
          <button className="btn btn-ghost" onClick={() => setOpen(false)}>
            취소
          </button>
        ) : (
          connected && (
            <button
              className="btn btn-ghost"
              style={{ color: "var(--danger)" }}
              onClick={handleDisconnect}
              disabled={disconnectMutation.isPending}
            >
              {disconnectMutation.isPending ? "해제 중…" : "연결 해제"}
            </button>
          )
        )}
      </div>
    </div>
  );
}

// =========================================================
// Slack
// =========================================================

function SlackCard({ projectId }: { projectId: string }) {
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState<ConnectSlackPayload>({ token: "" });
  const queryClient = useQueryClient();
  // 연결 상태는 로컬 state가 아니라 서버 연동 목록에서 도출 — 새로고침해도 유지된다.
  const integrationsQuery = useIntegrations(projectId);
  const slackIntegration = integrationsQuery.data?.find((i) => i.provider === "slack");
  const connected = Boolean(slackIntegration);
  const connectedName = slackIntegration?.displayName ?? "워크스페이스";

  const mutation = useMutation({
    mutationFn: () => connectSlackWorkspace(projectId, form),
    onSuccess: () => {
      setOpen(false);
      setForm({ token: "" });
      queryClient.invalidateQueries({ queryKey: queryKeys.integrations(projectId) });
    },
  });

  const disconnectMutation = useDisconnectIntegration(projectId);

  const handleDisconnect = () => {
    if (!slackIntegration) return;
    if (!window.confirm(`Slack(${connectedName}) 연동을 해제할까요?`)) return;
    disconnectMutation.mutate(slackIntegration.id);
  };

  return (
    <div className="source-card">
      <div className="src-head">
        <div className="src-logo slack">S</div>
        <div style={{ flex: 1 }}>
          <h4>Slack</h4>
          <div className="src-sub">
            {connected ? connectedName : "선택 사항 · 토론 맥락을 추가"}
          </div>
        </div>
        <span className={"badge " + (connected ? "success" : "")}>
          <span className="dot" />
          {connected ? "연결됨" : "미연결"}
        </span>
      </div>

      {!connected && !open && (
        <div
          style={{
            padding: "20px 0",
            textAlign: "center",
            color: "var(--fg-muted)",
            fontSize: 13,
          }}
        >
          Slack을 연결하면 채널 토론도 그래프에 들어갑니다.
        </div>
      )}

      {open && (
        <div className="connect-form">
          <Field
            label="User OAuth Token (xoxp-)"
            hint={
              <>
                Slack 앱 관리 → OAuth & Permissions → <strong>User OAuth Token</strong>{" "}
                발급. Bot Token(xoxb-)은 사용할 수 없습니다.
              </>
            }
          >
            <input
              type="password"
              placeholder="xoxp-..."
              value={form.token}
              onChange={(e) => setForm({ token: e.target.value.trim() })}
            />
          </Field>
          {form.token && !form.token.startsWith("xoxp-") && (
            <div style={{ color: "var(--warning)", fontSize: 12 }}>
              User OAuth Token은 <code>xoxp-</code>로 시작해야 합니다. Bot Token
              (xoxb-) 또는 다른 형식은 허용되지 않아요.
            </div>
          )}
          {mutation.isError && (
            <InlineError>연결에 실패했어요. 토큰을 확인해 주세요.</InlineError>
          )}
        </div>
      )}

      {disconnectMutation.isError && (
        <InlineError>연결 해제에 실패했어요. 잠시 후 다시 시도해 주세요.</InlineError>
      )}

      <div style={{ display: "flex", gap: 8, marginTop: "auto" }}>
        <button
          className={"btn " + (connected ? "" : "btn-primary")}
          style={{ flex: 1 }}
          onClick={() => {
            if (open) mutation.mutate();
            else setOpen(true);
          }}
          disabled={
            mutation.isPending ||
            (open && (!form.token || !form.token.startsWith("xoxp-")))
          }
        >
          {mutation.isPending
            ? "연결 중…"
            : open
              ? "연결"
              : connected
                ? "재연결"
                : "Slack 연결"}
        </button>
        {open ? (
          <button className="btn btn-ghost" onClick={() => setOpen(false)}>
            취소
          </button>
        ) : (
          connected && (
            <button
              className="btn btn-ghost"
              style={{ color: "var(--danger)" }}
              onClick={handleDisconnect}
              disabled={disconnectMutation.isPending}
            >
              {disconnectMutation.isPending ? "해제 중…" : "연결 해제"}
            </button>
          )
        )}
      </div>
    </div>
  );
}
