import type { ReactNode } from "react";

import { GithubMark, JiraMark, SlackMark } from "@/components/brand/BrandMarks";
import type { Localized } from "@/components/landing/LandingLanguageProvider";

// 기능 1(대화) 미리보기(FeatureSections.tsx ChatPreview)와 히어로 모바일 목업
// (HeroProductSlot.tsx HeroProductMockup)이 리터럴로 중복 보유하던 데모 문자열을 이
// 모듈로 모았다 — 같은 스토리(승인 라우팅 가중치, PAY-64 → PR #142 → #dev-pay)를 두
// 컴포넌트가 각자 하드코딩하던 것을 단일 출처로 청산한다.
//
// RAIL_NAV·COMPOSER_PLACEHOLDER·RELATED_GRAPH_TITLE의 en 값은 히어로 영상 재촬영
// 치환기 tools/hero-video/en-dom-overlay.mjs의 영어 사전과 같은 문자열이어야 하는
// 계약이다(그 파일은 실앱 DOM을 촬영 시점에만 영어로 바꾸는 별도 스크립트라 이 작업에서
// 수정하지 않는다) — 한쪽을 고치면 반드시 다른 쪽도 맞춘다.

// 브레드크럼·윈도우 크롬 공용 — 프로젝트명은 실앱 AppShell 규약상 언어와 무관한 고유명사.
export const DEMO_PROJECT_NAME = "payflow";

// 레일 내비 6항목 — Sidebar.tsx 기준 라벨(en-dom-overlay.mjs 사전과 문자열 계약 공유).
export const RAIL_NAV: Localized<{
  search: string;
  conversations: string;
  dataSources: string;
  actors: string;
  graphView: string;
  projectSettings: string;
}> = {
  ko: {
    search: "검색",
    conversations: "대화",
    dataSources: "데이터 소스",
    actors: "액터",
    graphView: "그래프 확인",
    projectSettings: "현재 프로젝트 설정",
  },
  en: {
    search: "Search",
    conversations: "Conversations",
    dataSources: "Data sources",
    actors: "Actors",
    graphView: "Graph view",
    projectSettings: "Project settings",
  },
};

// 레일 히스토리 4건 — 제목은 그럴듯한 데모 데이터(실제 앱 값 금지, DESIGN.md 랜딩 데모 세계관),
// 날짜는 언어 무관 리터럴.
export const RAIL_HISTORY: Array<{ id: string; title: Localized<string>; date: string }> = [
  {
    id: "approval-routing",
    title: { ko: "승인 라우팅 변경 경위", en: "Why the approval routing changed" },
    date: "2026-07-18",
  },
  {
    id: "refund-approval",
    title: { ko: "환불 승인 흐름 정리", en: "Sorting out the refund approval flow" },
    date: "2026-07-15",
  },
  {
    id: "3ds-auth",
    title: { ko: "3DS 인증 도입 경위", en: "Why we adopted 3DS authentication" },
    date: "2026-07-09",
  },
  {
    id: "txn-lookup-perf",
    title: { ko: "거래 조회 성능 개선", en: "Improving transaction lookup performance" },
    date: "2026-07-02",
  },
];

// 질문 버블 — payflow 세계관(티켓·프로젝트)은 히어로 영상과 공유하되 질문 자체는 다르게
// 유지한다(DESIGN.md 랜딩 「질문·스토리는 영상과 다르게 유지」 — 영상과 다른
// 질문이 제품의 질문 폭을 전달한다).
export const DEMO_QUESTION: Localized<string> = {
  ko: "결제 라우팅 로직 관련 PR이랑 지라 티켓 찾아줘",
  en: "Find the PRs and Jira tickets about the payment routing logic",
};

// 답변 산문 — full(기능 1 ChatPreview, 3문장)과 short(히어로 모바일 목업, 2문장) 두 변형을
// 나란히 둔다. 인라인 코드 3개(PAY-64/PR #142/#dev-pay)는 두 변형 모두 같은 스토리 앵커라
// 순서·개수가 같아야 한다.
export const DEMO_ANSWER_FULL: Localized<ReactNode> = {
  ko: (
    <>
      승인 라우팅 가중치 조정은 이슈 <code className="lp-feature-chat-code">PAY-64</code>
      에서 처음 제기됐습니다. 우선순위 고정 방식이 특정 결제사 장애에 취약하다는 지적에
      따라 성공률과 응답 시간을 반영하도록 다시 설계해{" "}
      <code className="lp-feature-chat-code">PR #142</code>로 반영됐습니다. 최종 가중치는
      6월 11일 <code className="lp-feature-chat-code">#dev-pay</code> 스레드에서
      합의됐습니다.
    </>
  ),
  en: (
    <>
      The change traces to <code className="lp-feature-chat-code">PAY-64</code> — fixed
      priorities were fragile during provider outages.{" "}
      <code className="lp-feature-chat-code">PR #142</code> redesigned routing around
      success rate and response time; the final weights were agreed in{" "}
      <code className="lp-feature-chat-code">#dev-pay</code> on June 11.
    </>
  ),
};

export const DEMO_ANSWER_SHORT: Localized<ReactNode> = {
  ko: (
    <>
      승인 라우팅 가중치는 <code className="lp-feature-chat-code">PAY-64</code>에서 제기된
      장애 취약성 문제를 계기로 재설계돼{" "}
      <code className="lp-feature-chat-code">PR #142</code>로 반영됐습니다. 최종 가중치는{" "}
      <code className="lp-feature-chat-code">#dev-pay</code> 스레드에서 합의됐습니다.
    </>
  ),
  en: (
    <>
      The approval routing weight was redesigned after{" "}
      <code className="lp-feature-chat-code">PAY-64</code> flagged the fragility issue,
      landing in <code className="lp-feature-chat-code">PR #142</code>. The final weights
      were agreed on in the <code className="lp-feature-chat-code">#dev-pay</code> thread.
    </>
  ),
};

// 출처 카드 3장 — 작성자·타입 라벨·날짜는 이미 영문/ISO라 언어 무관 상수, 인용문만 번역한다.
export const SOURCE_CARDS: Array<{
  id: string;
  Mark: typeof GithubMark;
  typeLabel: "issue" | "message" | "PR";
  author: string;
  date: string;
  quote: Localized<string>;
}> = [
  {
    id: "1",
    Mark: JiraMark,
    typeLabel: "issue",
    author: "Noah",
    date: "2026-06-12",
    quote: { ko: "승인 라우팅 가중치 개선", en: "Tune routing weights" },
  },
  {
    id: "2",
    Mark: SlackMark,
    typeLabel: "message",
    author: "Grace",
    date: "2026-06-11",
    quote: {
      ko: "성공률 가중치 0.7로 확정 — 스레드 합의",
      // 카드 인용문 칼럼은 157px — 영문은 21자 안쪽이어야 ko처럼 한 줄이다(레이아웃 파리티).
      en: "Settled on 0.7 weight",
    },
  },
  {
    id: "3",
    Mark: GithubMark,
    typeLabel: "PR",
    author: "Ethan",
    date: "2026-06-13",
    quote: { ko: "라우팅 가중치 적용", en: "Apply routing weights" },
  },
];

// 컴포저 placeholder — en-dom-overlay.mjs ATTR_ENTRIES와 동일 문자열 계약.
export const COMPOSER_PLACEHOLDER: Localized<string> = {
  ko: "payflow에 무엇이든 물어보세요. Shift+Enter로 줄바꿈",
  en: "Ask payflow anything. Shift+Enter for a new line",
};

// 관련 그래프 패널 타이틀(히어로 모바일 목업) — en-dom-overlay.mjs TEXT_ENTRIES와 동일
// 문자열 계약(RelatedGraphPanel.tsx 실앱 타이틀).
export const RELATED_GRAPH_TITLE: Localized<string> = {
  ko: "관련 그래프",
  en: "Related graph",
};
