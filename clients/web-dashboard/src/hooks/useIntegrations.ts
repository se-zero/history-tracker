import { useQuery } from "@tanstack/react-query";

import { listIntegrations } from "@/api/integrations";
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
