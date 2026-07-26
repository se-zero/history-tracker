import { GITHUB_AUTHORIZE_URL } from "@/api/auth";
import { HERO_CONSTELLATION } from "@/lib/heroConstellation";

// 최종 CTA — 중앙 정렬, 넓은 상하 여백으로 페이지를 닫는다.
// 2026-07-26 6차: 위 유스케이스(surface-1)와 경계가 흐릿해 "마침표" 임팩트가 없다는 지시로
// 두 가지를 채택했다(브리프 §7의 "히어로 그래프 에코 = 수미상관" 선택 사항을 이번에 채택).
// ① 배경을 그래프 캔버스 층(--lp-surface-canvas)으로 — 히어로와 같은 "우물" 층이라
//    수미상관이 색으로도 성립하고, surface-1과 두 단계 색차로 경계가 분명해진다.
//    (canvas 층은 그래프가 앉는 배경 전용이라는 DESIGN.md 규칙과도 맞는다 — 아래 에코가
//    실제로 이 층 위에 앉는 그래프다.)
// ② 히어로 성좌(HERO_CONSTELLATION — 같은 시드, 같은 배치)를 무채·저투명으로 에코 —
//    "처음의 그 그래프가 여기에도 있다"는 수미상관 신호. 무채(노드 타입색·앰버 없음)인
//    이유: 라이브한 것도, 특정 노드를 지시하는 것도 아니다(앰버·노드색 네임스페이스 비대상).
//    모션 없음(정적) — 이 섹션의 유일한 라이브 요소는 앰버 버튼 하나로 유지한다.
export function FinalCtaSection() {
  const { width, height, nodes, edges } = HERO_CONSTELLATION;
  const nodeById = new Map(nodes.map((n) => [n.id, n]));

  return (
    <section className="lp-final-cta">
      {/* 히어로 성좌 에코 — 동일 데이터의 무채 재렌더. 개별 opacity(생성기 부여)는 유지하고
          레이어 전체를 .lp-final-cta-echo에서 한 번 더 낮춘다. */}
      <div className="lp-final-cta-echo" aria-hidden="true">
        <svg
          className="lp-final-cta-echo-svg"
          viewBox={`0 0 ${width} ${height}`}
          preserveAspectRatio="xMidYMid slice"
        >
          <g>
            {edges.map((e) => {
              const from = nodeById.get(e.from)!;
              const to = nodeById.get(e.to)!;
              return (
                <line
                  key={e.id}
                  className="lp-final-cta-echo-edge"
                  x1={from.x}
                  y1={from.y}
                  x2={to.x}
                  y2={to.y}
                  opacity={e.opacity}
                />
              );
            })}
          </g>
          <g>
            {nodes.map((n) => (
              <circle
                key={n.id}
                className="lp-final-cta-echo-node"
                cx={n.x}
                cy={n.y}
                r={n.r}
                opacity={n.opacity}
              />
            ))}
          </g>
        </svg>
      </div>
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
