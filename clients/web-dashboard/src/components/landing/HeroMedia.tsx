import type { CSSProperties } from "react";

import { HERO_CONSTELLATION } from "@/lib/heroConstellation";
import { useInViewOnce } from "@/components/landing/useInViewOnce";

// 제품 영상 자리의 16:10 배경 일러스트.
// 실제 지식 그래프의 노드 타입 7색을 저투명 "성좌"로 조밀하게 깔고, 그 위에
// 질문에 답한 4노드/3엣지 경로만 앰버로 덮어 그려 시그니처 순간(그래프 점등)을 재연한다.
// 좌표는 heroConstellation.ts의 결정론적 생성기가 만들며, data-node-id/data-edge-id는
// 개별 요소를 참조하기 위한 훅이다.
//
// ── 모션 타임라인(landing.css의 애니메이션 delay와 아래 상수가 맞물린다) ──────────
// 1) 배경 노드 정착(스프링, scale 0.6→1 + 페이드) — index 기반 스태거.
// 2) 정착이 끝난 뒤 점등 경로가 노드→엣지→노드→엣지… 순으로 등장(엣지는 stroke-dashoffset 드로잉).
// 3) 점등 완료 후 8~10초 주기로 경로 전체가 ~0.5로 가라앉았다 재점등(루프) — 히어로가
//    뷰포트에 없으면 IntersectionObserver로 루프를 멈춘다(animation-play-state 토글).
const NODE_SETTLE_BASE_MS = 280; // 미디어 프레임(.lp-media-frame, 300ms 지연)이 들어오는 시점과 맞춘다
const NODE_SETTLE_STEP_MS = 3; // 노드 하나당 스태거 간격
const NODE_SETTLE_MAX_STAGGER_MS = 300; // 노드가 많아(약 80개) 무한정 늘어지지 않게 스태거 총량을 캡핑
// 배경 노드 정착이 대략 끝나는 시점 = BASE + 최대 스태거 + 정착 애니메이션 자체 길이(--lp-dur-slow=420ms)
const LIT_SEQUENCE_BASE_MS = NODE_SETTLE_BASE_MS + NODE_SETTLE_MAX_STAGGER_MS + 420;
const LIT_BEAT_MS = 190; // 점등 시퀀스 한 걸음의 길이

export function HeroMedia() {
  const { width, height, nodes, edges, litNodeIds, litEdgeIds } = HERO_CONSTELLATION;
  const nodeById = new Map(nodes.map((n) => [n.id, n]));
  const edgeById = new Map(edges.map((e) => [e.id, e]));
  const litNodes = litNodeIds.map((id) => nodeById.get(id)!);
  const litEdges = litEdgeIds.map((id) => edgeById.get(id)!);

  // 히어로가 뷰포트 밖이면(스크롤 이후) 은은한 재점등 루프를 멈춘다. 초기 정착·점등 시퀀스는
  // 페이지 로드 시 1회만 재생되므로 IO와 무관하다 — 여기서 관찰하는 건 오직 루프 재생 여부다.
  const { ref: mediaRef, inView: heroInView } = useInViewOnce<HTMLDivElement>({
    threshold: 0.1,
    once: false,
  });

  return (
    <div className="lp-hero-media" ref={mediaRef}>
      <div className="lp-media-glow" aria-hidden="true" />
      <div className="lp-media-frame">
        <svg
          className="lp-constellation"
          viewBox={`0 0 ${width} ${height}`}
          preserveAspectRatio="xMidYMid slice"
          aria-hidden="true"
        >
          {/* 배경 엣지 — 헤어라인 저투명, 최근접 이웃만 연결한다(전결합 아님). 노드 정착과 달리
              엣지 자체는 움직이지 않는다(브리프 범위: 정착은 노드에만 지시됨). */}
          <g className="lp-c-edges">
            {edges.map((e) => {
              const from = nodeById.get(e.from)!;
              const to = nodeById.get(e.to)!;
              return (
                <line
                  key={e.id}
                  data-edge-id={e.id}
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

          {/* 배경 노드 — 스프링 정착(scale 0.6→1 + 페이드)으로 등장한다. 최종 불투명도는 노드마다
              다르므로(HeroConstellation이 부여) --o로 넘겨 애니메이션 종료 후에도 유지되게 한다.
              --d(지연)는 index 기반 스태거 — 80개가량이라 스텝을 작게 잡아 총 스태거를 캡핑한다. */}
          <g className="lp-c-nodes">
            {nodes.map((n, i) => {
              const delay = Math.min(
                NODE_SETTLE_BASE_MS + i * NODE_SETTLE_STEP_MS,
                NODE_SETTLE_BASE_MS + NODE_SETTLE_MAX_STAGGER_MS,
              );
              return (
                <circle
                  key={n.id}
                  data-node-id={n.id}
                  className={`lp-c-node lp-c-node--${n.type}`}
                  cx={n.x}
                  cy={n.y}
                  r={n.r}
                  style={{ "--o": n.opacity, "--d": `${delay}ms` } as CSSProperties}
                />
              );
            })}
          </g>

          {/* 점등 경로 — 답변의 근거가 된 노드와 그 사이 엣지. 정착이 끝난 뒤 노드→엣지→노드…
              순으로 순차 등장한다(엣지는 stroke-dashoffset 드로잉, 노드는 페이드+스케일).
              litNodes[i]는 beat 2i, litEdges[i](litNodes[i]→litNodes[i+1] 구간)는 beat 2i+1 —
              시작 노드가 먼저 켜지고 그 다음 엣지가 다음 노드까지 그어지는 흐름이다.
              완료 후에는 이 그룹 전체에 은은한 재점등 루프(lp-lit-loop)가 걸린다 —
              히어로가 화면 밖이면 is-loop-paused로 정지한다. */}
          <g className={`lp-c-lit${heroInView ? "" : " is-loop-paused"}`}>
            {litEdges.map((e, i) => {
              const from = nodeById.get(e.from)!;
              const to = nodeById.get(e.to)!;
              const delay = LIT_SEQUENCE_BASE_MS + (2 * i + 1) * LIT_BEAT_MS;
              return (
                <line
                  key={e.id}
                  data-edge-id={e.id}
                  className="lp-c-lit-edge"
                  x1={from.x}
                  y1={from.y}
                  x2={to.x}
                  y2={to.y}
                  pathLength={1}
                  style={{ "--d": `${delay}ms` } as CSSProperties}
                />
              );
            })}
            {litNodes.map((n, i) => {
              const delay = LIT_SEQUENCE_BASE_MS + 2 * i * LIT_BEAT_MS;
              return (
                <circle
                  key={`${n.id}-halo`}
                  className="lp-c-lit-halo"
                  cx={n.x}
                  cy={n.y}
                  r={n.r + 9}
                  style={{ "--d": `${delay}ms` } as CSSProperties}
                />
              );
            })}
            {litNodes.map((n, i) => {
              const delay = LIT_SEQUENCE_BASE_MS + 2 * i * LIT_BEAT_MS;
              return (
                <circle
                  key={n.id}
                  data-node-id={n.id}
                  className="lp-c-lit-node"
                  cx={n.x}
                  cy={n.y}
                  r={n.r + 1.2}
                  style={{ "--d": `${delay}ms` } as CSSProperties}
                />
              );
            })}
          </g>
        </svg>
      </div>
    </div>
  );
}
