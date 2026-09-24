import { Link } from "react-router-dom";

import { LEGAL_CONTACT_EMAIL, LegalSection } from "@/components/landing/LegalLayout";
import { PRO_MONTHLY_PRICE_KRW } from "@/lib/plans";
import { PATHS } from "@/routes";

// 이용약관 본문(한국어) — 조항 13개. 이 파일의 조항을 고치면 TermsBodyEn.tsx의 같은 조항도
// 함께 고친다.
// 서비스 실물에 맞춘 조항: 제4조(연동 권한 보증)와 제8조(AI 답변의 한계)가 이 제품 고유의
// 위험 지점이라 일반 약관 템플릿보다 구체적으로 적었다. 제6조(유료 구독)는 Paddle을
// 판매 대행자(Merchant of Record)로 쓰는 구조를 반영한다(docs/billing.md). 나머지는 표준
// 골격이다.
export function TermsBodyKo() {
  return (
    <>
      <LegalSection index={1} heading="목적">
        <p>
          이 약관은 서비스의 이용 조건과 절차, 이용자와 운영자의 권리·의무 및 책임 사항을
          정하는 것을 목적으로 합니다.
        </p>
      </LegalSection>

      <LegalSection index={2} heading="정의">
        <ul>
          <li>
            <strong>서비스</strong> — GitHub·Jira·Slack 등 협업 도구의 기록을 연결해 지식
            그래프로 만들고, 자연어 질문에 근거와 함께 답하는 웹 애플리케이션을 말합니다.
          </li>
          <li>
            <strong>이용자</strong> — 이 약관에 동의하고 서비스를 이용하는 사람을 말합니다.
          </li>
          <li>
            <strong>프로젝트</strong> — 이용자가 서비스 안에 만드는 작업 단위로, 데이터
            소스 연동·지식 그래프·대화가 이 단위로 묶입니다.
          </li>
          <li>
            <strong>데이터 소스</strong> — 이용자가 프로젝트에 연결한 외부 서비스(GitHub
            저장소, Jira 프로젝트, Slack 워크스페이스)를 말합니다.
          </li>
          <li>
            <strong>무료 이용(Free)</strong> — 별도 결제 없이 이용하는 기본 플랜을 말합니다.
          </li>
          <li>
            <strong>유료 구독(Pro)</strong> — 매월 결제하여 이용하는 유료 플랜을 말합니다.
            각 플랜의 이용 범위와 요금은 <Link to={PATHS.pricing}>요금 페이지</Link>에
            게시합니다.
          </li>
        </ul>
      </LegalSection>

      <LegalSection index={3} heading="계정">
        <ol>
          <li>
            서비스는 GitHub 계정을 통한 소셜 로그인만 지원합니다. 별도의 비밀번호를 두지
            않으므로 GitHub 계정의 보안은 이용자가 관리합니다.
          </li>
          <li>
            이용자는 계정 설정에서 언제든지 탈퇴할 수 있습니다. 탈퇴 처리와 데이터 파기는{" "}
            <Link to={PATHS.privacy}>개인정보처리방침</Link>에 따릅니다.
          </li>
        </ol>
      </LegalSection>

      <LegalSection index={4} heading="데이터 소스 연동과 이용자의 보증">
        <p>
          이 조항은 서비스의 성격상 가장 중요합니다. 서비스는 이용자가 연결한 데이터
          소스의 기록을 읽어 그래프를 만들며, 그 기록에는 이용자 외 다른 구성원이 작성한
          내용이 포함됩니다.
        </p>
        <ol>
          <li>
            이용자는 자신이 연결하는 저장소·프로젝트·워크스페이스에 접근할 정당한 권한을
            보유하고 있음을 보증합니다.
          </li>
          <li>
            해당 데이터를 서비스에 반입하는 것에 대해 소속 조직의 정책상 필요한 승인을
            얻을 책임, 그리고 기록에 정보가 포함되는 구성원에게 고지할 책임은 연동을
            설정한 이용자와 그 조직에 있습니다.
          </li>
          <li>
            서비스는 연동 시 요청한 범위의 읽기 권한만 사용하며, 외부 서비스에 데이터를
            쓰거나 수정하지 않습니다. 이용자는 연동을 언제든지 해제할 수 있습니다.
          </li>
        </ol>
      </LegalSection>

      <LegalSection index={5} heading="서비스의 제공">
        <ol>
          <li>
            서비스는 데이터 소스 연동, 지식 그래프 구축, 자연어 질의응답, 대화 기록 관리
            기능을 제공합니다.
          </li>
          <li>
            서비스는 무료 이용(Free)과 유료 구독(Pro)으로 제공되며, 각 이용 범위와 요금은{" "}
            <Link to={PATHS.pricing}>요금 페이지</Link>에 게시합니다. 운영자는 기능의
            추가·변경·중단을 결정할 수 있고, 중단이 예정된 경우 사전에 공지합니다.
          </li>
          <li>
            외부 API 장애, 정기 점검, 천재지변 등 운영자가 통제할 수 없는 사유로 서비스가
            일시 중단될 수 있습니다.
          </li>
        </ol>
      </LegalSection>

      <LegalSection index={6} heading="유료 구독">
        <ol>
          <li>
            Pro 요금은 월 {PRO_MONTHLY_PRICE_KRW.toLocaleString("ko-KR")}원(부가세
            포함)이며, 해지하지 않는 한 한 달 단위 결제 주기에 맞춰 자동으로 갱신·결제됩니다.
          </li>
          <li>
            whycode의 주문 절차는 온라인 판매 대행자인 Paddle.com이 수행합니다.
            Paddle.com은 모든 주문의 판매자(Merchant of Record)이며, 고객 문의 응대와
            환불을 처리합니다. 결제, 영수증 발급, 결제수단 관리는 Paddle을 통해 이뤄집니다.
          </li>
          <li>
            이용자는 구독 관리 화면(Paddle 고객 포털)에서 언제든지 구독을 해지할 수
            있습니다.
          </li>
          <li>
            환불은 <Link to={PATHS.refund}>환불정책</Link>을 따릅니다.
          </li>
          <li>
            구독이 끝나면 무료 이용(Free)으로 전환됩니다. 프로젝트와 데이터 소스 연동은
            유지되고, 새 활동 자동 수집(증분 수집)이 멈추며, 무료 이용 한도가 적용되고,
            무료 질의 횟수는 새로 부여됩니다.
          </li>
          <li>
            운영자가 요금을 변경하려면 시행 30일 전까지 공지하고 기존 구독자의 동의를
            받습니다. 동의하지 않은 구독자에게는 변경된 요금을 청구하지 않으며, 그 구독은
            이미 결제한 기간이 끝날 때 갱신 없이 종료됩니다.
          </li>
          <li>운영자는 결제 예정일 7일 전까지 결제 금액과 결제일을 안내합니다.</li>
        </ol>
      </LegalSection>

      <LegalSection index={7} heading="이용자의 의무">
        <p>이용자는 다음 행위를 해서는 안 됩니다.</p>
        <ul>
          <li>접근 권한이 없는 데이터 소스를 연동하거나 타인의 계정을 도용하는 행위</li>
          <li>
            서비스를 통해 취득한 다른 구성원의 정보를 원래 목적과 무관하게 이용하거나 외부에
            공개하는 행위
          </li>
          <li>
            자동화된 수단으로 서비스에 과도한 부하를 일으키거나, 역설계·무단 복제하는 행위
          </li>
          <li>관계 법령을 위반하거나 제3자의 권리를 침해하는 행위</li>
        </ul>
      </LegalSection>

      <LegalSection index={8} heading="AI 생성 답변의 한계">
        <ol>
          <li>
            서비스의 답변은 대규모 언어 모델이 지식 그래프를 근거로 생성한 것으로, 사실과
            다르거나 불완전할 수 있습니다.
          </li>
          <li>
            답변은 참고 자료이며, 운영자는 그 정확성·완전성·특정 목적 적합성을 보증하지
            않습니다. 이용자는 중요한 판단을 내리기 전에 답변에 제시된 원본 기록(커밋, PR,
            이슈, 메시지)을 직접 확인해야 합니다.
          </li>
        </ol>
      </LegalSection>

      <LegalSection index={9} heading="지식재산권">
        <ol>
          <li>
            이용자가 연동한 데이터 소스의 콘텐츠에 대한 권리는 원래 권리자에게 있습니다.
            운영자는 서비스 제공에 필요한 범위에서만 이를 처리합니다.
          </li>
          <li>
            서비스의 소프트웨어·디자인·문서에 대한 권리는 운영자 또는 정당한 권리자에게
            있으며, 오픈소스로 공개된 부분은 해당 라이선스를 따릅니다.
          </li>
        </ol>
      </LegalSection>

      <LegalSection index={10} heading="이용계약의 해지">
        <ol>
          <li>
            이용자는 연동 해제, 프로젝트 삭제, 회원 탈퇴를 통해 언제든지 이용을 중단할 수
            있습니다. 프로젝트를 삭제하면 그 프로젝트의 대화·연동 정보와 지식 그래프가 함께
            삭제됩니다.
          </li>
          <li>
            이용자가 제7조를 위반한 경우 운영자는 사전 통지 후 이용을 제한하거나 계약을
            해지할 수 있습니다. 다만 긴급한 경우 통지를 사후에 할 수 있습니다.
          </li>
          <li>
            유료 구독의 해지와 환불은 제6조와 <Link to={PATHS.refund}>환불정책</Link>을
            따릅니다.
          </li>
        </ol>
      </LegalSection>

      <LegalSection index={11} heading="책임의 제한">
        <ol>
          <li>
            운영자는 무료 이용(Free)과 관련하여 고의 또는 중대한 과실이 없는 한 손해를
            배상할 책임을 지지 않습니다.
          </li>
          <li>
            유료 구독 이용자가 운영자의 귀책사유로 서비스를 이용하지 못한 경우, 운영자는
            관련 법령이 정하는 바에 따라 책임을 집니다.
          </li>
          <li>
            이용자가 권한 없이 연동한 데이터로 인해 발생한 분쟁과 손해에 대한 책임은 해당
            이용자에게 있습니다.
          </li>
        </ol>
      </LegalSection>

      <LegalSection index={12} heading="약관의 변경과 문의">
        <ol>
          <li>
            운영자는 필요한 경우 약관을 변경할 수 있으며, 변경 내용과 시행일을 시행 7일
            전(이용자에게 불리한 변경은 30일 전)까지 서비스 내에 공지합니다.
          </li>
          <li>
            공지 후 이용자가 시행일까지 거부 의사를 밝히지 않고 서비스를 계속 이용하면
            변경에 동의한 것으로 봅니다.
          </li>
          <li>
            약관에 관한 문의는 <a href={`mailto:${LEGAL_CONTACT_EMAIL}`}>{LEGAL_CONTACT_EMAIL}</a>
            로 보내주세요.
          </li>
        </ol>
      </LegalSection>

      <LegalSection index={13} heading="준거법과 관할">
        <p>
          이 약관은 대한민국 법을 준거법으로 하며, 서비스 이용과 관련한 분쟁은 민사소송법상
          관할 법원에 제기합니다.
        </p>
      </LegalSection>
    </>
  );
}
