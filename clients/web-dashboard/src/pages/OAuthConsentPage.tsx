import { useEffect, useRef, useState } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";

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
  // GitHub 로그인으로 보낸 뒤 사용자가 뒤로가기로 돌아오면 페이지가 bfcache로 복원된다 — status는
  // 그대로 unauthenticated라 아래 효과가 다시 돌지 않아 "이동하는 중…"에 갇힌다. 복원을 감지하면
  // 자동으로 다시 튕기지 않고(뒤로가기가 함정이 된다) 계속할지 그만둘지 고르게 한다.
  const [restoredFromLogin, setRestoredFromLogin] = useState(false);
  useEffect(() => {
    const handlePageShow = (e: PageTransitionEvent) => {
      if (e.persisted) setRestoredFromLogin(true);
    };
    window.addEventListener("pageshow", handlePageShow);
    return () => window.removeEventListener("pageshow", handlePageShow);
  }, []);

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
    if (restoredFromLogin) {
      return (
        <StatusView
          tone="info"
          title="로그인이 필요해요"
          description="앱 연결을 계속하려면 GitHub으로 로그인해 주세요."
          action={
            <>
              <a
                className="btn btn-primary"
                href={GITHUB_AUTHORIZE_URL}
                onClick={() => saveReturnPath(location.pathname + location.search)}
              >
                GitHub으로 계속
              </a>
              <Link className="btn btn-ghost" to={PATHS.landing}>
                취소
              </Link>
            </>
          }
          fullPage
        />
      );
    }
    return <StatusView tone="loading" description="로그인 화면으로 이동하는 중…" fullPage />;
  }
  // 현재 버전 약관에 동의하지 않은 사용자는 동의 화면을 먼저 통과해야 한다.
  // 동의하면 AuthProvider.refresh()가 requiresConsent를 바꾸고 이 페이지가 다음 단계로 넘어간다.
  if (user?.requiresConsent) {
    return <ConsentScreen />;
  }
  return <OAuthConsentCard query={location.search.slice(1)} />;
}
