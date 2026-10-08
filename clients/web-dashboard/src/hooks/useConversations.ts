import axios from "axios";
import { useSyncExternalStore } from "react";
import {
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
  type InfiniteData,
  type QueryClient,
} from "@tanstack/react-query";

import {
  deleteConversation,
  getConversation,
  listConversations,
  listOlderMessages,
  updateConversationTitle,
} from "@/api/conversations";
import type { Conversation, ConversationDetail, ConversationPage } from "@/types/api";
import { queryKeys } from "./queryKeys";

// 첫 메시지 생성 API는 답변이 끝날 때까지 응답하지 않는다. 그 사이에 목록에 보여줄 임시 id.
const OPTIMISTIC_PREFIX = "optimistic:";

export function isOptimisticConversationId(id: string): boolean {
  return id.startsWith(OPTIMISTIC_PREFIX);
}

// 첫 질문의 본문. ChatPage가 언마운트되어도 임시 대화를 다시 열면 로딩 화면을 복원한다.
export type PendingFirstSend = {
  optimisticId: string;
  text: string;
  createdId?: string;
};

const pendingByProject = new Map<string, PendingFirstSend>();
const pendingListeners = new Set<() => void>();

function emitPending() {
  pendingListeners.forEach((listener) => listener());
}

export function setPendingFirstSend(projectId: string, value: PendingFirstSend | null) {
  if (value) pendingByProject.set(projectId, value);
  else pendingByProject.delete(projectId);
  emitPending();
}

export function usePendingFirstSend(projectId: string): PendingFirstSend | null {
  return useSyncExternalStore(
    (listener) => {
      pendingListeners.add(listener);
      return () => pendingListeners.delete(listener);
    },
    () => pendingByProject.get(projectId) ?? null,
    () => null,
  );
}

// 서버 ConversationTitleGenerator와 같이 공백을 접고 80자(코드 포인트)에서 자른다.
function titleFromMessage(content: string): string {
  const title = content.trim().replace(/\s+/g, " ");
  const points = Array.from(title);
  return points.length <= 80 ? title : points.slice(0, 80).join("");
}

function writeConversationPages(
  queryClient: QueryClient,
  projectId: string,
  update: (pages: ConversationPage[]) => ConversationPage[],
) {
  queryClient.setQueryData<InfiniteData<ConversationPage>>(
    queryKeys.conversations(projectId),
    (prev) => {
      const pages = prev?.pages.length
        ? prev.pages
        : [{ items: [], nextCursor: null }];
      return {
        pages: update(pages),
        pageParams: prev?.pageParams.length ? prev.pageParams : [undefined],
      };
    },
  );
}

// 질문을 보낸 즉시 왼쪽 목록 맨 위에 제목을 올린다. 반환한 id로 나중에 서버 대화와 바꾼다.
export function showPendingConversation(
  queryClient: QueryClient,
  projectId: string,
  content: string,
): string {
  const now = new Date().toISOString();
  const item: Conversation = {
    id: `${OPTIMISTIC_PREFIX}${crypto.randomUUID()}`,
    projectId,
    userId: null,
    title: titleFromMessage(content),
    createdAt: now,
    updatedAt: now,
  };
  writeConversationPages(queryClient, projectId, (pages) => {
    const [first, ...rest] = pages;
    return [{ ...first, items: [item, ...first.items] }, ...rest];
  });
  return item.id;
}

export function dropPendingConversation(
  queryClient: QueryClient,
  projectId: string,
  optimisticId: string,
) {
  writeConversationPages(queryClient, projectId, (pages) =>
    pages.map((page) => ({
      ...page,
      items: page.items.filter((item) => item.id !== optimisticId),
    })),
  );
}

// 답변이 도착하면 임시 행을 서버가 준 대화로 바꾼다. 무효화 refetch 전에도 그 행을 누를 수 있다.
export function adoptPendingConversation(
  queryClient: QueryClient,
  projectId: string,
  optimisticId: string,
  created: ConversationDetail,
) {
  const real: Conversation = {
    id: created.id,
    projectId: created.projectId,
    userId: created.userId,
    title: created.title,
    createdAt: created.createdAt,
    updatedAt: created.updatedAt,
  };
  writeConversationPages(queryClient, projectId, (pages) =>
    pages.map((page) => ({
      ...page,
      items: page.items.map((item) => (item.id === optimisticId ? real : item)),
    })),
  );
}

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
    // 없는 대화(404)는 다시 물어도 답이 같다 — 전역 재시도(1회)에서 빼서 화면 전환이
    // 한 번의 왕복만큼 빨라진다. 그 외 오류는 일시적일 수 있으니 전역 설정 그대로 1회 재시도.
    retry: (failureCount, error) =>
      !(axios.isAxiosError(error) && error.response?.status === 404) && failureCount < 1,
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
