import { LEGAL_CONTACT_EMAIL, LegalSection } from "@/components/landing/LegalLayout";

// 환불정책 본문(한국어) — 조항 6개. 이 파일의 조항을 고치면 RefundBodyEn.tsx의 같은 조항도
// 함께 고친다. 초안이며 법률 검토 전이다(TermsBodyKo.tsx와 같은 톤).
// 제2조("사용"하지 않은 경우에만 7일 전액 환불)·제3조(일할 환불) 기준은 Paddle 환불정책이
// 주는 "사용 여부 무관 7일 철회"보다 좁다 — 판매 주체가 Paddle이라 구매자가 Paddle에 직접
// 요청하면 Paddle 정책으로 전액 환불될 수 있다(docs/billing.md에 위험으로 기록됨).
export function RefundBodyKo() {
  return (
    <>
      <LegalSection index={1} heading="적용 대상">
        <ol>
          <li>이 정책은 Pro 월 구독 결제에 적용됩니다.</li>
          <li>
            whycode의 주문 절차는 온라인 판매 대행자인 Paddle.com이 수행합니다. Paddle.com은
            모든 주문의 판매자(Merchant of Record)이며, 고객 문의 응대와 환불을 처리합니다.
          </li>
        </ol>
      </LegalSection>

      <LegalSection index={2} heading="7일 이내 청약철회">
        <ol>
          <li>
            결제일로부터 7일 이내이고, 결제 후 서비스를 사용하지 않은 경우 결제 금액 전액을
            환불합니다.
          </li>
          <li>
            "사용"이란 결제 후 이용자가 직접 한 다음 행동을 말합니다: 질의, 프로젝트 생성,
            데이터 소스 연동 추가, 그래프 재구축.
          </li>
          <li>Pro 전환에 따라 자동으로 이뤄지는 데이터 수집은 사용으로 보지 않습니다.</li>
        </ol>
      </LegalSection>

      <LegalSection index={3} heading="중도 해지와 일할 환불">
        <ol>
          <li>
            제2조에 해당하지 않는 경우(7일이 지났거나 사용한 경우)에 환불을 요청하면 구독을
            즉시 해지하고, 남은 기간의 이용료를 일할 계산해 환불합니다.
          </li>
          <li>
            계산식: 결제 금액 × 남은 일수 ÷ 해당 결제 기간의 일수(원 미만 절사). 남은 일수는
            요청을 접수한 다음 날부터 결제 기간 종료일까지로 계산합니다.
          </li>
          <li>
            해지 즉시 무료 이용(Free)으로 전환됩니다. 프로젝트·연동은 유지되고, 증분 수집이
            멈추며, 무료 이용 한도가 적용됩니다.
          </li>
        </ol>
      </LegalSection>

      <LegalSection index={4} heading="환불 없이 해지">
        <p>
          구독 관리 화면에서 언제든지 해지할 수 있습니다. 이미 결제한 기간이 끝날 때까지
          Pro를 계속 쓸 수 있으며, 다음 결제는 이뤄지지 않습니다.
        </p>
        <p>남은 기간의 환불을 원하면 제5조의 방법으로 요청해 주세요.</p>
      </LegalSection>

      <LegalSection index={5} heading="환불 요청 방법">
        <p>
          <a href={`mailto:${LEGAL_CONTACT_EMAIL}`}>{LEGAL_CONTACT_EMAIL}</a>로 계정 이메일과
          결제일(또는 영수증의 주문 번호)을 적어 요청해 주세요.
        </p>
        <p>
          확인 후 Paddle을 통해 원래 결제 수단으로 환불합니다. 결제 수단에 따라 반영까지
          시간이 걸릴 수 있습니다.
        </p>
      </LegalSection>

      <LegalSection index={6} heading="결제 오류">
        <p>중복 결제나 오류로 인한 결제는 기간이나 사용 여부와 무관하게 전액 환불합니다.</p>
      </LegalSection>
    </>
  );
}
