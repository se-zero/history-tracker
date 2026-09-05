import type { GraphEdge } from "@/types/graph";

export const IGNITE_NODE_MS = 280; // 노드 1개 발화(랜딩 기능 2 실측)
export const IGNITE_EDGE_MS = 600; // 엣지 드로잉(랜딩 기능 2 실측)
export const IGNITE_SEQ_CAP = 3; // 순차 발화 상한 — 3이면 총길이 280×3+600×2=2040ms

// 방향 무관 엣지 키. 노드 id에 나올 수 없는 "\n"으로 정렬된 두 id를 묶어, (a,b)/(b,a)가
// 같은 키로 조회되게 한다 — GraphVis가 렌더 시 이 키로 스케줄을 조회한다.
export function edgeKey(a: string, b: string): string {
  return a < b ? `${a}\n${b}` : `${b}\n${a}`;
}

export interface IgniteSchedule {
  nodeDelay: Map<string, number>;
  drawEdges: Map<string, number>;
  fadeEdges: Map<string, number>;
  totalMs: number;
}

// 인용 순서를 따라 시드 노드가 순차 발화하고, 발화 사이 실존 엣지가 있으면 드로잉으로
// 이어붙이는 안무 스케줄을 계산한다. 시드가 많아도 총 재생 시간이 폭주하지 않도록
// 순차 구간은 IGNITE_SEQ_CAP개로 제한하고 나머지는 마지막 박자에 일괄 발화시킨다.
export function buildIgniteSchedule(
  seedOrder: string[],
  edges: GraphEdge[],
): IgniteSchedule {
  const nodeDelay = new Map<string, number>();
  const drawEdges = new Map<string, number>();
  const fadeEdges = new Map<string, number>();

  // 1. 중복 제거(첫 등장 유지) — 같은 노드가 evidence에 여러 번 인용돼도 한 번만 발화한다.
  const order: string[] = [];
  const seen = new Set<string>();
  for (const id of seedOrder) {
    if (seen.has(id)) continue;
    seen.add(id);
    order.push(id);
  }
  if (order.length === 0) {
    return { nodeDelay, drawEdges, fadeEdges, totalMs: 0 };
  }

  // 2. 순차 발화 구간(seq)과 일괄 발화 구간(rest)으로 나눈다.
  const seq = order.slice(0, IGNITE_SEQ_CAP);
  const rest = order.slice(IGNITE_SEQ_CAP);

  // 3. 시드-시드 엣지만 추린다(양 끝이 모두 시드). edgeKey 기준 중복 제거.
  const seedSet = new Set(order);
  const seedEdges = new Map<string, [string, string]>();
  for (const e of edges) {
    if (seedSet.has(e.source) && seedSet.has(e.target)) {
      seedEdges.set(edgeKey(e.source, e.target), [e.source, e.target]);
    }
  }

  // 4. 체인: 첫 시드는 0ms에 발화. 이후 이웃과 실존 엣지가 있으면 드로잉이 닿는 순간
  // 발화하고(서사가 있는 경우), 없으면 박자만 진행한다(시드가 체인 형상이 아닐 수 있다).
  nodeDelay.set(seq[0], 0);
  const usedKeys = new Set<string>();
  for (let i = 1; i < seq.length; i++) {
    const prev = seq[i - 1];
    const cur = seq[i];
    const key = edgeKey(prev, cur);
    const prevDelay = nodeDelay.get(prev)!;
    if (seedEdges.has(key)) {
      usedKeys.add(key);
      const drawStart = prevDelay + IGNITE_NODE_MS;
      drawEdges.set(key, drawStart);
      nodeDelay.set(cur, drawStart + IGNITE_EDGE_MS);
    } else {
      nodeDelay.set(cur, prevDelay + IGNITE_NODE_MS);
    }
  }

  // 5. 마지막 박자에 나머지 시드 전원을 일괄 발화 — 순차를 무제한 늘리면 시드 N개에
  // 총길이가 N에 비례해 폭주한다.
  const finalBeat = nodeDelay.get(seq[seq.length - 1])!;
  for (const id of rest) {
    nodeDelay.set(id, finalBeat);
  }

  // 6. 체인에 안 쓰인 나머지 시드-시드 엣지는 조용히 페이드 — 드로잉 서사는 순차 구간의 전유물.
  for (const key of seedEdges.keys()) {
    if (!usedKeys.has(key)) {
      fadeEdges.set(key, finalBeat);
    }
  }

  const totalMs = finalBeat + IGNITE_NODE_MS;
  return { nodeDelay, drawEdges, fadeEdges, totalMs };
}
