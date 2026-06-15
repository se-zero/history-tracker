import { useState } from "react";
import axios from "axios";
import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";

import { Icons } from "@/components/Icons";
import { GITHUB_AUTHORIZE_URL, GITHUB_INSTALL_URL } from "@/api/auth";
import { listInstallationRepositories, listInstallations } from "@/api/github";
import {
  connectGitHubRepository,
  connectJiraProject,
  connectSlackWorkspace,
  disconnectIntegration,
  listIntegrations,
  type ConnectJiraPayload,
  type ConnectSlackPayload,
} from "@/api/integrations";
import type { GitHubInstallation, GitHubRepository, Project } from "@/types/api";

// 접힌 상태에서 보여줄 리포지토리 수 — Jira/Slack 카드와 높이를 맞추기 위함
const REPO_PREVIEW_COUNT = 4;

const PROVIDER_LABELS: Record<string, string> = {
  github: "GitHub",
  jira: "Jira",
  slack: "Slack",
};

function formatSyncedAt(iso: string | null): string {
  if (!iso) return "아직 수집 전";
  try {
    return new Date(iso).toLocaleString("ko-KR");
  } catch {
    return iso;
  }
}

export function SourcesPage({ project }: { project: Project }) {
  return (
    <div className="sources-page">
      <h1 className="page-title">데이터 소스</h1>
      <p className="page-sub">
        <span
          className="mono"
          style={{
            background: "var(--surface-2)",
            padding: "1px 6px",
            borderRadius: 4,
            fontSize: 12,
          }}
        >
          {project.name}
        </span>{" "}
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
  const integrationsQuery = useQuery({
    queryKey: ["integrations", projectId],
    queryFn: () => listIntegrations(projectId),
  });
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
  const installationsQuery = useQuery({
    queryKey: ["github", "installations"],
    queryFn: listInstallations,
  });
  const installations = installationsQuery.data ?? [];

  const repoQueries = useQueries({
    queries: installations.map((inst) => ({
      queryKey: ["github", "installations", inst.id, "repositories"],
      queryFn: () => listInstallationRepositories(inst.id),
    })),
  });

  const connected = installations.length > 0;
  const totalRepos = repoQueries.reduce((sum, q) => sum + (q.data?.length ?? 0), 0);

  const allRepoRows = installations.flatMap((inst, idx) => {
    const repos = repoQueries[idx]?.data ?? [];
    return repos.map((repo) => ({ installation: inst, repo }));
  });
  const [showAllRepos, setShowAllRepos] = useState(false);
  const visibleRepoRows = showAllRepos
    ? allRepoRows
    : allRepoRows.slice(0, REPO_PREVIEW_COUNT);
  const hiddenRepoCount = allRepoRows.length - visibleRepoRows.length;

  const integrationsQuery = useQuery({
    queryKey: ["integrations", projectId],
    queryFn: () => listIntegrations(projectId),
  });
  const githubIntegration = integrationsQuery.data?.find((i) => i.provider === "github");
  const connectedRepoId = githubIntegration?.metadata?.["repository_id"] as
    | number
    | undefined;

  const queryClient = useQueryClient();
  const connectMutation = useMutation({
    mutationFn: (payload: {
      installation: GitHubInstallation;
      repo: GitHubRepository;
    }) =>
      connectGitHubRepository(projectId, {
        installationId: payload.installation.id,
        repositoryId: payload.repo.id,
        repositoryFullName: payload.repo.full_name,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["integrations", projectId] });
    },
  });

  const connectErrorMessage = connectMutation.isError
    ? axios.isAxiosError(connectMutation.error) && connectMutation.error.response?.status === 409
      ? "이미 이 프로젝트에 연결된 GitHub 저장소가 있어요. 다른 저장소로 바꾸려면 먼저 기존 연동을 해제해 주세요."
      : "연결에 실패했어요. 잠시 후 다시 시도해 주세요."
    : null;

  const disconnectMutation = useMutation({
    mutationFn: (integrationId: string) => disconnectIntegration(projectId, integrationId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["integrations", projectId] });
    },
  });

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
            const isPending =
              connectMutation.isPending &&
              connectMutation.variables?.repo.id === r.id;
            return (
              <div key={`${inst.id}-${r.id}`} className="repo-row">
                <span className="repo-name">{r.full_name}</span>
                <span className="repo-meta">{r.visibility} · — events</span>
                <button
                  className="btn btn-ghost"
                  style={{ padding: "3px 8px", marginLeft: 8 }}
                  onClick={() =>
                    connectMutation.mutate({ installation: inst, repo: r })
                  }
                  disabled={isPending || isConnected || otherRepoConnected}
                >
                  {isConnected
                    ? "연결됨"
                    : isPending
                      ? "연결 중…"
                      : otherRepoConnected
                        ? "다른 저장소 연결됨"
                        : "이 프로젝트에 연결"}
                </button>
                {isConnected && (
                  <button
                    className="btn btn-ghost"
                    style={{ padding: "3px 8px", marginLeft: 4, color: "var(--danger)" }}
                    onClick={() => handleDisconnect(r.full_name)}
                    disabled={disconnectMutation.isPending}
                  >
                    {disconnectMutation.isPending ? "해제 중…" : "연결 해제"}
                  </button>
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
            <div style={{ color: "var(--danger)", fontSize: 12, padding: "8px 12px" }}>
              {connectErrorMessage}
            </div>
          )}
          {disconnectMutation.isError && (
            <div style={{ color: "var(--danger)", fontSize: 12, padding: "8px 12px" }}>
              연결 해제에 실패했어요. 잠시 후 다시 시도해 주세요.
            </div>
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
  const [connected, setConnected] = useState<string | null>(null);

  const queryClient = useQueryClient();
  const mutation = useMutation({
    mutationFn: () => connectJiraProject(projectId, form),
    onSuccess: (integration) => {
      setConnected(integration.displayName ?? form.projectKey);
      setOpen(false);
      setForm({ baseUrl: "", projectKey: "", email: "", apiToken: "" });
      queryClient.invalidateQueries({ queryKey: ["integrations", projectId] });
    },
  });

  return (
    <div className="source-card">
      <div className="src-head">
        <div className="src-logo jira">J</div>
        <div style={{ flex: 1 }}>
          <h4>Jira</h4>
          <div className="src-sub">{connected ?? "선택 사항"}</div>
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
          <div className="field">
            <label>Base URL</label>
            <input
              placeholder="https://acme.atlassian.net"
              value={form.baseUrl}
              onChange={(e) => setForm({ ...form, baseUrl: e.target.value })}
            />
          </div>
          <div className="field">
            <label>프로젝트 키</label>
            <input
              placeholder="AUTH"
              value={form.projectKey}
              onChange={(e) =>
                setForm({ ...form, projectKey: e.target.value.toUpperCase() })
              }
            />
          </div>
          <div className="field">
            <label>계정 이메일</label>
            <input
              placeholder="you@acme.com"
              value={form.email}
              onChange={(e) => setForm({ ...form, email: e.target.value })}
            />
          </div>
          <div className="field">
            <label>API Token</label>
            <input
              type="password"
              value={form.apiToken}
              onChange={(e) => setForm({ ...form, apiToken: e.target.value })}
            />
            <span className="hint">Atlassian → 계정 설정 → API 토큰에서 발급</span>
          </div>
          {mutation.isError && (
            <div style={{ color: "var(--danger)", fontSize: 12 }}>
              연결에 실패했어요. 입력 값을 확인해 주세요.
            </div>
          )}
        </div>
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
        {open && (
          <button className="btn btn-ghost" onClick={() => setOpen(false)}>
            취소
          </button>
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
  const [connected, setConnected] = useState<string | null>(null);

  const queryClient = useQueryClient();
  const mutation = useMutation({
    mutationFn: () => connectSlackWorkspace(projectId, form),
    onSuccess: (integration) => {
      setConnected(integration.displayName ?? "워크스페이스");
      setOpen(false);
      setForm({ token: "" });
      queryClient.invalidateQueries({ queryKey: ["integrations", projectId] });
    },
  });

  return (
    <div className="source-card">
      <div className="src-head">
        <div className="src-logo slack">S</div>
        <div style={{ flex: 1 }}>
          <h4>Slack</h4>
          <div className="src-sub">{connected ?? "선택 사항 · 토론 맥락을 추가"}</div>
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
          <div className="field">
            <label>User OAuth Token (xoxp-)</label>
            <input
              type="password"
              placeholder="xoxp-..."
              value={form.token}
              onChange={(e) => setForm({ token: e.target.value.trim() })}
            />
            <span className="hint">
              Slack 앱 관리 → OAuth & Permissions → <strong>User OAuth Token</strong>{" "}
              발급. Bot Token(xoxb-)은 사용할 수 없습니다.
            </span>
          </div>
          {form.token && !form.token.startsWith("xoxp-") && (
            <div style={{ color: "var(--warning)", fontSize: 12 }}>
              User OAuth Token은 <code>xoxp-</code>로 시작해야 합니다. Bot Token
              (xoxb-) 또는 다른 형식은 허용되지 않아요.
            </div>
          )}
          {mutation.isError && (
            <div style={{ color: "var(--danger)", fontSize: 12 }}>
              연결에 실패했어요. 토큰을 확인해 주세요.
            </div>
          )}
        </div>
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
        {open && (
          <button className="btn btn-ghost" onClick={() => setOpen(false)}>
            취소
          </button>
        )}
      </div>
    </div>
  );
}
