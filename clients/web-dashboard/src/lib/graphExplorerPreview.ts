// 기능 2(그래프 탐색) 미리보기 전용 데이터 — 작동 방식 섹션과 공유하는 MiniGraph 좌표계
// (howItWorksGraph.ts의 400x240 뷰박스·18개 노드·클러스터 A/B/C 배치)를 그대로 두고, 이 슬롯
// 에서만 추가되는 노드·엣지·라벨을 정의한다. MiniGraph의 opt-in prop(extraNodes/extraEdges/
// labels, 기본값 빈 배열)으로만 소비되므로 작동 방식 섹션(HowItWorksSection.tsx, stage prop만
// 전달)의 렌더에는 전혀 영향이 없다 — 이 파일은 FeatureSections.tsx에서만 import한다.
//
// 노드는 기존 3클러스터(A 좌상단/B 중앙하단/C 우상단) 내부에서만 늘린다 — 클러스터끼리는
// 여전히 엣지로 잇지 않는다(howItWorksGraph.ts와 같은 원칙, "다 연결되진 않는다"는 현실감
// 유지). 기존 18개 노드에 14개를 더해 32개(×1.78) — 요구 범위(1.5~2배) 안에서 클러스터
// 내부가 촘촘해지는 방식으로 "실제 그래프" 밀도를 낸다(균일 산포 아님).
import type { MiniGraphEdge, MiniGraphNode } from "./howItWorksGraph";

export interface MiniGraphLabel {
  /** 라벨이 붙는 노드 id — howItWorksGraph.ts 기존 노드 또는 아래 EXTRA_NODES 중 하나. */
  nodeId: string;
  text: string;
  /** 노드 점 기준 라벨 오프셋(실 px 고정값 — 그래프 자체는 뷰박스 스케일로 반응형이지만
      라벨은 DESIGN.md mono-label 12px 하한을 항상 지켜야 해서 그래프 스케일과 무관하게 CSS
      px로 고정한다. MiniGraph.tsx의 컨테이너 쿼리 단위(cqh/cqw) 위치 계산과 짝을 이룬다). */
  dx: number;
  dy: number;
  /** true면 텍스트 끝을 (노드 x + dx)에 맞춘다(좌측으로 뻗음) — 오른쪽에 다른 노드/엣지가
      붙어 있어 기본(우측으로 뻗는) 방향이 겹치는 라벨에 쓴다. */
  anchorEnd?: boolean;
  /** 앰버 점등 경로 위 라벨 하나만 text-primary로 살짝 밝힌다(선택된/점등 맥락). */
  emphasis?: boolean;
  /** 모바일(라벨 3~4개로 축소)에서도 유지하는 핵심 라벨인지. */
  core?: boolean;
  /** 캔버스 폭이 충분히 넓을 때만 보인다(1180px 미만에서 숨김) — anchorEnd로 텍스트가 길게
      왼쪽으로 뻗는 라벨은 캔버스가 좁아지면(2열 레이아웃의 좁은 구간, 768~1180px 근방)
      실 px 텍스트 폭이 캔버스 왼쪽 밖으로 밀려 잘린다(겹치거나 잘리면 라벨을 빼는 원칙). */
  wide?: boolean;
}

export const GRAPH_EXPLORER_EXTRA_NODES: MiniGraphNode[] = [
  // 클러스터 A 추가 — 좌측
  { id: "g0", x: 95, y: 22, type: "commit" },
  { id: "g1", x: 48, y: 30, type: "file" },
  { id: "g2", x: 143, y: 52, type: "person" },
  { id: "g3", x: 88, y: 132, type: "issue" },
  { id: "g4", x: 22, y: 98, type: "pr" },
  // 클러스터 B 추가 — 중앙 하단
  { id: "g5", x: 208, y: 108, type: "file" },
  { id: "g6", x: 262, y: 138, type: "commit" },
  { id: "g7", x: 192, y: 207, type: "jira" },
  { id: "g8", x: 257, y: 188, type: "file" },
  { id: "g9", x: 152, y: 158, type: "person" },
  // 클러스터 C 추가 — 우측 상단
  { id: "g10", x: 372, y: 70, type: "file" },
  { id: "g11", x: 368, y: 88, type: "commit" },
  { id: "g12", x: 288, y: 92, type: "issue" },
  { id: "g13", x: 347, y: 118, type: "pr" },
];

export const GRAPH_EXPLORER_EXTRA_EDGES: MiniGraphEdge[] = [
  // 클러스터 A — 기존 노드로 연결하거나 추가 노드끼리 이어 내부 메시를 촘촘히 한다.
  { id: "ge0", from: "g0", to: "n1", stage: 2 },
  { id: "ge1", from: "g1", to: "n4", stage: 2 },
  { id: "ge2", from: "g2", to: "n5", stage: 2 },
  { id: "ge3", from: "g3", to: "n3", stage: 2 },
  { id: "ge4", from: "g4", to: "n2", stage: 2 },
  { id: "ge5", from: "g0", to: "g2", stage: 2 },
  // 클러스터 B — n6/n7/n8/n10(03의 앰버 경로)은 그대로 두고 주변에만 붙인다.
  { id: "ge6", from: "g5", to: "n7", stage: 2 },
  { id: "ge7", from: "g6", to: "n9", stage: 2 },
  { id: "ge8", from: "g7", to: "n10", stage: 2 },
  { id: "ge9", from: "g8", to: "n11", stage: 2 },
  { id: "ge10", from: "g9", to: "n6", stage: 2 },
  { id: "ge11", from: "g6", to: "g8", stage: 2 },
  // 클러스터 C
  { id: "ge12", from: "g10", to: "n13", stage: 2 },
  { id: "ge13", from: "g11", to: "n15", stage: 2 },
  { id: "ge14", from: "g12", to: "n14", stage: 2 },
  { id: "ge15", from: "g13", to: "n13", stage: 2 },
  { id: "ge16", from: "g12", to: "g13", stage: 2 },
];

// 라벨 7개 — 일부 노드에만 붙여 실제 그래프 같은 밀도 편차를 만든다(전체 라벨링 금지).
// n7/n10/n8은 03의 앰버 점등 경로 위 노드(howItWorksGraph.ts e5/e6/e8) — 그중 n7 하나만
// emphasis(text-primary)로 밝힌다. 우하단 줌 컨트롤(.lp-feature-graph-zoom)과 겹치는 자리
// (g8 등 캔버스 우하단 노드)는 라벨을 빼 피한다(겹치면 라벨을 빼는 쪽을 택한다는 원칙을
// 라벨-라벨뿐 아니라 라벨-UI에도 적용). core 4개(n7/n8/n2/g12)는 모바일에서도 유지한다 —
// CheckpointProvider.java·WebhookDeliveryStore.java(24·26자)는 데스크톱 캔버스 폭에서는
// 여유가 있지만 모바일의 좁은 렌더 폭에서는 텍스트가 캔버스 밖으로 밀려나 잘리므로 core에서
// 뺐다(짧은 라벨 위주로 유지 — 겹치거나 잘리면 라벨을 빼는 원칙). "HT-71"은 원래 n13(y=75)에
// 붙였으나 모바일에서는 캔버스 상단이 불투명 헤더(.lp-feature-graph-header)에 가려지는
// 구간(대략 y<80)과 겹쳐 g12(y=92, 같은 issue 타입)로 옮겼다.
export const GRAPH_EXPLORER_LABELS: MiniGraphLabel[] = [
  { nodeId: "n7", text: "QueryService.java", dx: 8, dy: -9, emphasis: true, core: true },
  { nodeId: "n10", text: "CheckpointProvider.java", dx: -8, dy: 10, anchorEnd: true, wide: true },
  { nodeId: "n8", text: "#dev-search", dx: 4, dy: -12, core: true },
  { nodeId: "n2", text: "HT-64", dx: -6, dy: 9, anchorEnd: true, core: true },
  { nodeId: "n3", text: "WebhookDeliveryStore.java", dx: 8, dy: -13 },
  { nodeId: "g10", text: "GraphController.java", dx: 8, dy: 8 },
  { nodeId: "g12", text: "HT-71", dx: 8, dy: -11, core: true },
];
