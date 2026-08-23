import { Link } from "react-router-dom";

import { GITHUB_AUTHORIZE_URL } from "@/api/auth";
import { useAuth } from "@/auth/AuthProvider";
import { PATHS } from "@/routes";

// 랜딩 푸터 — 페이지의 마지막 섹션. 제품명 텍스트 / 약관·개인정보 / GitHub / 로그인 링크를
// 두는 미니멀 한 줄. 약관 링크가 푸터에만 있으므로(법적 고지의 관례적 위치) 랜딩뿐 아니라
// 약관·개인정보 페이지 자신도 이 푸터를 쓴다 — 두 문서가 서로를 오갈 수 있어야 한다.
// 로그인 링크는 헤더(LandingHeader)와 같은 인증 상태 분기를 쓴다 —
// 비로그인이면 GitHub OAuth 직행, 로그인 상태면 제품으로 바로 이동.
export function LandingFooter() {
  const { status } = useAuth();

  return (
    <footer className="lp-footer">
      <div className="lp-footer-inner">
        <span className="lp-footer-brand">whycode</span>
        <div className="lp-footer-links">
          <Link className="lp-footer-link" to={PATHS.terms}>
            이용약관
          </Link>
          <Link className="lp-footer-link" to={PATHS.privacy}>
            개인정보처리방침
          </Link>
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
              whycode 열기
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
