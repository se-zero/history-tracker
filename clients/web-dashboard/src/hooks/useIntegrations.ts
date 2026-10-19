import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import {
  connectGitHubRepository,
  connectSlackWorkspace,
  disconnectIntegration,
  listIntegrations,
  type ConnectGitHubPayload,
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

// GitHub 저장소 연결(데이터 소스 카드에서 이 프로젝트에 직접 붙인다). 성공하면 integrations를 무효화하는데,
// invalidateQueries의 promise를 onSuccess에서 반환하는 게 핵심이다 — 뮤테이션이 그 promise를 기다리므로
// 연동 목록을 다시 받을 때까지 isPending이 유지된다. 반환하지 않으면 요청이 끝나는 즉시 버튼이 풀리는데
// 카드는 아직 "저장소 없음"이라, 그 사이 "연결"을 한 번 더 눌러 409가 난다.
export function useConnectGitHubRepository(projectId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (payload: ConnectGitHubPayload) => connectGitHubRepository(projectId, payload),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.integrations(projectId) }),
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
