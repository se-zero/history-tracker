import { Field } from "@/components/ui/Field";
import { connectSlackWorkspace, type ConnectSlackPayload } from "@/api/integrations";
import { TokenIntegrationCard } from "./TokenIntegrationCard";

const INITIAL: ConnectSlackPayload = { token: "" };

export function SlackCard({ projectId }: { projectId: string }) {
  return (
    <TokenIntegrationCard<ConnectSlackPayload>
      projectId={projectId}
      provider="slack"
      title="Slack"
      logoClass="slack"
      logoText="S"
      subDisconnected="선택 사항 · 토론 맥락을 추가"
      connectedNameFallback="워크스페이스"
      emptyText="Slack을 연결하면 채널 토론도 그래프에 들어갑니다."
      errorText="연결에 실패했어요. 토큰을 확인해 주세요."
      initialForm={INITIAL}
      connect={connectSlackWorkspace}
      isSubmitDisabled={(form) => !form.token || !form.token.startsWith("xoxp-")}
      renderForm={(form, setForm) => (
        <>
          <Field
            label="User OAuth Token (xoxp-)"
            hint={
              <>
                Slack 앱 관리 → OAuth & Permissions → <strong>User OAuth Token</strong>{" "}
                발급. Bot Token(xoxb-)은 사용할 수 없습니다.
              </>
            }
          >
            <input
              type="password"
              placeholder="xoxp-..."
              value={form.token}
              onChange={(e) => setForm({ token: e.target.value.trim() })}
            />
          </Field>
          {form.token && !form.token.startsWith("xoxp-") && (
            <div style={{ color: "var(--warning)", fontSize: 12 }}>
              User OAuth Token은 <code>xoxp-</code>로 시작해야 합니다. Bot Token
              (xoxb-) 또는 다른 형식은 허용되지 않아요.
            </div>
          )}
        </>
      )}
    />
  );
}
