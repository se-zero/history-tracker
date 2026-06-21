import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useParams } from "react-router-dom";

import { StatusView } from "@/components/StatusView";
import { ChatEmpty } from "@/components/chat/ChatEmpty";
import { ChatStream } from "@/components/chat/ChatStream";
import { Composer } from "@/components/chat/Composer";
import { MessageItem, UserMessage } from "@/components/chat/Message";
import { ThinkingState } from "@/components/chat/ThinkingState";
import { createConversation, sendMessage } from "@/api/conversations";
import { useAuth } from "@/auth/AuthProvider";
import { queryKeys } from "@/hooks/queryKeys";
import { useConversation } from "@/hooks/useConversations";
import type { ConversationDetail, Project } from "@/types/api";

export function ChatPage({ project }: { project: Project }) {
  const { conversationId } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { user } = useAuth();

  const conversationQuery = useConversation(project.id, conversationId);

  const messages = conversationQuery.data?.messages ?? [];

  // 전송 직후 화면을 즉시 전환하기 위해 방금 보낸 메시지를 낙관적으로 들고 있는다. 응답이 오면 비운다.
  const [pendingMessage, setPendingMessage] = useState<string | null>(null);
  const [draft, setDraft] = useState("");
  const [sendError, setSendError] = useState(false);

  // 전송 실패 시 친 내용이 사라지지 않도록 입력창에 복구하고 에러를 표시한다.
  // 그 사이 새로 입력한 내용이 있으면 덮어쓰지 않는다.
  const restoreOnError = (failedText: string) => {
    setPendingMessage(null);
    setDraft((current) => (current.trim() ? current : failedText));
    setSendError(true);
  };

  const createMutation = useMutation({
    mutationFn: (firstMessage: string) =>
      createConversation(project.id, firstMessage),
    onSuccess: (detail) => {
      queryClient.invalidateQueries({
        queryKey: queryKeys.conversations(project.id),
      });
      queryClient.setQueryData(
        queryKeys.conversation(project.id, detail.id),
        detail,
      );
      setPendingMessage(null);
      navigate(`/projects/${project.id}/chat/${detail.id}`, { replace: true });
    },
    onError: (_error, firstMessage) => restoreOnError(firstMessage),
  });

  const sendMutation = useMutation({
    mutationFn: (content: string) =>
      sendMessage(project.id, conversationId!, content),
    onSuccess: (exchange) => {
      // 응답 쌍을 캐시에 바로 반영해 낙관적 메시지를 비울 때 공백이 생기지 않게 한다.
      queryClient.setQueryData<ConversationDetail>(
        queryKeys.conversation(project.id, conversationId),
        (prev) =>
          prev
            ? {
                ...prev,
                messages: [
                  ...prev.messages,
                  exchange.userMessage,
                  exchange.assistantMessage,
                ],
              }
            : prev,
      );
      queryClient.invalidateQueries({
        queryKey: queryKeys.conversations(project.id),
      });
      setPendingMessage(null);
    },
    onError: (_error, content) => restoreOnError(content),
  });

  const pending = createMutation.isPending || sendMutation.isPending;

  const handleSend = (text: string) => {
    const trimmed = text.trim();
    if (!trimmed || pending) return;
    setSendError(false);
    setPendingMessage(trimmed);
    setDraft(""); // 입력은 즉시 비우되, 실패하면 restoreOnError로 되돌린다
    if (conversationId) {
      sendMutation.mutate(trimmed);
    } else {
      createMutation.mutate(trimmed);
    }
  };

  const handleDraftChange = (value: string) => {
    setDraft(value);
    if (sendError) setSendError(false);
  };

  return (
    <div className="chat-wrap">
      <div className="chat">
        {pendingMessage !== null ? (
          <ChatStream>
            {messages.map((m) => (
              <MessageItem key={m.id} message={m} user={user} />
            ))}
            <UserMessage content={pendingMessage} user={user} />
            <ThinkingState />
          </ChatStream>
        ) : !conversationId ? (
          <ChatEmpty project={project} onPick={handleSend} />
        ) : conversationQuery.isLoading ? (
          <StatusView tone="loading" description="메시지를 불러오는 중…" />
        ) : conversationQuery.isError ? (
          <StatusView
            tone="error"
            title="대화를 불러오지 못했어요"
            description="다시 시도하거나 다른 대화를 선택해 보세요."
            action={
              <button
                className="btn"
                onClick={() => conversationQuery.refetch()}
              >
                다시 시도
              </button>
            }
          />
        ) : (
          <ChatStream>
            {messages.map((m) => (
              <MessageItem key={m.id} message={m} user={user} />
            ))}
          </ChatStream>
        )}
        <Composer
          project={project}
          value={draft}
          onChange={handleDraftChange}
          onSubmit={() => handleSend(draft)}
          disabled={pending}
          showThinkingHint={pending}
          error={
            sendError
              ? "전송에 실패했어요. 입력을 그대로 두었으니 다시 시도해 주세요."
              : null
          }
        />
      </div>
    </div>
  );
}
