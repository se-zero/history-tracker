import { useState, type Dispatch, type ReactNode, type SetStateAction } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";

import { InlineError } from "@/components/ui/InlineError";
import { queryKeys } from "@/hooks/queryKeys";
import { useIntegrations } from "@/hooks/useIntegrations";

// 토큰 입력형 연동 카드(Jira·Slack 공용 셸). 연결 상태는 서버 연동 목록에서 도출하고,
// 폼 본문만 renderForm으로 주입받는다 — 두 카드의 ~95% 동일하던 흐름을 하나로 합쳤다.
interface Props<TForm> {
  projectId: string;
  provider: string;
  title: string;
  logoClass: string;
  logoText: string;
  subDisconnected: string;
  connectedNameFallback: string;
  emptyText: string;
  errorText: string;
  initialForm: TForm;
  connect: (projectId: string, form: TForm) => Promise<unknown>;
  renderForm: (form: TForm, setForm: Dispatch<SetStateAction<TForm>>) => ReactNode;
  isSubmitDisabled?: (form: TForm) => boolean;
}

export function TokenIntegrationCard<TForm>({
  projectId,
  provider,
  title,
  logoClass,
  logoText,
  subDisconnected,
  connectedNameFallback,
  emptyText,
  errorText,
  initialForm,
  connect,
  renderForm,
  isSubmitDisabled,
}: Props<TForm>) {
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState<TForm>(initialForm);
  const queryClient = useQueryClient();

  const integrationsQuery = useIntegrations(projectId);
  const integration = integrationsQuery.data?.find((i) => i.provider === provider);
  const connected = Boolean(integration);
  const connectedName = integration?.displayName ?? connectedNameFallback;

  const mutation = useMutation({
    mutationFn: () => connect(projectId, form),
    onSuccess: () => {
      setOpen(false);
      setForm(initialForm);
      queryClient.invalidateQueries({ queryKey: queryKeys.integrations(projectId) });
    },
  });

  return (
    <div className="source-card">
      <div className="src-head">
        <div className={"src-logo " + logoClass}>{logoText}</div>
        <div style={{ flex: 1 }}>
          <h4>{title}</h4>
          <div className="src-sub">{connected ? connectedName : subDisconnected}</div>
        </div>
        <span className={"badge " + (connected ? "success" : "")}>
          <span className="dot" />
          {connected ? "연결됨" : "미연결"}
        </span>
      </div>

      {!connected && !open && (
        <div
          style={{
            padding: "20px 0",
            textAlign: "center",
            color: "var(--fg-muted)",
            fontSize: 13,
          }}
        >
          {emptyText}
        </div>
      )}

      {open && (
        <div className="connect-form">
          {renderForm(form, setForm)}
          {mutation.isError && <InlineError>{errorText}</InlineError>}
        </div>
      )}

      <div style={{ display: "flex", gap: 8, marginTop: "auto" }}>
        <button
          className={"btn " + (connected ? "" : "btn-primary")}
          style={{ flex: 1 }}
          onClick={() => {
            if (open) mutation.mutate();
            else setOpen(true);
          }}
          disabled={
            mutation.isPending || (open && Boolean(isSubmitDisabled?.(form)))
          }
        >
          {mutation.isPending
            ? "연결 중…"
            : open
              ? "연결"
              : connected
                ? "재연결"
                : `${title} 연결`}
        </button>
        {open && (
          <button className="btn btn-ghost" onClick={() => setOpen(false)}>
            취소
          </button>
        )}
      </div>
    </div>
  );
}
