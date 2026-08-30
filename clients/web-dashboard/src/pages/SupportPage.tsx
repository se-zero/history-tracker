import { LEGAL_OPERATOR, LegalLayout } from "@/components/landing/LegalLayout";
import { SupportBody } from "@/components/landing/SupportBody";

// 지원 — 공개 라우트(/support). Slack 마켓플레이스 Support URL 및 로그인 없는 문의 창구.
// 약관처럼 조항 번호는 두지 않는다. 셸만 LegalLayout을 빌려 헤더·푸터·언어를 맞춘다.
export function SupportPage() {
  return (
    <LegalLayout
      title={{ ko: "지원", en: "Support" }}
      summary={{
        ko: `${LEGAL_OPERATOR.ko}에 제품 이용·연동·개인정보 관련 문의를 보내 주세요. 영업일 기준 2일 안에 답합니다.`,
        en: `Write to ${LEGAL_OPERATOR.en} about using whycode, connections, or personal information. We respond within two business days.`,
      }}
    >
      <SupportBody />
    </LegalLayout>
  );
}
