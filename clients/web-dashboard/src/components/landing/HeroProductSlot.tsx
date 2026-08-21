import type { CSSProperties } from "react";

import { GithubMark, JiraMark, SlackMark } from "@/components/brand/BrandMarks";

// 히어로 하단 제품 UI 블록 = 영상 슬롯 (2026-07-25 4차 상하 구조·5차 와이드화,
// 2026-07-26 6차 기준선 통일). 텍스트 블록 아래에서 컨테이너 콘텐츠 폭 전체(1440 뷰포트에서
// 1200×633, 종횡비 1280:675 ≈ 1.9:1 — 16:10이 "세로가 길다"로 읽혀 와이드로 조정)를 쓰고,
// 슬롯 좌측은 로고·히어로 텍스트와 같은 단일 기준선에 앉는다(5차의 좌우 대칭 확장은
// 기준선 통일과 양립하지 않아 폐기 — landing.css .lp-hero-slot 주석 참조).
// 하단만 첫 화면(폴드) 경계에서 잘린다 — 좌·우 엣지와 상단 크롬은 온전하다. 실제 앱의
// 대화 화면 3단 셸(좌측 레일 → 채팅 스트림+컴포저 → 우측 "관련 그래프" 패널)을 DESIGN.md
// 토큰으로 재현한 라이브 DOM이다. 기능 1 섹션(FeatureSections.tsx ChatPreview)과 같은 시각
// 언어를 쓰므로 레일·버블·답변·출처 카드·컴포저는 그쪽의 .lp-feature-chat-* 클래스를 그대로
// 재사용한다(채팅 공통 언어 — 두 화면이 같은 제품임을 보장하는 장치. 단 히어로 슬롯은
// 실폭이 훨씬 넓어 폰트를 실제 앱 밀도(본문 15px)까지 키운다 — landing.css의 .lp-hero-app
// 스코프 오버라이드). 데모 스토리도 기능 섹션과 동일한 HT-64 → PR #142 → #dev-search
// 한 줄이고, 출처 카드 3장·레일 내비·대화 히스토리는 기능 1 미리보기와 문구까지 동일하다 —
// 같은 제품의 같은 화면이므로 어긋나면 교차 검증에 걸린다. 4차에서도 대화 내용(버블·답변·
// 카드 3장·점등 트리오)은 한 글자도 바뀌지 않았다 — 크기·폭·배치와 레일 추가뿐(단, 레일
// 항목·출처 카드 head·컴포저 placeholder는 2026-08-21 실앱 일치화로 갱신 — 아래 참조).
// 레일의 활성 표시는 앰버가 아니라 중립 강조(surface 상승·텍스트 강조)다 — 히어로의 앰버
// 초점은 패널 점등·컴포저 전송·CTA에만 남긴다(landing.css .lp-hero-app 레일 오버라이드).
//
// ── 영상 교체 지점 ────────────────────────────────────────────
// 제품 영상(질문 → 답변 → 그래프 점등)이 완성되면 이 컨테이너(.lp-hero-slot) 내부의
// .lp-hero-app 전체를 <video>로 교체한다. 프레임(헤어라인 보더·radius·edge-highlight·
// surface 배경)과 고정 종횡비(1280:675 ≈ 1.9:1)는 컨테이너(.lp-hero-slot)가 소유하므로
// 내용물만 갈아끼우면 레이아웃이 바뀌지 않는다(표준 16:9 녹화 영상은 object-fit: cover로
// 상하 수 % 크롭하면 맞는다). "하단만 폴드에서 잘림"도 컨테이너 레벨의 성질이다 — 슬롯이
// 문서 흐름에서 폴드 아래로 이어질 뿐 클리핑이 없으므로, <video>로 바꿔도 같은 방식으로
// 하단이 잘리고 스크롤하면 드러난다. 모바일(<768px)은 현재 레일·관련 그래프 패널을 숨기고
// 종횡비를 풀어(auto 높이) 채팅 스트림 중심으로 보여주므로(landing.css 반응형 블록), 영상
// 교체 시 모바일 처리(고정 종횡비 복원 또는 세로 크롭)를 함께 결정할 것.

type PanelNodeType = "commit" | "pr" | "issue" | "slack" | "jira" | "person" | "file";

interface PanelNode {
  id: string;
  x: number;
  y: number;
  r: number;
  type: PanelNodeType;
  /** 답변 근거로 인용돼 앰버로 점등되는 노드인지(출처 카드와 같은 대상). */
  lit?: boolean;
}

// 패널 그래프 좌표계 — 세로 확장 뷰박스(2026-07-25 4차 재설계, 5차 와이드화로 630→600).
// 3차의 "우측 크롭 대응" 지오메트리(가로 확장 440×480, 우측 배경 전용 구간)는 폐기됐다 —
// 전폭 슬롯에서 패널은 가로로 온전히 보이고, 잘리는 방향은 하단(첫 화면 폴드)뿐이다.
// 그래서 뷰박스를 패널 실폭(340px)×슬롯 잔여 높이(5차: 675 − 크롬 − 타이틀 ≈ 600px)로
// 세로로 늘리고 크롭을 지오메트리가 흡수한다: 점등 트리오·라벨은 상단 가시 영역
// (5차 1440×900 폴드 기준 — 텍스트 하향으로 4차보다 줄어든 y ≲ 330) 안에만 두고, 하단
// (폴드 아래로 이어지는 구간, y>390)은 저투명 배경 노드·엣지만 이어지게 해 "그래프가
// 화면 아래로 계속된다"로 읽히게 한다. 캔버스(.lp-hero-app-panel-canvas)가 같은 비율의
// aspect-ratio를 갖도록 고정해(landing.css) SVG가 레터박스·크롭 없이 항상 폭 기준으로
// 렌더되게 한다 — HTML 라벨 오버레이의 cqw 위치 계산이 이 전제 위에 선다(MiniGraph.tsx의
// 기능 2 라벨과 같은 방식). 패널 실폭이 340px 고정이라 뷰박스 1단위 ≈ 1px로 읽으면 된다.
const PANEL_W = 340;
const PANEL_H = 600;
const UNIT_TO_CQW = 100 / PANEL_W;

const PANEL_NODES: PanelNode[] = [
  // 점등(인용) 노드 3개 — 출처 카드 #1~#3과 같은 대상. 채움은 노드 타입 색을 유지하고
  // 앰버는 링·헤일로·경로에만 얹는다(비점등 노드는 노드 타입 색, 점등만 앰버).
  // 셋 다 라벨 끝(x + 14 + 텍스트 폭)이 340 안, 헤일로 하단(y + r + 13)이 305 안 —
  // 5차 1440×900 폴드 가시 영역(y ≲ 330)의 안전 마진 안에 들어온다.
  { id: "issue", x: 86, y: 96, r: 7.5, type: "issue", lit: true },
  { id: "pr", x: 188, y: 190, r: 7.5, type: "pr", lit: true },
  { id: "slack", x: 104, y: 284, r: 7.5, type: "slack", lit: true },
  // 배경(비인용) 노드 — 답변과 무관한 주변 그래프. 저투명으로 가라앉힌다.
  // 상단 가시 영역(y<390): 트리오 주변 + 좌우 공백 채움.
  { id: "b0", x: 252, y: 60, r: 5, type: "commit" },
  { id: "b1", x: 46, y: 44, r: 4.5, type: "jira" },
  { id: "b2", x: 28, y: 168, r: 5, type: "file" },
  { id: "b3", x: 270, y: 140, r: 5, type: "person" },
  { id: "b4", x: 302, y: 248, r: 4.5, type: "file" },
  { id: "b5", x: 44, y: 246, r: 5, type: "commit" },
  { id: "b6", x: 160, y: 38, r: 4.5, type: "person" },
  { id: "b7", x: 306, y: 48, r: 5, type: "pr" },
  { id: "b8", x: 238, y: 318, r: 4.5, type: "slack" },
  { id: "b9", x: 42, y: 352, r: 4.5, type: "jira" },
  // 하단(1440×900에서 폴드 아래로 이어지는 구간, y>390): 배경 전용 — 점등·라벨 금지.
  { id: "b10", x: 128, y: 404, r: 4.5, type: "file" },
  { id: "b11", x: 262, y: 420, r: 5, type: "commit" },
  { id: "b12", x: 58, y: 456, r: 4.5, type: "person" },
  { id: "b13", x: 306, y: 500, r: 4.5, type: "jira" },
  { id: "b14", x: 168, y: 522, r: 5, type: "file" },
  { id: "b15", x: 86, y: 568, r: 4.5, type: "commit" },
  { id: "b16", x: 276, y: 574, r: 4.5, type: "file" },
  { id: "b17", x: 206, y: 476, r: 4.5, type: "slack" },
];

// 배경 엣지 — 헤어라인 저투명. 인용 노드도 주변과 이어져 있어야 "그래프의 단면"으로 읽히고,
// 상단 가시 영역의 노드가 하단(폴드 아래 구간)의 노드와 이어져야 크롭이 "계속됨"의 서사가 된다.
const PANEL_BG_EDGES: Array<[string, string]> = [
  ["issue", "b1"],
  ["issue", "b2"],
  ["issue", "b6"],
  ["pr", "b0"],
  ["pr", "b3"],
  ["pr", "b4"],
  ["slack", "b5"],
  ["slack", "b8"],
  ["slack", "b9"],
  ["b6", "b0"],
  ["b0", "b7"],
  ["b3", "b7"],
  ["b4", "b8"],
  ["b2", "b5"],
  ["b8", "b11"],
  ["b9", "b10"],
  ["b10", "b12"],
  ["b10", "b17"],
  ["b11", "b13"],
  ["b11", "b17"],
  ["b17", "b14"],
  ["b12", "b15"],
  ["b14", "b15"],
  ["b13", "b16"],
  ["b14", "b16"],
];

// 점등 경로 — 스토리 순서(HT-64 → PR #142 → #dev-search) 그대로 잇는다.
const PANEL_LIT_EDGES: Array<[string, string]> = [
  ["issue", "pr"],
  ["pr", "slack"],
];

// 점등 노드 mono 라벨 — 라틴 기술 토큰만(모노 스코프 규칙). SVG <text>는 뷰박스 스케일을
// 따라 줄어들어 12px 하한을 못 지키므로, 기능 2 라벨과 같은 HTML 오버레이(고정 13px —
// 3차 대형화의 폰트 스케일업 동반, 12px 하한 위)로 둔다.
const PANEL_LABELS: Array<{ id: string; text: string; dx: number; dy: number }> = [
  // dx 14 — 점등 링(r+4, 캔버스가 커지는 구간에서 ~11px)에 라벨이 닿지 않는 오프셋.
  { id: "issue", text: "HT-64", dx: 14, dy: -7 },
  { id: "pr", text: "PR #142", dx: 14, dy: -7 },
  { id: "slack", text: "#dev-search", dx: 14, dy: -7 },
];

const RING_GAP = 4; // 점등 링(r + RING_GAP)
const HALO_GAP = 13; // 헤일로(r + HALO_GAP)

// 점등 엣지를 노드 중심이 아니라 링 바깥에서 시작/끝나게 다듬는다 — 노드 채움이 타입 색을
// 유지하므로 앰버 선이 노드 위를 가로지르면 안 된다(링과 경로가 분리돼 보여야 한다).
function trimLitEdge(a: PanelNode, b: PanelNode) {
  const dx = b.x - a.x;
  const dy = b.y - a.y;
  const d = Math.hypot(dx, dy);
  const offA = a.r + RING_GAP + 1.5;
  const offB = b.r + RING_GAP + 1.5;
  return {
    x1: a.x + (dx / d) * offA,
    y1: a.y + (dy / d) * offA,
    x2: b.x - (dx / d) * offB,
    y2: b.y - (dy / d) * offB,
  };
}

export function HeroProductSlot() {
  const byId = new Map(PANEL_NODES.map((n) => [n.id, n]));
  const litNodes = PANEL_NODES.filter((n) => n.lit);

  return (
    <div className="lp-hero-slot" aria-hidden="true">
      {/* ⚠️ 영상 완성 시 아래 .lp-hero-app 전체를 <video>로 교체한다. 프레임/16:10 종횡비는
          컨테이너(.lp-hero-slot)가 유지하므로 이 내부만 갈아끼우면 된다(파일 상단 주석 참조). */}
      <div className="lp-hero-app">
        {/* 상단 윈도우 크롬 — 가짜 파일명 바 대신 제품 내 위치를 말하는 브레드크럼(기능 1과
            동일 패턴). 얇은 바 + 헤어라인만. */}
        <div className="lp-hero-app-chrome">
          <span>history tracker</span>
          <span className="lp-hero-app-chrome-sep">/</span>
          <span className="lp-hero-app-chrome-current">대화</span>
        </div>
        <div className="lp-hero-app-body">
          {/* 좌: 레일(내비 + 대화 히스토리) — 실제 앱 3단 셸의 첫 컬럼(4차 신규, ≥1180px 전용).
              기능 1 미리보기의 레일과 같은 클래스·같은 데모 데이터를 쓴다(같은 제품의 같은
              사이드바 — 문구·날짜를 새로 발명하지 않는다). 항목은 전부 한글이라 본문 서체
              (모노는 Ctrl+K·날짜 같은 라틴 토큰만). 활성 내비("대화")·활성 대화(첫 히스토리
              항목)는 앰버가 아니라 중립 강조(surface-2 상승 + 텍스트 강조)다 — 히어로의 앰버
              초점은 패널 점등·컴포저 전송·CTA에만 남긴다(landing.css .lp-hero-app 오버라이드). */}
          <div className="lp-feature-chat-rail">
            <nav className="lp-feature-chat-rail-nav">
              <div className="lp-feature-chat-rail-item">
                <span>검색</span>
                <span className="lp-feature-chat-rail-kbd">Ctrl+K</span>
              </div>
              <div className="lp-feature-chat-rail-item lp-feature-chat-rail-item--active">
                대화
              </div>
              <div className="lp-feature-chat-rail-item">데이터 소스</div>
              <div className="lp-feature-chat-rail-item">액터</div>
              <div className="lp-feature-chat-rail-item">그래프 확인</div>
              <div className="lp-feature-chat-rail-item">현재 프로젝트 설정</div>
            </nav>
            <div className="lp-feature-chat-rail-divider" />
            <div className="lp-feature-chat-rail-history">
              {/* 첫 항목 = 지금 스트림에 떠 있는 대화("검색 기능 관련 PR…" 질문의 대화). */}
              <div className="lp-feature-chat-rail-history-item lp-hero-rail-history--active">
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
          {/* 중앙: 대화 컬럼 — 채팅 스트림(사용자 버블 → 답변 산문 → 출처 카드 3장) + 하단
              컴포저. 내용이 슬롯보다 길면 하단이 슬롯 경계에서 자연스럽게 잘린다(축소 금지). */}
          <div className="lp-hero-app-main">
            <div className="lp-hero-app-stream">
              <p className="lp-feature-chat-user">검색 기능 관련 PR이랑 지라 티켓 찾아줘</p>
              <p className="lp-feature-chat-answer">
                검색 랭킹 가중치는 <code className="lp-feature-chat-code">HT-64</code>에서 제기된
                편향 문제를 계기로 재설계돼 <code className="lp-feature-chat-code">PR #142</code>로
                반영됐습니다. 최종 가중치는{" "}
                <code className="lp-feature-chat-code">#dev-search</code> 스레드에서 합의됐습니다.
              </p>
              <div className="lp-hero-app-sources">
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
            {/* 하단 컴포저 — 기능 1과 같은 시각 언어(.lp-feature-chat-composer 재사용).
                대화 화면 구조의 일부(스트림 아래 입력창)라 "콘텐츠 불변" 원칙과 충돌하지
                않는다 — 대화 내용(버블·답변·카드·점등)은 그대로다. 전송 버튼 앰버는
                "실행"(DESIGN.md 허용 지점). margin-top:auto(landing.css .lp-hero-app-composer)가
                커진 슬롯의 남는 세로 공간을 흡수해 컴포저를 실제 앱처럼 하단에 붙인다. */}
            <div className="lp-feature-chat-composer lp-hero-app-composer">
              <div className="lp-feature-chat-composer-box">
                <span className="lp-feature-chat-composer-placeholder">
                  history tracker에 무엇이든 물어보세요. Shift+Enter로 줄바꿈
                </span>
                <div className="lp-feature-chat-composer-actions">
                  <span className="lp-feature-chat-composer-send">
                    {/* 앱 Icons.tsx의 Send 패스 — FeatureSections.tsx SendGlyph와 동일 원본. */}
                    <svg width={12} height={12} viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
                      <path d="M2 8 14 2.5 11 14l-3-5-6-1z" />
                    </svg>
                  </span>
                </div>
              </div>
            </div>
          </div>
          {/* 우: 관련 그래프 패널 — 답변 근거 노드가 앰버 링·헤일로·경로로 점등되는 시그니처
              순간의 정지 화면. 캔버스는 그래프 캔버스 층(--lp-surface-canvas). */}
          <aside className="lp-hero-app-panel">
            <p className="lp-hero-app-panel-title">관련 그래프</p>
            <div className="lp-hero-app-panel-canvas">
              <svg
                className="lp-hero-panel-svg"
                viewBox={`0 0 ${PANEL_W} ${PANEL_H}`}
                preserveAspectRatio="xMidYMid meet"
              >
                <g>
                  {PANEL_BG_EDGES.map(([a, b]) => {
                    const from = byId.get(a)!;
                    const to = byId.get(b)!;
                    return (
                      <line
                        key={`${a}-${b}`}
                        className="lp-hero-panel-edge"
                        x1={from.x}
                        y1={from.y}
                        x2={to.x}
                        y2={to.y}
                      />
                    );
                  })}
                </g>
                <g>
                  {PANEL_NODES.map((n) => (
                    <circle
                      key={n.id}
                      className={`lp-c-node--${n.type}${n.lit ? "" : " lp-hero-panel-node--dim"}`}
                      cx={n.x}
                      cy={n.y}
                      r={n.r}
                    />
                  ))}
                </g>
                {/* 점등 오버레이 — 로드 시퀀스에서 슬롯 프레임 다음에 그룹 전체가 1회 조용히
                    페이드인한다(.lp-hero-app-lit, landing.css). 루프 없음. */}
                <g className="lp-hero-app-lit">
                  {PANEL_LIT_EDGES.map(([a, b]) => {
                    const p = trimLitEdge(byId.get(a)!, byId.get(b)!);
                    return <line key={`lit-${a}-${b}`} className="lp-hero-panel-lit-edge" {...p} />;
                  })}
                  {litNodes.map((n) => (
                    <circle
                      key={`halo-${n.id}`}
                      className="lp-hero-panel-halo"
                      cx={n.x}
                      cy={n.y}
                      r={n.r + HALO_GAP}
                    />
                  ))}
                  {litNodes.map((n) => (
                    <circle
                      key={`ring-${n.id}`}
                      className="lp-hero-panel-ring"
                      cx={n.x}
                      cy={n.y}
                      r={n.r + RING_GAP}
                    />
                  ))}
                </g>
              </svg>
              {/* 점등 노드 라벨 — HTML 오버레이(고정 13px, cqw 위치 계산). 점등 그룹과 같은
                  타이밍으로 페이드인해야 하므로 같은 .lp-hero-app-lit 클래스를 공유한다. */}
              <div className="lp-hero-panel-labels lp-hero-app-lit">
                {PANEL_LABELS.map((l) => {
                  const n = byId.get(l.id)!;
                  return (
                    <span
                      key={l.id}
                      className="lp-hero-panel-label"
                      style={
                        {
                          left: `${(n.x * UNIT_TO_CQW).toFixed(2)}cqw`,
                          top: `${(n.y * UNIT_TO_CQW).toFixed(2)}cqw`,
                          "--lx": `${l.dx}px`,
                          "--ly": `${l.dy}px`,
                        } as CSSProperties
                      }
                    >
                      {l.text}
                    </span>
                  );
                })}
              </div>
            </div>
          </aside>
        </div>
      </div>
    </div>
  );
}
