import type { CSSProperties } from "react";

import {
  HOW_GRAPH_WIDTH,
  HOW_GRAPH_HEIGHT,
  HOW_IT_WORKS_NODES,
  HOW_IT_WORKS_EDGES,
  type MiniGraphNode,
  type MiniGraphEdge,
} from "@/lib/howItWorksGraph";
import type { MiniGraphLabel } from "@/lib/graphExplorerPreview";

const NODE_R = 4.5;

// 라벨 오버레이(HTML)를 SVG 뷰박스 좌표에 정확히 맞추기 위한 변환 상수 — 기능 2 캔버스는
// 항상 슬롯(.lp-feature-media--graph, landing.css aspect-ratio: 16/10) 전체를 채운다.
// 슬롯 비(1.6)가 뷰박스 비(400/240≈1.667)보다 항상 작아(뷰박스가 상대적으로 더 옆으로
// 넓다) SVG는 항상 width-constrained로 렌더된다 — 가로는 레터박스 없이 꽉 차고(1유닛 =
// 100/400 cqw), 세로에만 위아래 레터박스가 생긴다. 그 레터박스 폭은 두 비율(뷰박스·슬롯)
// 만으로 정해지는 상수라 브레이크포인트와 무관하게 항상 같다 — 슬롯 aspect-ratio를 바꾸면
// GRAPH_SLOT_ASPECT도 같이 바꿔야 한다.
// 라벨 컨테이너가 16:10 슬롯이 아닌 곳(작동 방식 섹션 — .lp-how-viz가 뷰박스 비율 그대로)
// 에서는 labelAspect prop으로 그 컨테이너의 비율을 넘긴다 — 뷰박스 비와 같으면 레터박스가
// 0으로 떨어져 같은 공식이 그대로 성립한다(컨테이너는 여전히 width-constrained여야 한다).
const GRAPH_SLOT_ASPECT = 16 / 10;
const UNIT_TO_CQW = 100 / HOW_GRAPH_WIDTH;

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
//
// extraNodes/extraEdges/labels는 기능 2 전용 opt-in prop이다(기본값 빈 배열). 작동 방식
// 섹션(HowItWorksSection.tsx)은 stage만 넘기므로 이 세 값이 항상 빈 배열이고, 그 경우
// allNodes/allEdges는 HOW_IT_WORKS_NODES/EDGES와 동일한 참조를 그대로 쓰고 라벨 레이어는
// 아예 렌더되지 않아 — 작동 방식 섹션의 출력은 이 prop 추가 전과 DOM이 완전히 동일하다.
export function MiniGraph({
  stage,
  extraNodes = [],
  extraEdges = [],
  labels = [],
  labelAspect = GRAPH_SLOT_ASPECT,
}: {
  stage: 1 | 2 | 3;
  extraNodes?: MiniGraphNode[];
  extraEdges?: MiniGraphEdge[];
  labels?: MiniGraphLabel[];
  /** 라벨 오버레이가 앉는 사이즈 컨테이너의 가로세로비 — 기본은 기능 2 슬롯(16:10).
      작동 방식 섹션은 뷰박스 비율 컨테이너(.lp-how-viz)라 뷰박스 비를 넘긴다(레터박스 0). */
  labelAspect?: number;
}) {
  const letterboxCqh = ((1 - (HOW_GRAPH_HEIGHT / HOW_GRAPH_WIDTH) * labelAspect) / 2) * 100;
  const allNodes = extraNodes.length ? [...HOW_IT_WORKS_NODES, ...extraNodes] : HOW_IT_WORKS_NODES;
  const allEdges = extraEdges.length ? [...HOW_IT_WORKS_EDGES, ...extraEdges] : HOW_IT_WORKS_EDGES;
  const nodeById = new Map(allNodes.map((n) => [n.id, n]));
  const visibleEdges = allEdges.filter((e) => e.stage <= (stage === 1 ? 1 : 2));
  const litEdges = stage === 3 ? HOW_IT_WORKS_EDGES.filter((e) => e.lit) : [];
  const litNodeIds = new Set<string>();
  for (const e of litEdges) {
    litNodeIds.add(e.from);
    litNodeIds.add(e.to);
  }
  const litNodes = [...litNodeIds].map((id) => nodeById.get(id)!);

  return (
    <>
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
          {allNodes.map((n, i) => {
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
      {/* 기능 2 라벨 오버레이 — labels가 빈 배열(작동 방식 섹션의 기본 호출)이면 아예 렌더되지
          않는다. SVG 안의 <text>는 뷰박스 스케일을 그대로 따라가 컨테이너가 줄어들면 글자도
          같이 줄어들어 DESIGN.md의 mono-label 12px 하한을 반응형 전 구간에서 지킬 수 없다 —
          그래서 라벨은 SVG 밖 실제 HTML(font-size: 12px 고정)로 오버레이한다. 좌표는 컨테이너
          쿼리 단위(cqw/cqh, .lp-feature-graph-canvas의 container-type: size)로 계산해 SVG의
          xMidYMid meet 렌더 위치와 정확히 맞춘다(letterbox 오프셋 포함, UNIT_TO_CQW/
          letterboxCqh 유도 참고) — 브레이크포인트와 무관하게 항상 정확하다. */}
      {labels.length > 0 && (
        <div className="lp-feature-graph-labels">
          {labels.map((l) => {
            const n = nodeById.get(l.nodeId);
            if (!n) return null;
            return (
              <span
                key={l.nodeId}
                className={`lp-feature-graph-label${l.emphasis ? " lp-feature-graph-label--lit" : ""}${l.anchorEnd ? " lp-feature-graph-label--end" : ""}${l.core ? " lp-feature-graph-label--core" : ""}${l.wide ? " lp-feature-graph-label--wide" : ""}`}
                style={
                  {
                    left: `calc(${(n.x * UNIT_TO_CQW).toFixed(2)}cqw)`,
                    top: `calc(${letterboxCqh.toFixed(2)}cqh + ${(n.y * UNIT_TO_CQW).toFixed(2)}cqw)`,
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
      )}
    </>
  );
}
