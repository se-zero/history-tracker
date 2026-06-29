import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useParams } from "react-router-dom";

import { Icons } from "@/components/Icons";
import { StatusView } from "@/components/StatusView";
import { ChatEmpty } from "@/components/chat/ChatEmpty";
import { ChatStream } from "@/components/chat/ChatStream";
import { Composer } from "@/components/chat/Composer";
import {
  MessageItem,
  UserMessage,
  type CitationLink,
} from "@/components/chat/Message";
import { RelatedGraphPanel } from "@/components/chat/RelatedGraphPanel";
import { ThinkingState } from "@/components/chat/ThinkingState";
import { createConversation, sendMessage } from "@/api/conversations";
import { useAuth } from "@/auth/AuthProvider";
import { queryKeys } from "@/hooks/queryKeys";
import {
  useConversation,
  useLoadOlderMessages,
} from "@/hooks/useConversations";
import { useMessageSubgraph } from "@/hooks/useGraph";
import type { ConversationDetail, Message, Project } from "@/types/api";
import type { GraphNode } from "@/types/graph";

export function ChatPage({ project }: { project: Project }) {
  const { conversationId } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { user } = useAuth();

  const conversationQuery = useConversation(project.id, conversationId);
  const detail = conversationQuery.data;
  const messages = detail?.messages ?? [];

  const loadOlder = useLoadOlderMessages(project.id, conversationId);

  // onReachTop을 안정적인 콜백으로 유지(observer 재구독 방지). 최신 값은 ref로 읽는다.
  // loadingRef: isPending은 리렌더 후에야 갱신돼, 첫 mutate 직후 짧은 창에 sentinel이 다시
  // 발화하면 같은 커서로 중복 로드된다. 동기 플래그로 그 창을 막는다.
  const loadingRef = useRef(false);
  const reachTopRef = useRef<() => void>(() => {});
  reachTopRef.current = () => {
    if (loadingRef.current || !detail?.hasMoreMessages || !detail.oldestCursor) return;
    loadingRef.current = true;
    loadOlder.mutate(detail.oldestCursor, {
      onSettled: () => {
        loadingRef.current = false;
      },
    });
  };
  const handleReachTop = useCallback(() => reachTopRef.current(), []);

  // 전송 직후 화면을 즉시 전환하기 위해 방금 보낸 메시지를 낙관적으로 들고 있는다. 응답이 오면 비운다.
  const [pendingMessage, setPendingMessage] = useState<string | null>(null);
  const [draft, setDraft] = useState("");
  const [sendError, setSendError] = useState(false);

  // 관련 그래프 패널 — 열림 여부는 사용자 선호라 localStorage에 영속한다.
  const [panelOpen, setPanelOpen] = useState(
    () => localStorage.getItem("chat:graphPanel") === "1",
  );
  useEffect(() => {
    localStorage.setItem("chat:graphPanel", panelOpen ? "1" : "0");
  }, [panelOpen]);
  // 화면 최상단에 보이는 답변(ChatStream이 통지) + 그 그래프에서 선택된 노드.
  const [activeMessageId, setActiveMessageId] = useState<string | null>(null);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  // 활성 답변이 바뀌면 이전 답변 그래프의 노드 선택을 비운다.
  useEffect(() => setSelectedNodeId(null), [activeMessageId]);

  const showPanel = panelOpen && !!conversationId;
  const activeMessage = useMemo(
    () =>
      messages.find((m) => m.id === activeMessageId && m.role === "ASSISTANT") ??
      null,
    [messages, activeMessageId],
  );
  // 인용 카드(seeds)와 패널이 같은 결과를 공유해야 하므로 조회는 페이지에서 한 번만 한다.
  const subgraphQuery = useMessageSubgraph(project.id, showPanel ? activeMessage : null);

  const handleAddToChat = (node: GraphNode) => {
    setDraft((d) => (d.trim() ? d + " " + node.title : node.title));
  };

  // 패널이 열린 활성 답변에만 인용 카드 ↔ 노드 연동 정보를 내려준다.
  const citationFor = (m: Message): CitationLink | undefined =>
    showPanel && m.id === activeMessageId && m.role === "ASSISTANT"
      ? {
          seeds: subgraphQuery.data?.seeds ?? [],
          selectedNodeId,
          onSelectNode: setSelectedNodeId,
        }
      : undefined;

  const renderMessages = () =>
    messages.map((m) => (
      <MessageItem key={m.id} message={m} user={user} citation={citationFor(m)} />
    ));

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
    onSuccess: (created) => {
      queryClient.invalidateQueries({
        queryKey: queryKeys.conversations(project.id),
      });
      queryClient.setQueryData(
        queryKeys.conversation(project.id, created.id),
        created,
      );
      setPendingMessage(null);
      navigate(`/projects/${project.id}/chat/${created.id}`, { replace: true });
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

  const streamProps = {
    conversationId,
    messages,
    pending: pendingMessage !== null,
    isLoadingOlder: loadOlder.isPending,
    olderError: loadOlder.isError,
    onReachTop: handleReachTop,
    onActiveMessageChange: setActiveMessageId,
  };

  return (
    <div className={"chat-wrap" + (showPanel ? " with-panel" : "")}>
      {conversationId && !panelOpen && (
        <button
          className="graph-toggle"
          onClick={() => setPanelOpen(true)}
          title="관련 그래프 보기"
        >
          <Icons.Graph size={15} /> 관련 그래프
        </button>
      )}
      <div className="chat">
        {pendingMessage !== null ? (
          <ChatStream {...streamProps}>
            {renderMessages()}
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
          <ChatStream {...streamProps}>{renderMessages()}</ChatStream>
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
      {showPanel && (
        <RelatedGraphPanel
          data={subgraphQuery.data}
          isLoading={subgraphQuery.isLoading}
          isError={subgraphQuery.isError}
          onRetry={() => subgraphQuery.refetch()}
          selectedNodeId={selectedNodeId}
          onSelectNode={setSelectedNodeId}
          onAddToChat={handleAddToChat}
          onClose={() => setPanelOpen(false)}
        />
      )}
    </div>
  );
}
