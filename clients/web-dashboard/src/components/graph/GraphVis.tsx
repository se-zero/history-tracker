import { useEffect, useMemo, useRef, useState } from "react";

import { Icons } from "@/components/Icons";
import { fitTo, runSimulation, type Positioned } from "@/lib/graphLayout";
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
  onBackgroundClick?: () => void;
  showLegend?: boolean;
  showControls?: boolean;
  showFit?: boolean;
  showFilters?: boolean;
  compact?: boolean;
  showLabels?: boolean;
  nodeTypeColors?: boolean;
}

function clamp(n: number, lo: number, hi: number) {
  return Math.min(hi, Math.max(lo, n));
}

function truncate(s: string, n: number) {
  return s.length > n ? s.slice(0, n - 1) + "…" : s;
}

export function GraphVis({
  nodes,
  edges,
  highlighted,
  selectedId,
  emphasizedId,
  onSelect,
  onBackgroundClick,
  showLegend = true,
  showControls = true,
  showFit = true,
  showFilters = true,
  compact = false,
  showLabels = true,
  nodeTypeColors = true,
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

  useEffect(() => {
    if (!wrapRef.current) return;
    const ro = new ResizeObserver(() => {
      const r = wrapRef.current!.getBoundingClientRect();
      setSize({ w: r.width, h: r.height });
    });
    ro.observe(wrapRef.current);
    return () => ro.disconnect();
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

  const isAllowed = (n: Positioned) =>
    !activeFilters || activeFilters.has(n.type);

  const visibleEdges = useMemo(() => {
    return edges.filter(([a, b]) => {
      const A = nodeById.get(a);
      const B = nodeById.get(b);
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
          {visibleEdges.map(([a, b], i) => {
            const A = nodeById.get(a)!;
            const B = nodeById.get(b)!;
            const cls = ["gedge"];
            if (hi && (hi.has(a) || hi.has(b))) {
              if (hi.has(a) && hi.has(b)) cls.push("hot");
            } else if (hi) {
              cls.push("dim");
            }
            if (selectedId && (selectedId === a || selectedId === b))
              cls.push("hot");
            const mx = (A.px + B.px) / 2;
            const my = (A.py + B.py) / 2;
            const dx = B.px - A.px;
            const dy = B.py - A.py;
            const len = Math.hypot(dx, dy) || 1;
            const off = 14;
            const cx = mx + (-dy / len) * off;
            const cy = my + (dx / len) * off;
            return (
              <path
                key={i}
                d={`M ${A.px} ${A.py} Q ${cx} ${cy} ${B.px} ${B.py}`}
                className={cls.join(" ")}
              />
            );
          })}
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
            return (
              <g
                key={n.id}
                onMouseEnter={() => setHover(n.id)}
                onMouseLeave={() => setHover(null)}
                onClick={(e) => {
                  e.stopPropagation();
                  onSelect?.(n);
                }}
                style={{ cursor: "pointer" }}
              >
                {(isSel || isHi) && (
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
                <circle
                  cx={n.px}
                  cy={n.py}
                  r={r}
                  className={cls.join(" ")}
                  fill={fill}
                  style={{ color: fill }}
                />
                {showLabels && (
                  <text
                    x={n.px + dx}
                    y={n.py + r + 12}
                    textAnchor={anchor}
                    className={"gnode-label" + (hi && !isHi ? " dim" : "")}
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
