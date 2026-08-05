// React Query 키를 한곳에서 생성한다. 같은 키 문자열이 여러 컴포넌트에 흩어지면
// 조회와 무효화 대상이 어긋나기 쉬워, 팩토리로 묶어 타입 안전하게 만든다.
export const queryKeys = {
  projects: () => ["projects"] as const,
  conversations: (projectId: string | undefined) =>
    ["conversations", projectId] as const,
  conversation: (
    projectId: string | undefined,
    conversationId: string | undefined,
  ) => ["conversation", projectId, conversationId] as const,
  integrations: (projectId: string) => ["integrations", projectId] as const,
  selectionSteps: (projectId: string, provider: string) =>
    ["selection", projectId, provider, "steps"] as const,
  // prior(앞 단계 선택값 맵)가 키에 들어간다 — 상위 선택이 바뀌면 다른 캐시 엔트리가 된다.
  selectionOptions: (
    projectId: string,
    provider: string,
    stepKey: string,
    prior: Record<string, string>,
  ) => ["selection", projectId, provider, "options", stepKey, prior] as const,
  githubInstallations: () => ["github", "installations"] as const,
  githubRepositories: (installationId: string) =>
    ["github", "installations", installationId, "repositories"] as const,
  githubBranches: (installationId: string, owner: string, repo: string) =>
    ["github", "branches", installationId, owner, repo] as const,
  graph: (projectId: string) => ["graph", projectId] as const,
  graphConstellation: (projectId: string) =>
    ["graph", projectId, "constellation"] as const,
  graphWorkUnit: (projectId: string, nodeId: string) =>
    ["graph", projectId, "workUnit", nodeId] as const,
  graphBuildStatus: (projectId: string) =>
    ["graph", projectId, "buildStatus"] as const,
  graphActivity: (projectId: string) =>
    ["graph", projectId, "activity"] as const,
  actors: (projectId: string) => ["actors", projectId] as const,
  actorDetail: (projectId: string, actorUuid: string) =>
    ["actors", projectId, actorUuid] as const,
  actorDecisions: (projectId: string) => ["actors", projectId, "decisions"] as const,
  graphSubgraph: (projectId: string, messageId: string) =>
    ["graph", projectId, "subgraph", messageId] as const,
  searchConversations: (projectId: string, q: string) =>
    ["search", projectId, "conversations", q] as const,
};
