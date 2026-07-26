import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { Icons } from "@/components/Icons";
import {
  ConstellationDetail,
  type Connection,
} from "@/components/graph/ConstellationDetail";
import { rgba, resolveVarRgb, shade, varName, type Rgb } from "@/lib/canvasColor";
import {
  buildConstellations,
  type ConstellationLayout,
  type Satellite,
} from "@/lib/constellation";
import { useTheme } from "@/theme/ThemeProvider";
import {
  NODE_TYPE_INFO,
  type GraphEdge,
  type GraphNode,
  type GraphNodeType,
} from "@/types/graph";

interface Props {
  nodes: GraphNode[];
  edges: GraphEdge[];
  selectedId: string | null;
  onSelect: (node: GraphNode) => void;
  onBackgroundClick: () => void;
}

/** 화면에 맞추는 기본 배율에 곱하는 여백 계수. */
const FIT_MARGIN = 0.92;
const MIN_ZOOM = 0.35;
/**
 * 최대 확대는 고정 배율(k)이 아니라 "월드 1단위가 화면 몇 px이 되는가"로 정한다.
 * 기본 배율은 그래프 규모(extent)에 따라 달라지므로 k에 고정 상한을 두면,
 * 성좌를 여는 순간 이미 상한에 닿아 그 안에서 더 확대할 수 없게 된다.
 * 위성 반지름이 2~3.6이라 28px/단위면 입자 하나가 지름 150px 안팎까지 커진다 —
 * 촘촘한 궤도에서 라벨을 하나씩 뜯어볼 수 있는 수준.
 */
const MAX_SCALE = 28;
/** 성좌를 열면 왼쪽 패널이 화면을 가리므로, 그만큼 중심을 오른쪽으로 민다. */
const PANEL_SHIFT = 150;
/** 카메라 이동 보간 계수 — 프레임마다 목표까지 남은 거리의 이 비율만큼 다가간다. */
const CAMERA_EASE = 0.16;
/** 이 배율 아래에서는 위성 라벨을 그리지 않는다 (겹쳐서 읽을 수 없다). */
const SATELLITE_LABEL_MIN_SPAN = 110;

interface View {
  k: number;
  tx: number;
  ty: number;
}

interface Size {
  w: number;
  h: number;
}

interface Hit {
  node: GraphNode;
  starIndex: number | null;
  isStar: boolean;
}

interface Palette {
  byType: Record<GraphNodeType, Rgb>;
  bg: Rgb;
  fg: Rgb;
  muted: Rgb;
  fontFamily: string;
}

interface BgStar {
  x: number;
  y: number;
  r: number;
  a: number;
  phase: number;
}

function makeStarfield(count: number): BgStar[] {
  // 고정 시드 LCG — 새로고침해도 같은 하늘이 나온다.
  let seed = 20260726;
  const rand = () => {
    seed = (seed * 1664525 + 1013904223) % 4294967296;
    return seed / 4294967296;
  };
  return Array.from({ length: count }, () => ({
    x: rand(),
    y: rand(),
    r: 0.4 + rand() * 1.1,
    a: 0.12 + rand() * 0.4,
    phase: rand() * Math.PI * 2,
  }));
}

const clamp = (n: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, n));

/** 뷰포트 전체를 채우는 기본 배율 (줌 1일 때). */
function baseScale(layout: ConstellationLayout, size: Size): number {
  return (Math.min(size.w, size.h) / (2 * Math.max(layout.extent, 1))) * FIT_MARGIN;
}

function transform(layout: ConstellationLayout, size: Size, view: View) {
  return {
    s: baseScale(layout, size) * view.k,
    cx: size.w / 2 + view.tx,
    cy: size.h / 2 + view.ty,
  };
}

function truncate(s: string, n: number) {
  return s.length > n ? s.slice(0, n - 1) + "…" : s;
}

/**
 * 반지름 radius의 원이 화면에 조금이라도 걸치는지.
 * 확대할수록 성운·헤일로 그라디언트가 수천 px로 커지는데, 화면 밖 성좌까지 매 프레임
 * 만들어 칠하면 그리기 비용이 배율에 비례해 늘어난다. 그래서 미리 걸러낸다.
 */
function inView(x: number, y: number, radius: number, size: Size): boolean {
  return (
    x + radius >= 0 && x - radius <= size.w && y + radius >= 0 && y - radius <= size.h
  );
}

export function ConstellationVis({
  nodes,
  edges,
  selectedId,
  onSelect,
  onBackgroundClick,
}: Props) {
  const wrapRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [size, setSize] = useState<Size>({ w: 900, h: 640 });
  const { theme } = useTheme();

  // 뷰(줌·팬)는 매 프레임 캔버스가 다시 그려지므로 React state로 둘 필요가 없다.
  const viewRef = useRef<View>({ k: 1, tx: 0, ty: 0 });
  // 카메라가 향할 목표 — 성좌를 열 때 부드럽게 날아가기 위한 것. 사용자가 직접 조작하면 취소된다.
  const targetRef = useRef<View | null>(null);
  const panRef = useRef<{ x: number; y: number; tx: number; ty: number } | null>(null);
  const draggedRef = useRef(false);
  const hitRef = useRef<Hit | null>(null);
  const selectedRef = useRef<string | null>(selectedId);
  const focusedRef = useRef<number | null>(null);

  const [hovered, setHovered] = useState<GraphNode | null>(null);
  const [focused, setFocused] = useState<number | null>(null);

  useEffect(() => {
    selectedRef.current = selectedId;
  }, [selectedId]);
  useEffect(() => {
    focusedRef.current = focused;
  }, [focused]);

  const layout = useMemo(() => buildConstellations(nodes, edges), [nodes, edges]);
  const starfield = useMemo(() => makeStarfield(220), []);

  // 확대 상한은 그래프 규모·뷰포트에 따라 달라진다. 휠 핸들러가 [] deps라 ref로 전달한다.
  const maxZoomRef = useRef(MAX_SCALE);
  useEffect(() => {
    maxZoomRef.current = Math.max(2, MAX_SCALE / baseScale(layout, size));
  }, [layout, size]);

  // 성좌가 바뀌면 열려 있던 포커스는 의미가 없다.
  useEffect(() => {
    setFocused(null);
    targetRef.current = null;
    viewRef.current = { k: 1, tx: 0, ty: 0 };
  }, [layout]);

  /** 별성 인덱스 → 다리로 이어진 다른 별성들. */
  const starNeighbors = useMemo(() => {
    const map = new Map<number, Set<number>>();
    for (const b of layout.bridges) {
      if (!map.has(b.a)) map.set(b.a, new Set());
      if (!map.has(b.b)) map.set(b.b, new Set());
      map.get(b.a)!.add(b.b);
      map.get(b.b)!.add(b.a);
    }
    return map;
  }, [layout]);

  const palette = useMemo<Palette>(() => {
    const byType = {} as Record<GraphNodeType, Rgb>;
    for (const type of Object.keys(NODE_TYPE_INFO) as GraphNodeType[]) {
      byType[type] = resolveVarRgb(varName(NODE_TYPE_INFO[type].cssVar), theme);
    }
    return {
      byType,
      bg: resolveVarRgb("--bg", theme),
      fg: resolveVarRgb("--fg", theme),
      muted: resolveVarRgb("--fg-muted", theme),
      fontFamily:
        getComputedStyle(document.documentElement)
          .getPropertyValue("--font-sans")
          .trim() || "system-ui, sans-serif",
    };
  }, [theme]);

  useEffect(() => {
    const el = wrapRef.current;
    if (!el) return;
    const ro = new ResizeObserver(() => {
      const r = el.getBoundingClientRect();
      setSize({ w: Math.max(1, r.width), h: Math.max(1, r.height) });
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  // React의 onWheel은 passive라 preventDefault가 먹지 않는다 — native로 직접 붙인다.
  // 래퍼가 아니라 캔버스에 붙이는 게 중요하다. 래퍼에 붙이면 위에 얹힌 상세 패널에서
  // 굴린 휠까지 버블링으로 잡아 preventDefault해 버려서, 목록이 스크롤되지 않고 줌이 된다.
  useEffect(() => {
    const el = canvasRef.current;
    if (!el) return;
    const handler = (e: WheelEvent) => {
      e.preventDefault();
      targetRef.current = null; // 직접 조작이 카메라 애니메이션보다 우선한다.
      const rect = el.getBoundingClientRect();
      const px = e.clientX - rect.left;
      const py = e.clientY - rect.top;
      const view = viewRef.current;
      const k = clamp(view.k * (e.deltaY > 0 ? 0.88 : 1.12), MIN_ZOOM, maxZoomRef.current);
      // 커서 아래 월드 지점이 고정되도록 중심 이동을 보정한다.
      const ratio = k / view.k;
      const cx = rect.width / 2 + view.tx;
      const cy = rect.height / 2 + view.ty;
      viewRef.current = {
        k,
        tx: view.tx + (px - cx) * (1 - ratio),
        ty: view.ty + (py - cy) * (1 - ratio),
      };
    };
    el.addEventListener("wheel", handler, { passive: false });
    return () => el.removeEventListener("wheel", handler);
  }, []);

  const focusOn = useCallback(
    (index: number) => {
      const star = layout.stars[index];
      if (!star) return;
      const base = baseScale(layout, size);
      // 성좌 하나가 화면의 약 36%를 차지하도록 배율을 정한다.
      const wanted = (Math.min(size.w, size.h) * 0.36) / Math.max(star.reach, 1);
      const k = clamp(wanted / base, MIN_ZOOM, maxZoomRef.current);
      const s = base * k;
      targetRef.current = {
        k,
        tx: PANEL_SHIFT - star.x * s,
        ty: -star.y * s,
      };
      setFocused(index);
    },
    [layout, size],
  );

  const exitFocus = useCallback(() => {
    setFocused(null);
    targetRef.current = { k: 1, tx: 0, ty: 0 };
  }, []);

  // ESC로 성좌에서 빠져나온다.
  useEffect(() => {
    if (focused === null) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === "Escape") exitFocus();
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [focused, exitFocus]);

  // 렌더 루프 — layout/palette/size가 바뀔 때만 재시작하고, 나머지는 ref로 읽는다.
  useEffect(() => {
    const canvas = canvasRef.current;
    const ctx = canvas?.getContext("2d");
    if (!canvas || !ctx) return;

    const dpr = Math.min(2, window.devicePixelRatio || 1);
    canvas.width = Math.round(size.w * dpr);
    canvas.height = Math.round(size.h * dpr);

    let raf = 0;
    const started = performance.now();
    const loop = () => {
      stepCamera();
      const focusedIndex = focusedRef.current;
      drawScene(ctx, {
        dpr,
        size,
        view: viewRef.current,
        layout,
        palette,
        starfield,
        time: (performance.now() - started) / 1000,
        focused: focusedIndex,
        related: focusedIndex === null ? null : (starNeighbors.get(focusedIndex) ?? new Set()),
        hoveredId: hitRef.current?.node.id ?? null,
        selectedId: selectedRef.current,
        hoveredStar: hitRef.current?.starIndex ?? null,
      });
      raf = requestAnimationFrame(loop);
    };
    raf = requestAnimationFrame(loop);
    return () => cancelAnimationFrame(raf);
  }, [layout, palette, size, starfield, starNeighbors]);

  /** 카메라 목표가 있으면 매 프레임 조금씩 다가간다. */
  const stepCamera = () => {
    const target = targetRef.current;
    if (!target) return;
    const v = viewRef.current;
    const k = v.k + (target.k - v.k) * CAMERA_EASE;
    const tx = v.tx + (target.tx - v.tx) * CAMERA_EASE;
    const ty = v.ty + (target.ty - v.ty) * CAMERA_EASE;
    const done =
      Math.abs(target.k - k) < 0.002 &&
      Math.abs(target.tx - tx) < 0.5 &&
      Math.abs(target.ty - ty) < 0.5;
    if (done) {
      viewRef.current = target;
      targetRef.current = null;
    } else {
      viewRef.current = { k, tx, ty };
    }
  };

  const pick = (clientX: number, clientY: number): Hit | null => {
    const el = wrapRef.current;
    if (!el) return null;
    const rect = el.getBoundingClientRect();
    return hitTest(layout, size, viewRef.current, clientX - rect.left, clientY - rect.top);
  };

  const onMouseDown = (e: React.MouseEvent) => {
    if (e.button !== 0) return;
    draggedRef.current = false;
    const view = viewRef.current;
    panRef.current = { x: e.clientX, y: e.clientY, tx: view.tx, ty: view.ty };
  };

  const onMouseMove = (e: React.MouseEvent) => {
    // 업데이터 지연 중 ref가 바뀌어도 안전하도록 값을 먼저 캡처한다.
    const pan = panRef.current;
    if (pan) {
      const dx = e.clientX - pan.x;
      const dy = e.clientY - pan.y;
      if (Math.abs(dx) > 3 || Math.abs(dy) > 3) {
        draggedRef.current = true;
        targetRef.current = null;
      }
      viewRef.current = { ...viewRef.current, tx: pan.tx + dx, ty: pan.ty + dy };
      return;
    }
    const hit = pick(e.clientX, e.clientY);
    hitRef.current = hit;
    // 툴팁/커서용 state는 대상이 실제로 바뀔 때만 갱신한다(매 프레임 리렌더 방지).
    setHovered((prev) => (prev?.id === hit?.node.id ? prev : (hit?.node ?? null)));
  };

  const endPan = () => {
    panRef.current = null;
  };

  const onClick = () => {
    if (draggedRef.current) return;
    const hit = hitRef.current;
    if (!hit) {
      if (focused !== null) exitFocus();
      onBackgroundClick();
      return;
    }
    // 별성을 누르면 그 성좌로 파고들고, 위성을 누르면 노드 상세만 연다.
    if (hit.isStar && hit.starIndex !== null) focusOn(hit.starIndex);
    onSelect(hit.node);
  };

  /**
   * 확대/축소 버튼. 성좌를 열었으면 그 성좌를 축으로 삼는다 —
   * 열린 성좌는 패널을 피해 중심에서 비켜나 있어서, 화면 중앙 기준으로 배율만 바꾸면
   * 확대할수록 성좌가 화면 밖으로 밀려난다.
   */
  const zoom = (factor: number) => {
    targetRef.current = null;
    const view = viewRef.current;
    const k = clamp(view.k * factor, MIN_ZOOM, maxZoomRef.current);
    const ratio = k / view.k;
    if (ratio === 1) return;
    const cx = size.w / 2 + view.tx;
    const cy = size.h / 2 + view.ty;
    const star = focused === null ? null : layout.stars[focused];
    const s = baseScale(layout, size) * view.k;
    // 이 화면 좌표에 있는 월드 지점이 확대 후에도 제자리에 있도록 이동을 보정한다.
    const ax = star ? cx + star.x * s : size.w / 2;
    const ay = star ? cy + star.y * s : size.h / 2;
    viewRef.current = {
      k,
      tx: view.tx + (ax - cx) * (1 - ratio),
      ty: view.ty + (ay - cy) * (1 - ratio),
    };
  };

  const focusedStar = focused === null ? null : (layout.stars[focused] ?? null);

  const connections = useMemo<Connection[]>(() => {
    if (focused === null) return [];
    return layout.bridges
      .filter((b) => b.a === focused || b.b === focused)
      .map((b) => {
        const other = b.a === focused ? b.b : b.a;
        return { index: other, star: layout.stars[other], shared: b.shared };
      })
      .filter((c) => c.star)
      .sort((a, b) => b.shared.length - a.shared.length);
  }, [layout, focused]);

  return (
    <div className="galaxy-wrap" ref={wrapRef}>
      <canvas
        ref={canvasRef}
        className="galaxy-canvas"
        style={{ width: size.w, height: size.h, cursor: hovered ? "pointer" : "grab" }}
        onMouseDown={onMouseDown}
        onMouseMove={onMouseMove}
        onMouseUp={endPan}
        onMouseLeave={() => {
          endPan();
          hitRef.current = null;
          setHovered(null);
        }}
        onClick={onClick}
      />

      {focusedStar ? (
        <ConstellationDetail
          star={focusedStar}
          connections={connections}
          selectedId={selectedId}
          onSelectNode={onSelect}
          onJump={(index) => {
            focusOn(index);
            onSelect(layout.stars[index].node);
          }}
          onClose={exitFocus}
        />
      ) : (
        <div className="galaxy-legend">
          <div className="galaxy-legend-hint">별성을 클릭하면 성좌가 열립니다</div>
          {(Object.keys(NODE_TYPE_INFO) as GraphNodeType[])
            .filter((t) => t !== "actor")
            .map((t) => (
              <div key={t} className="galaxy-legend-row">
                <span
                  className="galaxy-legend-dot"
                  style={{ background: NODE_TYPE_INFO[t].cssVar }}
                />
                <span>{NODE_TYPE_INFO[t].label}</span>
              </div>
            ))}
        </div>
      )}

      <div className="graph-controls">
        <button className="icon-btn" title="확대" onClick={() => zoom(1.25)}>
          <Icons.ZoomIn />
        </button>
        <button className="icon-btn" title="축소" onClick={() => zoom(0.8)}>
          <Icons.ZoomOut />
        </button>
        <button className="icon-btn" title="전체 보기" onClick={exitFocus}>
          <Icons.Fit />
        </button>
      </div>
    </div>
  );
}

function hitTest(
  layout: ConstellationLayout,
  size: Size,
  view: View,
  px: number,
  py: number,
): Hit | null {
  const { s, cx, cy } = transform(layout, size, view);
  let best: Hit | null = null;
  let bestDist = Infinity;

  const consider = (
    node: GraphNode,
    wx: number,
    wy: number,
    radius: number,
    star: number | null,
    isStar: boolean,
  ) => {
    const x = cx + wx * s;
    const y = cy + wy * s;
    // 화면 기준 최소 클릭 반경을 보장해 작은 입자도 집을 수 있게 한다.
    const hitR = Math.max(radius * s, 7);
    const d = Math.hypot(x - px, y - py);
    if (d <= hitR && d < bestDist) {
      bestDist = d;
      best = { node, starIndex: star, isStar };
    }
  };

  layout.stars.forEach((star, i) => {
    star.satellites.forEach((sat) => consider(sat.node, sat.x, sat.y, sat.r, i, false));
  });
  layout.dust.forEach((d) => consider(d.node, d.x, d.y, d.r, null, false));
  // 별성을 마지막에 보아 겹칠 때 우선 잡히게 한다.
  layout.stars.forEach((star, i) =>
    consider(star.node, star.x, star.y, star.r, i, true),
  );

  return best;
}

interface SceneParams {
  dpr: number;
  size: Size;
  view: View;
  layout: ConstellationLayout;
  palette: Palette;
  starfield: BgStar[];
  time: number;
  focused: number | null;
  related: Set<number> | null;
  hoveredId: string | null;
  hoveredStar: number | null;
  selectedId: string | null;
}

/**
 * 성좌별 강조도(0~1)를 미리 계산한다.
 * 포커스 중이면 연결된 성좌를 중간 밝기로 남겨 "어디로 이어지는지"가 보이게 한다.
 */
function computeEmphasis(p: SceneParams): number[] {
  const n = p.layout.stars.length;
  const emph = new Array<number>(n).fill(1);
  if (p.focused !== null) {
    for (let i = 0; i < n; i++) {
      emph[i] = i === p.focused ? 1 : p.related?.has(i) ? 0.5 : 0.1;
    }
  } else if (p.hoveredStar !== null) {
    for (let i = 0; i < n; i++) emph[i] = i === p.hoveredStar ? 1 : 0.32;
  }
  return emph;
}

function drawScene(ctx: CanvasRenderingContext2D, p: SceneParams): void {
  const { dpr, size, layout, view } = p;
  const { s, cx, cy } = transform(layout, size, view);
  const emph = computeEmphasis(p);

  ctx.save();
  ctx.scale(dpr, dpr);

  drawBackground(ctx, p);
  drawNebulae(ctx, p, emph, s, cx, cy);
  drawBridges(ctx, p, emph, s, cx, cy);
  drawSpokes(ctx, p, emph, s, cx, cy);
  drawParticles(ctx, p, emph, s, cx, cy);
  drawStars(ctx, p, emph, s, cx, cy);
  drawLabels(ctx, p, emph, s, cx, cy);

  ctx.restore();
}

function drawBackground(ctx: CanvasRenderingContext2D, p: SceneParams): void {
  const { size, palette, starfield, time, view } = p;

  ctx.fillStyle = rgba(palette.bg, 1);
  ctx.fillRect(0, 0, size.w, size.h);

  // 배경 별먼지 — 팬에 아주 약하게 따라 움직여 깊이감을 준다.
  const ox = view.tx * 0.04;
  const oy = view.ty * 0.04;
  ctx.globalCompositeOperation = "lighter";
  for (const st of starfield) {
    const x = (((st.x * size.w + ox) % size.w) + size.w) % size.w;
    const y = (((st.y * size.h + oy) % size.h) + size.h) % size.h;
    const twinkle = 0.65 + 0.35 * Math.sin(time * 0.9 + st.phase);
    ctx.fillStyle = rgba(palette.fg, st.a * twinkle * 0.5);
    ctx.beginPath();
    ctx.arc(x, y, st.r, 0, Math.PI * 2);
    ctx.fill();
  }
  ctx.globalCompositeOperation = "source-over";

  // 가장자리를 눌러 중앙에 시선을 모은다.
  const vignette = ctx.createRadialGradient(
    size.w / 2,
    size.h / 2,
    Math.min(size.w, size.h) * 0.25,
    size.w / 2,
    size.h / 2,
    Math.max(size.w, size.h) * 0.72,
  );
  vignette.addColorStop(0, rgba(palette.bg, 0));
  vignette.addColorStop(1, rgba(shade(palette.bg, 0.55), 0.85));
  ctx.fillStyle = vignette;
  ctx.fillRect(0, 0, size.w, size.h);
}

/** 성좌마다 은은한 색 구름을 깔아 밀도가 낮아도 화면이 비어 보이지 않게 한다. */
function drawNebulae(
  ctx: CanvasRenderingContext2D,
  p: SceneParams,
  emph: number[],
  s: number,
  cx: number,
  cy: number,
): void {
  const { layout, palette, time, size } = p;
  ctx.globalCompositeOperation = "lighter";
  layout.stars.forEach((star, i) => {
    const color = palette.byType[star.node.type];
    const x = cx + star.x * s;
    const y = cy + star.y * s;
    const radius = star.reach * s * 1.25;
    if (radius <= 0 || !inView(x, y, radius, size)) return;
    // 숨 쉬듯 아주 느리게 밝기가 오르내린다.
    const breathe = 0.9 + 0.1 * Math.sin(time * 0.5 + i);
    const alpha = 0.17 * emph[i] * breathe;
    const g = ctx.createRadialGradient(x, y, 0, x, y, radius);
    g.addColorStop(0, rgba(color, alpha));
    g.addColorStop(0.45, rgba(color, alpha * 0.4));
    g.addColorStop(1, rgba(color, 0));
    ctx.fillStyle = g;
    ctx.beginPath();
    ctx.arc(x, y, radius, 0, Math.PI * 2);
    ctx.fill();
  });
  ctx.globalCompositeOperation = "source-over";
}

/**
 * 성좌 사이의 다리 — 같은 파일·티켓을 공유한 작업들을 잇는 성좌선.
 * 포커스 중인 성좌에서 뻗어나가는 다리는 밝게 그리고, 흐르는 입자로 방향을 보여준다.
 */
function drawBridges(
  ctx: CanvasRenderingContext2D,
  p: SceneParams,
  emph: number[],
  s: number,
  cx: number,
  cy: number,
): void {
  const { layout, palette, focused, time } = p;
  ctx.globalCompositeOperation = "lighter";

  for (const bridge of layout.bridges) {
    const a = layout.stars[bridge.a];
    const b = layout.stars[bridge.b];
    if (!a || !b) continue;

    const isFocusLink = focused !== null && (bridge.a === focused || bridge.b === focused);
    // 포커스 중이면 그 성좌의 다리만 살리고 나머지는 확실히 눌러 둔다.
    const strength = isFocusLink ? 1 : Math.min(emph[bridge.a], emph[bridge.b]) * 0.7;

    const ax = cx + a.x * s;
    const ay = cy + a.y * s;
    const bx = cx + b.x * s;
    const by = cy + b.y * s;
    // 살짝 휘어야 직선 격자처럼 보이지 않는다.
    const mx = (ax + bx) / 2;
    const my = (ay + by) / 2;
    const dx = bx - ax;
    const dy = by - ay;
    const len = Math.hypot(dx, dy) || 1;
    const bow = Math.min(60, len * 0.12);
    const qx = mx + (-dy / len) * bow;
    const qy = my + (dx / len) * bow;

    ctx.strokeStyle = rgba(
      isFocusLink ? palette.byType[a.node.type] : palette.muted,
      (isFocusLink ? 0.5 : 0.28) * strength,
    );
    ctx.lineWidth = Math.min(2.6, 0.5 + bridge.weight * 0.22) * (isFocusLink ? 1.5 : 1);
    ctx.beginPath();
    ctx.moveTo(ax, ay);
    ctx.quadraticCurveTo(qx, qy, bx, by);
    ctx.stroke();

    if (!isFocusLink) continue;

    // 포커스한 성좌에서 상대 성좌 쪽으로 입자가 흐른다 — 연결의 방향과 존재를 눈에 띄게 한다.
    const forward = bridge.a === focused;
    const [sx, sy, ex, ey] = forward ? [ax, ay, bx, by] : [bx, by, ax, ay];
    const color = palette.byType[(forward ? a : b).node.type];
    for (let n = 0; n < 3; n++) {
      const t = ((time * 0.32 + n / 3) % 1 + 1) % 1;
      const mt = 1 - t;
      const px = mt * mt * sx + 2 * mt * t * qx + t * t * ex;
      const py = mt * mt * sy + 2 * mt * t * qy + t * t * ey;
      // 양 끝에서 서서히 나타났다 사라지게 해 갑작스러운 점멸을 없앤다.
      const fade = Math.sin(t * Math.PI);
      const g = ctx.createRadialGradient(px, py, 0, px, py, 7);
      g.addColorStop(0, rgba(shade(color, 1.4), 0.85 * fade));
      g.addColorStop(1, rgba(color, 0));
      ctx.fillStyle = g;
      ctx.beginPath();
      ctx.arc(px, py, 7, 0, Math.PI * 2);
      ctx.fill();
    }
  }
  ctx.globalCompositeOperation = "source-over";
}

/** 별성 → 위성 연결선. 아주 흐리게 깔아 성좌의 소속감만 만든다. */
function drawSpokes(
  ctx: CanvasRenderingContext2D,
  p: SceneParams,
  emph: number[],
  s: number,
  cx: number,
  cy: number,
): void {
  const { layout, palette, size } = p;
  ctx.globalCompositeOperation = "lighter";
  layout.stars.forEach((star, i) => {
    if (emph[i] < 0.3) return;
    const color = palette.byType[star.node.type];
    const x = cx + star.x * s;
    const y = cy + star.y * s;
    if (!inView(x, y, star.reach * s, size)) return;
    ctx.strokeStyle = rgba(color, 0.22 * emph[i]);
    ctx.lineWidth = 0.7;
    ctx.beginPath();
    for (const sat of star.satellites) {
      ctx.moveTo(x, y);
      ctx.lineTo(cx + sat.x * s, cy + sat.y * s);
    }
    ctx.stroke();
  });
  ctx.globalCompositeOperation = "source-over";
}

/** 위성 + 성간 먼지 — 발광하는 입자로 그린다. */
function drawParticles(
  ctx: CanvasRenderingContext2D,
  p: SceneParams,
  emph: number[],
  s: number,
  cx: number,
  cy: number,
): void {
  const { layout, palette, time, hoveredId, selectedId, focused, size } = p;
  ctx.globalCompositeOperation = "lighter";

  const paint = (sat: Satellite, strength: number) => {
    const color = palette.byType[sat.node.type];
    const x = cx + sat.x * s;
    const y = cy + sat.y * s;
    const isFocus = sat.node.id === hoveredId || sat.node.id === selectedId;
    if (!inView(x, y, Math.max(sat.r * s, 1.3) * 9, size)) return;
    const twinkle = 0.75 + 0.25 * Math.sin(time * 1.6 + sat.phase);
    // 줌 아웃해도 입자가 사라지지 않도록 화면 기준 최소 크기를 준다.
    const r = Math.max(sat.r * s, 1.3) * (isFocus ? 2 : 1);
    const alpha = strength * twinkle;

    const glow = ctx.createRadialGradient(x, y, 0, x, y, r * 4.5);
    glow.addColorStop(0, rgba(color, 0.5 * alpha));
    glow.addColorStop(1, rgba(color, 0));
    ctx.fillStyle = glow;
    ctx.beginPath();
    ctx.arc(x, y, r * 4.5, 0, Math.PI * 2);
    ctx.fill();

    ctx.fillStyle = rgba(shade(color, 1.15), Math.min(1, 0.85 * alpha));
    ctx.beginPath();
    ctx.arc(x, y, r, 0, Math.PI * 2);
    ctx.fill();
  };

  layout.stars.forEach((star, i) => {
    star.satellites.forEach((sat) => paint(sat, emph[i]));
  });
  layout.dust.forEach((d) => paint(d, focused === null ? 0.7 : 0.1));

  ctx.globalCompositeOperation = "source-over";
}

/** 별성 오브 — 헤일로 + 코어 + 링. */
function drawStars(
  ctx: CanvasRenderingContext2D,
  p: SceneParams,
  emph: number[],
  s: number,
  cx: number,
  cy: number,
): void {
  const { layout, palette, time, hoveredId, selectedId, focused, size } = p;

  layout.stars.forEach((star, i) => {
    const color = palette.byType[star.node.type];
    const x = cx + star.x * s;
    const y = cy + star.y * s;
    const isFocus = star.node.id === hoveredId || star.node.id === selectedId;
    const alpha = emph[i];
    const pulse = 1 + 0.05 * Math.sin(time * 1.2 + i * 0.7);
    const r = Math.max(star.r * s, 5) * pulse * (isFocus ? 1.25 : 1);
    // 열린 성좌는 궤도 링(reach)까지 그리므로 그만큼 넉넉히 잡고 판정한다.
    if (!inView(x, y, focused === i ? star.reach * s : r * 5, size)) return;

    ctx.globalCompositeOperation = "lighter";
    const glow = ctx.createRadialGradient(x, y, 0, x, y, r * 5);
    glow.addColorStop(0, rgba(color, 0.55 * alpha));
    glow.addColorStop(0.4, rgba(color, 0.18 * alpha));
    glow.addColorStop(1, rgba(color, 0));
    ctx.fillStyle = glow;
    ctx.beginPath();
    ctx.arc(x, y, r * 5, 0, Math.PI * 2);
    ctx.fill();
    ctx.globalCompositeOperation = "source-over";

    // 코어는 중심이 흰빛으로 타는 그라디언트라야 "빛나는 구슬"처럼 보인다.
    const core = ctx.createRadialGradient(x - r * 0.3, y - r * 0.3, r * 0.1, x, y, r);
    core.addColorStop(0, rgba(shade(color, 1.6), alpha));
    core.addColorStop(0.55, rgba(color, alpha));
    core.addColorStop(1, rgba(shade(color, 0.72), alpha));
    ctx.fillStyle = core;
    ctx.beginPath();
    ctx.arc(x, y, r, 0, Math.PI * 2);
    ctx.fill();

    ctx.strokeStyle = rgba(shade(color, 1.5), 0.55 * alpha);
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.arc(x, y, r + 3.5, 0, Math.PI * 2);
    ctx.stroke();

    // 열려 있는 성좌는 궤도 링을 하나 둘러 "여기를 보고 있다"를 명확히 한다.
    if (focused === i) {
      ctx.strokeStyle = rgba(shade(color, 1.4), 0.32);
      ctx.lineWidth = 1.2;
      ctx.setLineDash([4, 6]);
      ctx.beginPath();
      ctx.arc(x, y, star.reach * s, 0, Math.PI * 2);
      ctx.stroke();
      ctx.setLineDash([]);
    } else if (isFocus) {
      ctx.strokeStyle = rgba(shade(color, 1.4), 0.4);
      ctx.lineWidth = 1.5;
      ctx.beginPath();
      ctx.arc(x, y, r + 11, 0, Math.PI * 2);
      ctx.stroke();
    }
  });
}

/**
 * 라벨은 화면 좌표에 고정 크기로 그린다 — 줌을 해도 글자 크기가 변하지 않아
 * 어느 배율에서든 읽힌다.
 */
function drawLabels(
  ctx: CanvasRenderingContext2D,
  p: SceneParams,
  emph: number[],
  s: number,
  cx: number,
  cy: number,
): void {
  const { layout, palette, focused, hoveredId, selectedId, size } = p;

  ctx.textAlign = "center";
  ctx.textBaseline = "top";

  layout.stars.forEach((star, i) => {
    const x = cx + star.x * s;
    const y = cy + star.y * s;
    if (x < -160 || x > size.w + 160 || y < -100 || y > size.h + 100) return;

    const r = Math.max(star.r * s, 5);
    const color = palette.byType[star.node.type];

    ctx.font = `600 12px ${palette.fontFamily}`;
    ctx.fillStyle = rgba(palette.fg, 0.94 * emph[i]);
    ctx.fillText(truncate(star.node.title, 26), x, y + r + 9);

    // 부제(작성자)는 주목 중인 성좌에만 — 평소엔 화면을 어지럽히지 않는다.
    if (emph[i] > 0.9) {
      const sub = star.authors.length > 0 ? star.authors.join(", ") : star.node.meta;
      if (sub) {
        ctx.font = `500 10px ${palette.fontFamily}`;
        ctx.fillStyle = rgba(color, 0.85);
        ctx.fillText(truncate(sub, 30), x, y + r + 25);
      }
    }
  });

  // 열린 성좌의 위성 라벨 — 충분히 확대됐을 때만, 중심에서 바깥으로 뻗어 겹침을 줄인다.
  if (focused !== null) {
    const star = layout.stars[focused];
    if (star && star.reach * s > SATELLITE_LABEL_MIN_SPAN) {
      const sx = cx + star.x * s;
      const sy = cy + star.y * s;
      ctx.font = `500 10px ${palette.fontFamily}`;
      ctx.textBaseline = "middle";
      for (const sat of star.satellites) {
        const x = cx + sat.x * s;
        const y = cy + sat.y * s;
        if (x < -60 || x > size.w + 60 || y < -40 || y > size.h + 40) continue;
        const dx = x - sx;
        const dy = y - sy;
        const len = Math.hypot(dx, dy) || 1;
        const gap = Math.max(sat.r * s, 2) + 6;
        const lx = x + (dx / len) * gap;
        const ly = y + (dy / len) * gap;
        ctx.textAlign = dx >= 0 ? "left" : "right";
        ctx.fillStyle = rgba(
          palette.fg,
          sat.node.id === hoveredId || sat.node.id === selectedId ? 0.95 : 0.62,
        );
        ctx.fillText(truncate(sat.node.title, 22), lx, ly);
      }
      ctx.textBaseline = "top";
      ctx.textAlign = "center";
    }
  }
}
