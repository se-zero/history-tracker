import type { CSSProperties } from "react";

import {
  AsanaMark,
  ClickUpMark,
  DiscordMark,
  GithubMark,
  GoogleChatMark,
  JiraMark,
  LinearMark,
  NotionMark,
  SlackMark,
} from "@/components/brand/BrandMarks";
import { useInViewOnce } from "@/components/landing/useInViewOnce";

// 기능 3섹션 (대화 / 그래프 탐색 / 데이터 소스) — 좌/우 교차 레이아웃.
// 기능 2만 반전(우 텍스트 / 좌 미디어)해 스크롤 리듬에 변주를 준다(LANDING_BRIEF.md 6번).
// 미디어 슬롯은 실제 제품 스크린샷이 아니라, DESIGN.md 컴포넌트 스펙(채팅 답변·출처 카드·
// 그래프 캔버스·데이터 소스 카드)을 그대로 따르는 실제 DOM 미리보기다 — 앱이 DESIGN.md로
// 리스타일되면 이 컴포넌트가 곧 그 화면이므로 가짜 목업이 아니라 같은 디자인 시스템의
// 실제 컴포넌트다. 내부 텍스트는 곁의 카피가 의미를 전달하므로 aria-hidden으로 숨긴다.
//
// 슬롯 비율은 콘텐츠에 맞춰 슬롯별로 분리한다(2026-07-24 결정, mediaKind로 구분):
// 세 슬롯을 "같은 이미지 자리 3개"처럼 반복시키던 공통 16:10을 깼다.
//   - chat: 종횡비 없음 — 채팅 스트림은 세로 매체라 콘텐츠 높이를 그대로 따른다.
//   - graph: 16:10 유지 — 그래프 캔버스는 가로 폭이 필요하다.
//   - sources: 종횡비 없음 — 실앱 SourcesPage 구조(타이틀+서브 → 섹션 라벨 → 연동 행 →
//     섹션 라벨 → 타일 그리드) 높이 그대로, 억지로 늘리지 않는다.
const FEATURES = [
  {
    eyebrow: "CHAT",
    headline: "답과 근거를 함께 돌려준다.",
    body: "질문에 답하면서, 그 답의 출처가 된 커밋·이슈·메시지를 함께 제시한다. 곁의 그래프에서 근거 노드와 경로가 켜진다.",
    reversed: false,
    mediaKind: "chat",
  },
  {
    eyebrow: "GRAPH EXPLORER",
    headline: "코드베이스를 지도처럼 본다.",
    body: "커밋·PR·이슈·메시지·사람이 하나의 그래프로 놓인다. 타입별로 걸러 보고, 노드를 따라가며 맥락을 넓힌다.",
    reversed: true,
    mediaKind: "graph",
  },
  {
    eyebrow: "DATA SOURCES",
    headline: "한 번 연결하면, 계속 쌓인다.",
    body: "코드·티켓·대화·문서를 연결해두면 새 기록이 자동으로 그래프에 편입된다.",
    reversed: false,
    mediaKind: "sources",
  },
] as const;

// FEATURES와 같은 순서로 미디어 슬롯 콘텐츠를 매핑한다(별도 컴포넌트로 분리 — 각각이
// 고유한 마크업/스펙을 가져 하나의 제네릭 컴포넌트로 합치면 오히려 분기만 늘어난다).
const FEATURE_MEDIA = [
  <ChatPreview key="chat" />,
  <GraphExplorerPreview key="graph" />,
  <DataSourcesPreview key="sources" />,
];

export function FeatureSections() {
  return (
    <section className="lp-features">
      <div className="lp-features-inner">
        {FEATURES.map((f, i) => (
          <div
            className={`lp-feature-block${f.reversed ? " lp-feature-block--reverse" : ""}`}
            key={f.eyebrow}
          >
            <div className="lp-feature-text">
              <p className="lp-feature-eyebrow">{f.eyebrow}</p>
              <h2 className="lp-feature-headline">{f.headline}</h2>
              <p className="lp-feature-body">{f.body}</p>
            </div>
            {/* TODO: 실제 앱 스크린샷/영상으로 교체 가능(선택) — DESIGN.md 컴포넌트 스펙을
                따르는 실제 DOM 미리보기라 교체 전에도 유효한 화면이다. */}
            <div
              className={`lp-feature-media lp-feature-media--${f.mediaKind}`}
              aria-hidden="true"
            >
              {FEATURE_MEDIA[i]}
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}

// ── 기능 1: 대화 — 실제 앱 채팅 화면의 단면 (2026-07-24 밀도 개선) ─────────────────────
// "축소하지 말고 잘라내라": 앱 전체를 욱여넣는 대신, 좌측 사이드 레일 일부(검색·내비 6개·
// 대화 히스토리) + 상단 브레드크럼 + Q&A 한 턴(사용자 버블·답변 산문·출처 카드 3장) +
// 하단 입력창까지 이어 붙여 실제 화면의 크롭처럼 보이게 한다. 레일은 슬롯 좌측에 헤어라인
// 하나로만 구분(별도 elevation 없음 — 슬롯 전체가 이미 surface-1이므로 한 단계 더 파지 않는다).
// 답변 산문 속 라틴 기술 토큰(HT-64, PR #142, #dev-search)은 앱의 마크다운 인라인 코드
// 스타일(chat.css `.msg-content.markdown code`)을 그대로 lp 토큰으로 옮긴 인라인 코드 처리.
// 출처 카드는 실앱 cite-card(chat.css) 구조 그대로 — 2열 그리드 + [#N 칩][본문(브랜드 로고
// 13px + 무채 타입 라벨 + 작성자 + 날짜, 그 아래 인용문)]의 가로 flex(2026-08-21 실앱
// 일치화 — 이전의 노드색 배지·세로 나열은 폐기). 3번째 카드는 GithubMark(PR)로, 나머지
// 둘은 JiraMark(issue)·SlackMark(message)로 세 타입을 모두 보여준다.
function ChatPreview() {
  return (
    <div className="lp-feature-chat">
      <div className="lp-feature-chat-rail">
        <nav className="lp-feature-chat-rail-nav">
          <div className="lp-feature-chat-rail-item">
            <span>검색</span>
            <span className="lp-feature-chat-rail-kbd">Ctrl+K</span>
          </div>
          <div className="lp-feature-chat-rail-item lp-feature-chat-rail-item--active">대화</div>
          <div className="lp-feature-chat-rail-item">데이터 소스</div>
          <div className="lp-feature-chat-rail-item">액터</div>
          <div className="lp-feature-chat-rail-item">그래프 확인</div>
          <div className="lp-feature-chat-rail-item">현재 프로젝트 설정</div>
        </nav>
        <div className="lp-feature-chat-rail-divider" />
        <div className="lp-feature-chat-rail-history">
          <div className="lp-feature-chat-rail-history-item">
            <span className="lp-feature-chat-rail-history-title">검색 기능 PR 추적</span>
            <span className="lp-feature-chat-rail-history-date">2026-07-18</span>
          </div>
          <div className="lp-feature-chat-rail-history-item">
            <span className="lp-feature-chat-rail-history-title">인증 리팩토링 히스토리</span>
            <span className="lp-feature-chat-rail-history-date">2026-07-15</span>
          </div>
          <div className="lp-feature-chat-rail-history-item">
            <span className="lp-feature-chat-rail-history-title">웹훅 처리 변경 경위</span>
            <span className="lp-feature-chat-rail-history-date">2026-07-09</span>
          </div>
          <div className="lp-feature-chat-rail-history-item">
            <span className="lp-feature-chat-rail-history-title">그래프 캐시 성능 개선</span>
            <span className="lp-feature-chat-rail-history-date">2026-07-02</span>
          </div>
        </div>
      </div>
      <div className="lp-feature-chat-main">
        <div className="lp-feature-chat-breadcrumb">
          <span>history tracker</span>
          <span className="lp-feature-chat-breadcrumb-sep">/</span>
          <span className="lp-feature-chat-breadcrumb-current">대화</span>
        </div>
        <div className="lp-feature-chat-stream">
          <p className="lp-feature-chat-user">검색 기능 관련 PR이랑 지라 티켓 찾아줘</p>
          <p className="lp-feature-chat-answer">
            검색 랭킹 가중치 조정은 이슈 <code className="lp-feature-chat-code">HT-64</code>
            에서 처음 제기됐습니다. 노출 빈도 위주였던 로직이 편향된다는 지적에 따라 클릭률과
            최신성을 반영하도록 다시 설계해 <code className="lp-feature-chat-code">PR #142</code>
            로 반영됐습니다. 최종 가중치는 6월 11일{" "}
            <code className="lp-feature-chat-code">#dev-search</code> 스레드에서 합의됐습니다.
          </p>
          <div className="lp-feature-chat-sources">
            <div className="lp-feature-chat-source">
              <span className="lp-feature-chat-source-num">#1</span>
              <div className="lp-feature-chat-source-content">
                <div className="lp-feature-chat-meta">
                  <span className="lp-feature-chat-source-type">
                    <JiraMark size={13} className="lp-feature-chat-source-logo" />
                    issue
                  </span>
                  <span>·</span>
                  <span>김서진</span>
                  <span>·</span>
                  <span className="lp-feature-chat-source-date">2026-06-12</span>
                </div>
                <p className="lp-feature-chat-source-body">검색 랭킹 가중치 개선</p>
              </div>
            </div>
            <div className="lp-feature-chat-source">
              <span className="lp-feature-chat-source-num">#2</span>
              <div className="lp-feature-chat-source-content">
                <div className="lp-feature-chat-meta">
                  <span className="lp-feature-chat-source-type">
                    <SlackMark size={13} className="lp-feature-chat-source-logo" />
                    message
                  </span>
                  <span>·</span>
                  <span>이도현</span>
                  <span>·</span>
                  <span className="lp-feature-chat-source-date">2026-06-11</span>
                </div>
                <p className="lp-feature-chat-source-body">가중치 0.7로 확정 — 스레드 합의</p>
              </div>
            </div>
            <div className="lp-feature-chat-source">
              <span className="lp-feature-chat-source-num">#3</span>
              <div className="lp-feature-chat-source-content">
                <div className="lp-feature-chat-meta">
                  <span className="lp-feature-chat-source-type">
                    <GithubMark size={13} className="lp-feature-chat-source-logo" />
                    PR
                  </span>
                  <span>·</span>
                  <span>박한결</span>
                  <span>·</span>
                  <span className="lp-feature-chat-source-date">2026-06-13</span>
                </div>
                <p className="lp-feature-chat-source-body">검색 랭킹 가중치 적용</p>
              </div>
            </div>
          </div>
        </div>
        <div className="lp-feature-chat-composer">
          <div className="lp-feature-chat-composer-box">
            <span className="lp-feature-chat-composer-placeholder">
              history tracker에 무엇이든 물어보세요. Shift+Enter로 줄바꿈
            </span>
            <div className="lp-feature-chat-composer-actions">
              <span className="lp-feature-chat-composer-send">
                <SendGlyph />
              </span>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

// 전송 버튼 아이콘 — 앱 Icons.tsx의 Send 패스(M2 8 14 2.5 11 14l-3-5-6-1z)를 그대로 가져와
// 채움(fill)으로 렌더링(작은 앰버 사각 버튼 위에서 획선보다 채움이 또렷하다).
function SendGlyph() {
  return (
    <svg width={12} height={12} viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
      <path d="M2 8 14 2.5 11 14l-3-5-6-1z" />
    </svg>
  );
}

// ── 기능 2: 그래프 탐색 — 실제 "그래프 확인" 탭(작업 성좌 뷰)의 축소 재현 ──────────────
// 2026-08 재구축: 이전엔 "작동 방식" 섹션의 개념 도식(MiniGraph)을 확장해 쓰는 d3-force형
// 점-선 그래프였다. 그런데 실제 그래프 화면은 이슈/PR을 "별성"으로 삼고 커밋·파일·대화가
// 그 궤도를 도는 위성으로 붙는 성좌 뷰(ConstellationVis.tsx)로 이미 바뀌어 있어, 미리보기가
// 실제 제품과 너무 달라 완성도가 없어 보인다는 피드백에 따라 그 drawScene 시각 문법(배경
// 먼지 별·성좌 스포크·다리)을 그대로 옮긴 정적 SVG로 다시 그렸다. 캔버스는 DESIGN.md
// "그래프 캔버스 층"대로 가장 깊은 우물(surface-canvas)이고, 상단 툴바는 그 위에 뜨는
// 오버레이다(landing.css .lp-feature-graph 주석 참고 — 실제 그래프 툴의 플로팅 컨트롤과
// 같은 취급).
//
// 2026-08-21 재연출(2차): "노드가 전부 밝아 근거를 찾아주는 느낌이 없다"는 사용자
// 피드백으로 좌하단 렌즈 범례·우하단 줌 컨트롤을 걷어냈다(실제 컨트롤이 아닌 정적 장식이
// 캔버스만 가리고 있었다). 대신 히어로 관련 그래프 패널(HeroProductSlot.tsx)의 "점등 경로"
// 문법을 이 슬롯으로 옮겨, 답변 근거가 된 별성·위성·경로만 앰버로 밝고 나머지는 딤 처리된다
// (DESIGN.md 시그니처 — 앰버는 "답변 경로" 지시 대상, LANDING_BRIEF.md 미리보기 앰버
// 허용 지점 ③). 별성 라벨도 한글 제목+작성자 부제에서 라틴 모노 토큰 단독으로 바꿔 HOW IT
// WORKS·히어로 패널과 같은 라벨 문법(DESIGN.md 모노 스코프 — 라틴 기술 토큰 전용)으로
// 통일했다.
//
// 2026-08-21 재연출(3차): "어두운 노드들 중에서 근거를 탐색해서 찾아 빛내는 느낌"이 목표라는
// 사용자 피드백으로 두 가지를 바꿨다. ①트리오도 처음엔 나머지와 똑같이 딤 상태로 시작한다
// (2차에서는 트리오만 처음부터 밝았다) — 아래 GraphSatellite/GraphStar의 litId가 있어도
// 렌더 시 항상 lp-feature-graph-node--dim을 함께 받는다(landing.css). ②트리오가 동시에
// 점등되지 않고 HT-64 발화 → HT-64→PR#142 엣지 드로잉 → PR#142 발화 → PR#142→#dev-search
// 엣지 드로잉 → #dev-search 발화 순으로 하나씩 밝는다(순차 탐색 안무 — 지속시간은
// landing.css의 --gx-node-dur/--gx-edge-dur 로컬 변수, 시작 지연은 "베이스 클래스 + id
// 수식자 클래스" 복합 셀렉터에 리터럴 ms로 직접 매긴다 — 커스텀 프로퍼티 간접 참조로
// 지연이 전혀 적용되지 않는 실측 결함이 있어 2026-08-21 3차 재수정에서 걷어냈다,
// GraphExplorerPreview 하단 주석 참고). PR#142 별성은 헤일로가 상단 툴바에 가려지지 않도록
// cy를 50→66으로 내렸다(위성·다리·라벨 오프셋도 함께 재계산 — 아래 GRAPH_STARS[1]·
// GRAPH_BRIDGES 주석 참고).
//
// 데모 데이터(고정) — 기능 1 근거 카드와 같은 스토리(검색 랭킹 가중치 작업, HT-64 →
// PR #142 → #dev-search)를 성좌 4개로 펼친다. 좌표는 슬롯과 정확히 같은 비율(16:10 =
// 400x250)의 뷰박스에 손으로 배치했다 — 실제 force 시뮬레이션이 아니라 정적 장면이라
// 절차 생성 대신 균형 잡힌 배치를 직접 잡았고, 뷰박스 비가 슬롯 비와 같아 라벨 위치를
// x·y 모두 같은 cqw 계수 하나로 맞출 수 있다(MiniGraph.tsx가 쓰는 letterbox 보정은 두
// 비율이 다를 때만 필요하다).
interface GraphSatellite {
  x: number;
  y: number;
  r: number;
  type: "commit" | "file" | "slack";
  /** 점등(답변 경로) 대상이면 GRAPH_LIT_NODES id와 같은 값을 준다 — 순차 발화 애니메이션의
      타이밍 클래스(lp-feature-graph-node--ignite-{id})를 고르는 데 쓴다(landing.css). */
  litId?: "issue" | "pr" | "slack";
}

interface GraphStar {
  cx: number;
  cy: number;
  r: number;
  type: "issue" | "pr";
  satellites: GraphSatellite[];
  /** 점등(답변 경로) 대상인지 — 위 GraphSatellite.litId와 같은 의미. */
  litId?: "issue" | "pr" | "slack";
}

const GRAPH_SCENE_W = 400;
const GRAPH_SCENE_H = 250;
const GRAPH_SCENE_UNIT_CQW = 100 / GRAPH_SCENE_W;

// 점등 트리오의 위성 한 자리(#dev-search, slack 타입) — 별성 정의보다 먼저 선언해 별성 0의
// 위성 목록 안에서 같은 객체를 그대로 참조한다(좌표 중복·불일치 방지). 두 별성 사이·아래쪽에
// 둬서 라벨 3개가 겹치지 않는다(아래 GRAPH_LIT_LABELS 주석의 좌표 검증 참고).
const LIT_SLACK_SAT: GraphSatellite = { x: 200, y: 140, r: 3.3, type: "slack", litId: "slack" };

const GRAPH_STARS: GraphStar[] = [
  {
    cx: 155,
    cy: 100,
    r: 13,
    type: "issue",
    litId: "issue",
    satellites: [
      { x: 121, y: 100, r: 3.0, type: "commit" },
      { x: 129, y: 85, r: 3.2, type: "commit" },
      { x: 205, y: 132, r: 2.8, type: "commit" },
      { x: 139, y: 72, r: 3.0, type: "file" },
      { x: 172, y: 70, r: 3.6, type: "file" },
      { x: 155, y: 64, r: 3.3, type: "slack" },
      LIT_SLACK_SAT, // #dev-search
    ],
  },
  {
    // cy 50→66(2026-08-21 3차) — 원래 위치는 헤일로(r+14=25) 상단이 상단 툴바 오버레이에
    // 가려졌다. 위성은 중심 대비 상대 위치를 유지한 채 y만 +16 평행이동(x는 그대로).
    cx: 260,
    cy: 66,
    r: 11,
    type: "pr",
    litId: "pr",
    satellites: [
      { x: 292, y: 58, r: 3.2, type: "commit" },
      { x: 230, y: 74, r: 3.0, type: "commit" },
      { x: 270, y: 98, r: 3.4, type: "file" },
      { x: 238, y: 92, r: 2.8, type: "file" },
      { x: 290, y: 86, r: 3.1, type: "file" },
    ],
  },
  {
    cx: 110,
    cy: 185,
    r: 9,
    type: "issue",
    satellites: [
      { x: 136, y: 171, r: 2.9, type: "commit" },
      { x: 86, y: 195, r: 2.7, type: "commit" },
      { x: 124, y: 211, r: 3.0, type: "file" },
      { x: 94, y: 161, r: 2.8, type: "slack" },
    ],
  },
  {
    cx: 330,
    cy: 175,
    r: 8,
    type: "pr",
    satellites: [
      { x: 354, y: 159, r: 2.8, type: "commit" },
      { x: 308, y: 189, r: 2.6, type: "commit" },
      { x: 348, y: 197, r: 3.0, type: "file" },
      { x: 316, y: 153, r: 2.7, type: "file" },
    ],
  },
];

// 다리 — 같은 파일·티켓을 공유한 작업을 잇는 완만한 곡선(실앱 drawBridges와 같은 문법:
// quadratic 곡선, 별성보다 저알파). 1↔2, 2↔4를 잇는다(1↔4는 잇지 않는다 — "다 연결되진
// 않는다"는 현실감 유지). PR 별성 cy 이동(3차)에 맞춰 두 곡선의 끝점·제어점을 다시 계산했다
// — 원래 제어점이 직선 중점에서 벌어진 오프셋((5.5,13), (-15,8.5))을 그대로 유지한 채
// 새 중점으로 옮겼다(곡률은 그대로, 끝점만 따라간다).
const GRAPH_BRIDGES = ["M155,100 Q213,96 260,66", "M260,66 Q280,129 330,175"];

// 성좌에 속하지 않은 자유 노드 — 성좌 사이 빈 공간에 흩어진 먼지.
const GRAPH_DUST: Array<{ x: number; y: number; r: number; type: string }> = [
  { x: 200, y: 150, r: 2.0, type: "file" },
  { x: 30, y: 55, r: 1.8, type: "commit" },
  { x: 370, y: 40, r: 2.0, type: "issue" },
  { x: 170, y: 232, r: 1.9, type: "slack" },
  { x: 378, y: 215, r: 1.8, type: "pr" },
];

// 배경 먼지 별 — 실앱 makeStarfield처럼 무작위 생성하는 대신, 정적 장면이라 좌표를 고정해
// 손으로 흩뿌렸다(항상 같은 하늘). 원래도 저알파라 점등 연출과 무관하게 그대로 둔다.
const GRAPH_BG_STARS = [
  { x: 15, y: 15, r: 0.8, a: 0.06 },
  { x: 390, y: 20, r: 0.7, a: 0.08 },
  { x: 255, y: 12, r: 0.9, a: 0.07 },
  { x: 60, y: 240, r: 0.6, a: 0.09 },
  { x: 345, y: 235, r: 1.0, a: 0.06 },
  { x: 12, y: 175, r: 0.8, a: 0.08 },
  { x: 396, y: 115, r: 0.7, a: 0.07 },
  { x: 222, y: 235, r: 0.9, a: 0.1 },
  { x: 150, y: 12, r: 0.6, a: 0.06 },
  { x: 48, y: 110, r: 1.1, a: 0.08 },
  { x: 312, y: 15, r: 0.8, a: 0.07 },
  { x: 185, y: 245, r: 0.7, a: 0.09 },
];

interface GraphLitNode {
  id: string;
  x: number;
  y: number;
  r: number;
  /** 헤일로 반지름 — 별성(큰 원)과 위성(작은 원)의 크기 차이가 커서 히어로 패널처럼 고정
      오프셋 하나를 쓰면 위성의 헤일로가 과하게 커 보인다. 노드별로 직접 지정한다. */
  haloR: number;
}

// 앰버 링(r+4~5)·엣지 트림 여유 — 히어로 패널(HeroProductSlot.tsx RING_GAP·trimLitEdge)과
// 같은 값을 써서 두 슬롯의 점등 문법을 통일한다.
const RING_GAP = 5;
const EDGE_TRIM_GAP = RING_GAP + 1.5;

// 점등 트리오 — 스토리 순서(HT-64 → PR #142 → #dev-search) 그대로. 별성 둘은 위 GRAPH_STARS
// 좌표를 그대로 참조해 두 값이 어긋날 일이 없다.
const LIT_ISSUE: GraphLitNode = {
  id: "issue",
  x: GRAPH_STARS[0].cx,
  y: GRAPH_STARS[0].cy,
  r: GRAPH_STARS[0].r,
  haloR: 27,
};
const LIT_PR: GraphLitNode = {
  id: "pr",
  x: GRAPH_STARS[1].cx,
  y: GRAPH_STARS[1].cy,
  r: GRAPH_STARS[1].r,
  haloR: 25,
};
const LIT_SLACK: GraphLitNode = {
  id: "slack",
  x: LIT_SLACK_SAT.x,
  y: LIT_SLACK_SAT.y,
  r: LIT_SLACK_SAT.r,
  haloR: 10,
};
const GRAPH_LIT_NODES = [LIT_ISSUE, LIT_PR, LIT_SLACK];

// 점등 경로 — HT-64 → PR #142 → #dev-search 순서 그대로 잇는다.
const GRAPH_LIT_EDGES: Array<[GraphLitNode, GraphLitNode]> = [
  [LIT_ISSUE, LIT_PR],
  [LIT_PR, LIT_SLACK],
];

// 점등 노드 mono 라벨 — 라틴 기술 토큰만(모노 스코프 규칙), HOW IT WORKS·히어로 패널
// 라벨과 같은 문법(노드 중심 + dx/dy 오프셋). 뷰박스(400×250) 기준 앵커 좌표: HT-64
// (175,84), PR #142(280,60 — PR 별성 cy 50→66 이동에 맞춰 3차에서 44→60으로 따라감),
// #dev-search(212,130) — 셋 다 라벨 상자(가장 긴 "#dev-search" 11자 ≈ 80px, 줄높이
// ≈15px)를 더해도 겹치지 않는다. 이 섹션 컨테이너 폭이 가장 좁아지는 두 지점(데스크톱
// ≈572px → 1cqw≈1.43px, 모바일 390px 뷰포트에서 슬롯 ≈342px → 1cqw≈0.855px) 모두
// 좌표로 재검증했다 — 가장 가까운 쌍(모바일의 HT-64/PR #142)은 y 상자 사이 5px 남짓으로
// 좁아졌지만 x 상자가 전혀 겹치지 않아(53px+ 이격) 안전하고, PR #142/#dev-search는
// 모바일에서 x가 겹치는 구간이 있지만 y가 45px 이상 벌어져 안전하다(겹침 판정은 두 축
// 중 하나만 분리되면 충분). PR #142는 상단 툴바(데스크톱 ≈48px 높이, 모바일은 숨김)
// 아래로 더 넉넉한 여유(라벨 상자 상단 ≈86px)를 갖게 됐다.
const GRAPH_LIT_LABELS: Array<{ node: GraphLitNode; text: string; dx: number; dy: number }> = [
  { node: LIT_ISSUE, text: "HT-64", dx: 20, dy: -16 },
  { node: LIT_PR, text: "PR #142", dx: 20, dy: -6 },
  { node: LIT_SLACK, text: "#dev-search", dx: 12, dy: -10 },
];

// 점등 엣지를 노드 중심이 아니라 링 바깥에서 시작/끝나게 다듬는다(히어로 패널
// trimLitEdge와 동일 방식) — 노드 채움이 타입 색을 유지하므로 앰버 선이 노드 위를
// 가로지르면 안 된다(링과 경로가 분리돼 보여야 한다).
function trimLitEdge(a: GraphLitNode, b: GraphLitNode) {
  const dx = b.x - a.x;
  const dy = b.y - a.y;
  const d = Math.hypot(dx, dy);
  const offA = a.r + EDGE_TRIM_GAP;
  const offB = b.r + EDGE_TRIM_GAP;
  return {
    x1: a.x + (dx / d) * offA,
    y1: a.y + (dy / d) * offA,
    x2: b.x - (dx / d) * offB,
    y2: b.y - (dy / d) * offB,
  };
}

// 절제 원칙(브리프 B-4) — 페이지에 이미 움직이는 그래프가 둘(히어로·작동 방식)이라 여기는
// 점등 오버레이(채움·링·헤일로·경로·라벨)만 IO 1회 트리거로 재생한다(기존 is-played 패턴
// 재사용) — 배경 먼지·성좌·다리는 전부 정적이다.
//
// 2026-08-21 3차: 트리오가 한 번에 밝아지던 것을 "탐색" 서사로 바꿨다 — 장면 전체(트리오
// 포함)가 딤 상태로 시작하고, HT-64 발화 → 엣지 드로잉 → PR#142 발화 → 엣지 드로잉 →
// #dev-search 발화 순으로 하나씩 밝힌다. 순서·시점은 CSS만으로 관리한다: 각 노드/엣지가
// GRAPH_LIT_NODES.id·GRAPH_LIT_EDGES 쌍에서 파생된 "베이스 클래스 + id 수식자 클래스" 두
// 개(lp-feature-graph-node--ignite-{id}는 id가 클래스명에 이미 녹아 있어 하나,
// lp-feature-graph-lit-ring/-halo/-label과 --{id}, lp-feature-graph-lit-edge와
// --{a}-{b}는 두 클래스 조합)를 받고, landing.css가 그 조합을 겨냥한 복합 셀렉터에
// animation-delay 리터럴 ms 값을 직접 선언한다(2026-08-21 3차 재수정 — 커스텀 프로퍼티
// 간접 참조로 지연이 전혀 적용되지 않는 실측 결함이 있어 이 방식으로 바꿨다). 지속시간
// 상수(--gx-node-dur·--gx-edge-dur)는 여전히 landing.css 로컬 변수다(.lp-feature-graph
// 주석 참고 — 사람 튜닝 기준점).
//
// 캔버스가 슬롯 전체를 채우고 툴바(lp-feature-graph-header)는 그 위에 뜨는 오버레이다.
// DOM 순서는 캔버스(배경) → 오버레이지만, 오버레이가 position을 가져 항상 위로 그려진다.
function GraphExplorerPreview() {
  const { ref, inView } = useInViewOnce<HTMLDivElement>();
  return (
    <div className={`lp-feature-graph${inView ? " is-played" : ""}`} ref={ref}>
      <div className="lp-feature-graph-canvas">
        <svg
          className="lp-feature-graph-scene"
          viewBox={`0 0 ${GRAPH_SCENE_W} ${GRAPH_SCENE_H}`}
          preserveAspectRatio="xMidYMid meet"
        >
          <defs>
            {/* gradientUnits 기본값(objectBoundingBox) — 노드마다 위치·크기가 달라, 원 자신의
                바운딩 박스에 맞춰 자동으로 맞춰지는 편이 노드별로 gradient를 따로 두는 것보다
                간단하다(점등 노드가 하나뿐이던 이전 버전은 userSpaceOnUse로 고정 좌표를 썼다). */}
            <radialGradient id="lp-feature-graph-halo-gradient">
              <stop offset="0%" className="lp-feature-graph-halo-stop-start" />
              <stop offset="100%" className="lp-feature-graph-halo-stop-end" />
            </radialGradient>
          </defs>

          <g className="lp-feature-graph-bg">
            {GRAPH_BG_STARS.map((s, i) => (
              <circle
                key={i}
                className="lp-feature-graph-bg-star"
                cx={s.x}
                cy={s.y}
                r={s.r}
                fillOpacity={s.a}
              />
            ))}
          </g>

          <g className="lp-feature-graph-bridges">
            {GRAPH_BRIDGES.map((d, i) => (
              <path key={i} className="lp-feature-graph-bridge" d={d} />
            ))}
          </g>

          <g className="lp-feature-graph-spokes">
            {GRAPH_STARS.map((star, i) =>
              star.satellites.map((sat, j) => (
                <line
                  key={`${i}-${j}`}
                  className="lp-feature-graph-spoke"
                  x1={star.cx}
                  y1={star.cy}
                  x2={sat.x}
                  y2={sat.y}
                />
              )),
            )}
          </g>

          <g className="lp-feature-graph-satellites">
            {GRAPH_STARS.map((star, i) =>
              star.satellites.map((sat, j) => (
                <circle
                  key={`${i}-${j}`}
                  className={`lp-feature-graph-satellite lp-feature-graph-dot--${sat.type} lp-feature-graph-node--dim${
                    sat.litId ? ` lp-feature-graph-node--ignite-${sat.litId}` : ""
                  }`}
                  cx={sat.x}
                  cy={sat.y}
                  r={sat.r}
                />
              )),
            )}
          </g>

          <g className="lp-feature-graph-dust">
            {GRAPH_DUST.map((d, i) => (
              <circle
                key={i}
                className={`lp-feature-graph-dust-node lp-feature-graph-dot--${d.type}`}
                cx={d.x}
                cy={d.y}
                r={d.r}
              />
            ))}
          </g>

          <g className="lp-feature-graph-stars">
            {GRAPH_STARS.map((star, i) => (
              <circle
                key={i}
                className={`lp-feature-graph-star lp-feature-graph-dot--${star.type} lp-feature-graph-node--dim${
                  star.litId ? ` lp-feature-graph-node--ignite-${star.litId}` : ""
                }`}
                cx={star.cx}
                cy={star.cy}
                r={star.r}
              />
            ))}
          </g>

          {/* 점등 오버레이 — 이 슬롯에서 앰버가 허용되는 지점(답변 경로, DESIGN.md 시그니처).
              기존 브리지·스포크와 별개 레이어라 그 위에 겹쳐 그려도 원래 성좌 문법을 건드리지
              않는다. 각 요소는 "베이스 클래스 + id(엣지는 두 끝점 id) 수식자 클래스" 두 개를
              받아 landing.css의 복합 셀렉터 딜레이 규칙과 짝을 맞춘다(순차 발화 안무,
              landing.css .lp-feature-graph 주석 참고 — 커스텀 프로퍼티 간접 참조 없이
              리터럴 ms 값을 직접 매긴다). 엣지는 <line>이 pathLength를 안정적으로 반영하지
              않는 문제(실측 확인)로 <path d="M…L…">로 그린다. */}
          <g className="lp-feature-graph-lit-edges">
            {GRAPH_LIT_EDGES.map(([a, b]) => {
              const p = trimLitEdge(a, b);
              return (
                <path
                  key={`${a.id}-${b.id}`}
                  className={`lp-feature-graph-lit-edge lp-feature-graph-lit-edge--${a.id}-${b.id}`}
                  d={`M${p.x1},${p.y1} L${p.x2},${p.y2}`}
                  pathLength={1}
                />
              );
            })}
          </g>
          <g className="lp-feature-graph-lit-halos">
            {GRAPH_LIT_NODES.map((n) => (
              <circle
                key={`halo-${n.id}`}
                className={`lp-feature-graph-lit-halo lp-feature-graph-lit-halo--${n.id}`}
                cx={n.x}
                cy={n.y}
                r={n.haloR}
                fill="url(#lp-feature-graph-halo-gradient)"
              />
            ))}
          </g>
          <g className="lp-feature-graph-lit-rings">
            {GRAPH_LIT_NODES.map((n) => (
              <circle
                key={`ring-${n.id}`}
                className={`lp-feature-graph-lit-ring lp-feature-graph-lit-ring--${n.id}`}
                cx={n.x}
                cy={n.y}
                r={n.r + RING_GAP}
              />
            ))}
          </g>
        </svg>

        <div className="lp-feature-graph-labels">
          {GRAPH_LIT_LABELS.map((l) => (
            <span
              key={l.node.id}
              className={`lp-feature-graph-lit-label lp-feature-graph-lit-label--${l.node.id}`}
              style={
                {
                  left: `${(l.node.x * GRAPH_SCENE_UNIT_CQW).toFixed(2)}cqw`,
                  top: `${(l.node.y * GRAPH_SCENE_UNIT_CQW).toFixed(2)}cqw`,
                  "--lx": `${l.dx}px`,
                  "--ly": `${l.dy}px`,
                } as CSSProperties
              }
            >
              {l.text}
            </span>
          ))}
        </div>
      </div>
      <div className="lp-feature-graph-header">
        <div className="lp-feature-graph-toolbar">
          <div className="lp-feature-graph-actions">
            <span className="lp-feature-graph-action">그래프 재구축</span>
            <span className="lp-feature-graph-action">정밀 재구축 (LLM)</span>
          </div>
          <span className="lp-feature-graph-stats">
            <span className="lp-feature-graph-stats-mono">6,212</span> 노드{" "}
            <span className="lp-feature-graph-stats-mono">·</span>{" "}
            <span className="lp-feature-graph-stats-mono">19,038</span> 연결
          </span>
        </div>
      </div>
    </div>
  );
}

// ── 기능 3: 데이터 소스 — 실앱 SourcesPage 화면 구조 재현 (2026-08-21 재구축) ──────
// 실앱(SourcesPage.tsx·sources.css)은 "페이지 타이틀+서브 → '연동 중' 섹션 라벨(우측
// 헤어라인) → 연동 행들 → '추가 가능' 섹션 라벨 → 타일 그리드"다. 이전 라운드의 요약
// 스트립(총 노드/연결 수)·수집 항목 수·"동기화" 버튼·하단 카탈로그 로고 스트립은 실앱에
// 없는 랜딩 전용 요약이었으므로 폐기하고 이 구조로 완전히 재구축했다 — 수치는 기능 2
// 슬롯에만 남아 교차 정합 문제는 없다. 로고는 각 서비스의 공식 브랜드 마크 SVG(패스 원본
// 그대로, 근사·재해석 없음)다 — DESIGN.md "커넥터 로고는 브랜드 색" 규칙 적용(노드 색을
// 빌리지 않는다). 타일의 "연결" 라벨은 미리보기 안에서 포커스 대상이 되면 안 되므로 실제
// <button>이 아니라 스타일된 <span>이고, 앰버는 쓰지 않는다(라이브 컨트롤이 아니다).

// 연동 행 3개 — 실앱과 동일하게 GitHub·Jira·Slack이 "연동 중"이다. Jira의 target은
// 프로젝트 키(라틴, 모노)와 그 뒤에 붙는 한글 단어("프로젝트")로 나뉜다 — 모노는 라틴
// 기술 토큰 전용이라는 스코프 규칙 때문(DESIGN.md).
const SOURCE_ROWS = [
  { key: "github", name: "GitHub", target: "org/history-tracker", targetLabel: null, Mark: GithubMark },
  { key: "jira", name: "Jira", target: "HT", targetLabel: "프로젝트", Mark: JiraMark },
  { key: "slack", name: "Slack", target: "#dev-search", targetLabel: null, Mark: SlackMark },
] as const;

// "추가 가능" 타일 6개 — 나머지 소스(sourceCatalog.tsx의 실제 name·description을 그대로
// 옮긴다). 소스 카탈로그 자체를 import하지 않는 이유는 이 미리보기가 실제 연동 상태와
// 무관한 고정 데모 데이터이기 때문(레일 데모 데이터와 같은 원칙).
const SOURCE_TILES = [
  { key: "notion", name: "Notion", description: "문서 맥락", Mark: NotionMark },
  { key: "discord", name: "Discord", description: "대화 맥락", Mark: DiscordMark },
  { key: "google-chat", name: "Google Chat", description: "대화 맥락", Mark: GoogleChatMark },
  { key: "linear", name: "Linear", description: "이슈 맥락", Mark: LinearMark },
  { key: "asana", name: "Asana", description: "작업 맥락", Mark: AsanaMark },
  { key: "clickup", name: "ClickUp", description: "작업 맥락", Mark: ClickUpMark },
] as const;

function DataSourcesPreview() {
  return (
    <div className="lp-feature-sources">
      <div className="lp-feature-sources-head">
        <h3 className="lp-feature-sources-title">데이터 소스</h3>
        <p className="lp-feature-sources-sub">코드와 의사결정의 원본을 하나의 그래프로 모읍니다.</p>
      </div>

      <div className="lp-feature-sources-label">연동 중</div>
      <div className="lp-feature-source-rows">
        {SOURCE_ROWS.map((s) => (
          <div className="lp-feature-source-row" key={s.key}>
            <div className="lp-feature-source-logo-chip">
              <s.Mark size={20} />
            </div>
            <div className="lp-feature-source-meta">
              <span className="lp-feature-source-name">{s.name}</span>
              <span className="lp-feature-source-sub">
                <span className="lp-feature-source-sub-mono">{s.target}</span>
                {s.targetLabel && (
                  <span className="lp-feature-source-sub-label">{s.targetLabel}</span>
                )}
              </span>
            </div>
            <div className="lp-feature-source-status">
              <span className="lp-feature-source-pill">
                <span className="lp-feature-source-dot" />
                연결됨
              </span>
              <span className="lp-feature-source-synced">
                마지막 수집{" "}
                <span className="lp-feature-source-synced-mono">2026-07-24 14:32</span>
              </span>
            </div>
          </div>
        ))}
      </div>

      <div className="lp-feature-sources-label">추가 가능</div>
      <div className="lp-feature-source-tiles">
        {SOURCE_TILES.map((s) => (
          <div className="lp-feature-source-tile" key={s.key}>
            <div className="lp-feature-source-logo-chip lp-feature-source-logo-chip--sm">
              <s.Mark size={18} />
            </div>
            <div className="lp-feature-source-tile-meta">
              <span className="lp-feature-source-tile-name">{s.name}</span>
              <span className="lp-feature-source-tile-sub">{s.description}</span>
            </div>
            <span className="lp-feature-source-tile-action">연결</span>
          </div>
        ))}
      </div>
    </div>
  );
}
