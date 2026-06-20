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

// POST /projects/{id}/graph/build 응답 — 후처리 단계별 생성/갱신된 엣지 수.
export interface GraphBuildResult {
  backfilled: number;
  triggered_by: number;
  discussed_in: number;
  reference: number;
  thread_propagated: number;
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
  code: { label: "Code", cssVar: "var(--node-code)" },
};
