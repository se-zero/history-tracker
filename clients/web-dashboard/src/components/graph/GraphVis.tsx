import {
  forceCenter,
  forceCollide,
  forceLink,
  forceManyBody,
  forceSimulation,
  type SimulationLinkDatum,
  type SimulationNodeDatum,
} from "d3-force";
import { useEffect, useMemo, useRef, useState } from "react";

import { Icons } from "@/components/Icons";
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
  onSelect?: (node: GraphNode) => void;
  showLegend?: boolean;
  showControls?: boolean;
  showFilters?: boolean;
  compact?: boolean;
  showLabels?: boolean;
  nodeTypeColors?: boolean;
}

type SimNode = SimulationNodeDatum & GraphNode;
type SimLink = SimulationLinkDatum<SimNode>;

interface Positioned extends GraphNode {
  px: number;
  py: number;
}

function clamp(n: number, lo: number, hi: number) {
  return Math.min(hi, Math.max(lo, n));
}

function truncate(s: string, n: number) {
  return s.length > n ? s.slice(0, n - 1) + "…" : s;
}

function runSimulation(nodes: GraphNode[], edges: GraphEdge[]): SimNode[] {
  const simNodes: SimNode[] = nodes.map((n) => ({ ...n }));
  const idx = new Map(simNodes.map((n) => [n.id, n]));
  const simLinks: SimLink[] = edges
    .filter(([a, b]) => idx.has(a) && idx.has(b))
    .map(([a, b]) => ({ source: a, target: b }));
  const sim = forceSimulation<SimNode>(simNodes)
    .force(
      "link",
      forceLink<SimNode, SimLink>(simLinks)
        .id((d) => d.id)
        .distance(90)
        .strength(0.6),
    )
    .force("charge", forceManyBody<SimNode>().strength(-260))
    .force("center", forceCenter(0, 0))
    .force("collide", forceCollide<SimNode>(28))
    .stop();
  for (let i = 0; i < 320; i++) sim.tick();
  return simNodes;
}

function fitTo(
  simNodes: SimNode[],
  width: number,
  height: number,
  pad: number,
): Positioned[] {
  if (simNodes.length === 0) return [];
  const xs = simNodes.map((n) => n.x ?? 0);
  const ys = simNodes.map((n) => n.y ?? 0);
  const minX = Math.min(...xs);
  const maxX = Math.max(...xs);
  const minY = Math.min(...ys);
  const maxY = Math.max(...ys);
  const spanX = Math.max(maxX - minX, 1);
  const spanY = Math.max(maxY - minY, 1);
  const innerW = Math.max(width - pad * 2, 1);
  const innerH = Math.max(height - pad * 2, 1);
  return simNodes.map((n) => ({
    id: n.id,
    type: n.type,
    title: n.title,
    meta: n.meta,
    source: n.source,
    snippet: n.snippet,
    px: pad + (((n.x ?? 0) - minX) / spanX) * innerW,
    py: pad + (((n.y ?? 0) - minY) / spanY) * innerH,
  }));
}

export function GraphVis({
  nodes,
  edges,
  highlighted,
  selectedId,
  onSelect,
  showLegend = true,
  showControls = true,
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
      const factor = e.deltaY > 0 ? 0.9 : 1.1;
      setView((v) => ({ ...v, k: clamp(v.k * factor, 0.4, 2.5) }));
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
    panRef.current = { x: e.clientX, y: e.clientY, tx: view.tx, ty: view.ty };
  };
  const onMouseMove = (e: React.MouseEvent) => {
    if (!panRef.current) return;
    setView((v) => ({
      ...v,
      tx: panRef.current!.tx + (e.clientX - panRef.current!.x),
      ty: panRef.current!.ty + (e.clientY - panRef.current!.y),
    }));
  };
  const onMouseUp = () => {
    panRef.current = null;
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
      <svg className="graph-canvas" viewBox={`0 0 ${size.w} ${size.h}`}>
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
            const isHover = hover === n.id;
            const cls = ["gnode-circle"];
            if (hi && !isHi) cls.push("dim");
            if (isHi || isSel || isHover) cls.push("hot");
            const fill = nodeTypeColors
              ? NODE_TYPE_INFO[n.type].cssVar
              : "var(--node-code)";
            const r = isSel ? rSel : isHi || isHover ? rBase + 2 : rBase;
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
          <button className="icon-btn" title="Fit" onClick={fit}>
            <Icons.Fit />
          </button>
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
