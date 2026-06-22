import { Field } from "@/components/ui/Field";
import { connectJiraProject, type ConnectJiraPayload } from "@/api/integrations";
import { TokenIntegrationCard } from "./TokenIntegrationCard";

const INITIAL: ConnectJiraPayload = {
  baseUrl: "",
  projectKey: "",
  email: "",
  apiToken: "",
};

export function JiraCard({ projectId }: { projectId: string }) {
  return (
    <TokenIntegrationCard<ConnectJiraPayload>
      projectId={projectId}
      provider="jira"
      title="Jira"
      logoClass="jira"
      logoText="J"
      subDisconnected="선택 사항"
      connectedNameFallback="Jira"
      emptyText="Jira 티켓을 그래프에 포함하려면 연결하세요."
      errorText="연결에 실패했어요. 입력 값을 확인해 주세요."
      initialForm={INITIAL}
      connect={connectJiraProject}
      renderForm={(form, setForm) => (
        <>
          <Field label="Base URL">
            <input
              placeholder="https://acme.atlassian.net"
              value={form.baseUrl}
              onChange={(e) => setForm({ ...form, baseUrl: e.target.value })}
            />
          </Field>
          <Field label="프로젝트 키">
            <input
              placeholder="AUTH"
              value={form.projectKey}
              onChange={(e) =>
                setForm({ ...form, projectKey: e.target.value.toUpperCase() })
              }
            />
          </Field>
          <Field label="계정 이메일">
            <input
              placeholder="you@acme.com"
              value={form.email}
              onChange={(e) => setForm({ ...form, email: e.target.value })}
            />
          </Field>
          <Field label="API Token" hint="Atlassian → 계정 설정 → API 토큰에서 발급">
            <input
              type="password"
              value={form.apiToken}
              onChange={(e) => setForm({ ...form, apiToken: e.target.value })}
            />
          </Field>
        </>
      )}
    />
  );
}
