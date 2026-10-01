import { GitHubRecheckLink } from "@/components/sources/GitHubRecheckLink";
import { GitHubRepoAccessLink } from "@/components/sources/GitHubRepoAccessLink";
import { InlineError } from "@/components/ui/InlineError";
import { useGithubInstallations } from "@/hooks/useGithub";
import type { GitHubInstallation } from "@/types/api";

/**
 * whycode(GitHub App)가 읽을 수 있는 GitHub 계정·조직 목록과, 그 접근 범위를 바꾸는 GitHub 쪽 진입점.
 *
 * 저장소를 추가하거나 빼는 일은 우리 서버가 아니라 GitHub의 App 설정에서 일어난다 — 여기서는 갈 곳만
 * 알려 준다. 바꾼 뒤에는 "연결 확인"(재로그인)이 설치 목록을 새로 받는 유일한 수단이다.
 */
export function GitHubAccessCard() {
  const installationsQuery = useGithubInstallations();
  const installations = installationsQuery.data ?? [];
  const ready = !installationsQuery.isLoading && !installationsQuery.isError;

  return (
    <section className="source-card github-access-card">
      <div className="src-head">
        <div className="src-head-main">
          <h4>GitHub 저장소 접근</h4>
          <div className="src-sub">
            whycode가 읽을 수 있는 GitHub 계정·조직입니다. 저장소를 추가하거나 빼려면 GitHub에서
            설정하세요.
          </div>
        </div>
      </div>

      {installationsQuery.isLoading && <p className="github-access-empty">불러오는 중…</p>}
      {installationsQuery.isError && (
        <InlineError>GitHub 계정 목록을 불러오지 못했어요.</InlineError>
      )}
      {ready && installations.length === 0 && (
        <p className="github-access-empty">아직 연결된 GitHub 계정이 없어요.</p>
      )}

      {installations.length > 0 && (
        <ul className="github-access-list">
          {installations.map((installation) => (
            <li className="github-access-row" key={installation.id}>
              <div className="github-access-main">
                <span className="github-access-name mono">{installation.accountLogin}</span>
                <span className="github-access-kind">
                  {installation.accountType === "Organization" ? "조직" : "개인"}
                </span>
              </div>
              <a
                className="btn btn-ghost"
                href={installationSettingsUrl(installation)}
                target="_blank"
                rel="noopener noreferrer"
              >
                GitHub에서 설정
              </a>
            </li>
          ))}
        </ul>
      )}

      {ready && (
        <>
          <div className="github-access-actions">
            {/* 설치가 하나도 없으면 "다른"이 가리킬 대상이 없어, 목록이 빈 상태에선 라벨을 바꿔 하나만 둔다. */}
            <GitHubRepoAccessLink>
              {installations.length === 0 ? "GitHub App 설치하기" : "다른 계정·조직에 설치"}
            </GitHubRepoAccessLink>
            <GitHubRecheckLink className="btn btn-ghost">연결 확인</GitHubRecheckLink>
          </div>
          <p className="github-access-note">
            조직은 소유자만 설치하거나 저장소를 바꿀 수 있어요. 바꾼 뒤 연결 확인을 누르면 목록이
            갱신돼요.
          </p>
        </>
      )}
    </section>
  );
}

// 설치별 GitHub 설정 화면 주소 — 조직과 개인 계정의 경로가 다르다.
//   조직: https://github.com/organizations/{org}/settings/installations/{installationId}
//   개인: https://github.com/settings/installations/{installationId}
// 조직 설치는 조직 소유자만 바꿀 수 있어서, 소유자가 아니면 이 링크로 가도 설정을 바꾸지 못한다.
function installationSettingsUrl({
  accountType,
  accountLogin,
  installationId,
}: GitHubInstallation): string {
  if (accountType === "Organization") {
    return `https://github.com/organizations/${encodeURIComponent(accountLogin)}/settings/installations/${installationId}`;
  }
  return `https://github.com/settings/installations/${installationId}`;
}
