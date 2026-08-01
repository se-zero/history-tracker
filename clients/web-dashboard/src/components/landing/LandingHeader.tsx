import { useEffect, useState } from "react";
import { Link } from "react-router-dom";

import { GITHUB_AUTHORIZE_URL } from "@/api/auth";
import { useAuth } from "@/auth/AuthProvider";
import { PATHS } from "@/routes";
import type { LandingTheme } from "@/pages/LandingPage";

// 랜딩 헤더 — 로고/워드마크와 우측 액션(테마 토글 + 로그인 링크). 로그인 링크는 인증 상태로
// 분기한다(노션 방식) — 비로그인이면 히어로 CTA와 동일한 GitHub OAuth 직행, 로그인 상태면
// 제품으로 바로 이동. 섹션 내비는 대상 섹션이 생긴 뒤에 붙인다. 스크롤 시 하단 헤어라인 +
// backdrop-blur를 붙이기 위해 스크롤 위치를 추적한다. 테마 상태는 LandingPage가 소유한다
// (래퍼 data-theme) — 여기는 표시·토글만.
export function LandingHeader({
  theme,
  onToggleTheme,
}: {
  theme: LandingTheme;
  onToggleTheme: () => void;
}) {
  const [isScrolled, setIsScrolled] = useState(false);
  const { status } = useAuth();

  useEffect(() => {
    const handleScroll = () => setIsScrolled(window.scrollY > 8);
    handleScroll(); // 새로고침 시 스크롤이 남아 있을 수 있으므로 마운트 시 한 번 계산
    window.addEventListener("scroll", handleScroll, { passive: true });
    return () => window.removeEventListener("scroll", handleScroll);
  }, []);

  return (
    <header className={`lp-header${isScrolled ? " is-scrolled" : ""}`}>
      <div className="lp-header-inner">
        <Link className="lp-brand" to="/landing">
          <span className="lp-logo-mark" aria-hidden="true" />
          <span className="lp-wordmark">History Tracker</span>
        </Link>
        <div className="lp-header-actions">
          {/* 테마 토글 — 중립 스타일(앰버 금지: "라이브/실행/선택"이 아니다). 아이콘은
              전환될 대상 테마를 가리킨다(다크에서 해 = 라이트로 전환). */}
          <button
            type="button"
            className="lp-theme-toggle"
            aria-label={theme === "dark" ? "라이트 테마로 전환" : "다크 테마로 전환"}
            onClick={onToggleTheme}
          >
            {theme === "dark" ? <SunGlyph /> : <MoonGlyph />}
          </button>
          {status === "authenticated" ? (
            <Link className="lp-login-link" to={PATHS.root}>
              History Tracker 열기
            </Link>
          ) : (
            <a className="lp-login-link" href={GITHUB_AUTHORIZE_URL}>
              로그인
            </a>
          )}
        </div>
      </div>
    </header>
  );
}

// 해/달 아이콘 — 인라인 SVG(stroke, currentColor). 외부 아이콘 셋을 들이지 않는다.
function SunGlyph() {
  return (
    <svg
      width={16}
      height={16}
      viewBox="0 0 16 16"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.5}
      strokeLinecap="round"
      aria-hidden="true"
    >
      <circle cx="8" cy="8" r="3.2" />
      <path d="M8 1.2v1.6M8 13.2v1.6M1.2 8h1.6M13.2 8h1.6M3.2 3.2l1.13 1.13M11.67 11.67l1.13 1.13M12.8 3.2l-1.13 1.13M4.33 11.67L3.2 12.8" />
    </svg>
  );
}

function MoonGlyph() {
  return (
    <svg
      width={16}
      height={16}
      viewBox="0 0 16 16"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.5}
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="M13.4 9.6A5.8 5.8 0 0 1 6.4 2.6a5.8 5.8 0 1 0 7 7Z" />
    </svg>
  );
}
