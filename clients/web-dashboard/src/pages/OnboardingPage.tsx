import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import axios from "axios";

import { BranchSelect } from "@/components/BranchSelect";
import { Icons } from "@/components/Icons";
import { Field } from "@/components/ui/Field";
import { InlineError } from "@/components/ui/InlineError";
import { MonoChip } from "@/components/ui/MonoChip";
import { createProject } from "@/api/projects";
import { GITHUB_AUTHORIZE_URL, GITHUB_INSTALL_URL } from "@/api/auth";
import { Topbar } from "@/components/shell/Topbar";
import { queryKeys } from "@/hooks/queryKeys";
import { useGithubRepoRows } from "@/hooks/useGithub";
import type { GitHubInstallation, GitHubRepository, Project } from "@/types/api";

// 프로젝트는 STEP 01이 아니라 STEP 02의 "연결"에서, GitHub 연동과 함께 한 번에 만들어진다.
// 예전에는 STEP 01의 "다음"이 곧바로 createProject를 호출했는데, STEP 02가 라우트가 아니라
// 로컬 state 전환이라 뒤로가기·새로고침·탭 닫기로 이 화면을 벗어나면 화면 상태만 사라지고
// 서버에는 GitHub 연동이 없는 빈 프로젝트가 남았다(새로고침은 STEP 01로 되돌아가므로 같은
// 이름으로 하나 더 만들어지기까지 했다). 지금은 STEP 01이 서버를 부르지 않고, 생성·연동을
// backend가 한 트랜잭션으로 처리해(POST /projects의 github 블록) 반쪽 상태 자체가 없다.
export function OnboardingPage() {
  const navigate = useNavigate();
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [step, setStep] = useState<1 | 2>(1);

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim()) return;
    setStep(2);
  };

  return (
    <div className="app no-sidebar">
      <div className="main">
        <Topbar crumbs={["whycode", "시작하기"]} />
        <div className="onboarding">
          {step === 2 ? (
            <ConnectGitHubStep
              name={name.trim()}
              description={description.trim()}
              onDone={(projectId) =>
                navigate(`/projects/${projectId}/chat`, { replace: true })
              }
              onBack={() => setStep(1)}
            />
          ) : (
            <CreateProjectStep
              name={name}
              description={description}
              onName={setName}
              onDescription={setDescription}
              onSubmit={submit}
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

// 이 단계는 서버를 부르지 않는다 — 입력만 받아 STEP 02로 넘긴다(파일 상단 주석).
function CreateProjectStep({
  name,
  description,
  onName,
  onDescription,
  onSubmit,
}: {
  name: string;
  description: string;
  onName: (v: string) => void;
  onDescription: (v: string) => void;
  onSubmit: (e: React.FormEvent) => void;
}) {
  return (
    <>
      <StepIndicator step={1} />
      <h1>프로젝트를 만들어 보세요</h1>
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
        <Field label="프로젝트 이름">
          <input
            autoFocus
            value={name}
            onChange={(e) => onName(e.target.value)}
            placeholder="예: Payments Platform"
          />
        </Field>
        <Field label="설명 (선택)">
          <input
            value={description}
            onChange={(e) => onDescription(e.target.value)}
            placeholder="예: 결제 도메인 전반(주문, 정산, 환불)"
          />
        </Field>
        <button
          type="submit"
          className="btn btn-primary btn-lg"
          disabled={!name.trim()}
        >
          <Icons.Plus size={14} /> 다음: GitHub 연결
        </button>
      </form>
    </>
  );
}

// =========================================================
// STEP 02 — GitHub 저장소 연결
// =========================================================

function ConnectGitHubStep({
  name,
  description,
  onDone,
  onBack,
}: {
  name: string;
  description: string;
  onDone: (projectId: string) => void;
  onBack: () => void;
}) {
  const queryClient = useQueryClient();

  const {
    installationsQuery,
    installations,
    rows: repoRows,
    reposLoading,
    repoQueries,
  } = useGithubRepoRows();

  // 연결하려고 선택한 저장소(브랜치 선택 단계)
  const [selectedRepoId, setSelectedRepoId] = useState<number | null>(null);
  const [branch, setBranch] = useState("");

  // 프로젝트 생성과 저장소 연결은 backend가 한 트랜잭션으로 처리한다(요청 한 번) —
  // 둘 중 하나만 남는 상태가 없어서, 실패하면 서버에는 아무것도 만들어지지 않고 재시도가
  // 깨끗하다. 저장소 목록은 프로젝트가 아니라 사용자 단위(installations) 조회라
  // 프로젝트가 없는 상태에서도 이 화면을 그릴 수 있다.
  const connectMutation = useMutation({
    mutationFn: (payload: {
      installation: GitHubInstallation;
      repo: GitHubRepository;
      branch: string;
    }) =>
      createProject({
        name,
        description: description || undefined,
        github: {
          installationId: payload.installation.id,
          repositoryId: payload.repo.id,
          repositoryFullName: payload.repo.full_name,
          branch: payload.branch,
        },
      }),
    onSuccess: (project) => {
      // 새 프로젝트를 캐시에 즉시 반영한다. 이후 chat 이동 시 AppShell이 stale한 빈 목록을
      // 보고 /onboarding으로 되튕기는 것을 막는다.
      queryClient.setQueryData<Project[]>(queryKeys.projects(), (prev) =>
        prev ? [project, ...prev] : [project],
      );
      queryClient.invalidateQueries({ queryKey: queryKeys.projects() });
      onDone(project.id);
    },
  });

  const startBranchSelect = (repo: GitHubRepository) => {
    setSelectedRepoId(repo.id);
    setBranch(repo.default_branch);
  };

  const connected = installations.length > 0;
  // 빈 목록과 같은 화면이 되지만 원인은 권한 만료라, 저장소 권한 안내로 보내면 빠져나올 수 없다.
  const needsGitHubReauthorization =
    connected &&
    !reposLoading &&
    repoQueries.some((query) => isGitHubReauthorizationRequired(query.error));

  return (
    <>
      <StepIndicator step={2} />
      <h1>GitHub 저장소 연결</h1>
      <p className="lead">
        <MonoChip>{name}</MonoChip>{" "}
        · 분석할 저장소를 선택하세요. 저장소를 연결하면 프로젝트가 만들어집니다.
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
        ) : needsGitHubReauthorization ? (
          <GitHubReauthorizationCta />
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
                        {/* 이 버튼 하나가 프로젝트 생성 + 연결을 다 한다 — "만드는 중"까지
                            분리해 보여주면 단계가 둘로 읽혀 되돌릴 수 있다고 오해된다. */}
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
          <InlineError style={{ marginTop: 10 }}>
            {connectErrorContent(connectMutation.error)}
          </InlineError>
        )}

        {/* "나중에 연결하기"는 제거됐다 — GitHub 연결은 필수이고(위 lead 문구), 저장소 없이
            들어가면 그래프가 빈 채로 첫 화면이 열려 제품이 고장난 것처럼 보인다.
            대신 '이전'으로 STEP 01에 언제든 돌아갈 수 있다 — 실패했든 아직 시도 전이든
            서버에는 아무것도 만들어지지 않았으므로 되돌아가도 남는 게 없다.
            '연결 확인'은 GitHub App 설치·권한을 바꾸고 돌아와 목록을 다시 받는 용도다. */}
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
            onClick={onBack}
            disabled={connectMutation.isPending}
          >
            이전
          </button>
        </div>
      </div>
    </>
  );
}

const GITHUB_REAUTHORIZATION_MESSAGE = "GitHub reauthorization required.";

function isGitHubReauthorizationRequired(error: unknown): boolean {
  if (!axios.isAxiosError(error)) return false;
  if (error.response?.status !== 403) return false;
  const data = error.response.data as { message?: unknown } | undefined;
  return data?.message === GITHUB_REAUTHORIZATION_MESSAGE;
}

function GitHubReauthorizationCta() {
  return (
    <div className="onboarding-gh-empty">
      <span>
        로그인 때 받은 GitHub 권한이 없거나 만료돼 저장소 목록을 볼 수 없어요.
      </span>
      <a className="btn btn-primary" href={GITHUB_AUTHORIZE_URL}>
        다시 로그인하고 연결 확인
      </a>
    </div>
  );
}

function connectErrorContent(error: unknown) {
  if (isGitHubReauthorizationRequired(error)) {
    return (
      <>
        로그인 때 받은 GitHub 권한이 없거나 만료돼 저장소를 연결할 수 없어요.{" "}
        <a href={GITHUB_AUTHORIZE_URL}>다시 로그인하고 연결 확인</a>
      </>
    );
  }
  // 연동 한도 403은 재로그인으로 풀리지 않는다.
  if (axios.isAxiosError(error) && error.response?.status === 403) {
    return "플랜 한도에 도달했어요.";
  }
  return "프로젝트를 만들지 못했어요. 잠시 후 다시 시도해 주세요.";
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
