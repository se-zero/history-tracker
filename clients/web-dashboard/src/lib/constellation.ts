/**
 * 작업 성좌(Work Constellation) 레이아웃.
 *
 * 그래프를 "은하"로 그리기 위한 배치 계산이다. 핵심 발상:
 *
 *   별성(star) = PullRequest — 하나의 작업 단위
 *   위성(satellite) = 그 작업에 딸린 commit · file · Jira 티켓 · Slack/GitHub 논의
 *   성좌간 다리(bridge) = 두 작업이 같은 노드를 공유 (주로 같은 파일·같은 티켓)
 *
 * 별성은 한 종류만 쓴다 —
 *  PR과 Jira 티켓을 둘 다 별성으로 두면 같은 작업이 오브 두 개로 쪼개진다. 스키마에
 *  PullRequest→Issue 직접 관계가 없고 둘 다 ChangeSet에 붙어 있어서, 커밋이 양쪽에
 *  인접한 채 임의로 갈리기 때문이다.
 *
 * 그 밖의 위상 고려사항:
 *  1) Actor는 참여자 수가 적은데 모든 이벤트에 붙는 초거대 허브라, 그대로 force에 넣으면
 *     전체가 Actor 몇 개짜리 헤어볼로 붕괴한다. 그래서 **Actor는 배치에서 완전히 제외**하고
 *     각 별성의 작성자 이름만 뽑아 라벨 메타로 쓴다.
 *  2) 위성은 별성 기준 극좌표(phyllotaxis)로 직접 배치한다. 시뮬레이션 대상이 별성으로
 *     줄어들어 빠르고, 궤도가 규칙적이라 "오브 + 입자" 룩이 나온다.
 *
 * 좌표는 원점(0,0) 중심의 월드 좌표다. 뷰포트 맞춤(스케일·이동)은 렌더러가 담당한다.
 */

import {
  forceCenter,
  forceCollide,
  forceLink,
  forceManyBody,
  forceSimulation,
  forceX,
  forceY,
  type SimulationLinkDatum,
  type SimulationNodeDatum,
} from "d3-force";

import type { GraphEdge, GraphNode, GraphNodeType } from "@/types/graph";

/**
 * 별성으로 삼을 노드를 고른다.
 *
 * 작업 단위 판정은 서버(ai-engine)가 하고 프론트는 그 목록을 받아 쓴다. 노드 타입으로
 * 하드코딩하면, PR이 base 브랜치 기준으로 수집되는 탓에 PullRequest가 0건인 프로젝트에서
 * 별성이 통째로 사라진다(화면이 먼지 고리만 남는다). 서버는 그럴 때 Issue로 폴백한다.
 *
 * workUnitIds가 비어 있으면(구버전 응답 등) PR을 별성으로 보는 기존 동작으로 되돌아간다.
 */
const FALLBACK_STAR_TYPES: ReadonlySet<GraphNodeType> = new Set<GraphNodeType>(["pr"]);

function resolveStarPredicate(
  workUnitIds: readonly string[] | undefined,
): (node: GraphNode) => boolean {
  if (workUnitIds && workUnitIds.length > 0) {
    const ids = new Set(workUnitIds);
    return (node) => ids.has(node.id);
  }
  return (node) => FALLBACK_STAR_TYPES.has(node.type);
}

/**
 * 별성에서 몇 홉까지 위성으로 끌어올지.
 * PR 기준 실제 거리: commit 1홉 · file/Issue 2홉 · (Issue를 거친) Slack 대화 3홉.
 * 3홉까지 봐야 DISCUSSED_IN으로만 이어진 대화가 성좌 안으로 들어온다.
 */
const MAX_HOPS = 3;

/**
 * 위성 궤도 밴드 [안쪽, 바깥쪽]. 안쪽일수록 코드, 바깥일수록 "왜"에 가깝다 —
 * 제품 논지(왜 → 무엇)를 성좌 하나 안에서도 반경으로 읽히게 한다.
 */
const ORBIT_BAND: Partial<Record<GraphNodeType, [number, number]>> = {
  commit: [36, 62],
  code: [68, 94],
  jira: [100, 132],
  issue: [138, 172],
  slack: [138, 172],
};
const DEFAULT_BAND: [number, number] = [68, 94];

/** 위성 점 크기 — 타입별로 살짝 달리해 입자 바다에 결이 생기게 한다. */
const SATELLITE_RADIUS: Partial<Record<GraphNodeType, number>> = {
  commit: 2.6,
  code: 2.1,
  jira: 3.6,
  issue: 3.2,
  slack: 3.2,
};

/** 황금각 — 위성을 밴드 안에 고르게 흩는 phyllotaxis 배치에 쓴다. */
const GOLDEN_ANGLE = Math.PI * (3 - Math.sqrt(5));

export interface Satellite {
  node: GraphNode;
  x: number;
  y: number;
  r: number;
  /** 반짝임 위상 — 노드마다 고정이라 프레임마다 재계산하지 않는다. */
  phase: number;
}

export interface Star {
  node: GraphNode;
  x: number;
  y: number;
  /** 오브 반지름 — 위성 수에 따라 커진다. */
  r: number;
  /** 위성이 퍼진 최대 반경 — 성운 크기·충돌 반경에 쓴다. */
  reach: number;
  satellites: Satellite[];
  /** 이 작업에 관여한 사람 이름 (Actor는 배치에서 빼고 여기로만 남긴다). */
  authors: string[];
}

export interface Bridge {
  a: number;
  b: number;
  weight: number;
  /** 두 성좌를 실제로 잇는 공유 노드 — 상세 패널에서 "무엇을 공유하는지" 보여준다. */
  shared: GraphNode[];
}

export interface ConstellationLayout {
  stars: Star[];
  bridges: Bridge[];
  /** 어느 별성에도 속하지 못한 노드 — 바깥을 떠도는 성간 먼지. */
  dust: Satellite[];
  /** 원점 기준 최대 반경 — 렌더러가 뷰포트 맞춤 배율을 정할 때 쓴다. */
  extent: number;
}

/** 문자열 → [0,1) 결정적 해시. 같은 그래프는 항상 같은 그림이 나오게 한다. */
function hash01(s: string): number {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return ((h >>> 0) % 100000) / 100000;
}

interface StarSim extends SimulationNodeDatum {
  id: string;
  reach: number;
}

/**
 * @param frozen 이미 자리를 잡은 별성의 좌표. 드릴인으로 위성을 나중에 채울 때,
 *   이 좌표를 고정하지 않으면 시뮬레이션이 다시 돌아 사용자가 보고 있던 은하가
 *   통째로 재배치된다. 전부 고정되면 시뮬레이션 자체를 건너뛴다.
 */
export function buildConstellations(
  nodes: GraphNode[],
  edges: GraphEdge[],
  workUnitIds?: readonly string[],
  frozen?: ReadonlyMap<string, { x: number; y: number }>,
): ConstellationLayout {
  const isStar = resolveStarPredicate(workUnitIds);
  const byId = new Map(nodes.map((n) => [n.id, n]));

  // 인접 리스트 (무향). Actor로 향하는 간선도 담아두고 작성자 이름 추출에만 쓴다.
  const adj = new Map<string, string[]>();
  const push = (a: string, b: string) => {
    const list = adj.get(a);
    if (list) list.push(b);
    else adj.set(a, [b]);
  };
  for (const [a, b] of edges) {
    if (!byId.has(a) || !byId.has(b)) continue;
    push(a, b);
    push(b, a);
  }

  const starNodes = nodes.filter(isStar);
  const starIndex = new Map(starNodes.map((n, i) => [n.id, i]));

  const stars: Star[] = starNodes.map((node) => ({
    node,
    x: 0,
    y: 0,
    r: 0,
    reach: 0,
    satellites: [],
    authors: [],
  }));

  const bridgeMap = new Map<string, Bridge>();
  const addBridge = (i: number, j: number, shared: GraphNode | null) => {
    if (i === j) return;
    const key = i < j ? `${i}:${j}` : `${j}:${i}`;
    let entry = bridgeMap.get(key);
    if (!entry) {
      entry = { a: Math.min(i, j), b: Math.max(i, j), weight: 0, shared: [] };
      bridgeMap.set(key, entry);
    }
    entry.weight++;
    if (shared) entry.shared.push(shared);
  };

  // nodeId → 소속 별성. 다음 홉에서 "이미 자리를 잡은 이웃"을 찾는 데 쓴다.
  const assigned = new Map<string, number>();

  /** 후보 별성 중 가장 한산한 곳에 붙이고, 나머지 후보와는 다리를 놓는다. */
  const attach = (node: GraphNode, owners: number[]) => {
    let host = owners[0];
    for (const cand of owners) {
      if (stars[cand].satellites.length < stars[host].satellites.length) host = cand;
    }
    stars[host].satellites.push({
      node,
      x: 0,
      y: 0,
      r: SATELLITE_RADIUS[node.type] ?? 2.4,
      phase: hash01(node.id) * Math.PI * 2,
    });
    assigned.set(node.id, host);
    for (const other of owners) addBridge(host, other, node);
  };

  /** 이웃 중 조건에 맞는 별성 인덱스를 중복 없이 모은다. */
  const ownersOf = (nodeId: string, lookup: (nb: string) => number | undefined) => {
    const owners: number[] = [];
    for (const nb of adj.get(nodeId) ?? []) {
      const si = lookup(nb);
      if (si !== undefined && !owners.includes(si)) owners.push(si);
    }
    return owners;
  };

  // Actor는 배치에서 제외하고 인접 별성의 작성자 목록에만 이름을 남긴다.
  let pending: GraphNode[] = [];
  for (const node of nodes) {
    if (isStar(node)) continue;
    if (node.type === "actor") {
      for (const nb of adj.get(node.id) ?? []) {
        const si = starIndex.get(nb);
        if (si !== undefined && !stars[si].authors.includes(node.title)) {
          stars[si].authors.push(node.title);
        }
      }
      continue;
    }
    pending.push(node);
  }

  // 홉을 넓혀가며 배치한다. 각 홉의 조회 기준은 직전 홉까지의 결과로 고정해,
  // 같은 홉 안에서 처리 순서에 따라 배치가 흔들리지 않게 한다.
  for (let hop = 1; hop <= MAX_HOPS && pending.length > 0; hop++) {
    const snapshot = hop === 1 ? starIndex : new Map(assigned);
    const rest: GraphNode[] = [];
    for (const node of pending) {
      const owners = ownersOf(node.id, (nb) => snapshot.get(nb));
      if (owners.length === 0) rest.push(node);
      else attach(node, owners);
    }
    pending = rest;
  }
  const dustNodes = pending;

  // 별성끼리 직접 이어진 경우도 다리로 본다.
  // (현재 스키마에 PR↔PR 관계는 없어 거의 발생하지 않지만, 별성 타입이 바뀌어도 안전하도록 둔다.)
  for (const [a, b] of edges) {
    const ia = starIndex.get(a);
    const ib = starIndex.get(b);
    if (ia !== undefined && ib !== undefined) addBridge(ia, ib, null);
  }

  for (const star of stars) layoutSatellites(star);

  const bridges = [...bridgeMap.values()];
  simulateStars(stars, bridges, frozen);

  // 위성 좌표를 별성 위치 기준 절대 좌표로 확정한다.
  for (const star of stars) {
    for (const sat of star.satellites) {
      sat.x += star.x;
      sat.y += star.y;
    }
  }

  const galaxyRadius = stars.reduce(
    (max, s) => Math.max(max, Math.hypot(s.x, s.y) + s.reach),
    140,
  );
  const dust = layoutDust(dustNodes, galaxyRadius);
  const extent = dust.reduce(
    (max, d) => Math.max(max, Math.hypot(d.x, d.y) + 10),
    galaxyRadius,
  );

  return { stars, bridges, dust, extent };
}

/** 위성을 타입별 밴드 안에 황금각으로 흩는다 (개수가 많아도 균일하게 퍼진다). */
function layoutSatellites(star: Star): void {
  const byBand = new Map<string, Satellite[]>();
  for (const sat of star.satellites) {
    const band = ORBIT_BAND[sat.node.type] ?? DEFAULT_BAND;
    const key = `${band[0]}:${band[1]}`;
    const list = byBand.get(key);
    if (list) list.push(sat);
    else byBand.set(key, [sat]);
  }

  // 성좌마다 시작 각도를 달리해 전부 같은 모양으로 보이지 않게 한다.
  const baseAngle = hash01(star.node.id) * Math.PI * 2;
  let reach = 0;

  for (const [key, list] of byBand) {
    const [inner, outer] = key.split(":").map(Number);
    list.forEach((sat, i) => {
      // sqrt 분포라야 밴드 안에서 면적 기준으로 고르게 퍼진다.
      const t = list.length === 1 ? 0.5 : Math.sqrt((i + 0.5) / list.length);
      const radius = inner + (outer - inner) * t;
      const angle = baseAngle + i * GOLDEN_ANGLE;
      sat.x = Math.cos(angle) * radius;
      sat.y = Math.sin(angle) * radius;
      reach = Math.max(reach, radius);
    });
  }

  star.r = Math.min(24, 10 + Math.sqrt(star.satellites.length) * 2.4);
  star.reach = Math.max(reach + 14, star.r + 26);
}

/** 별성만 대상으로 force 시뮬레이션을 돌린다 — 대상이 적어 빠르고 안정적이다. */
function simulateStars(
  stars: Star[],
  bridges: Bridge[],
  frozen?: ReadonlyMap<string, { x: number; y: number }>,
): void {
  if (stars.length === 0) return;

  const simNodes: StarSim[] = stars.map((s, i) => {
    const fixed = frozen?.get(s.node.id);
    return {
      id: String(i),
      reach: s.reach,
      // 이미 자리를 잡았던 별성은 fx/fy로 못 박아 두어 위치가 유지된다.
      x: fixed?.x ?? Math.cos((i / stars.length) * Math.PI * 2) * 300,
      y: fixed?.y ?? Math.sin((i / stars.length) * Math.PI * 2) * 300,
      fx: fixed?.x,
      fy: fixed?.y,
    };
  });

  // 전부 고정이면 돌릴 이유가 없다 — 드릴인으로 위성만 늘어난 경우가 여기 해당한다.
  if (simNodes.every((n) => n.fx !== undefined)) {
    stars.forEach((star, i) => {
      star.x = simNodes[i].x ?? 0;
      star.y = simNodes[i].y ?? 0;
    });
    return;
  }

  const links: SimulationLinkDatum<StarSim>[] = bridges.map((b) => ({
    source: String(b.a),
    target: String(b.b),
  }));

  const sim = forceSimulation(simNodes)
    .force(
      "link",
      forceLink<StarSim, SimulationLinkDatum<StarSim>>(links)
        .id((d) => d.id)
        // 연결이 강할수록 가깝게 — 같은 파일을 여러 번 공유한 작업끼리 뭉친다.
        .distance((_, i) => 380 - Math.min(150, bridges[i].weight * 22))
        .strength((_, i) => Math.min(0.55, 0.12 + bridges[i].weight * 0.05)),
    )
    .force("charge", forceManyBody<StarSim>().strength(-900))
    .force(
      "collide",
      forceCollide<StarSim>((d) => d.reach + 28).strength(0.9),
    )
    // forceCenter는 무게중심을 원점으로 옮길 뿐 안쪽으로 끌어당기지 않는다.
    // 그것만 두면 척력에 밀려 은하가 계속 퍼져 성좌 하나가 화면에서 너무 작아지므로,
    // forceX/forceY로 약하게 원점을 향해 붙잡는다. (실측: 반경 1819 → 1086)
    .force("center", forceCenter(0, 0))
    .force("x", forceX<StarSim>(0).strength(0.05))
    .force("y", forceY<StarSim>(0).strength(0.05))
    .stop();

  for (let i = 0; i < 460; i++) sim.tick();

  stars.forEach((star, i) => {
    star.x = simNodes[i].x ?? 0;
    star.y = simNodes[i].y ?? 0;
  });
}

/** 별성에 속하지 못한 노드는 은하 바깥을 도는 먼지로 흩뿌린다. */
function layoutDust(nodes: GraphNode[], galaxyRadius: number): Satellite[] {
  if (nodes.length === 0) return [];
  const inner = galaxyRadius * 1.08;
  const outer = galaxyRadius * 1.34;
  return nodes.map((node, i) => {
    const t = nodes.length === 1 ? 0.5 : Math.sqrt((i + 0.5) / nodes.length);
    const radius = inner + (outer - inner) * t;
    const angle = i * GOLDEN_ANGLE + hash01(node.id) * 0.6;
    return {
      node,
      x: Math.cos(angle) * radius,
      y: Math.sin(angle) * radius,
      r: (SATELLITE_RADIUS[node.type] ?? 2.4) * 0.85,
      phase: hash01(node.id) * Math.PI * 2,
    };
  });
}
