import { LEGAL_CONTACT_EMAIL, LegalNotice, LegalSection } from "@/components/landing/LegalLayout";

// 환불정책 본문(영어) — RefundBodyKo.tsx의 번역. 이 파일의 조항을 고치면 RefundBodyKo.tsx의
// 같은 조항도 함께 고친다. 조항 수(6)·순서·구조는 한국어판과 동일하다 — 번역만 하고
// 재구성하지 않았다. 제1조 ②는 Paddle 핸드북 원문을 그대로 쓴다(번역하지 않는다).
export function RefundBodyEn() {
  return (
    <>
      <LegalNotice />

      <LegalSection index={1} heading="Scope">
        <ol>
          <li>This policy applies to payments for the Pro monthly subscription.</li>
          <li>
            Our order process is conducted by our online reseller Paddle.com. Paddle.com is
            the Merchant of Record for all our orders. Paddle provides all customer service
            inquiries and handles returns.
          </li>
        </ol>
      </LegalSection>

      <LegalSection index={2} heading="Withdrawal within 7 days">
        <ol>
          <li>
            If you request a refund within 7 days of payment and have not used the Service
            since paying, we refund the full amount.
          </li>
          <li>
            "Use" means the following actions a User takes directly after payment: sending a
            query, creating a project, adding a data source connection, or rebuilding the
            graph.
          </li>
          <li>
            Data collection that runs automatically upon upgrading to Pro is not considered
            use.
          </li>
        </ol>
      </LegalSection>

      <LegalSection index={3} heading="Mid-term cancellation and prorated refund">
        <ol>
          <li>
            If you request a refund outside the scope of Article 2 (more than 7 days have
            passed, or the Service has been used), we cancel the subscription immediately and
            refund the remaining period on a prorated basis.
          </li>
          <li>
            Formula: amount paid × remaining days ÷ days in the billing period, rounded down
            to the nearest KRW. Remaining days run from the day after we receive the request
            through the end of the billing period.
          </li>
          <li>
            The account switches to Free immediately upon cancellation. Projects and
            connections are kept, incremental sync stops, and Free usage limits apply.
          </li>
        </ol>
      </LegalSection>

      <LegalSection index={4} heading="Cancellation without a refund">
        <p>
          You can cancel anytime from the subscription management screen. You keep using Pro
          until the period you already paid for ends, and no further payment is charged.
        </p>
        <p>To request a refund for the remaining period, use the method in Article 5.</p>
      </LegalSection>

      <LegalSection index={5} heading="How to request a refund">
        <p>
          Email <a href={`mailto:${LEGAL_CONTACT_EMAIL}`}>{LEGAL_CONTACT_EMAIL}</a> with your
          account email and payment date (or the order number on your receipt).
        </p>
        <p>
          After we confirm, Paddle refunds the original payment method. It may take some time
          to reflect, depending on the payment method.
        </p>
      </LegalSection>

      <LegalSection index={6} heading="Billing errors">
        <p>
          Duplicate or erroneous payments are refunded in full, regardless of the period or
          whether the Service was used.
        </p>
      </LegalSection>
    </>
  );
}
