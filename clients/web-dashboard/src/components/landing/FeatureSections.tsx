import { MiniGraph } from "@/components/landing/MiniGraph";
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

// ── 기능 1: 대화 — 실제 Q&A 한 턴 (DESIGN.md "채팅 답변"·"출처 카드" 스펙) ──────────
// 사용자 메시지는 surface-2 버블 우측 정렬, 답변은 버블 없는 산문, 출처 카드 2장은
// 노드 색으로 틴트한 타입 배지 + 모노 메타데이터 + 본문 서체 내용 줄로 구성한다.
function ChatPreview() {
  return (
    <div className="lp-feature-chat">
      <p className="lp-feature-chat-user">검색 기능 관련 PR이랑 지라 티켓 찾아줘</p>
      <p className="lp-feature-chat-answer">
        검색 랭킹 가중치는 HT-64에서 논의된 뒤 PR #142로 반영됐습니다. 최종 수치 합의는 6월
        11일 #dev-search 스레드에 있습니다.
      </p>
      <div className="lp-feature-chat-sources">
        <div className="lp-feature-chat-source">
          <div className="lp-feature-chat-source-head">
            <span className="lp-feature-chat-badge lp-feature-chat-badge--issue">issue</span>
            <span className="lp-feature-chat-meta">HT-64 · 2026-06-12</span>
          </div>
          <p className="lp-feature-chat-source-body">검색 랭킹 가중치 개선</p>
        </div>
        <div className="lp-feature-chat-source">
          <div className="lp-feature-chat-source-head">
            <span className="lp-feature-chat-badge lp-feature-chat-badge--message">message</span>
            <span className="lp-feature-chat-meta">#dev-search · 2026-06-11</span>
          </div>
          <p className="lp-feature-chat-source-body">가중치 0.7로 확정 — 스레드 합의</p>
        </div>
      </div>
    </div>
  );
}

// ── 기능 2: 그래프 탐색 — 노드 색 범례 + 그래프 캔버스 ────────────────────────────
// 범례 칩(surface-2, pill radius)은 앱 패널 층 위에 뜬 필터 UI, 캔버스는 DESIGN.md대로
// 가장 깊은 층(surface-0)이라 슬롯 배경보다 한 단계 더 파낸다. MiniGraph는 stage=3(점등
// 경로 포함)을 저투명도 처리 없이 그대로 쓴다 — 이 페이지에서 앰버가 허용되는 유일한 자리.
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
// 채팅·데이터 소스 미리보기에는 진입 애니메이션을 아예 걸지 않는다.
function GraphExplorerPreview() {
  const { ref, inView } = useInViewOnce<HTMLDivElement>();
  return (
    <div className={`lp-feature-graph${inView ? " is-played" : ""}`} ref={ref}>
      <div className="lp-feature-graph-legend">
        {GRAPH_LEGEND.map((n) => (
          <span className="lp-feature-graph-pill" key={n.type}>
            <span className={`lp-feature-graph-dot lp-feature-graph-dot--${n.type}`} />
            {n.label}
          </span>
        ))}
      </div>
      <div className="lp-feature-graph-canvas">
        <MiniGraph stage={3} />
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

// GitHub — 공식 Octocat(Invertocat) 패스. 다크 배경용 흰색 변형(GitHub 브랜드 가이드라인).
function GithubMark() {
  return (
    <svg
      className="lp-feature-source-logo lp-feature-source-logo--github"
      viewBox="0 0 24 24"
      aria-hidden="true"
    >
      <path
        fill="var(--brand-github)"
        d="M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12"
      />
    </svg>
  );
}

// Jira — Atlassian 공식 마크(3개 겹친 셰브런). 공식 그라데이션(밝은 블루 --brand-jira ↔
// 딥 블루 --brand-jira-deep)을 linearGradient로 재현한다.
function JiraMark() {
  return (
    <svg
      className="lp-feature-source-logo lp-feature-source-logo--jira"
      viewBox="0 0 256 256"
      aria-hidden="true"
    >
      <defs>
        <linearGradient id="lp-jira-grad-a" x1="98.031%" x2="58.888%" y1=".161%" y2="40.766%">
          <stop offset="18%" stopColor="var(--brand-jira-deep)" />
          <stop offset="100%" stopColor="var(--brand-jira)" />
        </linearGradient>
        <linearGradient id="lp-jira-grad-b" x1="100.665%" x2="55.402%" y1=".455%" y2="44.727%">
          <stop offset="18%" stopColor="var(--brand-jira-deep)" />
          <stop offset="100%" stopColor="var(--brand-jira)" />
        </linearGradient>
      </defs>
      <path
        fill="var(--brand-jira)"
        d="M244.658 0H121.707a55.5 55.5 0 0 0 55.502 55.502h22.649V77.37c.02 30.625 24.841 55.447 55.466 55.467V10.666C255.324 4.777 250.55 0 244.658 0"
      />
      <path
        fill="url(#lp-jira-grad-a)"
        d="M183.822 61.262H60.872c.019 30.625 24.84 55.447 55.466 55.467h22.649v21.938c.039 30.625 24.877 55.43 55.502 55.43V71.93c0-5.891-4.776-10.667-10.667-10.667"
      />
      <path
        fill="url(#lp-jira-grad-b)"
        d="M122.951 122.489H0c0 30.653 24.85 55.502 55.502 55.502h22.72v21.867c.02 30.597 24.798 55.408 55.396 55.466V133.156c0-5.891-4.776-10.667-10.667-10.667"
      />
    </svg>
  );
}

// Slack — 공식 옥토소프 4색 마크. 옐로(--brand-slack-yellow)가 앰버(#EBA23C)와 근접하지만
// 이는 외부 브랜드 자산이라 DESIGN.md의 앰버 독점 규칙(hue 60~90은 액센트 전용) 대상이
// 아니다 — 커넥터 로고 전용 네임스페이스(tokens.css --brand-*)로 격리해 예외를 기록한다.
function SlackMark() {
  return (
    <svg
      className="lp-feature-source-logo lp-feature-source-logo--slack"
      viewBox="0 0 256 256"
      aria-hidden="true"
    >
      <path
        fill="var(--brand-slack-red)"
        d="M53.841 161.32c0 14.832-11.987 26.82-26.819 26.82S.203 176.152.203 161.32c0-14.831 11.987-26.818 26.82-26.818H53.84zm13.41 0c0-14.831 11.987-26.818 26.819-26.818s26.819 11.987 26.819 26.819v67.047c0 14.832-11.987 26.82-26.82 26.82c-14.83 0-26.818-11.988-26.818-26.82z"
      />
      <path
        fill="var(--brand-slack-blue)"
        d="M94.07 53.638c-14.832 0-26.82-11.987-26.82-26.819S79.239 0 94.07 0s26.819 11.987 26.819 26.819v26.82zm0 13.613c14.832 0 26.819 11.987 26.819 26.819s-11.987 26.819-26.82 26.819H26.82C11.987 120.889 0 108.902 0 94.069c0-14.83 11.987-26.818 26.819-26.818z"
      />
      <path
        fill="var(--brand-slack-green)"
        d="M201.55 94.07c0-14.832 11.987-26.82 26.818-26.82s26.82 11.988 26.82 26.82s-11.988 26.819-26.82 26.819H201.55zm-13.41 0c0 14.832-11.988 26.819-26.82 26.819c-14.831 0-26.818-11.987-26.818-26.82V26.82C134.502 11.987 146.489 0 161.32 0s26.819 11.987 26.819 26.819z"
      />
      <path
        fill="var(--brand-slack-yellow)"
        d="M161.32 201.55c14.832 0 26.82 11.987 26.82 26.818s-11.988 26.82-26.82 26.82c-14.831 0-26.818-11.988-26.818-26.82V201.55zm0-13.41c-14.831 0-26.818-11.988-26.818-26.82c0-14.831 11.987-26.818 26.819-26.818h67.25c14.832 0 26.82 11.987 26.82 26.819s-11.988 26.819-26.82 26.819z"
      />
    </svg>
  );
}

const CONNECTOR_LOGOS = {
  github: GithubMark,
  jira: JiraMark,
  slack: SlackMark,
} as const;

const CONNECTORS = [
  { key: "github", name: "GitHub" },
  { key: "jira", name: "Jira" },
  { key: "slack", name: "Slack" },
] as const;

function DataSourcesPreview() {
  return (
    <div className="lp-feature-sources">
      {CONNECTORS.map((c) => {
        const Logo = CONNECTOR_LOGOS[c.key];
        return (
          <div className="lp-feature-source-row" key={c.key}>
            <div className="lp-feature-source-id">
              <Logo />
              <div className="lp-feature-source-meta">
                <span className="lp-feature-source-name">{c.name}</span>
                <span className="lp-feature-source-sync">synced · 2026-07-24</span>
              </div>
            </div>
            <span className="lp-feature-source-action">동기화</span>
          </div>
        );
      })}
    </div>
  );
}
