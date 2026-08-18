import { GithubMark, JiraMark, SlackMark } from "@/components/brand/BrandMarks";
import { MiniGraph } from "@/components/landing/MiniGraph";
import { useInViewOnce } from "@/components/landing/useInViewOnce";
import {
  GRAPH_EXPLORER_EXTRA_EDGES,
  GRAPH_EXPLORER_EXTRA_NODES,
  GRAPH_EXPLORER_LABELS,
} from "@/lib/graphExplorerPreview";

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
//   - sources: 종횡비 없음 — 커넥터 카드 3장 높이 그대로, 억지로 늘리지 않는다.
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
    body: "GitHub·Jira·Slack을 연결해두면 새 기록이 자동으로 그래프에 편입된다.",
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
// "축소하지 말고 잘라내라": 앱 전체를 욱여넣는 대신, 좌측 사이드 레일 일부(검색·내비 5개·
// 대화 히스토리) + 상단 브레드크럼 + Q&A 한 턴(사용자 버블·답변 산문·출처 카드 3장) +
// 하단 입력창까지 이어 붙여 실제 화면의 크롭처럼 보이게 한다. 레일은 슬롯 좌측에 헤어라인
// 하나로만 구분(별도 elevation 없음 — 슬롯 전체가 이미 surface-1이므로 한 단계 더 파지 않는다).
// 답변 산문 속 라틴 기술 토큰(HT-64, PR #142, #dev-search)은 앱의 마크다운 인라인 코드
// 스타일(chat.css `.msg-content.markdown code`)을 그대로 lp 토큰으로 옮긴 인라인 코드 처리.
// 출처 카드는 DESIGN.md 스펙(surface-2, radius-md, 노드 색 배지, 모노 메타)에 mono 번호
// #1~#3을 더했다. 3번째 카드는 PR 노드(node-pr)로 세 타입(issue/message/pr)을 모두 보여준다.
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
          <div className="lp-feature-chat-rail-item">그래프 탐색</div>
          <div className="lp-feature-chat-rail-item">설정</div>
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
              <div className="lp-feature-chat-source-head">
                <span className="lp-feature-chat-source-num">#1</span>
                <span className="lp-feature-chat-badge lp-feature-chat-badge--issue">issue</span>
                <span className="lp-feature-chat-meta">HT-64 · 2026-06-12</span>
              </div>
              <p className="lp-feature-chat-source-body">검색 랭킹 가중치 개선</p>
            </div>
            <div className="lp-feature-chat-source">
              <div className="lp-feature-chat-source-head">
                <span className="lp-feature-chat-source-num">#2</span>
                <span className="lp-feature-chat-badge lp-feature-chat-badge--message">
                  message
                </span>
                <span className="lp-feature-chat-meta">#dev-search · 2026-06-11</span>
              </div>
              <p className="lp-feature-chat-source-body">가중치 0.7로 확정 — 스레드 합의</p>
            </div>
            <div className="lp-feature-chat-source">
              <div className="lp-feature-chat-source-head">
                <span className="lp-feature-chat-source-num">#3</span>
                <span className="lp-feature-chat-badge lp-feature-chat-badge--pr">pr</span>
                <span className="lp-feature-chat-meta">PR #142 · 2026-06-13</span>
              </div>
              <p className="lp-feature-chat-source-body">검색 랭킹 가중치 적용</p>
            </div>
          </div>
        </div>
        <div className="lp-feature-chat-composer">
          <div className="lp-feature-chat-composer-box">
            <span className="lp-feature-chat-composer-placeholder">
              이 코드가 왜 이렇게 바뀌었는지 물어보세요
            </span>
            <span className="lp-feature-chat-composer-send">
              <SendGlyph />
            </span>
          </div>
          <div className="lp-feature-chat-composer-hint">
            <span>
              <span className="lp-feature-chat-kbd">Enter</span> 전송
            </span>
            <span>
              <span className="lp-feature-chat-kbd">Shift+Enter</span> 줄바꿈
            </span>
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

// ── 기능 2: 그래프 탐색 — 노드 색 범례 + 그래프 캔버스 ────────────────────────────
// 범례 칩(surface-2, pill radius)은 앱 패널 층 위에 뜬 필터 UI, 캔버스는 DESIGN.md대로
// 가장 깊은 층(surface-0)이라 슬롯 배경보다 한 단계 더 파낸다. MiniGraph는 stage=3(점등
// 경로 포함)을 저투명도 처리 없이 그대로 쓴다 — 이 페이지에서 앰버가 허용되는 유일한 자리.
//
// 2026-07-24 밀도 개선 — 실제 탐색 화면의 단면으로 만든다("축소하지 말고 잘라내라"):
// 상단 툴바(재구축 액션 2개 + 노드/엣지 카운트), 노드 mono 라벨, 클러스터당 노드를 늘린
// 확장 그래프(graphExplorerPreview.ts), 우하단 줌 컨트롤을 더했다. 이 확장은 전부 MiniGraph의
// opt-in prop(extraNodes/extraEdges/labels, 기본값 빈 배열)으로만 들어간다 — "작동 방식"
// 섹션(HowItWorksSection.tsx)은 stage만 넘기므로 그 섹션의 렌더는 이 변경 전과 완전히
// 동일하다(MiniGraph.tsx 주석 참고). 액션 버튼·카운트·줌 컨트롤은 실제 컨트롤이 아니라
// 화면의 일부이므로 <span>이고 앰버를 쓰지 않는다(DESIGN.md: 앰버는 "지금 살아있는 것"에만,
// 이 슬롯에서 그 자리는 이미 점등 경로가 차지한다).
const GRAPH_LEGEND = [
  { type: "commit", label: "Commit" },
  { type: "pr", label: "PR" },
  { type: "issue", label: "Issue" },
  { type: "slack", label: "Slack" },
  { type: "jira", label: "Jira" },
  { type: "person", label: "Person" },
  { type: "file", label: "File" },
] as const;

// 절제 원칙(브리프 B-4) — 페이지에 이미 움직이는 그래프가 둘(히어로·작동 방식)이라 여기는
// 앰버 경로만 IO 1회 트리거로 부드럽게 페이드 인(드로잉 없이) — 나머지 노드/엣지는 정적이고,
// 채팅·데이터 소스 미리보기에는 진입 애니메이션을 아예 걸지 않는다. 새로 추가한 툴바·라벨·
// 줌 컨트롤도 정적이다(추가 안무 없음) — 노드/라벨은 처음부터 최종 상태로 보인다.
//
// 캔버스가 슬롯 전체를 채우고 툴바+범례(lp-feature-graph-header)는 그 위에 뜨는 오버레이다
// (landing.css .lp-feature-graph 주석 참고 — 실제 그래프 툴의 플로팅 컨트롤과 같은 취급).
// DOM 순서는 캔버스(배경) → 헤더(오버레이)지만, 헤더가 position을 가져 항상 위로 그려진다.
function GraphExplorerPreview() {
  const { ref, inView } = useInViewOnce<HTMLDivElement>();
  return (
    <div className={`lp-feature-graph${inView ? " is-played" : ""}`} ref={ref}>
      <div className="lp-feature-graph-canvas">
        <MiniGraph
          stage={3}
          extraNodes={GRAPH_EXPLORER_EXTRA_NODES}
          extraEdges={GRAPH_EXPLORER_EXTRA_EDGES}
          labels={GRAPH_EXPLORER_LABELS}
        />
        <div className="lp-feature-graph-zoom">
          <span className="lp-feature-graph-zoom-btn">+</span>
          <span className="lp-feature-graph-zoom-btn">−</span>
          <span className="lp-feature-graph-zoom-btn">⤢</span>
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
        <div className="lp-feature-graph-legend">
          {GRAPH_LEGEND.map((n) => (
            <span className="lp-feature-graph-pill" key={n.type}>
              <span className={`lp-feature-graph-dot lp-feature-graph-dot--${n.type}`} />
              {n.label}
            </span>
          ))}
        </div>
      </div>
    </div>
  );
}

// ── 기능 3: 데이터 소스 — 커넥터 카드 3장 (DESIGN.md "데이터 소스 카드" 스펙) ────────
// 로고는 각 서비스의 공식 브랜드 마크 SVG(패스 원본 그대로, 근사·재해석 없음)다 — DESIGN.md
// "커넥터 로고는 브랜드 색" 규칙 적용(노드 색을 빌리지 않는다). hex는 컴포넌트에 하드코딩하지
// 않고 tokens.css의 --brand-* 네임스페이스(--lp-* 팔레트와 분리, 앰버 독점 규칙 비대상)에서
// 가져온다. 액션은 미리보기 안에서 포커스 대상이 되면 안 되므로 실제 <button>이 아니라
// 스타일된 <span>이고, 앰버는 쓰지 않는다(라이브 컨트롤이 아니다).
//
// 2026-07-24 밀도 개선 — 세 슬롯 중 가장 성긴 곳이라 가장 많이 채운다("요약본"이 아니라
// 실제 데이터 소스 화면의 단면으로): 상단 요약 스트립(총 노드/연결 수 — 기능 2 슬롯의 같은
// 값과 교차 검증됨), 커넥터별 대상 식별자, 상태 점 + mono 상태 텍스트, 커넥터별 수집 항목 수
// (mono — 이 밀도의 핵심), 하단 "+ 데이터 소스 추가" 고스트 행을 더했다. 상태 점은 시맨틱
// success다(node-commit과 hex는 같지만 노드를 가리키지 않으므로 --lp-node-commit이 아니라
// --lp-success를 쓴다 — DESIGN.md 네임스페이스 규칙. tokens.css에 없던 토큰이라 새로
// 추가했다). 이 슬롯에는 "라이브/실행/선택"에 해당하는 것이 없으므로 앰버는 어디에도
// 쓰지 않는다.

const CONNECTOR_LOGOS = {
  github: GithubMark,
  jira: JiraMark,
  slack: SlackMark,
} as const;

// 커넥터별 대상 식별자·동기화 시각·수집 항목 수 — 실제 동기화라면 몇 분 차이가 나는 게
// 자연스러워 완전히 동일한 시각을 쓰지 않는다(14:32 / 14:31 / 14:32). 단 아래 요약
// 스트립의 "마지막 동기화"는 이 중 가장 최근 값(14:32)과 일치시킨다.
const CONNECTORS = [
  {
    key: "github",
    name: "GitHub",
    target: "org/history-tracker",
    targetLabel: null,
    syncedAt: "2026-07-24 14:32",
    counts: "1,240 commits · 86 PRs",
  },
  {
    key: "jira",
    name: "Jira",
    target: "HT",
    targetLabel: "프로젝트",
    syncedAt: "2026-07-24 14:31",
    counts: "312 issues",
  },
  {
    key: "slack",
    name: "Slack",
    target: "#dev-search",
    targetLabel: null,
    syncedAt: "2026-07-24 14:32",
    counts: "4,180 messages",
  },
] as const;

function DataSourcesPreview() {
  return (
    <div className="lp-feature-sources">
      {/* 총량 요약 — 기능 2 슬롯(.lp-feature-graph-stats)과 같은 수치("6,212" · "19,038")를
          공유한다(교차 검증 대비). */}
      <div className="lp-feature-sources-summary">
        <span>
          <span className="lp-feature-sources-summary-mono">6,212</span> 노드{" "}
          <span className="lp-feature-sources-summary-mono">·</span>{" "}
          <span className="lp-feature-sources-summary-mono">19,038</span> 연결
        </span>
        <span>
          마지막 동기화{" "}
          <span className="lp-feature-sources-summary-mono">2026-07-24 14:32</span>
        </span>
      </div>
      {CONNECTORS.map((c) => {
        const Logo = CONNECTOR_LOGOS[c.key];
        return (
          <div className="lp-feature-source-row" key={c.key}>
            <div className="lp-feature-source-id">
              <Logo className={`lp-feature-source-logo lp-feature-source-logo--${c.key}`} />
              <div className="lp-feature-source-meta">
                <span className="lp-feature-source-name">
                  {c.name}
                  <span className="lp-feature-source-target">{c.target}</span>
                  {c.targetLabel && (
                    <span className="lp-feature-source-target-label">{c.targetLabel}</span>
                  )}
                </span>
                <div className="lp-feature-source-status">
                  <span className="lp-feature-source-dot" />
                  <span className="lp-feature-source-sync">synced · {c.syncedAt}</span>
                </div>
                <span className="lp-feature-source-counts">{c.counts}</span>
              </div>
            </div>
            <span className="lp-feature-source-action">동기화</span>
          </div>
        );
      })}
      <div className="lp-feature-sources-add">+ 데이터 소스 추가</div>
    </div>
  );
}
