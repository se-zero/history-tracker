import { useEffect } from "react";
import { useLocation } from "react-router-dom";

import { LandingFooter } from "@/components/landing/LandingFooter";
import { LandingHeader } from "@/components/landing/LandingHeader";
import { useLandingTheme } from "@/components/landing/useLandingTheme";

// ⚠️ 배포 전 실제 값으로 교체할 자리 — 운영 주체명·문의처. 지금 값은 자리표시자다.
//    법인/사업자로 운영한다면 상호·대표자·주소·사업자등록번호도 여기서 채운다.
export const LEGAL_OPERATOR = "History Tracker 팀";
export const LEGAL_CONTACT_EMAIL = "contact@history-tracker.example";
export const LEGAL_CONTACT_URL = "https://github.com/se-zero/history-tracker/issues";

// 약관·개인정보처리방침 공통 셸. 랜딩과 같은 `.lp` 스코프를 써서 헤더·푸터·토큰(다크/라이트
// 토글 포함)을 그대로 재사용한다 — 공개 페이지끼리 크롬이 끊기면 이탈 지점처럼 보인다.
// 본문은 산문 한 컬럼(legal.css)이라 랜딩의 섹션 리듬을 따르지 않는다.
export function LegalLayout({
  title,
  effectiveDate,
  summary,
  children,
}: {
  title: string;
  effectiveDate: string;
  summary: string;
  children: React.ReactNode;
}) {
  const { theme, toggleTheme } = useLandingTheme();

  // 랜딩에서 스크롤한 상태로 푸터 링크를 누르면 문서 중간이 첫 화면이 된다.
  // 단 앵커(#slack 등)로 들어온 경우는 그 조항이 목적지다 — 외부 앱 심사에 제출하는 URL이라
  // 최상단으로 되돌리면 안 된다. SPA는 초기 렌더 전에 브라우저의 기본 앵커 이동이 끝나
  // 대상이 없으므로 여기서 직접 스크롤한다.
  const { hash } = useLocation();
  useEffect(() => {
    if (!hash) {
      window.scrollTo(0, 0);
      return;
    }
    document.getElementById(decodeURIComponent(hash.slice(1)))?.scrollIntoView();
  }, [hash]);

  return (
    <div className="lp" data-theme={theme}>
      <LandingHeader theme={theme} onToggleTheme={toggleTheme} />
      <main className="lp-legal">
        <article className="lp-legal-inner">
          <header className="lp-legal-head">
            <h1 className="lp-legal-title">{title}</h1>
            {/* 날짜만 모노 — "시행일"은 한글이라 본문 서체다(DESIGN.md 모노 스코프 규칙) */}
            <p className="lp-legal-meta">
              시행일 <time className="lp-legal-date">{effectiveDate}</time>
            </p>
            <p className="lp-legal-summary">{summary}</p>
          </header>
          {children}
        </article>
      </main>
      <LandingFooter />
    </div>
  );
}

// 조항 하나 — 번호는 CSS 카운터가 아니라 명시적으로 넘긴다(문서에서 "제3조"로 인용되므로
// 마크업 순서가 바뀌어도 번호가 흔들리면 안 된다). id는 외부에서 앵커로 지목하는 조항에만 준다.
export function LegalSection({
  id,
  index,
  heading,
  children,
}: {
  id?: string;
  index: number;
  heading: string;
  children: React.ReactNode;
}) {
  return (
    <section className="lp-legal-section" id={id}>
      <h2 className="lp-legal-heading">
        <span className="lp-legal-num">제{index}조</span> {heading}
      </h2>
      {children}
    </section>
  );
}

// 서비스별 연동 데이터 블록 — GitHub·Slack·Atlassian 앱 심사가 각자 자기 앵커(#github 등)를
// 제출 URL로 쓴다. 심사자가 그 서비스에 해당하는 내용만 바로 보게 하는 것이 목적이다.
export function LegalSourceBlock({
  id,
  name,
  children,
}: {
  id: string;
  name: string;
  children: React.ReactNode;
}) {
  return (
    <section className="lp-legal-source" id={id}>
      <h3 className="lp-legal-source-name">{name}</h3>
      {children}
    </section>
  );
}

// 블록 안의 한 항목(요청 권한 / 수집하는 정보 / 이용 목적 / 삭제)
export function LegalSourceRow({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}) {
  return (
    <div className="lp-legal-source-row">
      <div className="lp-legal-source-label">{label}</div>
      <div className="lp-legal-source-value">{children}</div>
    </div>
  );
}
