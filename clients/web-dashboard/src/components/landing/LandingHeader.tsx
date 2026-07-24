import { useEffect, useState } from "react";
import { Link } from "react-router-dom";

// 랜딩 헤더 — 로고/워드마크와 로그인 링크만. 섹션 내비는 대상 섹션이 생긴 뒤에 붙인다.
// 스크롤 시 하단 헤어라인 + backdrop-blur를 붙이기 위해 스크롤 위치를 추적한다.
export function LandingHeader() {
  const [isScrolled, setIsScrolled] = useState(false);

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
        <Link className="lp-login-link" to="/login">
          로그인
        </Link>
      </div>
    </header>
  );
}
