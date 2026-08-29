import { useEffect, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";

import { exchangeGitHubCode } from "@/api/auth";
import { StatusView } from "@/components/StatusView";
import { useAuth } from "@/auth/AuthProvider";
import { PATHS } from "@/routes";

export function AuthCallbackPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const { refresh } = useAuth();
  const [error, setError] = useState<string | null>(null);
  const ran = useRef(false);

  useEffect(() => {
    if (ran.current) return;
    ran.current = true;

    const code = params.get("code");
    const state = params.get("state");
    const installationId = params.get("installation_id");
    const oauthError = params.get("error");

    // 사용자가 GitHub 인증 화면에서 취소한 경우 등 — 에러 화면 없이 조용히 랜딩으로 돌려보낸다.
    if (oauthError || !code) {
      navigate(PATHS.landing, { replace: true });
      return;
    }

    (async () => {
      try {
        await exchangeGitHubCode({ code, state, installationId });
        await refresh();
        navigate("/", { replace: true });
      } catch (err) {
        console.error("auth callback failed", err);
        setError("로그인 처리에 실패했습니다. 다시 시도해 주세요.");
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (error) {
    return (
      <StatusView
        tone="error"
        title="로그인에 실패했어요"
        description={error}
        action={
          <button
            className="btn btn-primary"
            onClick={() => navigate(PATHS.landing, { replace: true })}
          >
            랜딩으로 돌아가기
          </button>
        }
        fullPage
      />
    );
  }

  return <StatusView tone="loading" description="GitHub 인증 처리 중…" fullPage />;
}
