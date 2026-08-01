import { InlineError } from "@/components/ui/InlineError";
import { SourceTile } from "@/components/sources/SourceTile";
import { sourceCatalog } from "@/components/sources/sourceCatalog";
import { useJiraAuthorize, useSlackAuthorize } from "@/hooks/useIntegrationOAuth";

// "추가 가능" 타일 그리드. GitHub는 항상 행이라 제외하고, Jira·Slack은 이미 연동 중(행)이면
// 제외한다 — 렌더는 카탈로그 배열의 filter+map으로만 한다. authorize 에러는 타일 균일성을
// 지키기 위해 타일 안이 아니라 그리드 바로 아래 전폭 InlineError로 보여준다.
export function SourceTileGrid({
  projectId,
  jiraLinked,
  slackLinked,
  oauthError,
}: {
  projectId: string;
  jiraLinked: boolean;
  slackLinked: boolean;
  oauthError?: { provider: string | null; message: string };
}) {
  const jiraAuthorize = useJiraAuthorize(projectId);
  const slackAuthorize = useSlackAuthorize(projectId);

  const tileSources = sourceCatalog.filter(
    (source) =>
      source.id !== "github" &&
      !(source.id === "jira" && jiraLinked) &&
      !(source.id === "slack" && slackLinked),
  );

  return (
    <>
      <div className="source-tiles">
        {tileSources.map((source) => {
          if (source.id === "jira") {
            return (
              <SourceTile
                key={source.id}
                source={source}
                onConnect={() => jiraAuthorize.mutate()}
                pending={jiraAuthorize.isPending || jiraAuthorize.isSuccess}
                error={oauthError?.provider === source.id}
              />
            );
          }
          if (source.id === "slack") {
            return (
              <SourceTile
                key={source.id}
                source={source}
                onConnect={() => slackAuthorize.mutate()}
                pending={slackAuthorize.isPending || slackAuthorize.isSuccess}
                error={oauthError?.provider === source.id}
              />
            );
          }
          return (
            <SourceTile
              key={source.id}
              source={source}
              error={oauthError?.provider === source.id}
            />
          );
        })}
      </div>

      {jiraAuthorize.isError && (
        <InlineError style={{ marginTop: 12 }}>
          Jira 연결에 실패했어요. 잠시 후 다시 시도해 주세요.
        </InlineError>
      )}
      {slackAuthorize.isError && (
        <InlineError style={{ marginTop: 12 }}>
          Slack 연결에 실패했어요. 잠시 후 다시 시도해 주세요.
        </InlineError>
      )}
      {oauthError && <InlineError style={{ marginTop: 12 }}>{oauthError.message}</InlineError>}
    </>
  );
}
