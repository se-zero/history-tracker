import { LegalLayout } from "@/components/landing/LegalLayout";
import { SlackBody } from "@/components/landing/SlackBody";

// Slack — 공개 라우트(/slack). Slack 마켓플레이스 심사·Slack 앱 설정의 Installation
// landing page URL이 가리키는 페이지다. 랜딩 안 Slack 앱 절에서 2026-09-19 분리했다 —
// 소개 흐름과 붙어 보이지 않아 전용 페이지로 뗐다. 셸만 LegalLayout을 빌린다.
export function SlackPage() {
  return (
    <LegalLayout
      title={{ ko: "Slack에서 쓰는 방법", en: "whycode in Slack" }}
      summary={{
        ko: (
          <>
            채널에서 <code>/why-code</code> 뒤에 질문을 붙이면, 연결된 프로젝트의 그래프에서 답을 찾아 본인에게만
            보여 줍니다.
          </>
        ),
        en: (
          <>
            Type a question after <code>/why-code</code> in a channel; whycode looks it up on the connected
            project's graph and replies only to you.
          </>
        ),
      }}
    >
      <SlackBody />
    </LegalLayout>
  );
}
