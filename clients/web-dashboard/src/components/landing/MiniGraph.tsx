import type { CSSProperties } from "react";

import {
  HOW_GRAPH_WIDTH,
  HOW_GRAPH_HEIGHT,
  HOW_IT_WORKS_NODES,
  HOW_IT_WORKS_EDGES,
} from "@/lib/howItWorksGraph";

const NODE_R = 4.5;

// 스테이지별 등장 리듬(landing.css의 애니메이션 delay/duration 선택과 짝을 이룬다):
// - stage 1: 노드가 스프링으로 정착한다(index 기반 스태거) — "하나씩 나타나는" 인상.
// - stage 2/3: 노드는 빠르게 한 번에 등장하고(스태거 없음), 엣지만 순서대로 그어진다(스태거 있음)
//   — "이미 있던 노드 사이에 관계가 이어지는" 인상. 두 스테이지가 같은 리듬을 공유하므로
//   NODE_LOCAL_STEP_MS/EDGE_LOCAL_STEP_MS는 stage 구분 없이 계산해두고, CSS 쪽에서
//   stage-1 규칙만 --local-d를 소비하고 stage-2/3 규칙은 무시한다(고정 지연만 사용).
const NODE_LOCAL_STEP_MS = 14;
const EDGE_LOCAL_STEP_MS = 40;
// stage 3 앰버 경로 전용 — 히어로의 "노드→엣지→노드…" 교차 스태거와 같은 공식(beat 2i/2i+1),
// 다만 미니 그래프는 더 작아서 보폭(BEAT)만 짧게 잡는다. 3의 앰버 경로는 항상 이 순서를 유지한다.
const LIT_BEAT_MS = 130;

// "작동 방식" 스텝 위에 얹는 미니 그래프. 01/02/03 세 인스턴스가 howItWorksGraph.ts의
// 같은 노드 배열을 그대로 렌더한다 — 노드는 스테이지와 무관하게 항상 전부 그리고,
// 엣지만 stage<=요청 스테이지인 것만 필터링한다(01은 stage 1뿐, 02·03은 누적 전부).
// 03에서만 앰버 오버레이(.lp-how-lit-*)를 기본 레이어 위에 덮어 그린다 — 히어로
// HeroMedia.tsx의 배경 레이어 + lit 오버레이 레이어 구조를 그대로 따른다.
//
// 이 컴포넌트는 두 군데서 재사용된다 — "작동 방식" 섹션(.lp-how-step 안, is-played로
// 스크롤 진입 시 발동하는 전체 등장 시퀀스)과 기능 2 미리보기(.lp-feature-graph 안, 앰버
// 경로만 한 번 부드럽게 페이드). 어느 애니메이션이 실제로 재생되는지는 이 컴포넌트가 아니라
// landing.css의 조상 셀렉터(.lp-how-step 대 .lp-feature-graph)가 결정한다 — 그래서 여기서는
// 스태거 계산에 필요한 CSS 변수(--o/--local-d/--lit-d)만 인라인으로 흘려보낸다.
export function MiniGraph({ stage }: { stage: 1 | 2 | 3 }) {
  const nodeById = new Map(HOW_IT_WORKS_NODES.map((n) => [n.id, n]));
  const visibleEdges = HOW_IT_WORKS_EDGES.filter((e) => e.stage <= (stage === 1 ? 1 : 2));
  const litEdges = stage === 3 ? HOW_IT_WORKS_EDGES.filter((e) => e.lit) : [];
  const litNodeIds = new Set<string>();
  for (const e of litEdges) {
    litNodeIds.add(e.from);
    litNodeIds.add(e.to);
  }
  const litNodes = [...litNodeIds].map((id) => nodeById.get(id)!);

  return (
    <svg
      className={`lp-how-graph lp-how-graph--stage-${stage}`}
      data-stage={stage}
      viewBox={`0 0 ${HOW_GRAPH_WIDTH} ${HOW_GRAPH_HEIGHT}`}
      preserveAspectRatio="xMidYMid meet"
      aria-hidden="true"
    >
      {/* 기본 레이어 — 엣지는 스테이지 누적분만, 노드는 항상 전부(같은 배치 공유). */}
      <g className="lp-how-graph-edges">
        {visibleEdges.map((e, i) => {
          const from = nodeById.get(e.from)!;
          const to = nodeById.get(e.to)!;
          const isLit = stage === 3 && !!e.lit;
          return (
            <line
              key={e.id}
              data-edge-id={e.id}
              data-lit={isLit}
              className={`lp-how-graph-edge lp-c-edge${isLit ? " is-lit" : ""}`}
              x1={from.x}
              y1={from.y}
              x2={to.x}
              y2={to.y}
              pathLength={1}
              style={{ "--local-d": `${i * EDGE_LOCAL_STEP_MS}ms` } as CSSProperties}
            />
          );
        })}
      </g>
      <g className="lp-how-graph-nodes">
        {HOW_IT_WORKS_NODES.map((n, i) => {
          const isLit = stage === 3 && litNodeIds.has(n.id);
          return (
            <circle
              key={n.id}
              data-node-id={n.id}
              data-lit={isLit}
              className={`lp-how-graph-node lp-c-node lp-c-node--${n.type}${isLit ? " is-lit" : ""}`}
              cx={n.x}
              cy={n.y}
              r={NODE_R}
              style={{ "--local-d": `${i * NODE_LOCAL_STEP_MS}ms` } as CSSProperties}
            />
          );
        })}
      </g>

      {/* 03 전용 앰버 오버레이 — 근거가 된 노드·경로가 답변과 함께 켜지는 시그니처 순간을
          이 미니 그래프 스케일로 재연한다. litNodes[i]는 beat 2i, litEdges[i]는 beat 2i+1 —
          히어로와 동일한 "노드→엣지→노드…" 교차 순서(HeroMedia.tsx 주석 참고). */}
      {stage === 3 && (
        <g className="lp-how-lit">
          {litEdges.map((e, i) => {
            const from = nodeById.get(e.from)!;
            const to = nodeById.get(e.to)!;
            const delay = (2 * i + 1) * LIT_BEAT_MS;
            return (
              <line
                key={e.id}
                data-edge-id={e.id}
                className="lp-how-lit-edge"
                x1={from.x}
                y1={from.y}
                x2={to.x}
                y2={to.y}
                pathLength={1}
                style={{ "--lit-d": `${delay}ms` } as CSSProperties}
              />
            );
          })}
          {litNodes.map((n, i) => {
            const delay = 2 * i * LIT_BEAT_MS;
            return (
              <circle
                key={`${n.id}-halo`}
                className="lp-how-lit-halo"
                cx={n.x}
                cy={n.y}
                r={NODE_R + 9}
                style={{ "--lit-d": `${delay}ms` } as CSSProperties}
              />
            );
          })}
          {litNodes.map((n, i) => {
            const delay = 2 * i * LIT_BEAT_MS;
            return (
              <circle
                key={n.id}
                data-node-id={n.id}
                className="lp-how-lit-node"
                cx={n.x}
                cy={n.y}
                r={NODE_R + 1.2}
                style={{ "--lit-d": `${delay}ms` } as CSSProperties}
              />
            );
          })}
        </g>
      )}
    </svg>
  );
}
