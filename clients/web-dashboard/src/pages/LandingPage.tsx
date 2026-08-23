import { FeatureSections } from "@/components/landing/FeatureSections";
import { FinalCtaSection } from "@/components/landing/FinalCtaSection";
import { HowItWorksSection } from "@/components/landing/HowItWorksSection";
import { LandingFooter } from "@/components/landing/LandingFooter";
import { LandingHeader } from "@/components/landing/LandingHeader";
import { LandingHero } from "@/components/landing/LandingHero";
import { ProblemSection } from "@/components/landing/ProblemSection";
import { UseCasesSection } from "@/components/landing/UseCasesSection";
import { useLandingTheme } from "@/components/landing/useLandingTheme";

// 제품 소개(랜딩) 페이지. 인증 가드 없는 공개 라우트이며, 현재는 히어로·문제 정의·작동 방식·기능 3섹션·유스케이스·최종 CTA·푸터까지 있다.
// 테마(랜딩 자체 다크/라이트 토글, 기본 다크)는 useLandingTheme이 소유한다 — 약관·개인정보
// 처리방침 페이지도 같은 훅을 쓰므로 전환 방식의 사유는 그쪽 주석에 모아 뒀다.
export function LandingPage() {
  const { theme, toggleTheme } = useLandingTheme();

  return (
    <div className="lp" data-theme={theme}>
      <LandingHeader theme={theme} onToggleTheme={toggleTheme} />
      <LandingHero theme={theme} />
      <ProblemSection />
      <HowItWorksSection />
      <FeatureSections />
      <UseCasesSection />
      <FinalCtaSection />
      <LandingFooter />
    </div>
  );
}
