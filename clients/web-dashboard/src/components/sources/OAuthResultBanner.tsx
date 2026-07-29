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

// OAuth 콜백이 붙여주는 ?connected=/?error=/?provider= 쿼리를 배너로 보여준 뒤 URL에서 지운다.
// 콜백은 전체 페이지 이동(302)이라 React Query 캐시가 비어 새로고침처럼 다시 fetch되므로
// 별도 invalidate 없이도 연결 상태가 최신으로 반영된다.
export function OAuthResultBanner() {
  const [searchParams, setSearchParams] = useSearchParams();
  // 마운트 시점 값을 한 번만 캡처한다 — effect가 쿼리를 지우면서 유발하는 리렌더에서
  // searchParams를 다시 읽으면 모두 null이 되어 배너가 뜨자마자 사라진다.
  const [result] = useState(() => ({
    connected: searchParams.get("connected"),
    error: searchParams.get("error"),
    provider: searchParams.get("provider"),
    restored: searchParams.get("restored") === "true",
  }));
  const [dismissed, setDismissed] = useState(false);

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

  if (dismissed || (!result.connected && !result.error)) return null;

  const tone = result.error ? "error" : "success";
  // Jira는 동의 직후 아직 미확정(사이트·프로젝트 선택 전)이므로 "완료"가 아니라 다음 단계를 안내한다.
  // 단, 갱신 실패로 끊겼던 연동이 재동의로 자동 복원된 경우(restored=true)는 선택 단계 없이 곧바로 완료다.
  const message = result.error
    ? errorMessage(result.error, result.provider)
    : result.connected === "jira" && !result.restored
      ? "Jira 연동을 계속하려면 사이트와 프로젝트를 선택하세요."
      : result.connected === "jira"
        ? "Jira 연동이 복원됐어요."
        : `${providerLabel(result.connected)} 연동이 완료됐어요.`;

  return (
    <div className={`oauth-result-banner ${tone}`} role="status">
      <span>{message}</span>
      <button onClick={() => setDismissed(true)} aria-label="닫기">
        ×
      </button>
    </div>
  );
}
