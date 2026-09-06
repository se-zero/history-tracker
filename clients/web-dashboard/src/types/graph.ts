export type GraphNodeType =
  | "commit"
  | "pr"
  | "issue"
  | "communication"
  | "actor"
  | "code"
  | "doc";

// focus 질의로 노드를 가리키는 도메인 키 — ai-engine 도구/evidence 어휘(프론트 GraphNodeType과 다름).
// 질의 도구 대상이 아닌 노드(actor/code)는 ref 없음(null) → 텍스트 폴백 처리.
export type NodeRef = {
  type: "commit" | "pull_request" | "issue" | "message" | "document";
  id: string;
};

export interface GraphNode {
  id: string;
  type: GraphNodeType;
  title: string;
  meta: string;
  source: string;
  snippet: string;
  ref?: NodeRef | null;
}

// 입력창에 첨부된 focus 노드 — 전송 시 ref만 focus_evidence로 보내고, label/nodeType은 칩 표시용.
export type AttachedNode = {
  ref: NodeRef;
  label: string;
  nodeType: GraphNodeType;
};

export interface GraphEdge {
  source: string;
  target: string;
  kind: string; // 관계 타입 (REFERENCE, CONTAINS, TRIGGERED_BY …)
  // 관계의 판별 방식(명시 참조 vs 임베딩 추론) — GraphNode.source(github/slack 같은 제품명)와는
  // 별개 축이다. 서버 Neo4j 속성명은 r.source지만 그 이름과 헷갈리지 않도록 계약에서 바꿨다.
  method: "text" | "semantic" | "propagated" | null;
  confidence: number | null;
  section: string | null;
}

export interface GraphData {
  nodes: GraphNode[];
  edges: GraphEdge[];
}

// 근거 관계 4종 — 소스 노드 자체가 아니라 다른 노드를 "근거로" 잇는 관계다.
// 이 목록에 없는 관계(CONTAINS 등)는 소스 시스템이 준 구조적 사실이라 늘 confirmed다.
const EVIDENCE_KINDS: ReadonlySet<string> = new Set([
  "TRIGGERED_BY",
  "REFERENCE",
  "DESCRIBED_IN",
  "DISCUSSED_IN",
]);

export type EdgeCertainty = "confirmed" | "inferred";

// 엣지 확실성 판정 — 서버가 대표 엣지를 고를 때 쓰는 것과 같은 어휘(kind/method)를 그대로 쓴다.
//
// confidence 유무로 판정하지 않는 이유: DISCUSSED_IN의 text 엣지는 의도적으로 confidence를
// 부여하지 않아, confidence만 보면 "낮은 확실성"으로 오판된다.
//
// method가 없는 구 데이터(N0 이전 REFERENCE)는 추측으로 본다 — 서버가 coalesce(r.source,
// 'semantic') 규약으로 semantic 취급하는 것과 같은 판정이다(graph/maintenance.py,
// docs/notion-integration.md §2-7). 별도의 "미상" 상태를 두면 실데이터의 상당수가 그리로
// 빠져 화면에 의미 없는 세 번째 분류가 생긴다.
//
// 이 판정을 서버가 아니라 프론트에 두는 이유: 임계값을 실험할 때 프론트 1티어만 고치면 끝나야
// 한다. 서버가 파생 분류(confirmed/inferred)를 내려보내면, 기준을 만질 때마다 ai-engine·
// backend·프론트 3티어를 다시 돌아야 한다.
export function edgeCertainty(edge: Pick<GraphEdge, "kind" | "method">): EdgeCertainty {
  if (!EVIDENCE_KINDS.has(edge.kind)) return "confirmed";
  if (edge.method === "text") return "confirmed";
  return "inferred"; // semantic·propagated, 그리고 method 없는 구 데이터
}

// 두 노드 id로 무향 엣지의 맵 키를 만든다. 사전순으로 고정해 (a,b)와 (b,a)가 같은 키가 되게 한다 —
// GraphEdge.source/target 방향은 배치·조회 목적에는 중요하지 않기 때문이다.
export function edgePairKey(a: string, b: string): string {
  return a < b ? `${a} ${b}` : `${b} ${a}`;
}

// 그래프 확인 화면용 그래프 — GraphData에 작업 단위 목록을 더한다.
// workUnitIds가 작업 단위로 그릴 노드다. 어떤 노드가 작업 단위인지는 서버가 정한다 —
// 프론트가 노드 타입으로 하드코딩하면 PR이 0건인 프로젝트에서 작업 단위가 사라진다.
export interface WorkUnitsData extends GraphData {
  workUnitIds: string[];
}

// 답변 evidence 관련 서브그래프 — GraphData에 seeds를 더한다.
// seeds는 입력 evidence 순서에 정렬된 시드 노드 id(미해석은 null) — 인용 카드 ↔ 노드 매핑용.
export interface SubgraphData extends GraphData {
  seeds: (string | null)[];
}

// 후처리 단계별 생성/갱신된 엣지 수 — 빌드 상태가 succeeded일 때 채워진다.
export interface GraphBuildResult {
  backfilled: number;
  triggered_by: number;
  discussed_in: number;
  reference: number;
  thread_propagated: number;
  // 문서 아키타입(Notion) 시맨틱 링크 — verify 여부와 무관하게 항상 자동구축(임베딩 전용)이다.
  document_reference: number;
  described_in_document: number;
}

export type GraphBuildState = "idle" | "running" | "succeeded" | "failed";

// POST /projects/{id}/graph/build(202) · GET .../graph/build/status 응답.
// 빌드는 백그라운드라 트리거는 running을 반환하고, 완료는 status 폴링으로 확인한다.
// result는 succeeded일 때만, error는 failed일 때만 채워진다.
export interface GraphBuildStatus {
  state: GraphBuildState;
  verify: boolean | null;
  startedAt: string | null;
  result: GraphBuildResult | null;
  error: string | null;
}

// GET /projects/{id}/graph/activity 응답 — 프론트 채팅 게이팅용.
// build/status와 별개 신호다: 최초 수집중(collecting)·수동 재구축중(building)이면 질문을 막는다.
export type GraphActivityState = "idle" | "collecting" | "building";

export interface GraphActivity {
  state: GraphActivityState;
}

export const NODE_TYPE_INFO: Record<
  GraphNodeType,
  { label: string; cssVar: string }
> = {
  commit: { label: "Commit", cssVar: "var(--node-commit)" },
  pr: { label: "PR", cssVar: "var(--node-pr)" },
  issue: { label: "Issue", cssVar: "var(--node-issue)" },
  communication: { label: "Communication", cssVar: "var(--node-communication)" },
  actor: { label: "Person", cssVar: "var(--node-actor)" },
  code: { label: "File", cssVar: "var(--node-code)" },
  doc: { label: "Document", cssVar: "var(--node-doc)" },
};
