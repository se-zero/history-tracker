import { CheckoutBody } from "@/components/landing/CheckoutBody";
import { LegalLayout } from "@/components/landing/LegalLayout";

// 결제 — 공개 라우트(/checkout). Paddle 계정의 "기본 결제 링크"로 등록하는 페이지다. 이 값이 없으면
// Paddle이 거래를 만들 수 없고, 고객에게 보내는 결제 링크(결제 실패 후 결제 정보 업데이트 메일,
// 결제수단 변경 링크)가 `?_ptxn=<거래 ID>`로 여기에 도착한다(docs/billing.md). 얇은 진입점: 셸에
// 제목·요약만 넘기고 결제창 열기와 상태 안내는 CheckoutBody가 갖는다. 조항 문서가 아니라
// effectiveDate는 없다.
export function CheckoutPage() {
  return (
    <LegalLayout
      title={{ ko: "결제", en: "Checkout" }}
      summary={{
        ko: "whycode의 결제는 판매 대행자인 Paddle.com이 처리합니다. 결제 링크로 들어오면 이 페이지에서 결제창이 열립니다.",
        en: "Payments for whycode are processed by our reseller, Paddle.com. When you arrive from a payment link, the checkout opens on this page.",
      }}
    >
      <CheckoutBody />
    </LegalLayout>
  );
}
