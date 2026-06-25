export type GraphNodeType =
  | "commit"
  | "pr"
  | "issue"
  | "slack"
  | "jira"
  | "actor"
  | "code";

export interface GraphNode {
  id: string;
  type: GraphNodeType;
  title: string;
  meta: string;
  source: string;
  snippet: string;
}

export type GraphEdge = [string, string];

export interface GraphData {
  nodes: GraphNode[];
  edges: GraphEdge[];
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
