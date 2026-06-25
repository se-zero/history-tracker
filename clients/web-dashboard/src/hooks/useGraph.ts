import { useEffect, useRef } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import {
  getGraphBuildStatus,
  getProjectGraph,
  rebuildProjectGraph,
} from "@/api/graph";
import { queryKeys } from "./queryKeys";

export function useGraph(projectId: string) {
  return useQuery({
    queryKey: queryKeys.graph(projectId),
    queryFn: () => getProjectGraph(projectId),
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
