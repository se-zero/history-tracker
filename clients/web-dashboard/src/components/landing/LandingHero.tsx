import { Link } from "react-router-dom";

import { HeroMedia } from "@/components/landing/HeroMedia";
import { GITHUB_AUTHORIZE_URL } from "@/api/auth";

// 히어로 — 텍스트가 먼저 도착하고 그다음 그래프가 켜지는 로드 시퀀스는 landing.css의 animation-delay가 담당한다.
export function LandingHero() {
  return (
    <section className="lp-hero">
      <div className="lp-hero-inner">
        <div className="lp-hero-text">
          <p className="lp-eyebrow">GITHUB · JIRA · SLACK → KNOWLEDGE GRAPH</p>
          <h1 className="lp-headline">
            <span className="lp-headline-line">코드는 남았고,</span>
            <span className="lp-headline-line">이유는 흩어졌다.</span>
          </h1>
          <p className="lp-sub">
            GitHub·Jira·Slack에 흩어진 PR·이슈·대화·티켓을 하나의 지식 그래프로 엮습니다. “이
            코드가 왜 이렇게 바뀌었어?”라고 물으면 답과 함께 근거가 된 노드와 경로를 보여줍니다.
          </p>
          <div className="lp-cta-row">
            <a className="lp-btn lp-btn--primary" href={GITHUB_AUTHORIZE_URL}>
              GitHub으로 시작
            </a>
            <Link className="lp-btn lp-btn--secondary" to="/demo/graph">
              그래프 만져보기
            </Link>
          </div>
          <p className="lp-cta-meta">
            GitHub 계정으로 로그인한 뒤, 연결할 저장소를 직접 고릅니다.
          </p>
        </div>

        <HeroMedia />
      </div>

      {/* #how 대상 섹션은 다음 라운드에 생긴다. 지금은 앵커만 걸어둔다. */}
      <a className="lp-scroll-cue" href="#how">
        <span className="lp-scroll-label">
          작동 방식
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path
              d="M12 5v14M5 12l7 7 7-7"
              stroke="currentColor"
              strokeWidth="1.6"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        </span>
      </a>
    </section>
  );
}
