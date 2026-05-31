import { useEffect, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";

import { exchangeGitHubCode } from "@/api/auth";
import { StatusView } from "@/components/StatusView";
import { useAuth } from "@/auth/AuthProvider";
import { tokenStorage } from "@/auth/tokenStorage";

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

    if (!code) {
      setError("GitHub로부터 code를 받지 못했습니다.");
      return;
    }

    (async () => {
      try {
        const tokens = await exchangeGitHubCode({ code, state, installationId });
        tokenStorage.set(tokens);
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
            onClick={() => navigate("/login", { replace: true })}
          >
            로그인으로 돌아가기
          </button>
        }
        fullPage
      />
    );
  }

  return <StatusView tone="loading" description="GitHub 인증 처리 중…" fullPage />;
}
