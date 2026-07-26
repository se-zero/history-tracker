import { useState } from "react";
import { flushSync } from "react-dom";

import { FeatureSections } from "@/components/landing/FeatureSections";
import { FinalCtaSection } from "@/components/landing/FinalCtaSection";
import { HowItWorksSection } from "@/components/landing/HowItWorksSection";
import { LandingFooter } from "@/components/landing/LandingFooter";
import { LandingHeader } from "@/components/landing/LandingHeader";
import { LandingHero } from "@/components/landing/LandingHero";
import { ProblemSection } from "@/components/landing/ProblemSection";
import { UseCasesSection } from "@/components/landing/UseCasesSection";

export type LandingTheme = "dark" | "light";

// 제품 소개(랜딩) 페이지. 인증 가드 없는 공개 라우트이며, 현재는 히어로·문제 정의·작동 방식·기능 3섹션·유스케이스·최종 CTA·푸터까지 있다.
// 테마: 랜딩 자체 다크/라이트 토글(기본 다크). 앱 ThemeProvider(documentElement의
// data-theme)와는 독립이다 — data-theme을 이 래퍼에만 붙이고, 토큰 오버라이드도
// .lp[data-theme="light"] 스코프(tokens.css)라 앱 라우트에 부작용이 없고 언마운트 정리도
// 필요 없다. 상태는 React useState뿐 — localStorage/sessionStorage는 쓰지 않는다.
// 토글은 래퍼의 attribute만 바꾸므로 자식 컴포넌트는 리마운트되지 않는다
// (IO 안무 상태·히어로 로드 시퀀스가 다시 돌지 않는다).
//
// 전환 크로스페이드는 View Transition API(document.startViewTransition)로 처리한다.
// 이전의 "래퍼 임시 클래스 + 전역 색 transition" 방식은 폐기 — 수천 요소에 transition을
// 일괄 부착하면 스타일 재계산 잼 중 클래스 제거가 미완주 transition을 취소하면서 Chromium이
// 해당 서브트리를 시작값(다크)에 동결시키는 버그가 실측됐다. View Transition은 스냅샷 합성
// 기반이라 요소별 transition이 없어 이 계열을 구조적으로 회피하고, 그래프 모션·레이아웃도
// 흔들리지 않는다(기본 크로스페이드 그대로, 커스텀 ::view-transition 규칙 없음).
export function LandingPage() {
  const [theme, setTheme] = useState<LandingTheme>("dark");

  const toggleTheme = () => {
    const next: LandingTheme = theme === "dark" ? "light" : "dark";
    // reduced-motion 또는 미지원 브라우저: 크로스페이드 생략, 즉시 점프(DESIGN.md 모션 규칙).
    if (
      window.matchMedia("(prefers-reduced-motion: reduce)").matches ||
      !document.startViewTransition
    ) {
      setTheme(next);
      return;
    }
    document.startViewTransition(() => {
      // flushSync — 스냅샷 콜백이 끝나는 시점에 새 테마 DOM이 커밋되어 있어야 한다
      // (비동기 배칭에 맡기면 "변경 후" 스냅샷이 옛 테마를 찍는다).
      flushSync(() => setTheme(next));
    });
  };

  return (
    <div className="lp" data-theme={theme}>
      <LandingHeader theme={theme} onToggleTheme={toggleTheme} />
      <LandingHero />
      <ProblemSection />
      <HowItWorksSection />
      <FeatureSections />
      <UseCasesSection />
      <FinalCtaSection />
      <LandingFooter />
    </div>
  );
}
