import { InlineError } from "@/components/ui/InlineError";
import { useIntegrations } from "@/hooks/useIntegrations";
import { useSlackAuthorize } from "@/hooks/useIntegrationOAuth";

export function SlackCard({ projectId }: { projectId: string }) {
  const integrationsQuery = useIntegrations(projectId);
  const integration = integrationsQuery.data?.find((i) => i.provider === "slack");
  const connected = Boolean(integration);
  const connectedName = integration?.displayName ?? "워크스페이스";

  const authorizeMutation = useSlackAuthorize(projectId);

  return (
    <div className="source-card">
      <div className="src-head">
        <div className="src-logo slack">S</div>
        <div style={{ flex: 1 }}>
          <h4>Slack</h4>
          <div className="src-sub">
            {connected ? connectedName : "선택 사항 · 토론 맥락을 추가"}
          </div>
        </div>
        <span className={"badge " + (connected ? "success" : "")}>
          <span className="dot" />
          {integrationsQuery.isLoading
            ? "확인 중"
            : connected
              ? "연결됨"
              : "미연결"}
        </span>
      </div>

      {!connected && (
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

      {authorizeMutation.isError && (
        <InlineError>연결에 실패했어요. 잠시 후 다시 시도해 주세요.</InlineError>
      )}

      <div style={{ display: "flex", gap: 8, marginTop: "auto" }}>
        {/* 재연결은 제공하지 않는다 — 연결됨이면 버튼을 비활성화한다 */}
        <button
          className={"btn " + (connected ? "" : "btn-primary")}
          style={{ flex: 1 }}
          onClick={() => authorizeMutation.mutate()}
          disabled={connected || integrationsQuery.isLoading || authorizeMutation.isPending}
        >
          {authorizeMutation.isPending
            ? "이동 중…"
            : connected
              ? "연결됨"
              : "Slack 연결"}
        </button>
      </div>
    </div>
  );
}
