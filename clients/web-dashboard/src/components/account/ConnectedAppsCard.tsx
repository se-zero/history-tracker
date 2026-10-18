import { useEffect, useState } from "react";
import { Link } from "react-router-dom";

import { InlineError } from "@/components/ui/InlineError";
import { useOAuthGrants, useRevokeOAuthGrant } from "@/hooks/useOAuthGrants";
import { formatTimestamp } from "@/lib/format";
import { isHttpUrl } from "@/lib/url";
import { getUiLang, uiText } from "@/i18n/language";
import { PATHS } from "@/routes";
import type { OAuthGrant } from "@/types/api";

/**
 * MCP 클라이언트(Claude Code·Codex 등)가 `/oauth/consent`에서 승인받아 연결된 목록.
 *
 * 연동 해제(DisconnectIntegration)와 달리 그래프 데이터를 지우지 않는다 — refresh 토큰과
 * 인가만 철회한다. backend가 `/mcp` 입구에서 요청마다 연결 행을 확인하므로, 이미 발급된
 * access 토큰도 끊는 즉시 거부된다.
 */
export function ConnectedAppsCard() {
  const grantsQuery = useOAuthGrants();
  const grants = grantsQuery.data ?? [];

  return (
    <section className="source-card connected-apps-card">
      <div className="src-head">
        <div className="src-head-main">
          <h4>{uiText("연결된 앱", "Connected apps")}</h4>
          <div className="src-sub">
            {uiText(
              "Claude Code·Codex 같은 코딩 에이전트에서 whycode에 연결한 앱입니다.",
              "Apps connected to whycode from a coding agent such as Claude Code or Codex.",
            )}
          </div>
        </div>
      </div>

      {grantsQuery.isLoading && (
        <p className="connected-apps-empty">
          {uiText("연결된 앱을 불러오는 중…", "Loading connected apps…")}
        </p>
      )}
      {grantsQuery.isError && (
        <InlineError>{uiText("연결된 앱을 불러오지 못했어요.", "Couldn't load connected apps.")}</InlineError>
      )}
      {!grantsQuery.isLoading && !grantsQuery.isError && grants.length === 0 && (
        <p className="connected-apps-empty">
          {uiText("연결된 앱이 없습니다. ", "No apps connected. ")}
          <Link className="connected-app-link" to={PATHS.mcpSetup}>
            {uiText("코딩 에이전트에서 쓰는 방법", "How to use it from a coding agent")}
          </Link>
        </p>
      )}

      {grants.length > 0 && (
        <>
          <div className="connected-apps-list">
            {grants.map((grant) => (
              <ConnectedAppRow grant={grant} key={grant.id} />
            ))}
          </div>
          {/* 다른 에이전트를 더 붙일 때 안내를 다시 찾을 수 있게 — 빈 상태에만 두면 첫 연결 뒤 링크가 사라진다 */}
          <p className="connected-apps-note">
            {getUiLang() === "en" ? (
              <>
                To connect another coding agent, see{" "}
                <Link className="connected-app-link" to={PATHS.mcpSetup}>
                  how to use it from a coding agent
                </Link>
                .
              </>
            ) : (
              <>
                다른 코딩 에이전트를 연결하는 방법은{" "}
                <Link className="connected-app-link" to={PATHS.mcpSetup}>
                  코딩 에이전트에서 쓰는 방법
                </Link>
                에 있습니다.
              </>
            )}
          </p>
        </>
      )}
    </section>
  );
}

function ConnectedAppRow({ grant }: { grant: OAuthGrant }) {
  const [confirming, setConfirming] = useState(false);
  const revoke = useRevokeOAuthGrant();

  // 다이얼로그를 닫았다 다시 열면 지난 실패 문구가 남아 있지 않게 한다.
  useEffect(() => {
    if (confirming) revoke.reset();
    // revoke는 매 렌더 새 객체라 의존성에 넣으면 무한 루프가 된다 — 열림 상태만 본다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [confirming]);

  const close = () => {
    if (revoke.isPending) return; // 요청 중 닫으면 결과를 알릴 곳이 사라진다
    setConfirming(false);
  };

  return (
    <div className="connected-app-row">
      <div className="connected-app-main">
        <div className="connected-app-name">
          {grant.clientUri && isHttpUrl(grant.clientUri) ? (
            <a
              className="connected-app-link"
              href={grant.clientUri}
              target="_blank"
              rel="noopener noreferrer"
            >
              {grant.clientName}
            </a>
          ) : (
            grant.clientName
          )}
        </div>
        <div className="connected-app-meta">
          {uiText("연결", "Connected")} <span className="mono">{formatTimestamp(grant.grantedAt)}</span>
          {" · "}
          {uiText("마지막 사용", "Last used")}{" "}
          {grant.lastUsedAt ? (
            <span className="mono">{formatTimestamp(grant.lastUsedAt)}</span>
          ) : (
            uiText("기록 없음", "never")
          )}
        </div>
      </div>
      <button type="button" className="btn btn-ghost" onClick={() => setConfirming(true)}>
        {uiText("연결 끊기", "Disconnect")}
      </button>

      {confirming && (
        <div className="confirm-overlay" onMouseDown={close}>
          <div
            className="confirm-dialog"
            role="dialog"
            aria-modal="true"
            aria-label={uiText(`${grant.clientName} 연결 끊기`, `Disconnect ${grant.clientName}`)}
            onMouseDown={(e) => e.stopPropagation()}
            onKeyDown={(e) => {
              if (e.key === "Escape") close();
            }}
          >
            <h4 className="confirm-title">
              {uiText(`${grant.clientName} 연결을 끊을까요?`, `Disconnect ${grant.clientName}?`)}
            </h4>
            <p className="confirm-copy">
              {uiText(
                "이 앱은 더 이상 whycode에 질문할 수 없습니다. 다시 쓰려면 앱에서 연결을 다시 시작하면 됩니다.",
                "This app can no longer ask whycode. To use it again, start the connection from the app.",
              )}
            </p>

            {revoke.isError && (
              <InlineError style={{ marginTop: 12 }}>
                {uiText(
                  "연결을 끊지 못했어요. 잠시 후 다시 시도해 주세요.",
                  "Couldn't disconnect. Try again in a moment.",
                )}
              </InlineError>
            )}

            <div className="confirm-actions">
              <button
                type="button"
                className="btn btn-ghost"
                onClick={close}
                disabled={revoke.isPending}
              >
                {uiText("취소", "Cancel")}
              </button>
              <button
                type="button"
                className="btn btn-danger"
                autoFocus
                onClick={() =>
                  revoke.mutate(grant.id, { onSuccess: () => setConfirming(false) })
                }
                disabled={revoke.isPending}
              >
                {revoke.isPending ? uiText("끊는 중…", "Disconnecting…") : uiText("연결 끊기", "Disconnect")}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
