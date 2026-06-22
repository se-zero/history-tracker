import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { disconnectIntegration, listIntegrations } from "@/api/integrations";
import { queryKeys } from "./queryKeys";

// 프로젝트 연동 목록. 수집 진행상황 카드처럼 주기적 갱신이 필요하면 refetchInterval을 넘긴다.
export function useIntegrations(
  projectId: string,
  options?: { refetchInterval?: number },
) {
  return useQuery({
    queryKey: queryKeys.integrations(projectId),
    queryFn: () => listIntegrations(projectId),
    refetchInterval: options?.refetchInterval,
  });
}

// 연동 해제. 성공 시 연동 목록을 무효화한다(세 카드가 공유).
export function useDisconnectIntegration(projectId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (integrationId: string) =>
      disconnectIntegration(projectId, integrationId),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: queryKeys.integrations(projectId),
      });
    },
  });
}
