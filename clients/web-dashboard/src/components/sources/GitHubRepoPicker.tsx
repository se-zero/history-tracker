import { useState, type CSSProperties, type ReactNode } from "react";
import axios from "axios";

import { BranchSelect } from "@/components/BranchSelect";
import { GitHubInstallEmptyState } from "@/components/sources/GitHubInstallEmptyState";
import { GitHubRecheckLink } from "@/components/sources/GitHubRecheckLink";
import { GitHubRepoAccessLink } from "@/components/sources/GitHubRepoAccessLink";
import { InlineError } from "@/components/ui/InlineError";
import { useGithubRepoRows } from "@/hooks/useGithub";
import { useUiLanguage, type UiLang } from "@/i18n/language";
import type { GitHubInstallation, GitHubRepository } from "@/types/api";

// GitHub 저장소 선택 화면 — 설치 확인부터 저장소 목록·브랜치 선택·권한 만료 안내까지 그린다.
// 온보딩 2단계(프로젝트를 만들며 연결)와 데이터 소스의 GitHubCard(저장소가 없는 프로젝트에 연결)가
// 같이 쓴다. "연결"을 눌렀을 때 서버를 부르는 일은 쓰는 쪽이 맡는다 — 온보딩은 프로젝트 생성과 연동을
// 한 번에, 카드는 이미 있는 프로젝트에 연동만 하기 때문이다. 여기서는 선택 상태와 오류 표시까지만 한다.
export function GitHubRepoPicker({
  onConnect,
  connectingRepoId,
  connectError,
  fallbackErrorMessage,
  triggerClassName = "btn btn-primary",
  emptyStateStyle,
  footerTail,
}: {
  onConnect: (selection: {
    installation: GitHubInstallation;
    repo: GitHubRepository;
    branch: string;
  }) => void;
  // 요청 중인 저장소의 id(없으면 null) — 그 행은 "연결 중…"으로 바뀌어 잠기고, 요청이 끝날 때까지 다른 행의
  // "이 저장소 연결"도 잠가 요청이 겹치지 않게 한다.
  connectingRepoId: number | null;
  // 마지막 연결 요청의 오류(없으면 null).
  connectError: unknown;
  // 권한 만료·플랜 한도가 아닌 오류에 보일 문구. 실패한 동작이 쓰는 쪽마다 달라서(온보딩은 프로젝트 생성,
  // 카드는 저장소 연결) 언어까지 골라 넘겨받는다.
  fallbackErrorMessage: string;
  // 앰버(btn-primary)는 한 화면의 "실행" 하나에만 쓴다 — 최종 "연결"이 따로 있는 카드는 여기를 낮춘다.
  triggerClassName?: string;
  // 설치 없음 빈 상태의 여백. 놓이는 자리마다 달라 쓰는 쪽이 준다.
  emptyStateStyle?: CSSProperties;
  // 하단 버튼 행 끝에 붙는다 — 온보딩의 "이전".
  footerTail?: ReactNode;
}) {
  const { lang } = useUiLanguage();
  const en = lang === "en";

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

  const startBranchSelect = (repo: GitHubRepository) => {
    setSelectedRepoId(repo.id);
    setBranch(repo.default_branch);
  };

  const connecting = connectingRepoId !== null;
  const connected = installations.length > 0;
  // 빈 목록과 같은 화면이 되지만 원인은 권한 만료라, 저장소 권한 안내로 보내면 빠져나올 수 없다.
  const needsGitHubReauthorization =
    connected &&
    !reposLoading &&
    repoQueries.some((query) => isGitHubReauthorizationRequired(query.error));
  // 저장소 목록(또는 0건 안내)이 그려질 때만 "저장소·조직 추가" 진입점을 둔다. 설치가 없으면 빈 상태가,
  // 권한 만료면 재로그인 안내가 각자 다음 행동을 이끌므로 거기에 길을 더 열면 갈래만 늘어난다.
  const showAccessEntry = connected && !reposLoading && !needsGitHubReauthorization;

  return (
    <>
      {installationsQuery.isLoading ? (
        <div className="onb-muted-block">
          {en ? "Loading GitHub workspaces…" : "GitHub 워크스페이스를 불러오는 중…"}
        </div>
      ) : !connected ? (
        <GitHubInstallEmptyState style={emptyStateStyle} />
      ) : reposLoading ? (
        <div className="onb-muted-block">
          {en ? "Loading repositories…" : "저장소 목록을 불러오는 중…"}
        </div>
      ) : needsGitHubReauthorization ? (
        <GitHubReauthorizationCta />
      ) : repoRows.length === 0 ? (
        <div className="onb-muted-block">
          {en
            ? "No repositories you can access. Add the ones whycode should read under “Add repositories or organizations on GitHub”, then press Check connection to refresh the list. Only the owner of that account (an organization owner, for an org) can change the installation."
            : "접근 가능한 저장소가 없어요. 아래 'GitHub에서 저장소·조직 추가'에서 whycode가 읽을 저장소를 고른 뒤 연결 확인을 누르면 목록이 갱신돼요. 설치는 그 계정의 소유자(조직은 조직 소유자)만 바꿀 수 있어요."}
        </div>
      ) : (
        <div className="repo-list" style={{ maxHeight: 320, overflowY: "auto" }}>
          {repoRows.map(({ installation, repo }) => {
            const isSelected = selectedRepoId === repo.id;
            const isPending = connectingRepoId === repo.id;
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
                      onClick={() => onConnect({ installation, repo, branch })}
                      disabled={isPending || !branch}
                    >
                      {/* 온보딩에서는 이 버튼 하나가 프로젝트 생성 + 연결을 다 한다 — "만드는 중"까지
                          분리해 보여주면 단계가 둘로 읽혀 되돌릴 수 있다고 오해된다. */}
                      {isPending ? (en ? "Connecting…" : "연결 중…") : en ? "Connect" : "연결"}
                    </button>
                    <button
                      className="btn btn-ghost"
                      style={{ padding: "3px 10px", marginLeft: 4 }}
                      onClick={() => setSelectedRepoId(null)}
                      disabled={isPending}
                    >
                      {en ? "Cancel" : "취소"}
                    </button>
                  </>
                ) : (
                  <>
                    <span className="repo-meta">{repo.visibility}</span>
                    <button
                      className={triggerClassName}
                      style={{ padding: "3px 10px", marginLeft: 8 }}
                      onClick={() => startBranchSelect(repo)}
                      disabled={connecting}
                    >
                      {en ? "Connect this repository" : "이 저장소 연결"}
                    </button>
                  </>
                )}
              </div>
            );
          })}
        </div>
      )}

      {connectError != null && (
        <InlineError style={{ marginTop: 10 }}>
          {connectErrorContent(connectError, lang, fallbackErrorMessage)}
        </InlineError>
      )}

      {/* 0건이면 위 안내 상자가 같은 내용을 이미 말하므로, 목록이 있을 때만 덧붙인다. */}
      {showAccessEntry && repoRows.length > 0 && (
        <p className="onb-access-hint">
          {en
            ? "Don't see the repository? Add it on GitHub, then press Check connection to refresh the list. Only the owner of that account (an organization owner, for an org) can change the installation."
            : "찾는 저장소가 없나요? GitHub에서 추가한 뒤 연결 확인을 누르면 목록이 갱신돼요. 설치는 그 계정의 소유자(조직은 조직 소유자)만 바꿀 수 있어요."}
        </p>
      )}

      {/* '연결 확인'은 GitHub App 설치·권한을 바꾸고 돌아와 목록을 다시 받는 용도다.
          '저장소·조직 추가'는 그 바꾸는 쪽(GitHub 설정)을 새 탭으로 여는 짝이다.
          설치가 없고 꼬리도 없으면 행이 비는데, 빈 채로 그리면 marginTop만 남아 아래에 빈 공간이 생긴다. */}
      {(connected || footerTail) && (
        <div
          style={{
            display: "flex",
            flexWrap: "wrap",
            alignItems: "center",
            gap: 8,
            marginTop: 20,
          }}
        >
          {showAccessEntry && <GitHubRepoAccessLink />}
          {connected && (
            <GitHubRecheckLink className="btn btn-ghost">
              {en ? "Check connection" : "연결 확인"}
            </GitHubRecheckLink>
          )}
          {footerTail}
        </div>
      )}
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
  const { lang } = useUiLanguage();
  const en = lang === "en";
  return (
    <div className="onboarding-gh-empty">
      <span>
        {en
          ? "The GitHub permission from sign-in is missing or expired, so the repository list can't be loaded."
          : "로그인 때 받은 GitHub 권한이 없거나 만료돼 저장소 목록을 볼 수 없어요."}
      </span>
      <GitHubRecheckLink className="btn btn-primary">
        {en ? "Sign in again and check the connection" : "다시 로그인하고 연결 확인"}
      </GitHubRecheckLink>
    </div>
  );
}

function connectErrorContent(error: unknown, lang: UiLang, fallbackMessage: string) {
  const en = lang === "en";
  if (isGitHubReauthorizationRequired(error)) {
    return (
      <>
        {en
          ? "The GitHub permission from sign-in is missing or expired, so the repository can't be connected. "
          : "로그인 때 받은 GitHub 권한이 없거나 만료돼 저장소를 연결할 수 없어요. "}
        <GitHubRecheckLink className="text-link">
          {en ? "Sign in again and check the connection" : "다시 로그인하고 연결 확인"}
        </GitHubRecheckLink>
      </>
    );
  }
  // 연동 한도 403은 재로그인으로 풀리지 않는다.
  if (axios.isAxiosError(error) && error.response?.status === 403) {
    return en ? "You've reached your plan limit." : "플랜 한도에 도달했어요.";
  }
  return fallbackMessage;
}
