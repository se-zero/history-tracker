import { useEffect, useState, type FormEvent } from "react";
import axios from "axios";

import { BusyLabel } from "@/components/ui/BusyLabel";
import { Field } from "@/components/ui/Field";
import { InlineError } from "@/components/ui/InlineError";
import { useConnectSlackWorkspace } from "@/hooks/useIntegrations";

const INITIAL = { token: "" };

function connectErrorMessage(error: unknown): string {
  if (!axios.isAxiosError(error)) {
    return "연결에 실패했어요. 잠시 후 다시 시도해 주세요.";
  }
  const status = error.response?.status;
  if (status === 400) return "연결에 실패했어요. 토큰을 확인해 주세요.";
  if (status === 409) return "이미 연동되어 있어요.";
  if (status === 403) return "플랜 한도에 도달했어요.";
  return "연결에 실패했어요. 잠시 후 다시 시도해 주세요.";
}

/**
 * Slack BYO(고객 Internal 앱의 User OAuth Token) 연결 다이얼로그.
 *
 * 등재 전까지 우리 앱 OAuth보다 수집이 빠르다. 사용자에게 보이는 이유는 "봇이 싫어서"가
 * 아니라 "빠르게 받고 싶을 때"다. 토큰 원문은 에러 UI·로그에 넣지 않는다.
 */
export function SlackTokenConnectDialog({
  projectId,
  onClose,
}: {
  projectId: string;
  onClose: () => void;
}) {
  const [form, setForm] = useState(INITIAL);
  const connect = useConnectSlackWorkspace(projectId);
  const canSubmit = form.token.startsWith("xoxp-") && !connect.isPending;

  useEffect(() => {
    connect.reset();
    // connect는 매 렌더 새 객체라 의존성에 넣으면 무한 루프가 된다 — 마운트 한 번만.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const close = () => {
    if (connect.isPending) return;
    onClose();
  };

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    const token = form.token.trim();
    if (!token.startsWith("xoxp-") || connect.isPending) return;
    connect.mutate(token, { onSuccess: onClose });
  };

  return (
    <div className="confirm-overlay" onMouseDown={close}>
      <div
        className="confirm-dialog confirm-dialog--wide"
        role="dialog"
        aria-modal="true"
        aria-labelledby="slack-token-connect-title"
        tabIndex={-1}
        onMouseDown={(e) => e.stopPropagation()}
        onKeyDown={(e) => {
          if (e.key === "Escape") close();
        }}
      >
        <h4 id="slack-token-connect-title" className="confirm-title">
          토큰으로 Slack 연결
        </h4>
        <p className="confirm-copy">
          수집을 더 빨리 받고 싶다면 워크스페이스에 Internal 앱을 만들고 User OAuth Token을 붙여
          연결할 수 있어요.
        </p>
        <ol className="token-connect-steps">
          <li>api.slack.com에서 앱을 만들고 Internal로 배포합니다.</li>
          <li>
            User Token Scopes에{" "}
            <code>channels:read,groups:read,channels:history,groups:history,users:read,users:read.email</code>
            을 넣습니다.
          </li>
          <li>워크스페이스에 앱을 설치합니다.</li>
          <li>
            User OAuth Token(<code>xoxp-</code>)을 복사합니다.
          </li>
          <li>아래에 붙여넣습니다.</li>
        </ol>

        <form className="token-connect-form" onSubmit={onSubmit}>
          <Field
            label="User OAuth Token (xoxp-)"
            hint={
              <>
                Slack 앱 관리 → OAuth & Permissions → <strong>User OAuth Token</strong> 발급. Bot
                Token(xoxb-)은 사용할 수 없습니다.
              </>
            }
          >
            <input
              type="password"
              placeholder="xoxp-..."
              value={form.token}
              autoComplete="off"
              spellCheck={false}
              autoFocus
              disabled={connect.isPending}
              onChange={(e) => {
                setForm({ token: e.target.value.trim() });
                connect.reset();
              }}
            />
          </Field>
          {form.token && !form.token.startsWith("xoxp-") && (
            <div className="token-prefix-hint">
              User OAuth Token은 <code>xoxp-</code>로 시작해야 합니다. Bot Token (xoxb-) 또는 다른
              형식은 허용되지 않아요.
            </div>
          )}
          {connect.isError && <InlineError>{connectErrorMessage(connect.error)}</InlineError>}

          <div className="confirm-actions">
            <button type="button" className="btn btn-ghost" onClick={close} disabled={connect.isPending}>
              취소
            </button>
            <button type="submit" className="btn btn-primary" disabled={!canSubmit}>
              <BusyLabel busy={connect.isPending} label="연결" busyLabel="연결 중…" />
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
