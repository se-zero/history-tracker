import { SlackMark } from "@/components/brand/BrandMarks";
import { InlineError } from "@/components/ui/InlineError";
import { useIntegrations } from "@/hooks/useIntegrations";
import { formatTimestamp } from "@/lib/format";

// Slack은 pending 단계가 없어(동의 즉시 확정) integration이 있으면 곧 연결 완료 상태다 —
// 미연결은 이 컴포넌트가 아니라 "추가 가능" 타일(SourceTileGrid)이 맡는다.
export function SlackCard({
  projectId,
  oauthError,
}: {
  projectId: string;
  oauthError?: string;
}) {
  const integrationsQuery = useIntegrations(projectId);
  const integration = integrationsQuery.data?.find((i) => i.provider === "slack");
  const connectedName = integration?.displayName ?? "워크스페이스";
  const lastSyncedAt = integration?.lastSyncedAt
    ? formatTimestamp(integration.lastSyncedAt)
    : null;

  return (
    <div className="source-row">
      <div className="source-row-top">
        <div className="source-logo-chip">
          <SlackMark size={20} />
        </div>
        <div className="source-row-main">
          <div className="source-row-name">Slack</div>
          <div className="source-row-sub">{connectedName}</div>
        </div>
        <div className="source-row-status">
          <span className="src-pill connected">
            <span className="dot" />
            연결됨
          </span>
          {lastSyncedAt && (
            <div className="source-row-synced">
              마지막 수집 <span className="mono">{lastSyncedAt}</span>
            </div>
          )}
        </div>
      </div>
      {oauthError && <InlineError style={{ marginTop: 10 }}>{oauthError}</InlineError>}
    </div>
  );
}
