import { LEGAL_OPERATOR, LegalLayout } from "@/components/landing/LegalLayout";
import { TermsBody } from "@/components/landing/legal/TermsBody";

// 이용약관 — 공개 라우트(/terms). 초안이며 법률 검토 전이다.
// 얇은 진입점: 셸(LegalLayout)에 언어별 제목·요약만 넘기고, 조항 본문은 legal/ 아래
// 언어별 컴포넌트(TermsBodyKo/En)가 갖는다 — 문장 단위 치환이 아니라 조항 전체를 번역해
// 자연스러운 법률 영어 문체를 쓰기 위함(4a).
export function TermsPage() {
  return (
    <LegalLayout
      title={{ ko: "이용약관", en: "Terms of Service" }}
      effectiveDate="2026-08-01"
      summary={{
        ko: `${LEGAL_OPERATOR.ko}이 제공하는 whycode(이하 "서비스")의 이용 조건을 정합니다. 서비스에 로그인하면 이 약관에 동의한 것으로 봅니다.`,
        en: `These Terms set out the conditions for using whycode (the "Service"), provided by ${LEGAL_OPERATOR.en}. Logging in to the Service is considered agreement to these Terms.`,
      }}
    >
      <TermsBody />
    </LegalLayout>
  );
}
