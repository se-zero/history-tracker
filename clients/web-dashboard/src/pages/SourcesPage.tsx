import { GitHubCard } from "@/components/sources/GitHubCard";
import { JiraCard } from "@/components/sources/JiraCard";
import { SlackCard } from "@/components/sources/SlackCard";
import { SourceTileGrid } from "@/components/sources/SourceTileGrid";
import { useOAuthCallbackError } from "@/components/sources/useOAuthCallbackError";
import { useIntegrations } from "@/hooks/useIntegrations";
import type { Project } from "@/types/api";

export function SourcesPage({ project }: { project: Project }) {
  // 페이지 레벨의 유일한 폴링 지점 — 구역 배치(어느 소스가 행/타일인지) 판단과 각 행의
  // 마지막 수집 시각 갱신을 겸한다. 하위 카드들도 같은 쿼리 키를 구독해 캐시를 공유한다.
  const integrationsQuery = useIntegrations(project.id, { refetchInterval: 60000 });
  const integrations = integrationsQuery.data ?? [];
  // integration 레코드 존재 여부 — Jira는 pending_project 단계도 포함되므로 "연결됨"이 아니라
  // "연동 행으로 렌더"라는 의미다.
  const jiraLinked = integrations.some((i) => i.provider === "jira");
  const slackLinked = integrations.some((i) => i.provider === "slack");

  const oauthError = useOAuthCallbackError();
  // 에러가 발생한 소스가 이미 "연동 중" 행으로 렌더되면 그 카드 자리에서 보여주고, 그 외에는
  // (타일 소스이거나 provider 불명) 타일 그리드 쪽으로 보낸다.
  const jiraOAuthError = oauthError?.provider === "jira" && jiraLinked ? oauthError.message : undefined;
  const slackOAuthError = oauthError?.provider === "slack" && slackLinked ? oauthError.message : undefined;
  const tileOAuthError = oauthError && !jiraOAuthError && !slackOAuthError ? oauthError : undefined;

  return (
    <div className="sources-page">
      <h1 className="page-title">데이터 소스</h1>
      <p className="page-sub">코드와 의사결정의 원본을 하나의 그래프로 모읍니다.</p>

      <div className="sources-section-label">연동 중</div>
      <div className="source-rows">
        <GitHubCard projectId={project.id} />
        {jiraLinked && <JiraCard projectId={project.id} oauthError={jiraOAuthError} />}
        {slackLinked && <SlackCard projectId={project.id} oauthError={slackOAuthError} />}
      </div>

      <div className="sources-section-label">추가 가능</div>
      <SourceTileGrid
        projectId={project.id}
        jiraLinked={jiraLinked}
        slackLinked={slackLinked}
        oauthError={tileOAuthError}
      />
    </div>
  );
}
