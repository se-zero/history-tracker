import {
  forceCenter,
  forceCollide,
  forceLink,
  forceManyBody,
  forceSimulation,
  type SimulationLinkDatum,
  type SimulationNodeDatum,
} from "d3-force";

import type { GraphEdge, GraphNode } from "@/types/graph";

export type SimNode = SimulationNodeDatum & GraphNode;
export type SimLink = SimulationLinkDatum<SimNode>;

export interface Positioned extends GraphNode {
  px: number;
  py: number;
}

// d3-force 시뮬레이션을 정적으로 320 tick 돌려 노드 좌표를 확정한다(렌더 전 1회).
export function runSimulation(nodes: GraphNode[], edges: GraphEdge[]): SimNode[] {
  const simNodes: SimNode[] = nodes.map((n) => ({ ...n }));
  const idx = new Map(simNodes.map((n) => [n.id, n]));
  const simLinks: SimLink[] = edges
    .filter((e) => idx.has(e.source) && idx.has(e.target))
    .map((e) => ({ source: e.source, target: e.target }));
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

// 시뮬레이션 좌표를 주어진 크기 안에 패딩을 두고 정규화한다.
export function fitTo(
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
