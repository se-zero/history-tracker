import {
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";

import {
  deleteConversation,
  getConversation,
  listConversations,
  listOlderMessages,
  updateConversationTitle,
} from "@/api/conversations";
import type { ConversationDetail } from "@/types/api";
import { queryKeys } from "./queryKeys";

// 프로젝트의 대화 목록(커서 무한 스크롤). select로 펼쳐 컴포넌트는 평면 배열만 본다.
// projectId가 없으면(라우트 전환 중) 돌지 않는다.
export function useConversations(projectId: string | undefined) {
  return useInfiniteQuery({
    queryKey: queryKeys.conversations(projectId),
    queryFn: ({ pageParam }) => listConversations(projectId!, pageParam),
    enabled: Boolean(projectId),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    select: (data) => data.pages.flatMap((page) => page.items),
  });
}

// 위로 스크롤 시 더 오래된 메시지를 불러와 상세 캐시의 앞쪽에 prepend한다.
export function useLoadOlderMessages(
  projectId: string,
  conversationId: string | undefined,
) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (before: string) =>
      listOlderMessages(projectId, conversationId!, before),
    onSuccess: (page) => {
      queryClient.setQueryData<ConversationDetail>(
        queryKeys.conversation(projectId, conversationId),
        (prev) =>
          prev
            ? {
                ...prev,
                messages: [...page.items, ...prev.messages],
                hasMoreMessages: page.hasMore,
                oldestCursor: page.nextCursor,
              }
            : prev,
      );
    },
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
