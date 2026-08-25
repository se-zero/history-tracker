import { HERO_BACKDROP_GRAPH } from "@/lib/heroBackdropGraph";

// 히어로 배경 그래프 — ≥1180px 전용, 텍스트 블록 구간 한정 스트립(landing.css에서 <1180px
// 숨김, 4차 상하 구조에서는 히어로 상단 560px + 하단 마스크 페이드로 한정 — 제품 UI 슬롯
// 위에는 깔지 않는다). 2026-07-25 2차 재설계로 히어로의 주인공이 "헤드라인 + 제품 UI
// 슬롯(HeroProductSlot)"으로 옮겨가면서 이 그래프는 배경으로 강등됐다: 앰버 점등 경로·
// 글로우·노드별 정착 스태거·재점등 루프를 전부 제거하고, 레이어 전체가 로드 시퀀스
// 마지막에 한 번 조용히 페이드인만 한다(.lp-hero-media의 lp-fade). 노드·엣지의 최종
// 불투명도는 생성기(heroBackdropGraph.ts)가 부여한 값을 attr로 그대로 쓴다.
// HERO_BACKDROP_GRAPH의 litNodeIds/litEdgeIds는 배경 렌더에서는 미사용이다 — 앰버 점등은
// 우측 제품 UI의 "관련 그래프" 패널(HeroProductSlot.tsx)이 자체적으로 가진다.
export function HeroMedia() {
  const { width, height, nodes, edges } = HERO_BACKDROP_GRAPH;
  const nodeById = new Map(nodes.map((n) => [n.id, n]));

  return (
    <div className="lp-hero-media" aria-hidden="true">
      <svg
        className="lp-backdrop-graph"
        viewBox={`0 0 ${width} ${height}`}
        preserveAspectRatio="xMidYMid slice"
      >
        {/* 배경 엣지 — 헤어라인 저투명, 최근접 이웃만 연결한다(전결합 아님). */}
        <g className="lp-c-edges">
          {edges.map((e) => {
            const from = nodeById.get(e.from)!;
            const to = nodeById.get(e.to)!;
            return (
              <line
                key={e.id}
                className="lp-c-edge"
                x1={from.x}
                y1={from.y}
                x2={to.x}
                y2={to.y}
                opacity={e.opacity}
              />
            );
          })}
        </g>
        {/* 배경 노드 — 개별 애니메이션 없음(레이어 단위 페이드만). */}
        <g className="lp-c-nodes">
          {nodes.map((n) => (
            <circle
              key={n.id}
              className={`lp-c-node--${n.type}`}
              cx={n.x}
              cy={n.y}
              r={n.r}
              opacity={n.opacity}
            />
          ))}
        </g>
      </svg>
    </div>
  );
}
