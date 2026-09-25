import { useEffect, useRef } from "react";
import { useLocation, useNavigate } from "react-router-dom";

import { GITHUB_AUTHORIZE_URL } from "@/api/auth";
import { ConsentScreen } from "@/components/auth/ConsentScreen";
import { OAuthConsentCard } from "@/components/oauth/OAuthConsentCard";
import { StatusView } from "@/components/StatusView";
import { useAuth } from "@/auth/AuthProvider";
import { saveReturnPath } from "@/auth/returnPath";
import { PATHS } from "@/routes";

// MCP 클라이언트(Claude Code·Codex)가 /oauth2/authorize를 열면 backend는 로그인 세션이
// 없으므로(대시보드는 access 토큰을 메모리에만 둔다) 이 SPA 라우트로 원본 쿼리를 그대로 붙여
// 302한다. AuthGate 밖이라 로그인·약관 동의 상태를 이 페이지가 스스로 분기한다.
export function OAuthConsentPage() {
  const { status, user } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  // 이 마운트에서 인증된 적이 있으면 이후의 미인증 전환은 로그아웃이다 — ConsentScreen의
  // 로그아웃이 GitHub 재로그인으로 튕기지 않게 AuthGate처럼 랜딩으로 보낸다.
  const wasAuthenticated = useRef(false);

  useEffect(() => {
    if (status === "authenticated") wasAuthenticated.current = true;
    if (status !== "unauthenticated") return;
    if (wasAuthenticated.current) {
      navigate(PATHS.landing, { replace: true });
      return;
    }
    saveReturnPath(location.pathname + location.search);
    window.location.href = GITHUB_AUTHORIZE_URL;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status]);

  if (status === "loading") {
    return <StatusView tone="loading" description="세션 확인 중…" fullPage />;
  }
  if (status === "unauthenticated") {
    return <StatusView tone="loading" description="로그인 화면으로 이동하는 중…" fullPage />;
  }
  // 현재 버전 약관에 동의하지 않은 사용자는 동의 화면을 먼저 통과해야 한다.
  // 동의하면 AuthProvider.refresh()가 requiresConsent를 바꾸고 이 페이지가 다음 단계로 넘어간다.
  if (user?.requiresConsent) {
    return <ConsentScreen />;
  }
  return <OAuthConsentCard query={location.search.slice(1)} />;
}
