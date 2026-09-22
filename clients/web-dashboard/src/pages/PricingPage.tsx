import { LegalLayout } from "@/components/landing/LegalLayout";
import { PricingBody } from "@/components/landing/PricingBody";

// 요금 — 공개 라우트(/pricing). Paddle 라이브 계정 심사(도메인 리뷰)가 메뉴에서 찾을 수 있는
// 가격 안내를 요구해 만들었다(docs/billing.md). 얇은 진입점: 셸(LegalLayout)에 제목·요약만
// 넘기고 비교표·CTA는 PricingBody가 갖는다. 조항 문서가 아니라 effectiveDate는 없다.
export function PricingPage() {
  return (
    <LegalLayout
      title={{ ko: "요금", en: "Pricing" }}
      summary={{
        ko: "whycode는 무료로 시작할 수 있습니다. 프로젝트·소스를 늘리거나 질의를 제한 없이 쓰려면 Pro로 전환하세요.",
        en: "whycode is free to start. Switch to Pro to add more projects and sources, or to query without limits.",
      }}
    >
      <PricingBody />
    </LegalLayout>
  );
}
