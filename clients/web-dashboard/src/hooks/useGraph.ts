import { useEffect, useMemo, useRef } from "react";
import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";

import {
  getGraphActivity,
  getGraphBuildStatus,
  getMessageSubgraph,
  getProjectGraph,
  getProjectWorkUnits,
  getWorkUnitNeighborhood,
  rebuildProjectGraph,
} from "@/api/graph";
import { extractStructured } from "@/components/chat/messageStructured";
import type { Message } from "@/types/api";
import type { GraphActivityState, GraphEdge, GraphNode } from "@/types/graph";
import { queryKeys } from "./queryKeys";

export function useGraph(projectId: string) {
  return useQuery({
    queryKey: queryKeys.graph(projectId),
    queryFn: () => getProjectGraph(projectId),
  });
}

// 그래프 확인 화면용 조회 — 작업 단위 전량 + 구성 노드 최신 N개.
// 키가 ["graph", projectId, "workUnits"]이라 빌드/수집 완료 시의
// invalidateQueries(queryKeys.graph)에 prefix로 함께 걸린다(별도 무효화 불필요).
export function useWorkUnits(projectId: string) {
  return useQuery({
    queryKey: queryKeys.graphWorkUnits(projectId),
    queryFn: () => getProjectWorkUnits(projectId),
  });
}

/**
 * 드릴인으로 펼친 작업 단위들의 이웃을 모아 하나의 {nodes, edges}로 준다.
 *
 * 작업 단위별 결과는 불변이라 오래 캐시한다(같은 묶음을 다시 열어도 재요청 없음).
 * 병합 결과는 레이아웃 useMemo의 입력이 되므로, 매 렌더 새 객체가 나오면 레이아웃이
 * 매번 다시 계산된다 — 그래서 데이터가 실제로 갱신됐을 때만(dataUpdatedAt) 새로 만든다.
 */
export function useWorkUnitNeighborhoods(projectId: string, nodeIds: string[]) {
  const results = useQueries({
    queries: nodeIds.map((nodeId) => ({
      queryKey: queryKeys.graphWorkUnit(projectId, nodeId),
      queryFn: () => getWorkUnitNeighborhood(projectId, nodeId),
      staleTime: Infinity,
    })),
  });

  const signature = results.map((r) => r.dataUpdatedAt).join("|");
  const isFetching = results.some((r) => r.isFetching);

  const merged = useMemo(() => {
    const nodes: GraphNode[] = [];
    const edges: GraphEdge[] = [];
    for (const r of results) {
      if (!r.data) continue;
      nodes.push(...r.data.nodes);
      edges.push(...r.data.edges);
    }
    return { nodes, edges };
    // results는 매 렌더 새 배열이라 deps로 쓸 수 없다 — 데이터 갱신 시각으로 대체한다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [signature]);

  return { ...merged, isFetching };
}

// 답변의 evidence를 {type, id}로 추린다 — 인용 카드 렌더와 동일한 순서를 유지한다.
function evidenceRefs(message: Message | null): Array<{ type: string; id: string }> {
  if (!message) return [];
  const structured = extractStructured(message.metadata);
  return (structured?.evidence ?? []).map((e) => ({ type: e.type, id: e.id }));
}

// 활성 답변(메시지)의 관련 서브그래프 — 메시지별 결과는 불변이라 길게 캐시한다.
// evidence가 없으면 호출하지 않는다(enabled). seeds는 evidence 순서에 정렬돼 인용 카드와 매핑된다.
export function useMessageSubgraph(projectId: string, message: Message | null) {
  const evidence = useMemo(() => evidenceRefs(message), [message]);
  return useQuery({
    queryKey: queryKeys.graphSubgraph(projectId, message?.id ?? "none"),
    queryFn: () => getMessageSubgraph(projectId, evidence),
    enabled: evidence.length > 0,
    staleTime: Infinity,
  });
}

// 빌드 상태 — 프로젝트의 현재 상태를 그대로 반영한다(개인 프로젝트라 빌드의 주인은 항상 본인).
// 진입 시 한 번 조회하고, running 동안만 3초 간격으로 폴링한다(종료 상태면 멈춤).
// succeeded 전이 시 그래프 쿼리를 무효화해 새로 생긴 연결을 반영한다(같은 빌드당 1회, started_at으로 중복 방지).
export function useGraphBuildStatus(projectId: string) {
  const queryClient = useQueryClient();
  const lastInvalidatedRef = useRef<string | null>(null);

  const query = useQuery({
    queryKey: queryKeys.graphBuildStatus(projectId),
    queryFn: () => getGraphBuildStatus(projectId),
    refetchInterval: (q) => (q.state.data?.state === "running" ? 3000 : false),
  });

  useEffect(() => {
    const data = query.data;
    if (
      data?.state === "succeeded" &&
      data.startedAt != null &&
      data.startedAt !== lastInvalidatedRef.current
    ) {
      lastInvalidatedRef.current = data.startedAt;
      queryClient.invalidateQueries({ queryKey: queryKeys.graph(projectId) });
    }
  }, [query.data, projectId, queryClient]);

  return query;
}

// 그래프 활동 상태 — 프론트 채팅 게이팅용(최초 수집중/수동 재구축중이면 질문 차단).
// 활동 중(idle 아님)엔 5초, idle이면 15초로 폴링해 새 활동을 감지한다.
// 활동중→idle 전이 시 그래프 쿼리를 무효화해 새로 쌓인 노드 수를 반영한다(게이트 자동 해제).
export function useGraphActivity(projectId: string) {
  const queryClient = useQueryClient();
  const prevStateRef = useRef<GraphActivityState | null>(null);

  const query = useQuery({
    queryKey: queryKeys.graphActivity(projectId),
    queryFn: () => getGraphActivity(projectId),
    refetchInterval: (q) =>
      q.state.data && q.state.data.state !== "idle" ? 5000 : 15000,
  });

  useEffect(() => {
    const state = query.data?.state;
    if (!state) return;
    const prev = prevStateRef.current;
    prevStateRef.current = state;
    if (prev && prev !== "idle" && state === "idle") {
      queryClient.invalidateQueries({ queryKey: queryKeys.graph(projectId) });
    }
  }, [query.data, projectId, queryClient]);

  return query;
}

export function useRebuildGraph(projectId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (verify: boolean) => rebuildProjectGraph(projectId, verify),
    // 202 응답(현재 상태)을 폴링 쿼리에 시드 → running이면 refetchInterval이 즉시 폴링을 재개한다.
    onSuccess: (status) => {
      queryClient.setQueryData(queryKeys.graphBuildStatus(projectId), status);
    },
  });
}
