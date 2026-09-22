import { TERMS_EFFECTIVE_DATE, LegalLayout } from "@/components/landing/LegalLayout";
import { RefundBody } from "@/components/landing/legal/RefundBody";

// 환불정책 — 공개 라우트(/refund). 초안이며 법률 검토 전이다.
// 얇은 진입점: 셸(LegalLayout)에 언어별 제목·요약만 넘기고, 조항 본문은 legal/ 아래
// 언어별 컴포넌트(RefundBodyKo/En)가 갖는다(TermsPage와 같은 패턴). 시행일은 약관과 같은
// 상수(TERMS_EFFECTIVE_DATE)를 쓴다 — 환불정책도 약관 개정과 같은 시행일에 함께 열린다.
export function RefundPage() {
  return (
    <LegalLayout
      title={{ ko: "환불정책", en: "Refund Policy" }}
      effectiveDate={TERMS_EFFECTIVE_DATE}
      summary={{
        ko: "whycode Pro 구독의 청약철회·해지·환불 기준을 정합니다. 결제와 환불은 판매 대행자인 Paddle.com이 처리합니다.",
        en: "This policy sets out the standards for withdrawal, cancellation, and refunds for whycode Pro subscriptions. Payments and refunds are handled by our reseller, Paddle.com.",
      }}
    >
      <RefundBody />
    </LegalLayout>
  );
}
