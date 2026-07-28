import { useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";

const PROVIDER_LABELS: Record<string, string> = {
  slack: "Slack",
};

const ERROR_MESSAGES: Record<string, string> = {
  invalid_state: "연결 요청이 만료되었거나 올바르지 않아요. 다시 시도해 주세요.",
  access_denied: "Slack 연결 요청을 취소했어요.",
  already_connected: "이미 연결된 프로젝트예요.",
  connect_failed: "연결에 실패했어요. 잠시 후 다시 시도해 주세요.",
};

// OAuth 콜백이 붙여주는 ?connected=/?error= 쿼리를 배너로 보여준 뒤 URL에서 지운다.
// 콜백은 전체 페이지 이동(302)이라 React Query 캐시가 비어 새로고침처럼 다시 fetch되므로
// 별도 invalidate 없이도 연결 상태가 최신으로 반영된다.
export function OAuthResultBanner() {
  const [searchParams, setSearchParams] = useSearchParams();
  // 마운트 시점 값을 한 번만 캡처한다 — effect가 쿼리를 지우면서 유발하는 리렌더에서
  // searchParams를 다시 읽으면 둘 다 null이 되어 배너가 뜨자마자 사라진다.
  const [result] = useState(() => ({
    connected: searchParams.get("connected"),
    error: searchParams.get("error"),
  }));
  const [dismissed, setDismissed] = useState(false);

  useEffect(() => {
    if (!result.connected && !result.error) return;
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        next.delete("connected");
        next.delete("error");
        return next;
      },
      { replace: true },
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (dismissed || (!result.connected && !result.error)) return null;

  const tone = result.error ? "error" : "success";
  const message = result.error
    ? (ERROR_MESSAGES[result.error] ?? "연결 중 문제가 발생했어요.")
    : `${PROVIDER_LABELS[result.connected!] ?? result.connected} 연동이 완료됐어요.`;

  return (
    <div className={`oauth-result-banner ${tone}`} role="status">
      <span>{message}</span>
      <button onClick={() => setDismissed(true)} aria-label="닫기">
        ×
      </button>
    </div>
  );
}
