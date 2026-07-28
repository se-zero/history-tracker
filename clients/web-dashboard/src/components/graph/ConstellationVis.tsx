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
  /** 별성으로 그릴 노드 id — 서버가 정한 작업 단위 목록. */
  workUnitIds: string[];
  selectedId: string | null;
  onSelect: (node: GraphNode) => void;
  onBackgroundClick: () => void;
  /** 성좌를 열 때 그 작업 단위의 이웃을 채워 달라는 요청 (드릴인 지연 로딩). */
  onExpandWorkUnit?: (nodeId: string) => void;
  /** 드릴인 조회가 진행 중인지 — 열린 성좌 패널에 표시한다. */
  expanding?: boolean;
}

/** 화면에 맞추는 기본 배율에 곱하는 여백 계수. */
const FIT_MARGIN = 0.92;
const MIN_ZOOM = 0.35;
/**
 * 최대 확대는 고정 배율(k)이 아니라 "월드 1단위가 화면 몇 px이 되는가"로 정한다.
 * 기본 배율은 그래프 규모(extent)에 따라 달라지므로 k에 고정 상한을 두면,
 * 성좌를 여는 순간 이미 상한에 닿아 그 안에서 더 확대할 수 없게 된다.
 */
const MAX_SCALE = 28;
/** 성좌를 열면 왼쪽 패널이 화면을 가리므로, 그만큼 중심을 오른쪽으로 민다. */
const PANEL_SHIFT = 150;
/** 카메라 이동 보간 계수 — 프레임마다 목표까지 남은 거리의 이 비율만큼 다가간다. */
const CAMERA_EASE = 0.16;

/** 선택·렌즈가 켜졌을 때, 그 바깥 노드에 남기는 투명도(디자인 시스템 기준 ~20%). */
const MUTED_ALPHA = 0.2;

/**
 * 아무것도 짚지 않았을 때의 기본 밝기.
 *
 * 1이 아닌 이유는 hover가 "밝힐 여지"를 남기기 위해서다. hover가 주변을 어둡게 하면
 * 마우스가 노드를 스칠 때마다 화면 전체가 켜졌다 꺼져 어지럽다. 그래서 어둡게 하는 건
 * 선택·렌즈처럼 의도한 행위에만 두고, hover는 이 값에서 1로 올리는 것만 한다 —
 * 배경 밝기가 고정이라 마우스를 움직여도 필드 전체가 출렁이지 않는다.
 */
const REST_ALPHA = 0.8;

/**
 * 라벨은 노드가 화면에서 이 반지름(px)보다 커졌을 때부터 나타난다.
 * 노드 크기는 별성이 위성 수에, 위성이 타입에 따라 다르므로 큰 것부터 차례로 켜진다 —
 * 배율 구간을 따로 나누지 않아도 확대에 따라 라벨이 순차적으로 드러난다.
 */
const STAR_LABEL_MIN_R = 6;
const SATELLITE_LABEL_MIN_R = 4;

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

/** 화면에 자리를 가진 노드 — 좌표 조회와 엣지 그리기에 쓴다. */
interface Placed {
  node: GraphNode;
  x: number;
  y: number;
  r: number;
  isStar: boolean;
  /** 소속 성좌 인덱스. 먼지는 null. */
  starIndex: number | null;
}

interface Palette {
  byType: Record<GraphNodeType, Rgb>;
  /** 그래프가 앉는 "우물" — UI 사다리보다 한 칸 낮은 전용 층. */
  canvas: Rgb;
  fg: Rgb;
  muted: Rgb;
  /** 엣지 전용 색 — border와 네임스페이스가 갈라진다. */
  edge: Rgb;
  /** "라이브 / 실행 / 선택"을 뜻하는 유일한 강조색. */
  accent: Rgb;
  /**
   * 배경이 어두운지. 색이 아니라 "방향"이 갈리는 표현들이 있어서 필요하다 —
   * 테두리를 밝혀야 또렷한지 어둡게 해야 또렷한지, 별먼지가 성립하는지 등.
   */
  isDark: boolean;
  fontFamily: string;
}

interface BgStar {
  x: number;
  y: number;
  r: number;
  a: number;
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
    r: 0.4 + rand() * 0.9,
    a: 0.06 + rand() * 0.14,
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

/** 반지름 radius의 원이 화면에 조금이라도 걸치는지 (확대 시 그리기 비용을 줄인다). */
function inView(x: number, y: number, radius: number, size: Size): boolean {
  return (
    x + radius >= 0 && x - radius <= size.w && y + radius >= 0 && y - radius <= size.h
  );
}

export function ConstellationVis({
  nodes,
  edges,
  workUnitIds,
  selectedId,
  onSelect,
  onBackgroundClick,
  onExpandWorkUnit,
  expanding = false,
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
  /**
   * 액터 렌즈 — 고른 사람이 관여한 노드만 밝히는 필터. 액터는 노드로 그리지 않는다.
   *
   * 다중 선택인 이유: 액터가 소스별로 쪼개져 있어(같은 사람의 GitHub·Jira·Slack 활동이
   * 서로 다른 Actor 노드) 하나만 고르면 그 사람의 절반만 보인다. 동일인 병합 전까지
   * 조각을 합쳐 볼 수 있어야 하고, 여러 사람의 작업 범위를 겹쳐 보는 데도 쓸 수 있다.
   */
  const [lensActorIds, setLensActorIds] = useState<string[]>([]);
  // 열린 성좌의 도달 반경 — 드릴인으로 위성이 늘어 커졌는지 판단해 카메라를 다시 맞춘다.
  const focusedReachRef = useRef<number | null>(null);

  useEffect(() => {
    selectedRef.current = selectedId;
  }, [selectedId]);
  useEffect(() => {
    focusedRef.current = focused;
  }, [focused]);

  // 직전 배치의 별성 좌표. 드릴인으로 위성이 늘어도 은하가 재배치되지 않도록 고정에 쓴다.
  const starPosRef = useRef<Map<string, { x: number; y: number }>>(new Map());
  const layout = useMemo(
    () => buildConstellations(nodes, edges, workUnitIds, starPosRef.current),
    [nodes, edges, workUnitIds],
  );
  useEffect(() => {
    const positions = new Map<string, { x: number; y: number }>();
    for (const star of layout.stars) positions.set(star.node.id, { x: star.x, y: star.y });
    starPosRef.current = positions;
  }, [layout]);
  const starfield = useMemo(() => makeStarfield(140), []);

  // 확대 상한은 그래프 규모·뷰포트에 따라 달라진다. 휠 핸들러가 [] deps라 ref로 전달한다.
  const maxZoomRef = useRef(MAX_SCALE);
  useEffect(() => {
    maxZoomRef.current = Math.max(2, MAX_SCALE / baseScale(layout, size));
  }, [layout, size]);

  /** 노드 id → 화면상의 자리. 강조·엣지 그리기에서 좌표를 찾는 데 쓴다. */
  const placed = useMemo(() => {
    const map = new Map<string, Placed>();
    layout.stars.forEach((star, i) => {
      map.set(star.node.id, {
        node: star.node, x: star.x, y: star.y, r: star.r, isStar: true, starIndex: i,
      });
      star.satellites.forEach((sat) => {
        map.set(sat.node.id, {
          node: sat.node, x: sat.x, y: sat.y, r: sat.r, isStar: false, starIndex: i,
        });
      });
    });
    layout.dust.forEach((d) => {
      map.set(d.node.id, {
        node: d.node, x: d.x, y: d.y, r: d.r, isStar: false, starIndex: null,
      });
    });
    return map;
  }, [layout]);

  /**
   * 실제 그래프 인접 관계. 성좌 배치가 만드는 스포크·다리와 달리 원본 엣지 그대로다 —
   * 선택한 노드의 "진짜 이웃"을 강조하고 그 연결선을 그리는 데 쓴다.
   */
  const adjacency = useMemo(() => {
    const map = new Map<string, string[]>();
    const push = (a: string, b: string) => {
      const list = map.get(a);
      if (list) list.push(b);
      else map.set(a, [b]);
    };
    for (const [a, b] of edges) {
      push(a, b);
      push(b, a);
    }
    return map;
  }, [edges]);

  /** 별성 id → 그 성좌에 속한 위성 id들. 별성을 고를 때 성좌 전체를 살리는 데 쓴다. */
  const constellationMembers = useMemo(() => {
    const map = new Map<string, string[]>();
    for (const star of layout.stars) {
      map.set(star.node.id, star.satellites.map((sat) => sat.node.id));
    }
    return map;
  }, [layout]);

  /**
   * 열린 성좌 안의 실제 그래프 엣지.
   *
   * 별성→위성 스포크는 배치가 만든 소속 표시일 뿐 원본 관계가 아니다. 스키마상 PR에서
   * 나가는 관계는 CONTAINS(→커밋) 하나뿐이고 파일·티켓·대화는 커밋을 거치므로,
   * 스포크만 그리면 "왜 바뀌었나"의 사슬(커밋→파일, 커밋→티켓, 티켓→대화)이 보이지 않는다.
   */
  const focusedEdges = useMemo(() => {
    if (focused === null) return [];
    const star = layout.stars[focused];
    if (!star) return [];

    const members = new Set<string>([star.node.id]);
    for (const sat of star.satellites) members.add(sat.node.id);

    const pairs: Array<[Placed, Placed]> = [];
    const seen = new Set<string>();
    for (const id of members) {
      const from = placed.get(id);
      if (!from) continue;
      for (const nb of adjacency.get(id) ?? []) {
        if (!members.has(nb)) continue;
        // 무향으로 다루므로 같은 쌍을 양쪽에서 두 번 그리지 않게 정렬한 키로 거른다.
        const key = id < nb ? `${id} ${nb}` : `${nb} ${id}`;
        if (seen.has(key)) continue;
        seen.add(key);
        const to = placed.get(nb);
        if (to) pairs.push([from, to]);
      }
    }
    return pairs;
  }, [focused, layout, adjacency, placed]);

  // 렌더 루프를 재시작시키지 않으려고 ref로 넘긴다(재시작하면 캔버스가 리사이즈되며 깜빡인다).
  const focusedEdgesRef = useRef<Array<[Placed, Placed]>>([]);
  useEffect(() => {
    focusedEdgesRef.current = focusedEdges;
  }, [focusedEdges]);

  /**
   * 액터 렌즈 후보 — 배치된 노드에 실제로 관여한 사람만.
   *
   * 액터는 연결 수가 50~133으로 다른 노드(중앙값 3)의 수십 배라 배치에 넣으면 은하가
   * 붕괴한다. 그래서 노드로 그리지 않고, 대신 "이 사람이 무엇을 했나"를 강조로 답한다.
   * 기여 수를 함께 보여주는 건 동일인이 여러 액터로 남아 있을 때(병합 전) 구분하기 위함이다.
   */
  const lensActors = useMemo(() => {
    return nodes
      .filter((n) => n.type === "actor")
      .map((node) => ({
        node,
        count: (adjacency.get(node.id) ?? []).filter((id) => placed.has(id)).length,
      }))
      .filter((a) => a.count > 0)
      .sort((a, b) => b.count - a.count);
  }, [nodes, adjacency, placed]);

  /** 렌즈가 켜졌을 때 밝힐 노드 집합(고른 사람들의 합집합). 액터 자신은 배치되지 않아 제외된다. */
  const lensSignature = lensActorIds.join("|");
  const lensHighlight = useMemo(() => {
    if (lensActorIds.length === 0) return null;
    const set = new Set<string>();
    for (const actorId of lensActorIds) {
      for (const nb of adjacency.get(actorId) ?? []) {
        if (placed.has(nb)) set.add(nb);
      }
    }
    // 파일은 한 홉으로 절대 걸리지 않는다 — 스키마에 (Actor)→(File) 관계가 없기 때문이다.
    // 전체 노드의 절반이 파일이라, 그대로 두면 렌즈를 켜도 화면 대부분이 꺼진 채 남는다.
    // File은 (ChangeSet)-[MODIFIED]->(File)로만 이어지므로 "이 사람이 만진 노드에 인접한
    // 파일" = "이 사람이 바꾼 파일"이다. 그래서 파일에 한해 한 홉만 더 넓힌다.
    for (const id of [...set]) {
      for (const nb of adjacency.get(id) ?? []) {
        if (placed.get(nb)?.node.type === "code") set.add(nb);
      }
    }
    return set.size > 0 ? set : null;
    // lensActorIds는 매번 새 배열이라 문자열 시그니처로 대체한다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lensSignature, adjacency, placed]);

  // focusedEdges와 같은 이유로 ref 경유 — deps에 넣으면 렌더 루프가 재시작하며 깜빡인다.
  const lensHighlightRef = useRef<Set<string> | null>(null);
  useEffect(() => {
    lensHighlightRef.current = lensHighlight;
  }, [lensHighlight]);

  // 그래프가 바뀌면 이전 프로젝트의 액터를 가리키는 렌즈는 의미가 없다.
  useEffect(() => {
    setLensActorIds([]);
  }, [nodes]);

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
      canvas: resolveVarRgb("--surface-canvas", theme),
      fg: resolveVarRgb("--fg", theme),
      muted: resolveVarRgb("--fg-muted", theme),
      edge: resolveVarRgb("--graph-edge", theme),
      accent: resolveVarRgb("--accent-ink", theme),
      isDark: theme === "dark",
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

  // 그래프가 바뀌면 열려 있던 포커스는 의미가 없다.
  // 판정 기준을 layout이 아니라 작업 단위 목록으로 둔다 — 드릴인으로 위성만 늘어난 경우엔
  // layout 객체가 새로 만들어져도 보고 있던 성좌를 닫으면 안 된다.
  const workSignature = workUnitIds.join("|");
  useEffect(() => {
    setFocused(null);
    targetRef.current = null;
    viewRef.current = { k: 1, tx: 0, ty: 0 };
  }, [workSignature]);

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
      targetRef.current = { k, tx: PANEL_SHIFT - star.x * s, ty: -star.y * s };
      setFocused(index);
      // 위성이 아직 없는 성좌(최신 창 밖의 오래된 작업)를 채워 달라고 알린다.
      onExpandWorkUnit?.(star.node.id);
    },
    [layout, size, onExpandWorkUnit],
  );

  const exitFocus = useCallback(() => {
    setFocused(null);
    focusedReachRef.current = null;
    targetRef.current = { k: 1, tx: 0, ty: 0 };
  }, []);

  // 드릴인으로 위성이 채워지면 성좌가 커진다 — 새 위성이 화면 밖에 놓이지 않게 다시 맞춘다.
  useEffect(() => {
    if (focused === null) return;
    const star = layout.stars[focused];
    if (!star) return;
    const prev = focusedReachRef.current;
    focusedReachRef.current = star.reach;
    if (prev !== null && Math.abs(star.reach - prev) > 1) focusOn(focused);
  }, [layout, focused, focusOn]);

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
    const loop = () => {
      stepCamera();
      const focusedIndex = focusedRef.current;
      const hoveredId = hitRef.current?.node.id ?? null;
      const selected = selectedRef.current;
      // 엣지·라벨은 지금 주목 중인 대상을 따른다(hover가 있으면 hover).
      const activeId = hoveredId ?? selected;
      drawScene(ctx, {
        dpr,
        size,
        view: viewRef.current,
        layout,
        palette,
        starfield,
        placed,
        adjacency,
        activeId,
        // 선택이 렌즈보다 우선 — 렌즈는 아무것도 안 짚었을 때의 바탕 상태다.
        highlight:
          buildHighlight(selected, adjacency, placed, constellationMembers) ??
          lensHighlightRef.current,
        hoverLift: buildHighlight(hoveredId, adjacency, placed, constellationMembers),
        focusedEdges: focusedEdgesRef.current,
        focused: focusedIndex,
        related: focusedIndex === null ? null : (starNeighbors.get(focusedIndex) ?? new Set()),
        selectedId: selectedRef.current,
      });
      raf = requestAnimationFrame(loop);
    };
    raf = requestAnimationFrame(loop);
    return () => cancelAnimationFrame(raf);
  }, [layout, palette, size, starfield, starNeighbors, placed, adjacency, constellationMembers]);

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
    // 커서용 state는 대상이 실제로 바뀔 때만 갱신한다(매 프레임 리렌더 방지).
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
          loading={expanding}
          onSelectNode={onSelect}
          onJump={(index) => {
            focusOn(index);
            onSelect(layout.stars[index].node);
          }}
          onClose={exitFocus}
        />
      ) : (
        <div className="galaxy-legend">
          {lensActors.length > 0 && (
            <div className="galaxy-lens">
              <div className="galaxy-legend-hint">
                사람별로 보기{lensActorIds.length > 0 ? ` · ${lensActorIds.length}명` : ""}
              </div>
              {lensActors.map(({ node, count }) => (
                <button
                  key={node.id}
                  className={
                    "galaxy-lens-chip" +
                    (lensActorIds.includes(node.id) ? " active" : "")
                  }
                  onClick={() =>
                    setLensActorIds((cur) =>
                      cur.includes(node.id)
                        ? cur.filter((id) => id !== node.id)
                        : [...cur, node.id],
                    )
                  }
                  title={`${node.title} — 관여한 노드 ${count}개 (여러 명 함께 선택 가능)`}
                >
                  <span className="galaxy-lens-name">{node.title}</span>
                  <span className="galaxy-lens-count">{count}</span>
                </button>
              ))}
            </div>
          )}
          <div className="galaxy-legend-hint">노드를 클릭하면 이웃이 강조됩니다</div>
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

/**
 * 강조 집합 = 대상 노드 + 실제 엣지로 이어진 이웃.
 * Actor처럼 배치에서 빠진 노드는 그릴 자리가 없으므로 제외한다.
 *
 * 별성(작업 단위)을 고르면 그 성좌 전체를 함께 살린다. 작업 단위는 커밋만 직접 이웃이고
 * 파일·티켓·대화는 2~3홉이라, 1홉만 살리면 방금 연 성좌의 대부분이 눌려서 안 보인다.
 */
function buildHighlight(
  activeId: string | null,
  adjacency: Map<string, string[]>,
  placed: Map<string, Placed>,
  members: Map<string, string[]>,
): Set<string> | null {
  if (!activeId) return null;
  const node = placed.get(activeId);
  if (!node) return null;

  const set = new Set<string>([activeId]);
  for (const nb of adjacency.get(activeId) ?? []) {
    if (placed.has(nb)) set.add(nb);
  }
  if (node.isStar) {
    for (const id of members.get(activeId) ?? []) set.add(id);
  }
  return set;
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
  placed: Map<string, Placed>;
  adjacency: Map<string, string[]>;
  activeId: string | null;
  /** 나머지를 눌러 대비를 만드는 강조 — 선택·렌즈처럼 의도한 행위에서만 온다. */
  highlight: Set<string> | null;
  /** 밝히기만 하는 강조 — hover. 주변 밝기를 건드리지 않아 마우스를 움직여도 출렁이지 않는다. */
  hoverLift: Set<string> | null;
  /** 열린 성좌 내부의 실제 엣지 (월드 좌표 노드 쌍). */
  focusedEdges: Array<[Placed, Placed]>;
  focused: number | null;
  related: Set<number> | null;
  selectedId: string | null;
}

/**
 * 성좌별 기본 밝기(0~1). 성좌를 열었을 때만 주변을 눌러 주고,
 * 그 외에는 전부 같은 밝기다 — 노드 단위 강조가 흐려지지 않게.
 */
function computeEmphasis(p: SceneParams): number[] {
  const n = p.layout.stars.length;
  const emph = new Array<number>(n).fill(1);
  if (p.focused !== null) {
    for (let i = 0; i < n; i++) {
      emph[i] = i === p.focused ? 1 : p.related?.has(i) ? 0.55 : 0.16;
    }
  }
  return emph;
}

/**
 * 노드 하나의 최종 투명도.
 *
 * 강조는 두 갈래다. 선택·렌즈는 의도한 행위라 나머지를 눌러 대비를 만들고,
 * hover는 스쳐 지나가는 것이라 밝히기만 한다(주변을 건드리지 않는다).
 */
function nodeAlpha(p: SceneParams, node: Placed, emph: number[]): number {
  let alpha =
    node.starIndex === null
      ? p.focused === null
        ? 0.5
        : 0.14
      : emph[node.starIndex] * REST_ALPHA;

  if (p.highlight) {
    alpha = p.highlight.has(node.node.id) ? 1 : Math.min(alpha, MUTED_ALPHA);
  }
  if (p.hoverLift?.has(node.node.id)) alpha = 1;
  return alpha;
}

function drawScene(ctx: CanvasRenderingContext2D, p: SceneParams): void {
  const { dpr, size, layout, view } = p;
  const { s, cx, cy } = transform(layout, size, view);
  const emph = computeEmphasis(p);

  ctx.save();
  ctx.scale(dpr, dpr);

  drawBackground(ctx, p);
  drawBridges(ctx, p, emph, s, cx, cy);
  drawSpokes(ctx, p, emph, s, cx, cy);
  drawConstellationEdges(ctx, p, s, cx, cy);
  drawHighlightEdges(ctx, p, s, cx, cy);
  drawNodes(ctx, p, emph, s, cx, cy);
  drawLabels(ctx, p, emph, s, cx, cy);

  ctx.restore();
}

function drawBackground(ctx: CanvasRenderingContext2D, p: SceneParams): void {
  const { size, palette, starfield, view } = p;

  ctx.fillStyle = rgba(palette.canvas, 1);
  ctx.fillRect(0, 0, size.w, size.h);

  // 별먼지는 밤하늘의 장치라 밝은 배경에선 성립하지 않는다 — 어두운 점 140개가
  // 질감이 아니라 얼룩으로 보인다. 라이트에서 그래프는 "잉크로 찍힌 도식"이므로
  // 배경은 비워 둔다.
  if (!palette.isDark) return;

  const ox = view.tx * 0.04;
  const oy = view.ty * 0.04;
  for (const st of starfield) {
    const x = (((st.x * size.w + ox) % size.w) + size.w) % size.w;
    const y = (((st.y * size.h + oy) % size.h) + size.h) % size.h;
    ctx.fillStyle = rgba(palette.fg, st.a);
    ctx.beginPath();
    ctx.arc(x, y, st.r, 0, Math.PI * 2);
    ctx.fill();
  }
}

/** 성좌 사이의 다리 — 같은 파일·티켓을 공유한 작업들을 잇는다. */
function drawBridges(
  ctx: CanvasRenderingContext2D,
  p: SceneParams,
  emph: number[],
  s: number,
  cx: number,
  cy: number,
): void {
  const { layout, palette, focused, highlight } = p;

  for (const bridge of layout.bridges) {
    const a = layout.stars[bridge.a];
    const b = layout.stars[bridge.b];
    if (!a || !b) continue;

    const isFocusLink = focused !== null && (bridge.a === focused || bridge.b === focused);
    let strength = isFocusLink ? 1 : Math.min(emph[bridge.a], emph[bridge.b]);
    // 노드 강조 중에는 구조선을 확실히 눌러 강조 대상이 묻히지 않게 한다.
    if (highlight) strength = Math.min(strength, 0.25);

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

    ctx.strokeStyle = rgba(palette.edge, 0.5 * strength);
    ctx.lineWidth = Math.min(1.8, 0.5 + bridge.weight * 0.16);
    ctx.beginPath();
    ctx.moveTo(ax, ay);
    ctx.quadraticCurveTo(mx + (-dy / len) * bow, my + (dx / len) * bow, bx, by);
    ctx.stroke();
  }
}

/** 별성 → 위성 연결선. 성좌의 소속감만 만드는 얇은 구조선이다. */
function drawSpokes(
  ctx: CanvasRenderingContext2D,
  p: SceneParams,
  emph: number[],
  s: number,
  cx: number,
  cy: number,
): void {
  const { layout, palette, size, highlight } = p;
  layout.stars.forEach((star, i) => {
    // 열린 성좌는 방사형 소속선 대신 실제 엣지를 그린다 — drawConstellationEdges가 맡는다.
    if (p.focused === i) return;
    let strength = emph[i];
    // 강조 중이면 "다른" 성좌의 구조선만 눌러 둔다.
    // 일괄로 누르면 방금 연 성좌의 위성 연결까지 사라져, 커밋만 이어진 것처럼 보인다.
    if (highlight && !highlight.has(star.node.id)) strength = Math.min(strength, 0.15);
    if (strength < 0.05) return;
    const x = cx + star.x * s;
    const y = cy + star.y * s;
    if (!inView(x, y, star.reach * s, size)) return;
    ctx.strokeStyle = rgba(palette.edge, 0.45 * strength);
    ctx.lineWidth = 0.6;
    ctx.beginPath();
    for (const sat of star.satellites) {
      ctx.moveTo(x, y);
      ctx.lineTo(cx + sat.x * s, cy + sat.y * s);
    }
    ctx.stroke();
  });
}

/**
 * 열린 성좌 안의 실제 관계를 그린다 — 커밋→파일, 커밋→티켓, 티켓→대화.
 * 별성에서 뻗는 방사선만 있으면 "PR은 커밋에만 이어진다"로만 읽히고,
 * 나머지 위성이 왜 이 작업에 속하는지가 화면에서 사라진다.
 */
function drawConstellationEdges(
  ctx: CanvasRenderingContext2D,
  p: SceneParams,
  s: number,
  cx: number,
  cy: number,
): void {
  const { focusedEdges, palette, size } = p;
  if (focusedEdges.length === 0) return;

  // 밝은 배경에선 같은 알파라도 선이 훨씬 진하게 읽혀 도식이 지저분해진다.
  ctx.strokeStyle = rgba(palette.fg, palette.isDark ? 0.3 : 0.2);
  ctx.lineWidth = 0.8;
  ctx.beginPath();
  for (const [from, to] of focusedEdges) {
    const fx = cx + from.x * s;
    const fy = cy + from.y * s;
    const tx = cx + to.x * s;
    const ty = cy + to.y * s;
    // 양 끝이 모두 화면 밖이면 선분도 대체로 밖이다 — 확대 시 그리기 비용을 줄인다.
    if (!inView(fx, fy, 0, size) && !inView(tx, ty, 0, size)) continue;
    ctx.moveTo(fx, fy);
    ctx.lineTo(tx, ty);
  }
  ctx.stroke();
}

/**
 * 선택(또는 hover)한 노드의 실제 그래프 엣지.
 * 성좌 스포크는 배치가 만든 선이라 원본 관계와 다르다 — 여기서만 진짜 관계를 그린다.
 */
function drawHighlightEdges(
  ctx: CanvasRenderingContext2D,
  p: SceneParams,
  s: number,
  cx: number,
  cy: number,
): void {
  const { activeId, adjacency, placed, palette } = p;
  if (!activeId) return;
  const from = placed.get(activeId);
  if (!from) return;

  const fx = cx + from.x * s;
  const fy = cy + from.y * s;
  ctx.strokeStyle = rgba(palette.fg, palette.isDark ? 0.5 : 0.38);
  ctx.lineWidth = 1.2;
  ctx.beginPath();
  for (const nbId of adjacency.get(activeId) ?? []) {
    const to = placed.get(nbId);
    if (!to) continue;
    ctx.moveTo(fx, fy);
    ctx.lineTo(cx + to.x * s, cy + to.y * s);
  }
  ctx.stroke();
}

/**
 * 노드 = 채운 원 + 얇은 테두리. 글로우(가산 합성·방사 그라디언트)는 쓰지 않는다.
 * 강조는 밝기와 테두리 굵기로만 표현해, 강조 대상이 어디인지 한눈에 들어오게 한다.
 */
function drawNodes(
  ctx: CanvasRenderingContext2D,
  p: SceneParams,
  emph: number[],
  s: number,
  cx: number,
  cy: number,
): void {
  const { layout, palette, size, highlight, activeId, selectedId } = p;

  const paint = (node: Placed) => {
    const alpha = nodeAlpha(p, node, emph);
    if (alpha <= 0.02) return;

    const x = cx + node.x * s;
    const y = cy + node.y * s;
    const isActive = node.node.id === activeId;
    const isNeighbor = !isActive && !!highlight && highlight.has(node.node.id);
    // hover로 밝아진 이웃 — 크기는 건드리지 않는다. 마우스가 스칠 때마다 수십 개가
    // 커졌다 작아지면 밝기만 바뀌는 것보다 훨씬 어지럽다.
    const isLifted = !isActive && !isNeighbor && !!p.hoverLift?.has(node.node.id);
    const minR = node.isStar ? 5 : 1.6;
    let r = Math.max(node.r * s, minR);
    if (isActive) r *= 1.5;
    else if (isNeighbor) r *= 1.15;

    if (!inView(x, y, r + 14, size)) return;

    const color = palette.byType[node.node.type];
    ctx.fillStyle = rgba(color, alpha);
    ctx.beginPath();
    ctx.arc(x, y, r, 0, Math.PI * 2);
    ctx.fill();

    // 얇은 테두리 — 경계를 또렷하게 만든다. 배경에 따라 방향이 뒤집힌다.
    // 다크는 채움색을 밝혀서, 라이트는 잉크색으로 찍어서 경계를 만든다
    // (밝은 배경에서 밝힌 테두리는 배경에 묻힌다).
    ctx.strokeStyle = palette.isDark
      ? rgba(shade(color, 1.35), Math.min(1, alpha * 1.1))
      : rgba(palette.fg, alpha);
    ctx.lineWidth = isActive ? 2 : isNeighbor ? 1.4 : isLifted ? 1.3 : 1;
    ctx.stroke();

    // 선택 표시는 앰버 링 + 헤일로다. 앰버는 이 시스템에서 "라이브 / 실행 / 선택"만
    // 뜻하므로, 선택은 노드 색이 아니라 반드시 이 색으로 말해야 한다.
    // 글로우도 여기서만 쓴다 — 모든 노드가 빛나면 신호가 아니라 배경이 된다.
    if (node.node.id === selectedId) {
      // 라이트에선 발광이 성립하지 않는다 — 헤일로를 소프트 링 수준으로만 남긴다.
      const halo = ctx.createRadialGradient(x, y, r, x, y, r + 14);
      halo.addColorStop(0, rgba(palette.accent, palette.isDark ? 0.32 : 0.16));
      halo.addColorStop(1, rgba(palette.accent, 0));
      ctx.fillStyle = halo;
      ctx.beginPath();
      ctx.arc(x, y, r + 14, 0, Math.PI * 2);
      ctx.fill();

      ctx.strokeStyle = rgba(palette.accent, 0.95);
      ctx.lineWidth = 1.5;
      ctx.beginPath();
      ctx.arc(x, y, r + 5, 0, Math.PI * 2);
      ctx.stroke();
    }
  };

  // 위성 → 먼지 → 별성 순으로 그려 큰 노드가 위에 오게 한다.
  layout.stars.forEach((star) => {
    star.satellites.forEach((sat) => {
      const node = p.placed.get(sat.node.id);
      if (node) paint(node);
    });
  });
  layout.dust.forEach((d) => {
    const node = p.placed.get(d.node.id);
    if (node) paint(node);
  });
  layout.stars.forEach((star) => {
    const node = p.placed.get(star.node.id);
    if (node) paint(node);
  });

  // 열린 성좌의 궤도 경계 — 점선 링 하나로만 표시한다.
  if (p.focused !== null) {
    const star = layout.stars[p.focused];
    if (star) {
      const x = cx + star.x * s;
      const y = cy + star.y * s;
      if (inView(x, y, star.reach * s, size)) {
        ctx.strokeStyle = rgba(palette.edge, 0.6);
        ctx.lineWidth = 1;
        ctx.setLineDash([4, 6]);
        ctx.beginPath();
        ctx.arc(x, y, star.reach * s, 0, Math.PI * 2);
        ctx.stroke();
        ctx.setLineDash([]);
      }
    }
  }
}

/** 라벨 하나를 그리는 데 필요한 정보. */
interface LabelCandidate {
  id: string;
  x: number;
  y: number;
  title: string;
  isStar: boolean;
  /** 화면상의 노드 반지름 — 노출 판정 기준이자 라벨을 밀어낼 거리다. */
  r: number;
  /** 위성은 성좌 중심에서 바깥으로 뻗는다 — 그 방향. */
  dx: number;
  dy: number;
  alpha: number;
  /** 별성의 부제(작성자). 위성은 없다. */
  sub: string | null;
  subColor: Rgb;
}

/**
 * 라벨은 화면 좌표에 고정 크기로 그린다 — 줌을 해도 글자 크기가 변하지 않아
 * 어느 배율에서든 읽힌다.
 *
 * 표시 규칙은 배율 하나로 정한다 — 노드가 화면에서 일정 크기보다 커져야 라벨이 붙는다.
 * 노드 크기가 제각각이라(별성은 위성 수, 위성은 타입에 따라) 확대할수록 큰 것부터
 * 차례로 켜지고, 전체 뷰에서는 아무 라벨도 뜨지 않는다.
 * hover·선택한 노드만 배율과 무관하게 항상 보인다.
 */
function drawLabels(
  ctx: CanvasRenderingContext2D,
  p: SceneParams,
  emph: number[],
  s: number,
  cx: number,
  cy: number,
): void {
  const { layout, palette, size, placed, activeId, selectedId } = p;

  const onScreen = (x: number, y: number) =>
    x >= -30 && x <= size.w + 30 && y >= -30 && y <= size.h + 30;

  const candidates: LabelCandidate[] = [];

  layout.stars.forEach((star) => {
    const x = cx + star.x * s;
    const y = cy + star.y * s;
    if (!onScreen(x, y)) return;
    const node = placed.get(star.node.id);
    candidates.push({
      id: star.node.id,
      x,
      y,
      title: star.node.title,
      isStar: true,
      // 최소 크기 보정 없이 실제 배율 크기로 판정해야 확대에 비례해 차례로 켜진다.
      r: star.r * s,
      dx: 0,
      dy: 0,
      alpha: node ? nodeAlpha(p, node, emph) : 1,
      sub: star.authors.length > 0 ? star.authors.join(", ") : star.node.meta,
      subColor: palette.byType[star.node.type],
    });
  });

  // 위성도 모든 성좌에서 후보가 된다 — 노출을 배율만으로 정하므로
  // 성좌를 열지 않고 확대해 들어가도 라벨이 나타난다.
  layout.stars.forEach((star) => {
    const sx = cx + star.x * s;
    const sy = cy + star.y * s;
    for (const sat of star.satellites) {
      const x = cx + sat.x * s;
      const y = cy + sat.y * s;
      if (!onScreen(x, y)) continue;
      const node = placed.get(sat.node.id);
      candidates.push({
        id: sat.node.id,
        x,
        y,
        title: sat.node.title,
        isStar: false,
        r: sat.r * s,
        dx: x - sx,
        dy: y - sy,
        alpha: node ? nodeAlpha(p, node, emph) : 1,
        sub: null,
        subColor: palette.byType[sat.node.type],
      });
    }
  });

  for (const c of candidates) {
    const active = c.id === activeId || c.id === selectedId;
    // 배율 게이트 — 노드가 충분히 커지기 전엔 라벨을 달지 않는다.
    if (!active && c.r < (c.isStar ? STAR_LABEL_MIN_R : SATELLITE_LABEL_MIN_R)) continue;
    // 강조 대상 바깥으로 밀려난 노드의 라벨은 읽을 필요가 없다.
    if (!active && c.alpha <= MUTED_ALPHA) continue;

    const title = truncate(c.title, c.isStar ? 26 : 22);

    if (c.isStar) {
      const drawR = Math.max(c.r, 5);
      ctx.textAlign = "center";
      ctx.textBaseline = "top";
      ctx.font = `600 12px ${palette.fontFamily}`;
      ctx.fillStyle = rgba(palette.fg, 0.94 * (active ? 1 : c.alpha));
      ctx.fillText(title, c.x, c.y + drawR + 9);
      // 부제는 주목 중인 성좌에만 — 평소엔 화면을 어지럽히지 않는다.
      if (c.sub && (active || c.alpha > 0.9)) {
        ctx.font = `500 10px ${palette.fontFamily}`;
        ctx.fillStyle = rgba(c.subColor, 0.85);
        ctx.fillText(truncate(c.sub, 30), c.x, c.y + drawR + 25);
      }
    } else {
      const len = Math.hypot(c.dx, c.dy) || 1;
      const gap = Math.max(c.r, 2) + 6;
      ctx.textBaseline = "middle";
      ctx.textAlign = c.dx >= 0 ? "left" : "right";
      ctx.font = `500 10px ${palette.fontFamily}`;
      ctx.fillStyle = rgba(palette.fg, (active ? 0.95 : 0.62) * (active ? 1 : c.alpha));
      ctx.fillText(title, c.x + (c.dx / len) * gap, c.y + (c.dy / len) * gap);
    }
  }

  ctx.textAlign = "center";
  ctx.textBaseline = "top";
}
