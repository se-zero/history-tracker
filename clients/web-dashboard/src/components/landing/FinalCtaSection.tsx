import { GITHUB_AUTHORIZE_URL } from "@/api/auth";

// 최종 CTA — 중앙 정렬, 넓은 상하 여백으로 페이지를 조용히 닫는다.
// 히어로 그래프 에코는 넣지 않는다(브리프 선택 사항, 생략으로 결정) — 앰버 버튼이
// 이 섹션의 유일한 라이브 요소로 남게 한다.
export function FinalCtaSection() {
  return (
    <section className="lp-final-cta">
      <div className="lp-final-cta-inner">
        <h2 className="lp-final-cta-headline">당신의 저장소에도 같은 그래프가 있다.</h2>
        <p className="lp-final-cta-sub">아직 연결되지 않았을 뿐이다.</p>
        <div className="lp-final-cta-actions">
          <a className="lp-btn lp-btn--primary" href={GITHUB_AUTHORIZE_URL}>
            GitHub으로 시작
          </a>
        </div>
        <p className="lp-final-cta-meta">
          GitHub 계정으로 로그인한 뒤, 연결할 저장소를 직접 고릅니다.
        </p>
      </div>
    </section>
  );
}
