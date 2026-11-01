import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import {
  connectGitHubRepository,
  connectSlackWorkspace,
  disconnectIntegration,
  listIntegrations,
  type ConnectGitHubPayload,
  type IntegrationProvider,
} from "@/api/integrations";
import type { Integration } from "@/types/api";
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

// GitHub 저장소 연결(데이터 소스 카드에서 이 프로젝트에 직접 붙인다). 성공하면 응답으로 받은 연동 행을 목록 캐시에
// 바로 넣고 integrations를 무효화한다 — 다시 받기가 실패해도 카드가 "저장소 없음"으로 되돌아가 "연결"을 또 누르게
// 되지 않게 하려는 것이다(그러면 409가 난다). 무효화 promise를 onSuccess에서 반환해, 다시 받을 때까지 isPending을 유지한다.
export function useConnectGitHubRepository(projectId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (payload: ConnectGitHubPayload) => connectGitHubRepository(projectId, payload),
    onSuccess: (integration) => {
      queryClient.setQueryData<Integration[]>(queryKeys.integrations(projectId), (prev) => [
        ...(prev ?? []).filter((existing) => existing.provider !== integration.provider),
        integration,
      ]);
      return queryClient.invalidateQueries({ queryKey: queryKeys.integrations(projectId) });
    },
  });
}

// Slack BYO(xoxp 붙여넣기) 연결. 성공하면 타일이 사라지고 연동 행이 되므로 integrations만 무효화한다.
export function useConnectSlackWorkspace(projectId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (token: string) => connectSlackWorkspace(projectId, token),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.integrations(projectId) });
    },
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
