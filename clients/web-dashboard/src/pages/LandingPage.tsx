import { FeatureSections } from "@/components/landing/FeatureSections";
import { FinalCtaSection } from "@/components/landing/FinalCtaSection";
import { HowItWorksSection } from "@/components/landing/HowItWorksSection";
import { LandingFooter } from "@/components/landing/LandingFooter";
import { LandingHeader } from "@/components/landing/LandingHeader";
import { LandingHero } from "@/components/landing/LandingHero";
import { ProblemSection } from "@/components/landing/ProblemSection";
import { UseCasesSection } from "@/components/landing/UseCasesSection";

// 제품 소개(랜딩) 페이지. 인증 가드 없는 공개 라우트이며, 현재는 히어로·문제 정의·작동 방식·기능 3섹션·유스케이스·최종 CTA·푸터까지 있다.
export function LandingPage() {
  return (
    <div className="lp">
      <LandingHeader />
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
