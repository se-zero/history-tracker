// "작동 방식 3스텝" 섹션의 미니 그래프 시각화 데이터.
// 핵심 제약: 01(연결)/02(구축)/03(질문) 세 스테이지는 반드시 같은 노드 배치(좌표·타입·개수)를
// 공유한다 — 서로 다른 그래프 3개를 그리면 장식일 뿐, "하나의 그래프가 완성되는 과정"이
// 되려면 노드는 고정하고 엣지 구성·하이라이트만 스테이지별로 달라져야 한다.
// 그래서 노드 배열은 이 파일에 단일 정의 하나만 두고(스테이지별 복붙 없음), 각 엣지가
// "몇 번째 스테이지부터 보이는지"(stage)와 "03의 앰버 경로에 속하는지"(lit)를 데이터로 갖는다.
//
// 노드는 heroConstellation.ts처럼 절차적으로 생성하지 않고 손으로 배치했다 — 18개뿐이라
// 절차 생성보다 손 배치가 "02에서 엣지가 이어졌을 때 자연스러운 클러스터"를 의도대로
// 심기 쉽다. 3개 클러스터(A/B/C)는 서로 다른 좌상단·중하단·우상단 영역에 두고, 클러스터끼리는
// 엣지로 잇지 않는다(02에서 "2~3개 클러스터"로 명확히 갈라져 읽히도록). 그 사이에 흩어진
// 필러 노드 2개(n16/n17)는 어느 스테이지에서도 엣지가 없는 진짜 외톨이로 남겨, 01의 "서로
// 무관한 기록들" 인상을 강화하고 02/03에서도 "다 연결되진 않는다"는 현실감을 준다.
//
// 01(연결)에서는 클러스터 A·B 안의 페어 하나씩(e0, e5)만 보여 "이미 느슨하게 이어진 둘"
// 정도로 두고, 나머지는 무연결 점으로 읽히게 한다. 02(구축)는 나머지 엣지가 전부 등장해
// A/B/C 세 클러스터 모양이 완성된다. 03(질문)의 앰버 경로(e5·e6·e8, n6→n7/n6→n8/n8→n10)는
// 클러스터 B 내부의 실제 엣지만 재사용한 4노드 체인이다 — 새 지오메트리를 만들지 않고
// 이미 그어진 엣지 위에만 앰버를 얹는다(히어로 lit path와 동일한 원칙).

export type MiniGraphNodeType = "commit" | "pr" | "issue" | "slack" | "jira" | "person" | "file";

export interface MiniGraphNode {
  id: string;
  x: number;
  y: number;
  type: MiniGraphNodeType;
}

export interface MiniGraphEdge {
  id: string;
  from: string;
  to: string;
  /** 이 엣지가 처음 등장하는 스테이지. 이후 스테이지에도 그대로 남는다(누적). */
  stage: 1 | 2;
  /** 03에서 앰버로 점등되는 경로에 속하는 엣지인지. */
  lit?: boolean;
}

export const HOW_GRAPH_WIDTH = 400;
export const HOW_GRAPH_HEIGHT = 240;

export const HOW_IT_WORKS_NODES: MiniGraphNode[] = [
  // 클러스터 A — 좌측
  { id: "n0", x: 75, y: 50, type: "file" },
  { id: "n1", x: 110, y: 65, type: "commit" },
  { id: "n2", x: 65, y: 95, type: "issue" },
  { id: "n3", x: 105, y: 110, type: "file" },
  { id: "n4", x: 40, y: 70, type: "person" },
  { id: "n5", x: 130, y: 85, type: "pr" },
  // 클러스터 B — 중앙 하단
  { id: "n6", x: 185, y: 140, type: "commit" },
  { id: "n7", x: 230, y: 130, type: "file" },
  { id: "n8", x: 200, y: 175, type: "slack" },
  { id: "n9", x: 245, y: 160, type: "jira" },
  { id: "n10", x: 175, y: 185, type: "file" },
  { id: "n11", x: 235, y: 195, type: "pr" },
  // 클러스터 C — 우측 상단
  { id: "n12", x: 300, y: 45, type: "file" },
  { id: "n13", x: 335, y: 75, type: "issue" },
  { id: "n14", x: 315, y: 100, type: "commit" },
  { id: "n15", x: 355, y: 55, type: "person" },
  // 필러 — 어느 스테이지에서도 엣지 없이 홀로 남는 노드
  { id: "n16", x: 165, y: 35, type: "file" },
  { id: "n17", x: 150, y: 210, type: "commit" },
];

export const HOW_IT_WORKS_EDGES: MiniGraphEdge[] = [
  // 클러스터 A
  { id: "e0", from: "n0", to: "n1", stage: 1 },
  { id: "e1", from: "n0", to: "n2", stage: 2 },
  { id: "e2", from: "n0", to: "n4", stage: 2 },
  { id: "e3", from: "n1", to: "n5", stage: 2 },
  { id: "e4", from: "n2", to: "n3", stage: 2 },
  // 클러스터 B — e5/e6/e8이 03의 앰버 경로(n7–n6–n8–n10)
  { id: "e5", from: "n6", to: "n7", stage: 1, lit: true },
  { id: "e6", from: "n6", to: "n8", stage: 2, lit: true },
  { id: "e7", from: "n7", to: "n9", stage: 2 },
  { id: "e8", from: "n8", to: "n10", stage: 2, lit: true },
  { id: "e9", from: "n9", to: "n11", stage: 2 },
  // 클러스터 C
  { id: "e10", from: "n12", to: "n13", stage: 2 },
  { id: "e11", from: "n12", to: "n15", stage: 2 },
  { id: "e12", from: "n13", to: "n14", stage: 2 },
];
