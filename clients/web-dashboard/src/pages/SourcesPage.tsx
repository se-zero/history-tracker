import { GitHubCard } from "@/components/sources/GitHubCard";
import { OAuthSourceCard } from "@/components/sources/OAuthSourceCard";
import { SourceTileGrid } from "@/components/sources/SourceTileGrid";
import { useOAuthCallbackError } from "@/components/sources/useOAuthCallbackError";
import { useIntegrations } from "@/hooks/useIntegrations";
import type { Project } from "@/types/api";

export function SourcesPage({ project }: { project: Project }) {
  // 페이지 레벨의 유일한 폴링 지점 — 구역 배치(어느 소스가 행/타일인지) 판단과 각 행의
  // 마지막 수집 시각 갱신을 겸한다. 하위 카드들도 같은 쿼리 키를 구독해 캐시를 공유한다.
  const integrationsQuery = useIntegrations(project.id, { refetchInterval: 60000 });
  const integrations = integrationsQuery.data ?? [];
  // GitHub는 설치 기반이라 항상 전용 행(GitHubCard)이고, 나머지는 integration 레코드가 존재하면
  // (pending 선택 단계 포함) OAuthSourceCard 행으로 렌더한다 — "연결됨"이 아니라 "연동 행" 의미다.
  const rowProviders = integrations
    .map((integration) => integration.provider)
    .filter((provider) => provider !== "github");

  const oauthError = useOAuthCallbackError();
  // 에러가 발생한 소스가 이미 "연동 중" 행으로 렌더되면 그 카드 자리에서 보여주고, 그 외에는
  // (타일 소스이거나 provider 불명) 타일 그리드 쪽으로 보낸다.
  const rowError =
    oauthError && oauthError.provider && rowProviders.includes(oauthError.provider)
      ? oauthError
      : undefined;
  const tileOAuthError = oauthError && !rowError ? oauthError : undefined;

  return (
    <div className="sources-page">
      <h1 className="page-title">데이터 소스</h1>
      <p className="page-sub">코드와 의사결정의 원본을 하나의 그래프로 모읍니다.</p>

      <div className="sources-section-label">연동 중</div>
      <div className="source-rows">
        <GitHubCard projectId={project.id} />
        {rowProviders.map((provider) => (
          <OAuthSourceCard
            key={provider}
            projectId={project.id}
            provider={provider}
            oauthError={rowError?.provider === provider ? rowError.message : undefined}
          />
        ))}
      </div>

      <div className="sources-section-label">추가 가능</div>
      <SourceTileGrid
        projectId={project.id}
        linkedProviders={rowProviders}
        oauthError={tileOAuthError}
      />
    </div>
  );
}
