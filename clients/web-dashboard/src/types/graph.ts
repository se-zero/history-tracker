export type GraphNodeType =
  | "commit"
  | "pr"
  | "issue"
  | "slack"
  | "jira"
  | "actor"
  | "code";

// focus 질의로 노드를 가리키는 도메인 키 — ai-engine 도구/evidence 어휘(프론트 GraphNodeType과 다름).
// 질의 도구 대상이 아닌 노드(actor/code)는 ref 없음(null) → 텍스트 폴백 처리.
export type NodeRef = {
  type: "commit" | "pull_request" | "issue" | "message";
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

export type GraphEdge = [string, string];

export interface GraphData {
  nodes: GraphNode[];
  edges: GraphEdge[];
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
  slack: { label: "Slack", cssVar: "var(--node-slack)" },
  jira: { label: "Jira", cssVar: "var(--node-jira)" },
  actor: { label: "Person", cssVar: "var(--node-actor)" },
  code: { label: "File", cssVar: "var(--node-code)" },
};
