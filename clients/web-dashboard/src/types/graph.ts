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
