import { api } from "./client";
import type {
  Conversation,
  ConversationDetail,
  Message,
  MessageExchange,
} from "@/types/api";

export async function listConversations(projectId: string): Promise<Conversation[]> {
  const { data } = await api.get<Conversation[]>(
    `/projects/${projectId}/conversations`,
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

export async function sendMessage(
  projectId: string,
  conversationId: string,
  content: string,
): Promise<MessageExchange> {
  const { data } = await api.post<MessageExchange>(
    `/projects/${projectId}/conversations/${conversationId}/messages`,
    { content },
  );
  return data;
}

export async function listMessages(
  projectId: string,
  conversationId: string,
): Promise<Message[]> {
  const { data } = await api.get<Message[]>(
    `/projects/${projectId}/conversations/${conversationId}/messages`,
  );
  return data;
}
