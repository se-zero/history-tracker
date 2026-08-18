import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import {
  getActorDecisions,
  getActorDetail,
  getActors,
  mergeActors,
  renameActor,
  splitActor,
} from "@/api/actors";
import { queryKeys } from "./queryKeys";

// Actor 조작은 목록·결정 이력·그래프의 관계를 모두 바꾸므로 성공 후 함께 새로 고친다.
function useActorMutation<T>(
  projectId: string,
  mutationFn: (input: T) => Promise<void>,
) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.actors(projectId) });
      queryClient.invalidateQueries({ queryKey: queryKeys.actorDecisions(projectId) });
      queryClient.invalidateQueries({ queryKey: queryKeys.graph(projectId) });
    },
  });
}

export function useActors(projectId: string) {
  return useQuery({
    queryKey: queryKeys.actors(projectId),
    queryFn: () => getActors(projectId),
  });
}

export function useActorDetail(projectId: string, actorUuid: string | null) {
  return useQuery({
    queryKey: queryKeys.actorDetail(projectId, actorUuid ?? ""),
    queryFn: () => getActorDetail(projectId, actorUuid!),
    enabled: Boolean(actorUuid),
  });
}

export function useActorDecisions(projectId: string) {
  return useQuery({
    queryKey: queryKeys.actorDecisions(projectId),
    queryFn: () => getActorDecisions(projectId),
  });
}

export function useMergeActors(projectId: string) {
  return useActorMutation(projectId, (input: {
    uuidA: string;
    uuidB: string;
    note?: string;
  }) => mergeActors(projectId, input));
}

export function useSplitActor(projectId: string) {
  return useActorMutation(projectId, (input: {
    actorUuid: string;
    sourceIds: string[];
  }) => splitActor(projectId, input));
}

export function useRenameActor(projectId: string) {
  return useActorMutation(projectId, (input: {
    actorUuid: string;
    name: string;
  }) => renameActor(projectId, input));
}
