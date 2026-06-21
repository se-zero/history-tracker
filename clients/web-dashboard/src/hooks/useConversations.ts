import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import {
  deleteConversation,
  getConversation,
  listConversations,
  updateConversationTitle,
} from "@/api/conversations";
import { queryKeys } from "./queryKeys";

// 프로젝트의 대화 목록. projectId가 없으면(라우트 전환 중) 돌지 않는다.
export function useConversations(projectId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.conversations(projectId),
    queryFn: () => listConversations(projectId!),
    enabled: Boolean(projectId),
  });
}

// 단일 대화 상세(메시지 포함). conversationId가 있을 때만 조회한다.
export function useConversation(
  projectId: string,
  conversationId: string | undefined,
) {
  return useQuery({
    queryKey: queryKeys.conversation(projectId, conversationId),
    queryFn: () => getConversation(projectId, conversationId!),
    enabled: Boolean(conversationId),
  });
}

// 대화 제목 변경. 목록과 해당 대화 상세를 무효화한다.
export function useRenameConversation(projectId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, title }: { id: string; title: string }) =>
      updateConversationTitle(projectId, id, title),
    onSuccess: (updated) => {
      queryClient.invalidateQueries({
        queryKey: queryKeys.conversations(projectId),
      });
      queryClient.invalidateQueries({
        queryKey: queryKeys.conversation(projectId, updated.id),
      });
    },
  });
}

// 대화 삭제. 목록을 무효화하고 삭제된 대화 상세 캐시를 제거한다.
export function useDeleteConversation(projectId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => deleteConversation(projectId, id),
    onSuccess: (_data, id) => {
      queryClient.invalidateQueries({
        queryKey: queryKeys.conversations(projectId),
      });
      queryClient.removeQueries({
        queryKey: queryKeys.conversation(projectId, id),
      });
    },
  });
}
