import { Link } from "react-router-dom";

import { HeroMedia } from "@/components/landing/HeroMedia";
import { HeroProductSlot } from "@/components/landing/HeroProductSlot";
import type { LandingTheme } from "@/components/landing/useLandingTheme";
import { GITHUB_AUTHORIZE_URL } from "@/api/auth";
import { useAuth } from "@/auth/AuthProvider";
import { PATHS } from "@/routes";

// 히어로 — 상하 구조(2026-07-25 4차 재설계): 위 텍스트 블록(좌측 정렬) → 아래 제품 UI
// 슬롯(영상 슬롯, 컨테이너 전폭). 슬롯은 히어로가 클리핑하지 않아 하단이 첫 화면(폴드)
// 경계에서만 잘리고 스크롤하면 나머지가 드러난다 — 잘림은 오직 하단 한 방향이다(좌·우·
// 상단 크롬은 온전). 스크롤 큐는 제거됐다 — 전폭 슬롯이 폴드에서 잘리는 것 자체가
// "아래 더 있다"의 어포던스라 큐가 중복이고, 슬롯 위에 얹으면 제품 UI를 가린다.
// 로드 시퀀스(텍스트 rise → 슬롯 프레임 rise → 배경 성좌 페이드)는 landing.css의
// animation-delay가 담당한다(패널 점등 페이드는 데스크톱이 영상으로 대체되며 소멸 —
// HeroProductSlot.tsx 참조). HeroMedia는 .lp-hero-inner 밖의 직계 자식이어야 한다 —
// absolute(상단 텍스트 구간 한정 스트립)의 기준 박스가 .lp-hero여야 하기 때문이다.
// 주 CTA는 헤더·푸터와 같은 인증 상태 분기를 쓴다 — 로그인 상태면 OAuth 대신 제품으로 바로 이동.
// theme은 LandingPage가 소유한 랜딩 테마 상태를 그대로 받아 HeroProductSlot에 전달한다
// (데스크톱 영상 src·poster를 테마별로 고르는 데 필요 — LandingHeader와 같은 전달 방식).
export function LandingHero({ theme }: { theme: LandingTheme }) {
  const { status } = useAuth();

  return (
    <section className="lp-hero">
      <HeroMedia />
      <div className="lp-hero-inner">
        <div className="lp-hero-text">
          {/* 한글 eyebrow — 모노 금지(DESIGN.md: 한글 콘텐츠에 모노를 쓰지 않는다), 트래킹 0. */}
          <p className="lp-eyebrow">개발 히스토리를 위한 지식 그래프</p>
          {/* 두 스팬은 inline-block(landing.css) — 전폭 텍스트 행이라 데스크톱에선 한 줄로
              흐르고, 폭이 좁아지면 정확히 절 경계에서 두 줄로 꺾인다(카피 문자열 불변,
              스팬 사이 공백은 한 줄 조판 시 필요한 어절 간격이다). */}
          <h1 className="lp-headline">
            <span className="lp-headline-line">코드베이스의 모든 결정을,</span>{" "}
            <span className="lp-headline-line">되짚을 수 있게.</span>
          </h1>
          <p className="lp-sub">
            흩어진 커밋·PR·이슈·대화·문서를 하나의 그래프로 묶어, 물으면 근거와 함께 답합니다.
          </p>
          <div className="lp-cta-row">
            {status === "authenticated" ? (
              <Link className="lp-btn lp-btn--primary" to={PATHS.root}>
                whycode 열기
              </Link>
            ) : (
              <a className="lp-btn lp-btn--primary" href={GITHUB_AUTHORIZE_URL}>
                GitHub으로 시작
              </a>
            )}
          </div>
          {status !== "authenticated" && (
            <p className="lp-cta-meta">
              GitHub 계정으로 로그인한 뒤, 연결할 저장소를 직접 고릅니다.
            </p>
          )}
        </div>

        <HeroProductSlot theme={theme} />
      </div>
    </section>
  );
}
