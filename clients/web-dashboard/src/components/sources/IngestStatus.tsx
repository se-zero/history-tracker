import { Icons } from "@/components/Icons";
import { useIntegrations } from "@/hooks/useIntegrations";
import { formatDateTime } from "@/lib/format";

const PROVIDER_LABELS: Record<string, string> = {
  github: "GitHub",
  jira: "Jira",
  slack: "Slack",
};

function formatSyncedAt(iso: string | null): string {
  return iso ? formatDateTime(iso) : "아직 수집 전";
}

// 연동별 마지막 수집 시각
export function IngestStatus({ projectId }: { projectId: string }) {
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
