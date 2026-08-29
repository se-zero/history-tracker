import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import {
  disconnectIntegration,
  listIntegrations,
  type IntegrationProvider,
} from "@/api/integrations";
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

// 연동 해제. 그 소스에서 수집한 그래프까지 서버에서 삭제되므로 연동 목록뿐 아니라
// 그래프 캐시(작업 단위·활동 등 graph 키 하위 전체)도 무효화해야 화면이 삭제 후 상태를 반영한다.
// 그 소스의 ActorAlias·Actor도 함께 지워지므로 액터 관리 카드(actors 키 하위 전체)도 무효화한다.
export function useDisconnectIntegration(projectId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (provider: IntegrationProvider) =>
      disconnectIntegration(projectId, provider),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.integrations(projectId) });
      queryClient.invalidateQueries({ queryKey: queryKeys.graph(projectId) });
      queryClient.invalidateQueries({ queryKey: queryKeys.actors(projectId) });
    },
  });
}
