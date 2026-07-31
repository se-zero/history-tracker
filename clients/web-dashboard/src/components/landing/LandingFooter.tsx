import { Link } from "react-router-dom";

import { GITHUB_AUTHORIZE_URL } from "@/api/auth";
import { useAuth } from "@/auth/AuthProvider";
import { PATHS } from "@/routes";

// 랜딩 푸터 — 페이지의 마지막 섹션. 제품명 텍스트 / GitHub 링크 / 로그인 링크만 두는
// 미니멀 한 줄. 로그인 링크는 헤더(LandingHeader)와 같은 인증 상태 분기를 쓴다 —
// 비로그인이면 GitHub OAuth 직행, 로그인 상태면 제품으로 바로 이동.
export function LandingFooter() {
  const { status } = useAuth();

  return (
    <footer className="lp-footer">
      <div className="lp-footer-inner">
        <span className="lp-footer-brand">History Tracker</span>
        <div className="lp-footer-links">
          <a
            className="lp-footer-link"
            href="https://github.com/se-zero/history-tracker"
            target="_blank"
            rel="noreferrer"
          >
            GitHub
          </a>
          {status === "authenticated" ? (
            <Link className="lp-footer-link" to={PATHS.root}>
              History Tracker 열기
            </Link>
          ) : (
            <a className="lp-footer-link" href={GITHUB_AUTHORIZE_URL}>
              로그인
            </a>
          )}
        </div>
      </div>
    </footer>
  );
}
