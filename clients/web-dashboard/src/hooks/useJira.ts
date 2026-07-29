import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import {
  completeJiraProject,
  listJiraProjects,
  listJiraSites,
  type CompleteJiraProjectPayload,
} from "@/api/integrations";
import { queryKeys } from "./queryKeys";

// pending(사이트 선택 필요) 상태일 때만 활성화한다 — 미연결 상태에서 호출하면 404다.
export function useJiraSites(projectId: string, enabled: boolean) {
  return useQuery({
    queryKey: queryKeys.jiraSites(projectId),
    queryFn: () => listJiraSites(projectId),
    enabled,
  });
}

export function useJiraProjects(projectId: string, cloudId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.jiraProjects(projectId, cloudId ?? ""),
    queryFn: () => listJiraProjects(projectId, cloudId!),
    enabled: Boolean(cloudId),
  });
}

// 확정은 일반 POST라 Slack 콜백(302 전체 페이지 이동)과 달리 캐시가 저절로 비지 않는다 —
// 명시적으로 integrations를 invalidate해야 배지가 "연결됨"으로 갱신된다.
export function useCompleteJiraProject(projectId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (payload: CompleteJiraProjectPayload) => completeJiraProject(projectId, payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.integrations(projectId) });
    },
  });
}
