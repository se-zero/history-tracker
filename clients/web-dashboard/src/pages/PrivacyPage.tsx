import { LEGAL_OPERATOR, LegalLayout } from "@/components/landing/LegalLayout";
import { PrivacyBody } from "@/components/landing/legal/PrivacyBody";

// 개인정보처리방침 — 공개 라우트(/privacy). 초안이며 법률 검토 전이다.
// 연동 앱 심사가 모두 이 페이지 하나를 개인정보처리방침 URL로 쓴다 (서비스별로 문서를 나누면
// 수집 코드가 바뀔 때 여러 곳이 갈라진다). 심사자가 자기 서비스만 바로 보도록 제2조에 앵커를
// 뒀다 — /privacy#github · #slack · #jira · #discord · #google-chat.
// 앞의 셋은 이미 심사에 제출된 URL이라 **그 id는 바꾸지 않는다**. Google Chat의
// directory.readonly는 민감 범위라 OAuth 검증에서 이 URL을 요구한다.
//
// 얇은 진입점: 셸(LegalLayout)에 언어별 제목·요약만 넘기고, 조항 본문은 legal/ 아래
// 언어별 컴포넌트(PrivacyBodyKo/En)가 갖는다(4b) — 실제 수집 항목·보유기간·위탁처 등
// 코드 참조 계약은 PrivacyBodyKo.tsx 상단 주석을 따른다.
export function PrivacyPage() {
  return (
    <LegalLayout
      title={{ ko: "개인정보처리방침", en: "Privacy Policy" }}
      effectiveDate="2026-08-01"
      summary={{
        ko: `${LEGAL_OPERATOR.ko}은 whycode를 운영하며 처리하는 개인정보의 항목과 이용 방식을 아래와 같이 안내합니다. 서비스의 성격상 이용자 본인뿐 아니라 이용자가 연동한 협업 도구의 기록도 처리하므로, 그 범위를 구체적으로 밝힙니다.`,
        en: `This page explains, on behalf of ${LEGAL_OPERATOR.en}, what personal information whycode processes and how — including, given the nature of the Service, records from the collaboration tools a User connects, not just the User's own information.`,
      }}
    >
      <PrivacyBody />
    </LegalLayout>
  );
}
