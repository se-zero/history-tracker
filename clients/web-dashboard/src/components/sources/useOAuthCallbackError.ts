import { useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";

const PROVIDER_LABELS: Record<string, string> = {
  slack: "Slack",
  jira: "Jira",
};

const ERROR_MESSAGES: Record<string, string> = {
  invalid_state: "연결 요청이 만료되었거나 올바르지 않아요. 다시 시도해 주세요.",
  already_connected: "이미 연결된 프로젝트예요.",
  connect_failed: "연결에 실패했어요. 잠시 후 다시 시도해 주세요.",
};

function providerLabel(provider: string | null): string {
  if (!provider) return "연동";
  return PROVIDER_LABELS[provider] ?? provider;
}

function errorMessage(errorCode: string, provider: string | null): string {
  if (errorCode === "access_denied") {
    return `${providerLabel(provider)} 연결 요청을 취소했어요.`;
  }
  return ERROR_MESSAGES[errorCode] ?? "연결 중 문제가 발생했어요.";
}

export interface OAuthCallbackError {
  provider: string | null;
  message: string;
}

// OAuth 콜백이 붙여주는 ?connected=/?error=/?provider=/?restored= 쿼리를 처리한다.
// 성공(connected)은 행/타일 상태가 이미 보여주므로 중복 안내하지 않고 URL만 정리하며, 실패
// (error)만 값으로 반환해 호출자(SourcesPage)가 해당 소스 카드/타일 자리에서 보여주게 한다.
// 서버 상태가 아니라 URL 쿼리 상태라 hooks/(React Query 레이어) 대신 sources에 colocate한다.
export function useOAuthCallbackError(): OAuthCallbackError | null {
  const [searchParams, setSearchParams] = useSearchParams();
  // 마운트 시점 값을 한 번만 캡처한다 — effect가 쿼리를 지우면서 유발하는 리렌더에서
  // searchParams를 다시 읽으면 모두 null이 되어 에러가 뜨자마자 사라진다.
  const [result] = useState(() => ({
    connected: searchParams.get("connected"),
    error: searchParams.get("error"),
    provider: searchParams.get("provider"),
  }));

  useEffect(() => {
    if (!result.connected && !result.error) return;
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        next.delete("connected");
        next.delete("error");
        next.delete("provider");
        next.delete("restored");
        return next;
      },
      { replace: true },
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (!result.error) return null;

  return { provider: result.provider, message: errorMessage(result.error, result.provider) };
}
