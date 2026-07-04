import { api } from "./client";
import type {
  Conversation,
  ConversationDetail,
  ConversationPage,
  ConversationSearchItem,
  MessageExchange,
  MessagePage,
} from "@/types/api";
import type { NodeRef } from "@/types/graph";

// 통합 검색 — 제목·메시지 본문 부분 일치 대화 (updatedAt 역순, 매치 스니펫 포함)
export async function searchConversations(
  projectId: string,
  q: string,
): Promise<ConversationSearchItem[]> {
  const { data } = await api.get<{ items: ConversationSearchItem[] }>(
    `/projects/${projectId}/conversations/search`,
    { params: { q } },
  );
  return data.items;
}

export async function listConversations(
  projectId: string,
  cursor?: string,
): Promise<ConversationPage> {
  const { data } = await api.get<ConversationPage>(
    `/projects/${projectId}/conversations`,
    { params: cursor ? { cursor } : undefined },
  );
  return data;
}

// 위로 스크롤 시 호출 — 주어진 커서보다 더 오래된 메시지 한 페이지(오름차순)
export async function listOlderMessages(
  projectId: string,
  conversationId: string,
  before: string,
): Promise<MessagePage> {
  const { data } = await api.get<MessagePage>(
    `/projects/${projectId}/conversations/${conversationId}/messages`,
    { params: { before } },
  );
  return data;
}

export async function getConversation(
  projectId: string,
  conversationId: string,
): Promise<ConversationDetail> {
  const { data } = await api.get<ConversationDetail>(
    `/projects/${projectId}/conversations/${conversationId}`,
  );
  return data;
}

export async function createConversation(
  projectId: string,
  message: string,
): Promise<ConversationDetail> {
  const { data } = await api.post<ConversationDetail>(
    `/projects/${projectId}/conversations`,
    { message },
  );
  return data;
}

export async function updateConversationTitle(
  projectId: string,
  conversationId: string,
  title: string,
): Promise<Conversation> {
  const { data } = await api.patch<Conversation>(
    `/projects/${projectId}/conversations/${conversationId}`,
    { title },
  );
  return data;
}

export async function deleteConversation(
  projectId: string,
  conversationId: string,
): Promise<void> {
  await api.delete(`/projects/${projectId}/conversations/${conversationId}`);
}

export async function sendMessage(
  projectId: string,
  conversationId: string,
  content: string,
  focusEvidence?: NodeRef[],
): Promise<MessageExchange> {
  const { data } = await api.post<MessageExchange>(
    `/projects/${projectId}/conversations/${conversationId}/messages`,
    { content, focusEvidence },
  );
  return data;
}
