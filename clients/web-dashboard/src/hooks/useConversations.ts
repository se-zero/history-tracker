import { useQuery } from "@tanstack/react-query";

import { getConversation, listConversations } from "@/api/conversations";
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
