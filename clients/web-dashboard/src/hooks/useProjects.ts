import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { listProjects, reorderProjects } from "@/api/projects";
import type { Project } from "@/types/api";
import { queryKeys } from "./queryKeys";

// 프로젝트 목록 조회. App 루트에서는 인증된 경우에만 돌도록 enabled를 넘긴다.
export function useProjects(options?: { enabled?: boolean }) {
  return useQuery({
    queryKey: queryKeys.projects(),
    queryFn: listProjects,
    enabled: options?.enabled,
  });
}

// 프로젝트 순서 변경. 드래그 즉시 반영되도록 낙관적 업데이트하고 실패 시 롤백한다.
export function useReorderProjects() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (orderedIds: string[]) => reorderProjects(orderedIds),
    onMutate: async (orderedIds) => {
      await queryClient.cancelQueries({ queryKey: queryKeys.projects() });
      const previous = queryClient.getQueryData<Project[]>(queryKeys.projects());
      if (previous) {
        const byId = new Map(previous.map((p) => [p.id, p]));
        const next = orderedIds
          .map((id) => byId.get(id))
          .filter((p): p is Project => p !== undefined);
        // 목록에 없던(동시 생성 등) 프로젝트는 기존 위치를 유지하며 뒤에 붙인다.
        for (const p of previous) if (!orderedIds.includes(p.id)) next.push(p);
        queryClient.setQueryData(queryKeys.projects(), next);
      }
      return { previous };
    },
    onError: (_error, _orderedIds, context) => {
      if (context?.previous) {
        queryClient.setQueryData(queryKeys.projects(), context.previous);
      }
    },
    onSuccess: (serverProjects) => {
      queryClient.setQueryData(queryKeys.projects(), serverProjects);
    },
  });
}
