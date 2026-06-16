import { useState } from "react";
import {
  useMutation,
  useQueries,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";

import { BranchSelect } from "@/components/BranchSelect";
import { Icons } from "@/components/Icons";
import { createProject } from "@/api/projects";
import { listInstallationRepositories, listInstallations } from "@/api/github";
import { connectGitHubRepository } from "@/api/integrations";
import { GITHUB_AUTHORIZE_URL, GITHUB_INSTALL_URL } from "@/api/auth";
import { Topbar } from "@/components/shell/Topbar";
import type { GitHubInstallation, GitHubRepository, Project } from "@/types/api";

const NAME_CHIP: React.CSSProperties = {
  background: "var(--surface-2)",
  padding: "1px 6px",
  borderRadius: 4,
  fontSize: 12,
};

export function OnboardingPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  // 생성된 프로젝트가 있으면 STEP 02(GitHub 연결)로 전환한다.
  const [createdProject, setCreatedProject] = useState<Project | null>(null);

  const createMutation = useMutation({
    mutationFn: () => createProject({ name, description: description || undefined }),
    onSuccess: (project) => {
      // 새 프로젝트를 캐시에 즉시 반영한다. 이후 chat 이동 시 AppShell이 stale한 빈 목록을
      // 보고 /onboarding으로 되튕기는 것을 막는다.
      queryClient.setQueryData<Project[]>(["projects"], (prev) =>
        prev ? [project, ...prev] : [project],
      );
      queryClient.invalidateQueries({ queryKey: ["projects"] });
      setCreatedProject(project);
    },
  });

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim()) return;
    createMutation.mutate();
  };

  const goToProject = () => {
    if (createdProject) {
      navigate(`/projects/${createdProject.id}/chat`, { replace: true });
    }
  };

  return (
    <div className="app no-sidebar">
      <div className="main">
        <Topbar crumbs={["History Tracker", "시작하기"]} />
        <div className="onboarding">
          {createdProject ? (
            <ConnectGitHubStep project={createdProject} onDone={goToProject} />
          ) : (
            <CreateProjectStep
              name={name}
              description={description}
              onName={setName}
              onDescription={setDescription}
              onSubmit={submit}
              pending={createMutation.isPending}
              error={createMutation.isError}
            />
          )}
        </div>
      </div>
    </div>
  );
}

// =========================================================
// STEP 01 — 프로젝트 생성
// =========================================================

function CreateProjectStep({
  name,
  description,
  onName,
  onDescription,
  onSubmit,
  pending,
  error,
}: {
  name: string;
  description: string;
  onName: (v: string) => void;
  onDescription: (v: string) => void;
  onSubmit: (e: React.FormEvent) => void;
  pending: boolean;
  error: boolean;
}) {
  return (
    <>
      <StepIndicator step={1} />
      <h1>첫 프로젝트를 만들어 보세요</h1>
      <p className="lead">
        분석할 단위(서비스 또는 팀)를 만들면, 연동된 소스들로부터 자동으로 그래프가
        만들어집니다.
      </p>
      <div className="step-grid">
        <StepCard
          num="STEP 01"
          title="프로젝트 생성"
          body={`예: "Payments Platform", "Auth Team" — 하나의 컨텍스트 경계를 정합니다.`}
          icon={<Icons.Layers size={22} />}
        />
        <StepCard
          num="STEP 02"
          title="GitHub 연결"
          body="분석할 저장소를 선택합니다. 권한이 있는 워크스페이스만 보입니다."
          icon={<Icons.Plug size={22} />}
        />
        <StepCard
          num="STEP 03"
          title="질문하기"
          body={`"왜 이렇게 짰지?"를 자연어로 던지면, 출처와 함께 맥락을 돌려드립니다.`}
          icon={<Icons.Sparkle size={22} />}
        />
      </div>

      <form
        onSubmit={onSubmit}
        style={{
          display: "flex",
          flexDirection: "column",
          gap: 12,
          maxWidth: 480,
          margin: "32px auto 0",
        }}
      >
        <div className="field">
          <label>프로젝트 이름</label>
          <input
            autoFocus
            value={name}
            onChange={(e) => onName(e.target.value)}
            placeholder="Payments Platform"
          />
        </div>
        <div className="field">
          <label>설명 (선택)</label>
          <input
            value={description}
            onChange={(e) => onDescription(e.target.value)}
            placeholder="결제 도메인 전반(주문, 정산, 환불)"
          />
        </div>
        {error && (
          <div style={{ color: "var(--danger)", fontSize: 12 }}>
            프로젝트를 만들지 못했어요. 다시 시도해 주세요.
          </div>
        )}
        <button
          type="submit"
          className="btn btn-primary btn-lg"
          disabled={!name.trim() || pending}
        >
          <Icons.Plus size={14} />{" "}
          {pending ? "만드는 중…" : "다음: GitHub 연결"}
        </button>
      </form>
    </>
  );
}

// =========================================================
// STEP 02 — GitHub 저장소 연결
// =========================================================

function ConnectGitHubStep({
  project,
  onDone,
}: {
  project: Project;
  onDone: () => void;
}) {
  const queryClient = useQueryClient();

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

  const repoRows = installations.flatMap((inst, idx) => {
    const repos = repoQueries[idx]?.data ?? [];
    return repos.map((repo) => ({ installation: inst, repo }));
  });

  // 연결하려고 선택한 저장소(브랜치 선택 단계)
  const [selectedRepoId, setSelectedRepoId] = useState<number | null>(null);
  const [branch, setBranch] = useState("");

  const connectMutation = useMutation({
    mutationFn: (payload: {
      installation: GitHubInstallation;
      repo: GitHubRepository;
      branch: string;
    }) =>
      connectGitHubRepository(project.id, {
        installationId: payload.installation.id,
        repositoryId: payload.repo.id,
        repositoryFullName: payload.repo.full_name,
        branch: payload.branch,
      }),
    onSuccess: () => {
      // 연결 시 초기 수집이 트리거된다. 연동 캐시를 비우고 프로젝트로 이동.
      queryClient.invalidateQueries({ queryKey: ["integrations", project.id] });
      onDone();
    },
  });

  const startBranchSelect = (repo: GitHubRepository) => {
    setSelectedRepoId(repo.id);
    setBranch(repo.default_branch);
  };

  const connected = installations.length > 0;
  const reposLoading = repoQueries.some((q) => q.isLoading);

  return (
    <>
      <StepIndicator step={2} />
      <h1>GitHub 저장소 연결</h1>
      <p className="lead">
        <span className="mono" style={NAME_CHIP}>
          {project.name}
        </span>{" "}
        · 분석할 저장소를 선택하세요. GitHub은 필수예요.
      </p>

      <div style={{ maxWidth: 560, margin: "0 auto", width: "100%" }}>
        {installationsQuery.isLoading ? (
          <div className="onb-muted-block">GitHub 워크스페이스를 불러오는 중…</div>
        ) : !connected ? (
          <div className="onboarding-gh-empty">
            <span>이 계정으로 GitHub App이 설치된 워크스페이스가 없어요.</span>
            <a
              className="btn btn-primary"
              href={GITHUB_INSTALL_URL}
              target="_blank"
              rel="noopener noreferrer"
            >
              GitHub App 설치하기
            </a>
            <span style={{ fontSize: 12 }}>
              설치 후 <a href={GITHUB_AUTHORIZE_URL}>연결 확인</a>을 눌러주세요.
            </span>
          </div>
        ) : reposLoading ? (
          <div className="onb-muted-block">저장소 목록을 불러오는 중…</div>
        ) : repoRows.length === 0 ? (
          <div className="onb-muted-block">
            접근 가능한 저장소가 없어요. GitHub App 설정에서 저장소 권한을 확인해 주세요.
          </div>
        ) : (
          <div className="repo-list" style={{ maxHeight: 320, overflowY: "auto" }}>
            {repoRows.map(({ installation, repo }) => {
              const isSelected = selectedRepoId === repo.id;
              const isPending =
                connectMutation.isPending &&
                connectMutation.variables?.repo.id === repo.id;
              return (
                <div key={`${installation.id}-${repo.id}`} className="repo-row">
                  <span className="repo-name">{repo.full_name}</span>
                  {isSelected ? (
                    <>
                      <BranchSelect
                        installationId={installation.id}
                        owner={repo.owner}
                        repo={repo.name}
                        value={branch}
                        onChange={setBranch}
                        disabled={isPending}
                      />
                      <button
                        className="btn btn-primary"
                        style={{ padding: "3px 10px", marginLeft: 8 }}
                        onClick={() =>
                          connectMutation.mutate({ installation, repo, branch })
                        }
                        disabled={isPending || !branch}
                      >
                        {isPending ? "연결 중…" : "연결"}
                      </button>
                      <button
                        className="btn btn-ghost"
                        style={{ padding: "3px 10px", marginLeft: 4 }}
                        onClick={() => setSelectedRepoId(null)}
                        disabled={isPending}
                      >
                        취소
                      </button>
                    </>
                  ) : (
                    <>
                      <span className="repo-meta">{repo.visibility}</span>
                      <button
                        className="btn btn-primary"
                        style={{ padding: "3px 10px", marginLeft: 8 }}
                        onClick={() => startBranchSelect(repo)}
                        disabled={connectMutation.isPending}
                      >
                        이 저장소 연결
                      </button>
                    </>
                  )}
                </div>
              );
            })}
          </div>
        )}

        {connectMutation.isError && (
          <div style={{ color: "var(--danger)", fontSize: 12, marginTop: 10 }}>
            연결에 실패했어요. 잠시 후 다시 시도해 주세요.
          </div>
        )}

        <div
          style={{
            display: "flex",
            alignItems: "center",
            gap: 8,
            marginTop: 20,
          }}
        >
          {connected && (
            <a className="btn btn-ghost" href={GITHUB_AUTHORIZE_URL}>
              연결 확인
            </a>
          )}
          <button
            className="btn btn-ghost"
            style={{ marginLeft: "auto" }}
            onClick={onDone}
            disabled={connectMutation.isPending}
          >
            나중에 연결하기
          </button>
        </div>
      </div>
    </>
  );
}

function StepIndicator({ step }: { step: 1 | 2 }) {
  return (
    <div className="onb-steps">
      <span className={"onb-step-dot" + (step === 1 ? " active" : "")}>1</span>
      <span className="onb-step-line" />
      <span className={"onb-step-dot" + (step === 2 ? " active" : "")}>2</span>
    </div>
  );
}

function StepCard({
  num,
  title,
  body,
  icon,
}: {
  num: string;
  title: string;
  body: string;
  icon: React.ReactNode;
}) {
  return (
    <div className="step-card">
      <div className="step-num">{num}</div>
      <h3>{title}</h3>
      <p>{body}</p>
      <div className="step-visual">{icon}</div>
    </div>
  );
}
