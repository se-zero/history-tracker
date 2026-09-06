import { useEffect, useMemo, useRef, useState } from "react";

import { Icons } from "@/components/Icons";
import { fitTo, runSimulation, type Positioned } from "@/lib/graphLayout";
import {
  buildIgniteSchedule,
  edgeKey,
  IGNITE_EDGE_MS,
  IGNITE_NODE_MS,
} from "@/lib/igniteSchedule";
import {
  NODE_TYPE_INFO,
  type GraphEdge,
  type GraphNode,
  type GraphNodeType,
} from "@/types/graph";

interface Props {
  nodes: GraphNode[];
  edges: GraphEdge[];
  highlighted?: Iterable<string> | null;
  selectedId?: string | null;
  // 외부(인용 카드 hover)에서 강조할 노드 — selected보다 크게 부각해 다른 시드와 구분한다.
  emphasizedId?: string | null;
  onSelect?: (node: GraphNode) => void;
  // 노드 hover를 외부에 알린다 — 대응 인용 카드를 역방향으로 강조하기 위함.
  onHoverNode?: (id: string | null) => void;
  onBackgroundClick?: () => void;
  showLegend?: boolean;
  showControls?: boolean;
  showFit?: boolean;
  showFilters?: boolean;
  compact?: boolean;
  showLabels?: boolean;
  nodeTypeColors?: boolean;
  // 마운트 시점에 true면 시드 점등 안무를 1회 재생한다(ChatPage의 fresh 신호).
  ignite?: boolean;
  onIgniteConsumed?: () => void;
}

// 리사이즈가 잠잠해질 때까지 기다리는 시간 — 아래 ResizeObserver 콜백 참고.
const RESIZE_SETTLE_MS = 200;

function clamp(n: number, lo: number, hi: number) {
  return Math.min(hi, Math.max(lo, n));
}

function truncate(s: string, n: number) {
  return s.length > n ? s.slice(0, n - 1) + "…" : s;
}

// base 엣지와 시드-시드 오버레이가 같은 곡선을 그리도록 경로 계산을 공유한다(이차 베지어).
function edgePath(A: Positioned, B: Positioned): string {
  const mx = (A.px + B.px) / 2;
  const my = (A.py + B.py) / 2;
  const dx = B.px - A.px;
  const dy = B.py - A.py;
  const len = Math.hypot(dx, dy) || 1;
  const off = 14;
  const cx = mx + (-dy / len) * off;
  const cy = my + (dx / len) * off;
  return `M ${A.px} ${A.py} Q ${cx} ${cy} ${B.px} ${B.py}`;
}

export function GraphVis({
  nodes,
  edges,
  highlighted,
  selectedId,
  emphasizedId,
  onSelect,
  onHoverNode,
  onBackgroundClick,
  showLegend = true,
  showControls = true,
  showFit = true,
  showFilters = true,
  compact = false,
  showLabels = true,
  nodeTypeColors = true,
  ignite,
  onIgniteConsumed,
}: Props) {
  const wrapRef = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState({ w: 800, h: 600 });
  const [view, setView] = useState({ k: 1, tx: 0, ty: 0 });
  const panRef = useRef<{ x: number; y: number; tx: number; ty: number } | null>(
    null,
  );
  // 팬(드래그) 후 발생하는 click과 순수 클릭을 구분 — 드래그였으면 선택 해제하지 않는다
  const draggedRef = useRef(false);
  const [hover, setHover] = useState<string | null>(null);
  const [activeFilters, setActiveFilters] = useState<Set<GraphNodeType> | null>(
    null,
  );

  // 점등 안무는 마운트 시점에 1회만 결정한다. 성립 전제(답변이 바뀌면 새 마운트)는
  // RelatedGraphPanel이 활성 답변 id를 key로 걸어 구조적으로 보장한다 — 캐시 히트
  // (staleTime: Infinity)로 로딩 구간 없이 답변이 전환되는 경로에서도 인스턴스가 유지되지
  // 않는다(유지되면 재생을 마친 래치가 과거 답변 그래프에서 점등을 재재생한다 — PR #108).
  // prefers-reduced-motion이면 재생 자체를 건너뛰고 즉시 최종 상태(랜딩 reduce 블록과 같은 원칙).
  const [igniting] = useState(
    () => !!ignite && !window.matchMedia("(prefers-reduced-motion: reduce)").matches,
  );
  // 콜백은 ref로 최신 값을 유지 — 아래 effect는 마운트 시 1회만 실행해야 하므로 deps에 넣지 않는다.
  const onIgniteConsumedRef = useRef(onIgniteConsumed);
  onIgniteConsumedRef.current = onIgniteConsumed;
  useEffect(() => {
    // 애니메이션 "종료"가 아니라 "시작"(마운트) 시점에 소비한다 — 중간에 리사이즈 등으로
    // 재생이 끊겨도 다시 재생 대상으로 남지 않는다. reduced-motion으로 재생 자체를 건너뛴
    // 경우도 재생된 것으로 취급해 동일하게 소비한다.
    if (ignite) onIgniteConsumedRef.current?.();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (!wrapRef.current) return;
    // 언마운트 직전 경합으로 타이머 콜백이 실행될 때 wrapRef.current가 null일 수 있어 가드한다.
    const commit = () => {
      if (!wrapRef.current) return;
      const r = wrapRef.current.getBoundingClientRect();
      setSize({ w: r.width, h: r.height });
    };
    let first = true;
    let timer: ReturnType<typeof setTimeout> | null = null;
    const ro = new ResizeObserver(() => {
      if (first) {
        // 최초 측정은 즉시 커밋한다 — 마운트 직후 기본값(800×600)에서 실측 크기로 넘어가는
        // 첫 커밋이 지연되면 첫 페인트가 어긋난 좌표로 보인다.
        first = false;
        commit();
        return;
      }
      // 패널 폭이 420ms 동안 전환되거나(.chat-wrap의 grid-template-columns) 핸들 드래그로
      // 리사이즈되는 동안 매 프레임 fitTo가 재계산되면 노드가 고무줄처럼 늘었다 줄어드는
      // 왜곡이 생기고, 다음 단계에서 얹을 점등 안무와도 충돌한다. transitionend를 구독하는
      // 방식은 부모 .chat-wrap과의 결합이 생기고 resizing prop 배선까지 필요한데, 패널이
      // 열리는 도중 이 컴포넌트가 마운트되는 경우까지 챙기려면 더 복잡해진다 — trailing
      // 디바운스는 "마지막 리사이즈 이벤트 후 일정 시간 잠잠하면 1회 커밋"이라 그 경우를
      // 별도 처리 없이 포함한다. viewBox는 그대로 유지되어 움직이는 동안에도
      // xMidYMid meet 균일 스케일로만 따라가고, 멈춘 뒤 한 번만 refit된다.
      if (timer) clearTimeout(timer);
      timer = setTimeout(commit, RESIZE_SETTLE_MS);
    });
    ro.observe(wrapRef.current);
    return () => {
      if (timer) clearTimeout(timer);
      ro.disconnect();
    };
  }, []);

  // React의 onWheel은 passive listener라 preventDefault가 무시됨.
  // 그래프 안에서는 페이지 스크롤 막고 zoom만 동작하도록 native listener를 직접 부착.
  useEffect(() => {
    const el = wrapRef.current;
    if (!el) return;
    const handler = (e: WheelEvent) => {
      e.preventDefault();
      // 커서의 wrap 기준 좌표 = SVG 좌표(viewBox가 wrap 크기와 1:1).
      const rect = el.getBoundingClientRect();
      const cx = e.clientX - rect.left;
      const cy = e.clientY - rect.top;
      const factor = e.deltaY > 0 ? 0.9 : 1.1;
      setView((v) => {
        const k = clamp(v.k * factor, 0.4, 2.5);
        // screen = t + k·graph 이므로, 커서 아래 지점을 고정하려면
        // 줌 비율만큼 변환을 보정한다. (clamp로 k가 안 바뀌면 ratio=1 → 변화 없음)
        const ratio = k / v.k;
        return {
          k,
          tx: cx - ratio * (cx - v.tx),
          ty: cy - ratio * (cy - v.ty),
        };
      });
    };
    el.addEventListener("wheel", handler, { passive: false });
    return () => el.removeEventListener("wheel", handler);
  }, []);

  const simNodes = useMemo(() => runSimulation(nodes, edges), [nodes, edges]);
  const pad = compact ? 24 : 60;
  const positioned = useMemo(
    () => fitTo(simNodes, size.w, size.h, pad),
    [simNodes, size, pad],
  );
  const nodeById = useMemo(() => {
    const m = new Map<string, Positioned>();
    positioned.forEach((n) => m.set(n.id, n));
    return m;
  }, [positioned]);

  const hi = useMemo<Set<string> | null>(() => {
    if (!highlighted) return null;
    return new Set(highlighted);
  }, [highlighted]);

  // 안무 스케줄은 재생이 결정된 마운트에서만 계산한다 — 시드 순서는 highlighted의 반복 순서.
  const schedule = useMemo(
    () =>
      igniting && hi ? buildIgniteSchedule(Array.from(highlighted ?? []), edges) : null,
    [igniting, hi, highlighted, edges],
  );

  const isAllowed = (n: Positioned) =>
    !activeFilters || activeFilters.has(n.type);

  const visibleEdges = useMemo(() => {
    return edges.filter((e) => {
      const A = nodeById.get(e.source);
      const B = nodeById.get(e.target);
      return A && B && isAllowed(A) && isAllowed(B);
    });
  }, [edges, nodeById, activeFilters]);

  const onMouseDown = (e: React.MouseEvent) => {
    if (e.button !== 0) return;
    draggedRef.current = false;
    panRef.current = { x: e.clientX, y: e.clientY, tx: view.tx, ty: view.ty };
  };
  const onMouseMove = (e: React.MouseEvent) => {
    // ref를 미리 값으로 잡아 둔다. setView 업데이터는 React가 나중에 실행하는데,
    // 그 사이 mouseup/leave로 panRef.current가 null이 되면 업데이터 안에서
    // 다시 읽을 때 null 역참조로 터지기 때문이다.
    const pan = panRef.current;
    if (!pan) return;
    const dx = e.clientX - pan.x;
    const dy = e.clientY - pan.y;
    if (Math.abs(dx) > 3 || Math.abs(dy) > 3) draggedRef.current = true;
    setView((v) => ({
      ...v,
      tx: pan.tx + dx,
      ty: pan.ty + dy,
    }));
  };
  const onMouseUp = () => {
    panRef.current = null;
  };

  // 빈 캔버스 클릭 시 선택 해제 (노드 클릭은 stopPropagation으로 여기 도달하지 않음)
  const onCanvasClick = () => {
    if (draggedRef.current) return;
    onBackgroundClick?.();
  };

  const zoom = (factor: number) =>
    setView((v) => ({ ...v, k: clamp(v.k * factor, 0.4, 2.5) }));
  const fit = () => setView({ k: 1, tx: 0, ty: 0 });

  const allTypes = Object.keys(NODE_TYPE_INFO) as GraphNodeType[];

  const toggleFilter = (t: GraphNodeType) => {
    setActiveFilters((cur) => {
      const next = new Set(cur ?? allTypes);
      if (next.has(t)) next.delete(t);
      else next.add(t);
      if (next.size === allTypes.length) return null;
      return next;
    });
  };

  const rBase = compact ? 5 : 7;
  const rSel = compact ? 8 : 11;

  return (
    <div
      className="graph-wrap"
      ref={wrapRef}
      onMouseDown={onMouseDown}
      onMouseMove={onMouseMove}
      onMouseUp={onMouseUp}
      onMouseLeave={onMouseUp}
    >
      <svg
        className="graph-canvas"
        viewBox={`0 0 ${size.w} ${size.h}`}
        onClick={onCanvasClick}
      >
        <defs>
          <pattern
            id="ht-grid"
            width="36"
            height="36"
            patternUnits="userSpaceOnUse"
          >
            <path
              d="M36 0 L0 0 0 36"
              fill="none"
              stroke="var(--border)"
              strokeWidth="1"
              opacity="0.4"
            />
          </pattern>
        </defs>
        {!compact && (
          <rect width={size.w} height={size.h} fill="url(#ht-grid)" opacity="0.5" />
        )}
        <g transform={`translate(${view.tx} ${view.ty}) scale(${view.k})`}>
          {visibleEdges.map((e, i) => {
            const a = e.source;
            const b = e.target;
            const A = nodeById.get(a)!;
            const B = nodeById.get(b)!;
            const cls = ["gedge"];
            // 시드-시드 hot 승격은 제거 — 아래 오버레이(gedge-lit)가 대체한다. base가 이미
            // 앰버면 오버레이의 드로잉 연출이 그 위에서 보이지 않는다.
            if (hi && !(hi.has(a) || hi.has(b))) cls.push("dim");
            if (selectedId && (selectedId === a || selectedId === b))
              cls.push("hot");
            return (
              <path key={i} d={edgePath(A, B)} className={cls.join(" ")} />
            );
          })}
          {(() => {
            // 데이터에 같은 시드 쌍이 중복(양방향·다중 엣지)으로 와도 오버레이는 한 번만
            // 그린다 — edgeKey가 React key라 중복이면 키 충돌 + 이중 렌더가 된다.
            const litSeen = new Set<string>();
            return visibleEdges.map((e) => {
            const a = e.source;
            const b = e.target;
            if (!hi || !hi.has(a) || !hi.has(b)) return null;
            const key = edgeKey(a, b);
            if (litSeen.has(key)) return null;
            litSeen.add(key);
            const A = nodeById.get(a)!;
            const B = nodeById.get(b)!;
            const cls = ["gedge-lit"];
            let anim: React.CSSProperties | undefined;
            if (igniting && schedule) {
              const drawStart = schedule.drawEdges.get(key);
              const fadeStart = schedule.fadeEdges.get(key);
              if (drawStart !== undefined) {
                cls.push("ignite-draw");
                anim = {
                  animationDelay: drawStart + "ms",
                  animationDuration: IGNITE_EDGE_MS + "ms",
                };
              } else if (fadeStart !== undefined) {
                cls.push("ignite-fade");
                anim = {
                  animationDelay: fadeStart + "ms",
                  animationDuration: IGNITE_NODE_MS + "ms",
                };
              }
            }
            return (
              <path
                key={key}
                className={cls.join(" ")}
                d={edgePath(A, B)}
                pathLength={1}
                style={anim}
              />
            );
            });
          })()}
          {positioned.filter(isAllowed).map((n) => {
            const isHi = !!hi && hi.has(n.id);
            const isSel = selectedId === n.id;
            const isEmph = !!emphasizedId && emphasizedId === n.id;
            const isHover = hover === n.id || isEmph;
            const cls = ["gnode-circle"];
            if (hi && !isHi) cls.push("dim");
            if (isHi || isSel || isHover) cls.push("hot");
            const fill = nodeTypeColors
              ? NODE_TYPE_INFO[n.type].cssVar
              : "var(--node-code)";
            // 카드 hover 강조는 selected보다 한 단계 더 키워 다른 시드 사이에서 또렷하게 한다.
            const r = isEmph
              ? rSel + 2
              : isSel
                ? rSel
                : isHi || isHover
                  ? rBase + 2
                  : rBase;
            let anchor: "start" | "middle" | "end" = "middle";
            let dx = 0;
            if (n.px < 90) {
              anchor = "start";
              dx = -r - 4;
            } else if (size.w - n.px < 90) {
              anchor = "end";
              dx = r + 4;
            }
            // 시드 발화 지연 — igniting이 아니거나 스케줄에 없으면 undefined(정적 최종 상태로 렌더).
            const igniteDelay = schedule?.nodeDelay.get(n.id);
            const isIgniteNode = igniteDelay !== undefined;
            const igniteStyle = isIgniteNode
              ? {
                  animationDelay: igniteDelay + "ms",
                  animationDuration: IGNITE_NODE_MS + "ms",
                }
              : undefined;
            return (
              <g
                key={n.id}
                onMouseEnter={() => {
                  setHover(n.id);
                  onHoverNode?.(n.id);
                }}
                onMouseLeave={() => {
                  setHover(null);
                  onHoverNode?.(null);
                }}
                onClick={(e) => {
                  e.stopPropagation();
                  onSelect?.(n);
                }}
                style={{ cursor: "pointer" }}
              >
                {isSel && (
                  <circle
                    cx={n.px}
                    cy={n.py}
                    r={r + 6}
                    fill="none"
                    stroke={fill}
                    strokeOpacity={0.3}
                    strokeWidth={6}
                  />
                )}
                {isHi && (
                  <>
                    <circle
                      cx={n.px}
                      cy={n.py}
                      r={r + 6}
                      className={"gseed-halo" + (isIgniteNode ? " ignite" : "")}
                      style={igniteStyle}
                    />
                    <circle
                      cx={n.px}
                      cy={n.py}
                      r={r + 4}
                      className={"gseed-ring" + (isIgniteNode ? " ignite" : "")}
                      style={igniteStyle}
                    />
                  </>
                )}
                <circle
                  cx={n.px}
                  cy={n.py}
                  r={r}
                  className={cls.join(" ") + (isIgniteNode ? " ignite-node" : "")}
                  fill={fill}
                  style={
                    isIgniteNode ? { color: fill, ...igniteStyle } : { color: fill }
                  }
                />
                {showLabels && (
                  <text
                    x={n.px + dx}
                    y={n.py + r + 12}
                    textAnchor={anchor}
                    className={
                      "gnode-label" +
                      (hi && !isHi ? " dim" : "") +
                      (isIgniteNode ? " ignite-node" : "")
                    }
                    style={igniteStyle}
                  >
                    {truncate(n.title, compact ? 14 : 26)}
                  </text>
                )}
              </g>
            );
          })}
        </g>
      </svg>

      {showFilters && (
        <div className="graph-filters">
          {allTypes.map((t) => {
            const active = !activeFilters || activeFilters.has(t);
            return (
              <button
                key={t}
                className={"filter-chip" + (active ? " active" : "")}
                onClick={() => toggleFilter(t)}
              >
                <span
                  className="ch-dot"
                  style={{ background: NODE_TYPE_INFO[t].cssVar }}
                />
                {NODE_TYPE_INFO[t].label}
              </button>
            );
          })}
        </div>
      )}

      {showControls && (
        <div className="graph-controls">
          <button className="icon-btn" title="Zoom in" onClick={() => zoom(1.2)}>
            <Icons.ZoomIn />
          </button>
          <button className="icon-btn" title="Zoom out" onClick={() => zoom(0.83)}>
            <Icons.ZoomOut />
          </button>
          {showFit && (
            <button className="icon-btn" title="Fit" onClick={fit}>
              <Icons.Fit />
            </button>
          )}
        </div>
      )}

      {showLegend && !compact && (
        <div className="graph-legend">
          {allTypes.map((t) => (
            <div key={t} className="legend-row">
              <span
                className="legend-dot"
                style={{ background: NODE_TYPE_INFO[t].cssVar }}
              />
              <span>{NODE_TYPE_INFO[t].label}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
