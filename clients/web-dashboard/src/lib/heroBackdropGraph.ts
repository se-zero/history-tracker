// 히어로 배경의 장식용 그래프 일러스트 데이터를 생성한다.
// 결정론적 PRNG(mulberry32)로 클러스터 중심 주변에 가우시안 분포로 노드를 뭉치고,
// 클러스터 내부는 촘촘하게(k-최근접 이웃), 클러스터 사이는 성긴 공유 연결 몇 개로만 이어
// 실제 지식 그래프처럼 밀도가 고르지 않은 배경을 만든다(균일 분포는 "점 그리드"로 보인다).
// 그 위에 가장 조밀한 코어 클러스터 "안"만 도는 4노드/3엣지 트레이스를 골라 앰버로
// 덮어써 시그니처 순간(질문 → 코어를 가로지르는 근거 트레이스)을 재연한다. 시작점에서
// 먼 이웃을 우선 선택해 중심에 뭉치지 않고 코어 전체로 퍼진 경로가 되도록 한다.
//
// ── 풀블리드 리워크(히어로 재설계) ─────────────────────────────
// viewBox를 히어로 전체 비율(1440×820)로 잡고 그래프가 히어로 배경 레이어 전체에
// 흐르게 한다. 텍스트 가독성은 CSS 스크림이 아니라 여기 "존 기반 감쇠"가 담당한다 —
// 텍스트 존(x<740, y<560) 안의 노드는 opacity를 ×0.25~0.35로 감쇠하고, 그 존에 걸친
// 엣지도 양 끝 노드 감쇠 계수의 min으로 같이 감쇠한다. 클러스터는 텍스트 존 밖
// (우측 ⅔ + 좌하단)에만 두고, 존 안에는 저투명 아웃라이어만 흩뿌린다.

export type HeroNodeType = "commit" | "pr" | "issue" | "slack" | "jira" | "person" | "file";

export interface HeroNode {
  id: string;
  x: number;
  y: number;
  r: number;
  type: HeroNodeType;
  opacity: number;
}

export interface HeroEdge {
  id: string;
  from: string;
  to: string;
  opacity: number;
}

export interface HeroBackdropGraph {
  width: number;
  height: number;
  nodes: HeroNode[];
  edges: HeroEdge[];
  /** 점등 경로 — 2026-07-25 히어로 2차 재설계 이후 배경 렌더(HeroMedia)에서는 미사용
      (앰버 점등은 제품 UI 슬롯의 "관련 그래프" 패널이 자체적으로 가진다). 공개 인터페이스는
      유지한다. */
  litNodeIds: string[];
  litEdgeIds: string[];
}

const WIDTH = 1440;
const HEIGHT = 820;
const PADDING = 16;
const SEED = 20260419; // 임의 고정 시드 — 바뀌면 배치가 전부 달라지므로 건드리지 않는다.

// 텍스트 존 — 히어로 카피(eyebrow~CTA 안내문)가 앉는 좌상단 영역. 이 존 안의 노드·엣지는
// 감쇠 계수(0.25~0.35)를 곱해 카피 가독성을 지킨다. 존 밖은 계수 1(감쇠 없음).
// x 경계는 렌더 실측으로 확정 — 헤드라인 1행이 화면 x≈873(≈viewBox 800)까지 닿아
// 계획치(740)보다 넓혀야 감쇠 안 된 아웃라이어가 헤드라인 옆에 앉지 않는다.
const TEXT_ZONE_X_MAX = 800;
const TEXT_ZONE_Y_MAX = 560;
const TEXT_ZONE_FACTOR_MIN = 0.25;
const TEXT_ZONE_FACTOR_SPAN = 0.1; // 0.25~0.35

// 클러스터 소속 — HeroNode에는 공개 필드로 노출하지 않고(공개 인터페이스 고정),
// 생성기 내부에서만 Map으로 추적해 엣지·점등 경로 계산에 쓴다.
type ClusterId = "core" | "medA" | "medB" | "medC" | "medD" | "lobeSW" | "outlier";

interface ClusterSpec {
  id: ClusterId;
  cx: number;
  cy: number;
  count: number;
  spread: number;
}

// 클러스터 중심·개수·가우시안 확산도. core가 가장 조밀하고, outlier는 클러스터가 아니라
// 캔버스 전역에 흩뿌리는 외톨이 노드라 이 배열에는 넣지 않는다(아래 OUTLIER_COUNT).
// 배치 원칙: 조밀한 코어는 우중앙, 중밀도 클러스터는 우측 ⅔ 영역에 분산, 좌하단
// (CTA 아래 빈 공간)에 중~저밀도 로브 하나. 텍스트 존(TEXT_ZONE_X/Y_MAX)에는 클러스터를
// 두지 않는다 — 그 존은 저투명 아웃라이어만 지나간다.
// 1440×900 뷰포트에서 slice 크롭이 좌우를 ~70px씩 자르므로 우측 클러스터 중심은
// x≤1300 안쪽에 둔다.
const CLUSTERS: ClusterSpec[] = [
  { id: "core", cx: 975, cy: 350, count: 38, spread: 46 },
  { id: "medA", cx: 1160, cy: 180, count: 20, spread: 50 },
  { id: "medB", cx: 1290, cy: 520, count: 17, spread: 44 },
  { id: "medC", cx: 790, cy: 650, count: 16, spread: 46 },
  { id: "medD", cx: 1080, cy: 700, count: 15, spread: 40 },
  { id: "lobeSW", cx: 300, cy: 690, count: 16, spread: 52 },
];
const OUTLIER_COUNT = 28;
// 아웃라이어는 캔버스 전역에 흩뿌린다 — 텍스트 존 안도 허용(존 감쇠가 저투명을 보장한다).
const OUTLIER_X_MIN = PADDING;
const OUTLIER_X_MAX = WIDTH - PADDING;
const OUTLIER_Y_MIN = PADDING;
const OUTLIER_Y_MAX = HEIGHT - PADDING;

// 클러스터 내부 k-최근접 이웃 연결 개수 — core는 더 촘촘하게 4, 나머지는 2.
const INTRA_K: Record<Exclude<ClusterId, "outlier">, number> = {
  core: 4,
  medA: 2,
  medB: 2,
  medC: 2,
  medD: 2,
  lobeSW: 2,
};

// 클러스터 간 성긴 공유 연결 — 지정된 쌍마다 두 클러스터에서 가장 가까운 노드끼리 딱 1개만 잇는다.
// lobeSW는 core가 아니라 medC와 잇는다 — core와 직결하면 공유 연결이 텍스트 존을 대각선으로
// 가로질러 카피 위를 지나가기 때문이다(medC 경유는 존 아래(y>560)로 돈다).
const SHARED_PAIRS: Array<[ClusterId, ClusterId]> = [
  ["core", "medA"],
  ["core", "medB"],
  ["core", "medC"],
  ["core", "medD"],
  ["medA", "medB"],
  ["medB", "medD"],
  ["medC", "lobeSW"],
];

const OVERLAP_GUARD = 7; // 배치 시 기존 노드와 최소 이 거리(px) 이상 떨어지도록 시도한다 — 코어를 촘촘히 채우려 짧게 잡는다.
const OVERLAP_ATTEMPTS = 6; // 위 조건을 만족하는 후보를 이 횟수만큼만 재시도하고, 실패해도 마지막 후보를 그대로 쓴다.

// mulberry32 — 32비트 정수 시드 기반 결정론적 PRNG. Math.random() 대체.
// 같은 시드는 항상 같은 난수열을 만들어 리렌더/재로드에도 배치가 고정된다.
function mulberry32(seed: number): () => number {
  let a = seed;
  return function next() {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function dist(ax: number, ay: number, bx: number, by: number): number {
  return Math.hypot(ax - bx, ay - by);
}

function clamp(v: number, lo: number, hi: number): number {
  return Math.min(hi, Math.max(lo, v));
}

// Box–Muller — rng() 두 번으로 표준정규분포(평균 0, 표준편차 1) 난수 하나를 만든다.
// 균일분포 대신 이걸 쓰는 이유: 클러스터 중심 근처는 촘촘하고 멀어질수록 성겨야
// "뭉쳐 있는 그래프"로 보이기 때문이다 — 균일분포면 다시 고르게 흩어진 점이 된다.
function gaussian(rng: () => number): number {
  const u1 = Math.max(rng(), 1e-9); // log(0) 방지
  const u2 = rng();
  return Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
}

interface PlacedPoint {
  x: number;
  y: number;
  clusterId: ClusterId;
}

function isTooClose(x: number, y: number, placed: PlacedPoint[]): boolean {
  return placed.some((p) => dist(p.x, p.y, x, y) < OVERLAP_GUARD);
}

// 텍스트 존 감쇠 계수 — 존 안이면 0.25~0.35, 존 밖이면 1.
// rng는 존 안일 때만 소비한다(존 밖 노드가 난수열을 흔들지 않게).
function zoneFactor(x: number, y: number, rng: () => number): number {
  if (x < TEXT_ZONE_X_MAX && y < TEXT_ZONE_Y_MAX) {
    return TEXT_ZONE_FACTOR_MIN + rng() * TEXT_ZONE_FACTOR_SPAN;
  }
  return 1;
}

// 클러스터 노드 배치 — 중심에서 가우시안 오프셋만큼 떨어뜨리고 캔버스 안쪽으로 clamp한다.
// 거리 분산을 일부러 크게 허용하므로(균일 간격을 강제하지 않음) 겹침 방지는 가볍게
// 몇 번만 재시도하고, 그래도 실패하면 마지막 후보를 그냥 받아들인다 — 분산 자체가 목적이다.
function placeClusterPoint(
  cluster: ClusterSpec,
  rng: () => number,
  placed: PlacedPoint[],
): { x: number; y: number } {
  let x = cluster.cx;
  let y = cluster.cy;
  for (let attempt = 0; attempt < OVERLAP_ATTEMPTS; attempt++) {
    x = clamp(cluster.cx + gaussian(rng) * cluster.spread, PADDING, WIDTH - PADDING);
    y = clamp(cluster.cy + gaussian(rng) * cluster.spread, PADDING, HEIGHT - PADDING);
    if (!isTooClose(x, y, placed)) return { x, y };
  }
  return { x, y };
}

// 아웃라이어 배치 — 클러스터 중심 없이 캔버스 전역에 고르게 흩뿌린다.
// 텍스트 존 안에 떨어져도 그대로 둔다 — 존 감쇠가 저투명을 보장하므로 카피를 해치지 않고,
// 좌측에도 "그래프가 이어져 있다"는 존재감을 준다.
function placeOutlierPoint(rng: () => number, placed: PlacedPoint[]): { x: number; y: number } {
  let x = OUTLIER_X_MIN;
  let y = OUTLIER_Y_MIN;
  for (let attempt = 0; attempt < OVERLAP_ATTEMPTS; attempt++) {
    x = OUTLIER_X_MIN + rng() * (OUTLIER_X_MAX - OUTLIER_X_MIN);
    y = OUTLIER_Y_MIN + rng() * (OUTLIER_Y_MAX - OUTLIER_Y_MIN);
    if (!isTooClose(x, y, placed)) return { x, y };
  }
  return { x, y };
}

function placeAllPoints(rng: () => number): PlacedPoint[] {
  const placed: PlacedPoint[] = [];
  for (const cluster of CLUSTERS) {
    for (let i = 0; i < cluster.count; i++) {
      const { x, y } = placeClusterPoint(cluster, rng, placed);
      placed.push({ x, y, clusterId: cluster.id });
    }
  }
  for (let i = 0; i < OUTLIER_COUNT; i++) {
    const { x, y } = placeOutlierPoint(rng, placed);
    placed.push({ x, y, clusterId: "outlier" });
  }
  return placed;
}

// 타입 분포 — file이 가장 조용해야 하므로(DESIGN.md) 비중을 가장 높게 두고 나머지 6종이 나눠 갖는다.
const TYPE_WEIGHTS: Array<{ type: HeroNodeType; weight: number }> = [
  { type: "file", weight: 0.3 },
  { type: "commit", weight: 0.14 },
  { type: "pr", weight: 0.12 },
  { type: "issue", weight: 0.11 },
  { type: "slack", weight: 0.11 },
  { type: "jira", weight: 0.11 },
  { type: "person", weight: 0.11 },
];

function pickType(rng: () => number): HeroNodeType {
  const r = rng();
  let cumulative = 0;
  for (const { type, weight } of TYPE_WEIGHTS) {
    cumulative += weight;
    if (r < cumulative) return type;
  }
  return TYPE_WEIGHTS[TYPE_WEIGHTS.length - 1].type;
}

// 노드 시각 속성 — 클러스터 노드는 진하고 크게, 아웃라이어는 작고 흐리게 둬서
// "가장자리에 홀로 떨어진 노드"처럼 보이게 한다. 풀블리드 배경에서 그래프가 첫 화면의
// 명확한 시각 요소로 읽히도록 클러스터 노드는 opacity 0.45~0.7 / r 3.4~5.6으로 올렸다
// (단 앰버 점등 경로가 항상 그 위로 튀어야 하므로 그 이상은 올리지 않는다).
// 텍스트 존 안 노드는 zoneFactor(0.25~0.35)를 곱해 카피 가독성을 지킨다.
function buildNodes(rng: () => number): {
  nodes: HeroNode[];
  clusterOf: Map<string, ClusterId>;
  factorOf: Map<string, number>;
} {
  const points = placeAllPoints(rng);
  const clusterOf = new Map<string, ClusterId>();
  const factorOf = new Map<string, number>();
  const nodes = points.map((p, i) => {
    const id = `n${i}`;
    clusterOf.set(id, p.clusterId);
    const isOutlier = p.clusterId === "outlier";
    const r = isOutlier ? 2.4 + rng() * 1.0 : 3.4 + rng() * 2.2;
    const baseOpacity = isOutlier ? 0.2 + rng() * 0.15 : 0.45 + rng() * 0.25;
    const factor = zoneFactor(p.x, p.y, rng);
    factorOf.set(id, factor);
    return {
      id,
      x: Math.round(p.x * 100) / 100,
      y: Math.round(p.y * 100) / 100,
      r: Math.round(r * 100) / 100,
      type: pickType(rng),
      opacity: Math.round(baseOpacity * factor * 1000) / 1000,
    };
  });
  return { nodes, clusterOf, factorOf };
}

function edgeKey(a: number, b: number): string {
  return `${Math.min(a, b)}-${Math.max(a, b)}`;
}

// 엣지 — 클러스터 내부는 k-최근접 이웃으로 촘촘하게, 클러스터 사이는 지정된 쌍마다
// 가장 가까운 노드끼리 딱 1개씩만 이어 성긴 공유 연결을 만든다. 무향 그래프이므로
// a-b/b-a 중복은 인덱스 쌍 키로 제거한다. 전결합은 절대 하지 않는다.
// 텍스트 존에 걸친 엣지는 양 끝 노드 감쇠 계수의 min을 곱해 노드와 함께 가라앉는다.
function buildEdges(
  nodes: HeroNode[],
  clusterOf: Map<string, ClusterId>,
  factorOf: Map<string, number>,
  rng: () => number,
): HeroEdge[] {
  const seen = new Set<string>();
  const pairs: Array<{ a: number; b: number; kind: "intra" | "shared" }> = [];

  const addPair = (a: number, b: number, kind: "intra" | "shared") => {
    const key = edgeKey(a, b);
    if (seen.has(key)) return;
    seen.add(key);
    pairs.push({ a: Math.min(a, b), b: Math.max(a, b), kind });
  };

  const byCluster = new Map<ClusterId, number[]>();
  nodes.forEach((n, i) => {
    const c = clusterOf.get(n.id)!;
    if (!byCluster.has(c)) byCluster.set(c, []);
    byCluster.get(c)!.push(i);
  });

  // 클러스터 내부 k-최근접 이웃 (아웃라이어는 클러스터 내부 연결이 없다)
  for (const [clusterId, idxs] of byCluster) {
    if (clusterId === "outlier") continue;
    const k = INTRA_K[clusterId];
    for (const i of idxs) {
      const neighbors = idxs
        .filter((j) => j !== i)
        .map((j) => ({ j, d: dist(nodes[i].x, nodes[i].y, nodes[j].x, nodes[j].y) }))
        .sort((n1, n2) => n1.d - n2.d)
        .slice(0, k);
      for (const n of neighbors) addPair(i, n.j, "intra");
    }
  }

  // 클러스터 간 공유 연결 — 지정된 쌍마다 두 클러스터에서 가장 가까운 노드쌍 하나만
  for (const [c1, c2] of SHARED_PAIRS) {
    const idxs1 = byCluster.get(c1) ?? [];
    const idxs2 = byCluster.get(c2) ?? [];
    let best: { a: number; b: number; d: number } | null = null;
    for (const i of idxs1) {
      for (const j of idxs2) {
        const d = dist(nodes[i].x, nodes[i].y, nodes[j].x, nodes[j].y);
        if (!best || d < best.d) best = { a: i, b: j, d };
      }
    }
    if (best) addPair(best.a, best.b, "shared");
  }

  // 아웃라이어는 절반만(인덱스 기준 하나 걸러 하나) 전역 최근접 노드에 연결하고
  // 나머지 절반은 진짜 외톨이로 남겨 둔다.
  const outlierIdxs = byCluster.get("outlier") ?? [];
  for (let order = 0; order < outlierIdxs.length; order++) {
    if (order % 2 !== 0) continue;
    const i = outlierIdxs[order];
    let best: { j: number; d: number } | null = null;
    for (let j = 0; j < nodes.length; j++) {
      if (j === i) continue;
      const d = dist(nodes[i].x, nodes[i].y, nodes[j].x, nodes[j].y);
      if (!best || d < best.d) best = { j, d };
    }
    if (best) addPair(i, best.j, "shared");
  }

  pairs.sort((p1, p2) => p1.a - p2.a || p1.b - p2.b);
  return pairs.map((pair, idx) => {
    const baseOpacity =
      pair.kind === "intra" ? 0.14 + rng() * 0.12 : 0.09 + rng() * 0.06;
    const factor = Math.min(
      factorOf.get(`n${pair.a}`) ?? 1,
      factorOf.get(`n${pair.b}`) ?? 1,
    );
    return {
      id: `e${idx}`,
      from: `n${pair.a}`,
      to: `n${pair.b}`,
      opacity: Math.round(baseOpacity * factor * 1000) / 1000,
    };
  });
}

interface AdjacentNode {
  id: string;
  edgeId: string;
  d: number;
}

function buildAdjacency(nodes: HeroNode[], edges: HeroEdge[]): Map<string, AdjacentNode[]> {
  const adj = new Map<string, AdjacentNode[]>();
  const byId = new Map(nodes.map((n) => [n.id, n]));
  for (const n of nodes) adj.set(n.id, []);

  for (const e of edges) {
    const from = byId.get(e.from)!;
    const to = byId.get(e.to)!;
    const d = dist(from.x, from.y, to.x, to.y);
    adj.get(e.from)!.push({ id: e.to, edgeId: e.id, d });
    adj.get(e.to)!.push({ id: e.from, edgeId: e.id, d });
  }
  for (const list of adj.values()) list.sort((a, b) => a.d - b.d);
  return adj;
}

// 실제 엣지를 따라 아직 안 쓴 이웃으로 3번 걸어가 4노드/3엣지 체인을 만든다.
// 막히면(이웃이 모두 소진되면) 백트래킹으로 다른 이웃 조합을 시도한다.
// buildLitPath가 코어에서 뭉치를 못 찾는 극단적인 경우에만 쓰는 안전망이다.
function walkChain(
  start: string,
  adj: Map<string, AdjacentNode[]>,
): { nodeIds: string[]; edgeIds: string[] } | null {
  const nodeIds = [start];
  const edgeIds: string[] = [];
  const visited = new Set([start]);

  function extend(): boolean {
    if (nodeIds.length === 4) return true;
    const current = nodeIds[nodeIds.length - 1];
    const neighbors = (adj.get(current) ?? []).filter((n) => !visited.has(n.id));
    for (const n of neighbors) {
      nodeIds.push(n.id);
      edgeIds.push(n.edgeId);
      visited.add(n.id);
      if (extend()) return true;
      nodeIds.pop();
      edgeIds.pop();
      visited.delete(n.id);
    }
    return false;
  }

  return extend() ? { nodeIds, edgeIds } : null;
}

function bboxDiagonal(pathNodes: HeroNode[]): number {
  const xs = pathNodes.map((n) => n.x);
  const ys = pathNodes.map((n) => n.y);
  const w = Math.max(...xs) - Math.min(...xs);
  const h = Math.max(...ys) - Math.min(...ys);
  return Math.hypot(w, h);
}

// 코어 내부 엣지만 따라, 시작 노드에서 유클리드 거리가 먼 이웃을 우선 골라 4노드
// 단순 경로(3엣지)를 만든다 — 중심에 뭉치지 않고 코어를 가로지르는 트레이스가 목표다.
// 막히면 백트래킹으로 다른 이웃 조합을 시도한다.
function walkCorePath(
  start: string,
  coreAdj: Map<string, AdjacentNode[]>,
  nodeById: Map<string, HeroNode>,
): { nodeIds: string[]; edgeIds: string[] } | null {
  const startNode = nodeById.get(start)!;
  const nodeIds = [start];
  const edgeIds: string[] = [];
  const visited = new Set([start]);

  function extend(): boolean {
    if (nodeIds.length === 4) return true;
    const current = nodeIds[nodeIds.length - 1];
    const neighbors = (coreAdj.get(current) ?? [])
      .filter((n) => !visited.has(n.id))
      .map((n) => ({ ...n, distFromStart: dist(nodeById.get(n.id)!.x, nodeById.get(n.id)!.y, startNode.x, startNode.y) }))
      .sort((a, b) => b.distFromStart - a.distFromStart); // 시작점에서 먼 이웃 우선
    for (const n of neighbors) {
      nodeIds.push(n.id);
      edgeIds.push(n.edgeId);
      visited.add(n.id);
      if (extend()) return true;
      nodeIds.pop();
      edgeIds.pop();
      visited.delete(n.id);
    }
    return false;
  }

  return extend() ? { nodeIds, edgeIds } : null;
}

// 점등 경로 — 가장 조밀한 코어 클러스터 "안"만 도는 4노드/3엣지 트레이스를 고른다.
// 새 지오메트리를 만들지 않고 buildEdges가 만든 코어 내부 엣지만 재사용하므로,
// 앰버 엣지는 주변 배경 엣지와 형태상 구별되지 않는다 — 코어 중심에 뭉치는 덩어리가
// 아니라, 코어를 가로질러 퍼진 트레이스로 보이도록 시작점(최소 x)에서 먼 이웃을
// 우선 선택하고, 4노드의 바운딩박스 대각선이 일정 길이 이상 되도록 강제한다.
function buildLitPath(
  nodes: HeroNode[],
  edges: HeroEdge[],
  clusterOf: Map<string, ClusterId>,
): { litNodeIds: string[]; litEdgeIds: string[] } {
  // 코어 spread(40)에 비례한 최소 스팬 — 기존 spread 26 기준 55에서 확대.
  const MIN_SPAN = 85;
  const coreNodes = nodes.filter((n) => clusterOf.get(n.id) === "core");
  const coreEdges = edges.filter(
    (e) => clusterOf.get(e.from) === "core" && clusterOf.get(e.to) === "core",
  );
  const coreAdj = buildAdjacency(coreNodes, coreEdges);
  const nodeById = new Map(nodes.map((n) => [n.id, n]));

  // 코어 노드 중 x가 가장 작은(코어 좌측) 노드부터 순서대로 시작점 후보로 시도한다.
  const startCandidates = [...coreNodes].sort((a, b) => a.x - b.x);

  let bestPath: { nodeIds: string[]; edgeIds: string[] } | null = null;
  let bestSpan = -1;

  for (const start of startCandidates) {
    const path = walkCorePath(start.id, coreAdj, nodeById);
    if (!path) continue;
    const span = bboxDiagonal(path.nodeIds.map((id) => nodeById.get(id)!));
    if (span > bestSpan) {
      bestSpan = span;
      bestPath = path;
    }
    if (span >= MIN_SPAN) {
      return { litNodeIds: path.nodeIds, litEdgeIds: path.edgeIds };
    }
  }

  if (bestPath) return { litNodeIds: bestPath.nodeIds, litEdgeIds: bestPath.edgeIds };

  // 코어 내부만으로 4노드 경로를 전혀 못 찾는 극단적인 경우의 안전망 — 전체 그래프에서 체인 워크.
  const fullAdj = buildAdjacency(nodes, edges);
  for (const start of nodes) {
    const chain = walkChain(start.id, fullAdj);
    if (chain && chain.edgeIds.length >= 2) {
      return { litNodeIds: chain.nodeIds, litEdgeIds: chain.edgeIds };
    }
  }
  return { litNodeIds: coreNodes.slice(0, 4).map((n) => n.id), litEdgeIds: [] };
}

function generateBackdropGraph(): HeroBackdropGraph {
  const rng = mulberry32(SEED);
  const { nodes, clusterOf, factorOf } = buildNodes(rng);
  const edges = buildEdges(nodes, clusterOf, factorOf, rng);
  const { litNodeIds, litEdgeIds } = buildLitPath(nodes, edges, clusterOf);
  return { width: WIDTH, height: HEIGHT, nodes, edges, litNodeIds, litEdgeIds };
}

// 모듈 로드 시 1회 생성 — 시드가 고정이라 항상 같은 배치가 나온다.
export const HERO_BACKDROP_GRAPH: HeroBackdropGraph = generateBackdropGraph();
