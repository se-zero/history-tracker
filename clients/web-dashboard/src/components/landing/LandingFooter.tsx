import { Link } from "react-router-dom";

// 랜딩 푸터 — 페이지의 마지막 섹션. 제품명 텍스트 / GitHub 링크 / 로그인 링크만 두는
// 미니멀 한 줄. 로그인 링크는 헤더(LandingHeader)와 같은 방식(react-router Link)으로 처리한다.
export function LandingFooter() {
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
          <Link className="lp-footer-link" to="/login">
            로그인
          </Link>
        </div>
      </div>
    </footer>
  );
}
