import { Link } from "react-router-dom";
import axios from "axios";

import { BusyLabel } from "@/components/ui/BusyLabel";
import { InlineError } from "@/components/ui/InlineError";
import { StatusView } from "@/components/StatusView";
import { useOAuthConsentDecision, useOAuthConsentPreview } from "@/hooks/useOAuthConsent";
import { isHttpUrl } from "@/lib/url";
import { PATHS } from "@/routes";

// scope 식별자 → 사용자에게 보여줄 설명. 모르는 scope는 문자열 그대로 보여준다
// (새 scope가 backend에 먼저 생겨도 화면이 깨지지 않게).
const SCOPE_DESCRIPTIONS: Record<string, string> = {
  "mcp:query": "내 whycode 프로젝트에 질문하고 답을 받습니다(코드 변경 이유·의사결정 맥락).",
};

function previewErrorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    // 응답이 없거나(네트워크 단절) 5xx면 요청이 아니라 서버 쪽 문제다 — "앱에서 다시 시작하라"고
    // 안내하면 사용자가 엉뚱한 곳을 고치게 된다.
    const status = error.response?.status;
    if (!error.response || (status !== undefined && status >= 500)) {
      return "서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.";
    }
    const data = error.response.data as { message?: unknown } | undefined;
    if (typeof data?.message === "string") return data.message;
  }
  return "요청이 올바르지 않습니다. 앱에서 연결을 다시 시작해 주세요.";
}

// client_uri는 MCP 클라이언트가 등록한 값이라 형식이 보장되지 않는다 — 파싱에 실패하면
// 원문을 그대로 보여준다.
function hostOf(url: string): string {
  try {
    return new URL(url).host;
  } catch {
    return url;
  }
}

// 로그인·약관 동의를 마친 사용자에게 실제 미리보기를 물어 보여주는 카드.
// query는 window.location.search에서 ?를 뗀 원본 문자열 그대로 받는다(가공 금지 — backend가
// 이 문자열의 해시로 티켓을 검증한다).
export function OAuthConsentCard({ query }: { query: string }) {
  const previewQuery = useOAuthConsentPreview(query);
  const decision = useOAuthConsentDecision();

  if (previewQuery.isLoading) {
    return <StatusView tone="loading" description="요청 정보를 불러오는 중…" fullPage />;
  }

  if (previewQuery.isError) {
    // redirect_uri 검증에 실패한 요청이라 어디로도 리다이렉트하지 않는다 — 신뢰할 수 없는
    // 주소로 사용자를 보낼 수는 없다.
    return (
      <StatusView
        tone="error"
        title="연결 요청을 처리할 수 없어요"
        description={previewErrorMessage(previewQuery.error)}
        action={
          <Link className="btn btn-primary" to={PATHS.root}>
            앱 열기
          </Link>
        }
        fullPage
      />
    );
  }

  const preview = previewQuery.data;
  // isLoading/isError를 통과하면 항상 존재하지만, useQuery의 data 타입 자체는 undefined를
  // 배제하지 않는다 — 타입 좁히기용 가드.
  if (!preview) return null;

  return (
    <div className="oauth-consent">
      <div className="oauth-consent-card">
        <h1 className="oauth-consent-title">
          {preview.clientName}이(가) whycode 연결을 요청합니다
        </h1>
        {preview.clientUri && isHttpUrl(preview.clientUri) && (
          <a
            className="oauth-consent-client-link"
            href={preview.clientUri}
            target="_blank"
            rel="noopener noreferrer"
          >
            {hostOf(preview.clientUri)}
          </a>
        )}

        <div className="oauth-consent-section">
          <h2 className="oauth-consent-section-title">허용하면 이 앱이 할 수 있는 것</h2>
          <ul className="oauth-consent-scopes">
            {preview.scopes.map((scope) => (
              <li key={scope}>{SCOPE_DESCRIPTIONS[scope] ?? scope}</li>
            ))}
          </ul>
        </div>

        <div className="oauth-consent-section">
          <p className="oauth-consent-redirect">돌아갈 주소: {preview.redirectHost}</p>
          {preview.loopback && (
            <p className="oauth-consent-loopback-warning">
              이 컴퓨터에서 실행 중인 프로그램(localhost)으로 돌아갑니다. 지금 이 연결을
              시작한 앱이 아니라면 거부하세요.
            </p>
          )}
        </div>

        {decision.isError && (
          <InlineError style={{ marginBottom: 12 }}>
            처리에 실패했어요. 잠시 후 다시 시도해 주세요.
          </InlineError>
        )}

        <div className="oauth-consent-actions">
          <button
            type="button"
            className="btn btn-secondary btn-lg"
            disabled={decision.isPending}
            onClick={() => decision.mutate({ query, approved: false })}
          >
            <BusyLabel busy={decision.isPending} label="거부" busyLabel="이동 중…" />
          </button>
          <button
            type="button"
            className="btn btn-primary btn-lg"
            disabled={decision.isPending}
            onClick={() => decision.mutate({ query, approved: true })}
          >
            <BusyLabel busy={decision.isPending} label="허용" busyLabel="이동 중…" />
          </button>
        </div>

        <p className="oauth-consent-footnote">
          연결은 언제든 계정 페이지의 연결된 앱에서 끊을 수 있습니다.
        </p>
      </div>
    </div>
  );
}
