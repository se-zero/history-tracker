import { useEffect, useState } from "react";
import { Link } from "react-router-dom";

import { InlineError } from "@/components/ui/InlineError";
import { useOAuthGrants, useRevokeOAuthGrant } from "@/hooks/useOAuthGrants";
import { formatTimestamp } from "@/lib/format";
import { isHttpUrl } from "@/lib/url";
import { PATHS } from "@/routes";
import type { OAuthGrant } from "@/types/api";

/**
 * MCP 클라이언트(Claude Code·Codex 등)가 `/oauth/consent`에서 승인받아 연결된 목록.
 *
 * 연동 해제(DisconnectIntegration)와 달리 그래프 데이터를 지우지 않는다 — refresh 토큰과
 * 인가만 철회한다. 이미 발급된 access 토큰은 backend 한계로 최대 1시간 더 유효해서, 그
 * 사실을 해제 확인 다이얼로그 문구에 그대로 알린다.
 */
export function ConnectedAppsCard() {
  const grantsQuery = useOAuthGrants();
  const grants = grantsQuery.data ?? [];

  return (
    <section className="source-card connected-apps-card">
      <div className="src-head">
        <div className="src-head-main">
          <h4>연결된 앱</h4>
          <div className="src-sub">
            Claude Code·Codex 같은 코딩 에이전트에서 whycode에 연결한 앱입니다.
          </div>
        </div>
      </div>

      {grantsQuery.isLoading && (
        <p className="connected-apps-empty">연결된 앱을 불러오는 중…</p>
      )}
      {grantsQuery.isError && (
        <InlineError>연결된 앱을 불러오지 못했어요.</InlineError>
      )}
      {!grantsQuery.isLoading && !grantsQuery.isError && grants.length === 0 && (
        <p className="connected-apps-empty">
          연결된 앱이 없습니다.{" "}
          <Link className="connected-app-link" to={PATHS.mcpSetup}>
            코딩 에이전트에서 쓰는 방법
          </Link>
        </p>
      )}

      {grants.length > 0 && (
        <div className="connected-apps-list">
          {grants.map((grant) => (
            <ConnectedAppRow grant={grant} key={grant.id} />
          ))}
        </div>
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
          연결 <span className="mono">{formatTimestamp(grant.grantedAt)}</span>
          {" · "}
          마지막 사용{" "}
          {grant.lastUsedAt ? (
            <span className="mono">{formatTimestamp(grant.lastUsedAt)}</span>
          ) : (
            "기록 없음"
          )}
        </div>
      </div>
      <button type="button" className="btn btn-ghost" onClick={() => setConfirming(true)}>
        연결 끊기
      </button>

      {confirming && (
        <div className="confirm-overlay" onMouseDown={close}>
          <div
            className="confirm-dialog"
            role="dialog"
            aria-modal="true"
            aria-label={`${grant.clientName} 연결 끊기`}
            onMouseDown={(e) => e.stopPropagation()}
            onKeyDown={(e) => {
              if (e.key === "Escape") close();
            }}
          >
            <h4 className="confirm-title">{grant.clientName} 연결을 끊을까요?</h4>
            <p className="confirm-copy">
              이 앱은 더 이상 whycode에 질문할 수 없습니다. 이미 발급된 접속 권한은 최대 1시간
              동안 유지될 수 있습니다. 다시 쓰려면 앱에서 연결을 다시 시작하면 됩니다.
            </p>

            {revoke.isError && (
              <InlineError style={{ marginTop: 12 }}>
                연결을 끊지 못했어요. 잠시 후 다시 시도해 주세요.
              </InlineError>
            )}

            <div className="confirm-actions">
              <button
                type="button"
                className="btn btn-ghost"
                onClick={close}
                disabled={revoke.isPending}
              >
                취소
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
                {revoke.isPending ? "끊는 중…" : "연결 끊기"}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
