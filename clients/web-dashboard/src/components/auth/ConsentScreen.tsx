import { useState } from "react";

import { InlineError } from "@/components/ui/InlineError";
import { postConsent } from "@/api/auth";
import { useAuth } from "@/auth/AuthProvider";
import { PATHS } from "@/routes";

// GitHub OAuth 콜백 하나로 가입·로그인이 함께 처리돼서, 이 화면 이전에는 우리 서비스 약관에
// 동의를 받는 지점이 없었다. 로그인 후 서버가 requiresConsent로 실제 동의 여부를 판단하고,
// true인 동안 AuthGate가 children 대신 이 화면을 렌더해 막아선다(전용 라우트 없음).
export function ConsentScreen() {
  const { refresh, logout } = useAuth();
  const [agreed, setAgreed] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleAgree = async () => {
    setSubmitting(true);
    setError(null);
    try {
      await postConsent();
      // 재조회하면 requiresConsent가 false로 바뀌어 있고, 이 컴포넌트를 렌더하던
      // AuthGate가 그 값을 보고 자동으로 다음 화면으로 넘어간다.
      await refresh();
    } catch {
      setError("동의 처리에 실패했어요. 잠시 후 다시 시도해 주세요.");
      setSubmitting(false);
    }
  };

  const handleLogout = async () => {
    setError(null);
    try {
      await logout();
      // AuthProvider가 상태를 unauthenticated로 바꾸면 AuthGate가 랜딩으로 보낸다.
    } catch {
      setError("로그아웃에 실패했어요. 잠시 후 다시 시도해 주세요.");
    }
  };

  return (
    <div
      style={{
        minHeight: "100vh",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        padding: 32,
      }}
    >
      <div
        style={{
          width: "100%",
          maxWidth: 440,
          padding: 28,
          borderRadius: "var(--r-lg)",
          border: "1px solid var(--border)",
          background: "var(--surface)",
        }}
      >
        <h1
          style={{
            fontSize: 20,
            fontWeight: 600,
            letterSpacing: "-0.02em",
            margin: "0 0 8px",
          }}
        >
          약관 동의가 필요해요
        </h1>
        <p
          style={{
            fontSize: 13,
            color: "var(--fg-muted)",
            lineHeight: 1.6,
            margin: "0 0 24px",
          }}
        >
          whycode를 계속 이용하시려면{" "}
          {/* 동의 화면 상태(체크박스)를 잃지 않도록 새 탭에서 연다 */}
          <a
            href={PATHS.terms}
            target="_blank"
            rel="noopener noreferrer"
            style={{ color: "var(--accent-ink)", textDecoration: "underline" }}
          >
            이용약관
          </a>
          과{" "}
          <a
            href={PATHS.privacy}
            target="_blank"
            rel="noopener noreferrer"
            style={{ color: "var(--accent-ink)", textDecoration: "underline" }}
          >
            개인정보처리방침
          </a>
          에 동의해야 합니다.
        </p>

        <label
          style={{
            display: "flex",
            alignItems: "flex-start",
            gap: 8,
            fontSize: 13,
            cursor: "pointer",
            marginBottom: 20,
          }}
        >
          <input
            type="checkbox"
            checked={agreed}
            onChange={(e) => setAgreed(e.target.checked)}
            style={{ marginTop: 2 }}
          />
          <span>이용약관과 개인정보처리방침을 읽었으며 동의합니다.</span>
        </label>

        {error && <InlineError style={{ marginBottom: 12 }}>{error}</InlineError>}

        <button
          type="button"
          className="btn btn-primary btn-lg"
          style={{ width: "100%" }}
          disabled={!agreed || submitting}
          onClick={handleAgree}
        >
          {submitting ? "처리 중…" : "동의하고 계속하기"}
        </button>

        <div style={{ textAlign: "center", marginTop: 16 }}>
          <button
            type="button"
            className="btn btn-ghost"
            style={{ fontSize: 12 }}
            onClick={handleLogout}
          >
            동의하지 않고 로그아웃
          </button>
        </div>
      </div>
    </div>
  );
}
