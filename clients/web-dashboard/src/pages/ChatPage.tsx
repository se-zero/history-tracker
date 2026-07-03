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
import { useGraph, useGraphActivity, useMessageSubgraph } from "@/hooks/useGraph";
import { useIntegrations } from "@/hooks/useIntegrations";
import type { ConversationDetail, Message, Project } from "@/types/api";
import type { GraphNode } from "@/types/graph";

// 관련 그래프 패널 너비 한계(px). 채팅 영역이 너무 좁아지지 않게 드래그 시 동적 상한도 적용한다.
const MIN_PANEL_W = 280;
const MAX_PANEL_W = 680;
const clampPanelWidth = (w: number) =>
  Math.min(MAX_PANEL_W, Math.max(MIN_PANEL_W, w));

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
  // 어느 대화에 속한 낙관적 메시지인지 함께 들고 있어, 응답 대기 중 다른 대화로 이동해도
  // 그 대화에 스피너/입력 거품이 새어 보이지 않게 한다(새 대화는 conversationId가 undefined).
  const [pendingMessage, setPendingMessage] = useState<{
    conversationId: string | undefined;
    text: string;
  } | null>(null);
  const [draft, setDraft] = useState("");
  const [sendError, setSendError] = useState(false);

  // 관련 그래프 패널 — 열림 여부는 사용자 선호라 localStorage에 영속한다.
  const [panelOpen, setPanelOpen] = useState(
    () => localStorage.getItem("chat:graphPanel") === "1",
  );
  useEffect(() => {
    localStorage.setItem("chat:graphPanel", panelOpen ? "1" : "0");
  }, [panelOpen]);
  // 패널 너비도 사용자 선호라 영속한다. 드래그 중엔 transition을 꺼 끊김을 없앤다.
  const [panelWidth, setPanelWidth] = useState(() =>
    clampPanelWidth(Number(localStorage.getItem("chat:graphPanelWidth")) || 360),
  );
  const [resizing, setResizing] = useState(false);
  const chatWrapRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    localStorage.setItem("chat:graphPanelWidth", String(panelWidth));
  }, [panelWidth]);
  // 화면 최상단에 보이는 답변(ChatStream이 통지) + 그 그래프에서 선택된 노드.
  const [activeMessageId, setActiveMessageId] = useState<string | null>(null);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  // 인용 카드 hover로 강조 중인 노드, 그리고 그 강조를 어느 답변의 어느 카드에서 요청했는지.
  const [hoveredNodeId, setHoveredNodeId] = useState<string | null>(null);
  const [pendingHover, setPendingHover] = useState<{
    messageId: string;
    index: number;
  } | null>(null);
  // 닫힌 패널/다른 답변 카드를 클릭하면, 그 답변의 서브그래프가 로드된 뒤 노드 정보를 연다.
  const [pendingSelect, setPendingSelect] = useState<{
    messageId: string;
    index: number;
  } | null>(null);
  // 비활성 답변 카드 hover 시 그래프 전환을 살짝 늦춰 스쳐 지나가는 오발동을 막는다.
  const hoverTimer = useRef<number | null>(null);
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

  // 카드 hover: 활성 답변이면 바로 강조, 아니면 인텐트 딜레이 후 그 답변으로 전환한다.
  // 어느 쪽이든 pendingHover로 표시해 두고, 그 답변의 서브그래프가 준비되면 노드 강조로 해석한다.
  const handleCardHover = (messageId: string, index: number) => {
    if (hoverTimer.current) {
      clearTimeout(hoverTimer.current);
      hoverTimer.current = null;
    }
    if (messageId === activeMessageId) {
      setPendingHover({ messageId, index });
    } else {
      hoverTimer.current = window.setTimeout(() => {
        hoverTimer.current = null;
        setActiveMessageId(messageId); // 그래프를 이 답변으로 전환(치워도 유지됨)
        setPendingHover({ messageId, index });
      }, 200);
    }
  };
  // 마우스를 치우면 강조만 해제하고 전환된 그래프는 유지한다. 아직 안 뜬 전환 타이머는 취소.
  const handleCardLeave = () => {
    if (hoverTimer.current) {
      clearTimeout(hoverTimer.current);
      hoverTimer.current = null;
    }
    setPendingHover(null);
    setHoveredNodeId(null);
  };
  // 카드 클릭: 패널이 열린 현재 답변의 해석된 노드면 정보↔그래프 토글, 그 외(닫힘/다른 답변)는
  // 패널을 열고 그 답변으로 전환한 뒤 로드되면 노드 정보를 연다.
  const handleCardClick = (messageId: string, index: number) => {
    if (panelOpen && messageId === activeMessageId) {
      const nodeId = subgraphQuery.data?.seeds[index] ?? null;
      if (nodeId == null) return;
      setSelectedNodeId(nodeId === selectedNodeId ? null : nodeId);
      return;
    }
    if (!panelOpen) setPanelOpen(true);
    setActiveMessageId(messageId);
    setPendingSelect({ messageId, index });
  };
  // 강조 대상 답변의 서브그래프가 준비되면(전환 직후 로딩 포함) 해당 시드 노드를 강조한다.
  useEffect(() => {
    if (!pendingHover || pendingHover.messageId !== activeMessageId) return;
    const data = subgraphQuery.data;
    if (!data) return;
    setHoveredNodeId(data.seeds[pendingHover.index] ?? null);
    setPendingHover(null);
  }, [pendingHover, activeMessageId, subgraphQuery.data]);
  // 클릭으로 연 답변의 서브그래프가 준비되면 해당 노드 정보(NodeDetail)를 연다.
  useEffect(() => {
    if (!pendingSelect || pendingSelect.messageId !== activeMessageId) return;
    const data = subgraphQuery.data;
    if (!data) return;
    setSelectedNodeId(data.seeds[pendingSelect.index] ?? null);
    setPendingSelect(null);
  }, [pendingSelect, activeMessageId, subgraphQuery.data]);
  // 언마운트 시 전환 타이머 정리.
  useEffect(
    () => () => {
      if (hoverTimer.current) clearTimeout(hoverTimer.current);
    },
    [],
  );

  const handleAddToChat = (node: GraphNode) => {
    setDraft((d) => (d.trim() ? d + " " + node.title : node.title));
  };

  // 패널 왼쪽 핸들 드래그로 너비 조절 — wrap 오른쪽 끝 기준으로 너비를 계산한다.
  // 채팅 영역이 360px 미만으로 좁아지지 않게 드래그 시작 시점에 상한을 정한다.
  const startResize = (e: React.PointerEvent) => {
    e.preventDefault();
    const wrap = chatWrapRef.current;
    if (!wrap) return;
    const rect = wrap.getBoundingClientRect();
    const wrapRight = rect.right;
    const maxW = Math.max(MIN_PANEL_W, Math.min(MAX_PANEL_W, rect.width - 360));
    setResizing(true);
    document.body.style.userSelect = "none";
    document.body.style.cursor = "col-resize";
    const onMove = (ev: PointerEvent) => {
      setPanelWidth(Math.min(maxW, Math.max(MIN_PANEL_W, wrapRight - ev.clientX)));
    };
    const onUp = () => {
      setResizing(false);
      document.body.style.userSelect = "";
      document.body.style.cursor = "";
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", onUp);
    };
    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onUp);
  };

  // 대화가 있으면 모든 답변 카드에 연동 정보를 내려준다 — 패널이 닫혀 있어도 카드 클릭으로 열 수 있게.
  // seeds·hover 강조는 패널이 열린 활성 답변일 때만 의미가 있다.
  const citationFor = (m: Message): CitationLink | undefined =>
    conversationId && m.role === "ASSISTANT"
      ? {
          panelOpen,
          isActive: m.id === activeMessageId,
          seeds: m.id === activeMessageId ? subgraphQuery.data?.seeds ?? [] : [],
          selectedNodeId,
          hoveredNodeId: m.id === activeMessageId ? hoveredNodeId : null,
          onCardClick: (index) => handleCardClick(m.id, index),
          onHoverCard: (index) => handleCardHover(m.id, index),
          onLeaveCard: handleCardLeave,
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
    // 대화 id를 변수로 함께 넘긴다 — 응답 대기 중 다른 대화로 이동해 conversationId가 바뀌어도
    // 응답이 원래 대화의 캐시에 정확히 반영되도록(엉뚱한 대화에 답변이 끼는 것 방지).
    mutationFn: ({ cid, content }: { cid: string; content: string }) =>
      sendMessage(project.id, cid, content),
    onSuccess: (exchange, { cid }) => {
      // 응답 쌍을 캐시에 바로 반영해 낙관적 메시지를 비울 때 공백이 생기지 않게 한다.
      // 응답 대기 중 다른 대화를 다녀오면 그 사이 이 대화 상세가 refetch되어 서버 메시지가
      // 이미 들어와 있을 수 있다. id로 중복을 걸러 같은 메시지가 두 번 붙는 것을 막는다.
      queryClient.setQueryData<ConversationDetail>(
        queryKeys.conversation(project.id, cid),
        (prev) => {
          if (!prev) return prev;
          const existing = new Set(prev.messages.map((m) => m.id));
          const added = [exchange.userMessage, exchange.assistantMessage].filter(
            (m) => !existing.has(m.id),
          );
          return added.length
            ? { ...prev, messages: [...prev.messages, ...added] }
            : prev;
        },
      );
      queryClient.invalidateQueries({
        queryKey: queryKeys.conversations(project.id),
      });
      setPendingMessage(null);
    },
    onError: (_error, { content }) => restoreOnError(content),
  });

  const pending = createMutation.isPending || sendMutation.isPending;

  // 채팅 게이팅 — 연동이 없거나(응답 불가·API 낭비) 그래프가 구축 중이면(답변 신뢰도 낮음) 질문을 막는다.
  // 차단 강제는 프론트에서만 한다(백엔드 게이트 없음). 세 신호를 우선순위로 합친다:
  //   1) 연동 없음  2) 노드 없음(연동은 있으나 데이터 아직 없음) 또는 활동중(최초 수집/수동 재구축)
  const integrationsQuery = useIntegrations(project.id);
  const graphQuery = useGraph(project.id);
  const activityQuery = useGraphActivity(project.id);
  const chatBlock = useMemo<"no-integration" | "building" | "loading" | null>(() => {
    // 세 신호 중 하나라도 최초 로딩 중이면 아직 판정할 수 없다 — 그 사이 낭비성 질문이
    // 새어나가지 않게 조용히 차단한다(배너 없음). isLoading은 최초 1회만 true라 폴링엔 영향 없음.
    if (
      integrationsQuery.isLoading ||
      graphQuery.isLoading ||
      activityQuery.isLoading
    ) {
      return "loading";
    }
    if (integrationsQuery.data && integrationsQuery.data.length === 0) {
      return "no-integration";
    }
    const nodes = graphQuery.data?.nodes;
    const activity = activityQuery.data?.state;
    if ((nodes && nodes.length === 0) || (activity && activity !== "idle")) {
      return "building";
    }
    return null;
  }, [
    integrationsQuery.isLoading,
    integrationsQuery.data,
    graphQuery.isLoading,
    graphQuery.data,
    activityQuery.isLoading,
    activityQuery.data,
  ]);
  const blockNotice =
    chatBlock === "no-integration"
      ? "소스가 아직 연결되지 않았어요. 먼저 소스를 연결하면 질문할 수 있어요."
      : chatBlock === "building"
        ? "그래프를 만드는 중이에요. 준비되면 자동으로 질문할 수 있어요."
        : null;

  const handleSend = (text: string) => {
    const trimmed = text.trim();
    if (!trimmed || pending || chatBlock) return;
    setSendError(false);
    setPendingMessage({ conversationId, text: trimmed });
    setDraft(""); // 입력은 즉시 비우되, 실패하면 restoreOnError로 되돌린다
    if (conversationId) {
      sendMutation.mutate({ cid: conversationId, content: trimmed });
    } else {
      createMutation.mutate(trimmed);
    }
  };

  const handleDraftChange = (value: string) => {
    setDraft(value);
    if (sendError) setSendError(false);
  };

  // 낙관적 메시지는 그것이 속한 대화를 보고 있을 때만 렌더한다 — 응답 대기 중 다른 대화로
  // 이동해도 스피너/입력 거품이 그 대화에 새어 보이지 않게 한다.
  const showPending =
    pendingMessage !== null && pendingMessage.conversationId === conversationId;

  const streamProps = {
    conversationId,
    messages,
    pending: showPending,
    isLoadingOlder: loadOlder.isPending,
    olderError: loadOlder.isError,
    onReachTop: handleReachTop,
    onActiveMessageChange: setActiveMessageId,
  };

  return (
    <div
      ref={chatWrapRef}
      className={"chat-wrap" + (showPanel ? " with-panel" : "")}
      style={
        showPanel
          ? {
              gridTemplateColumns: `1fr ${panelWidth}px`,
              transition: resizing ? "none" : undefined,
            }
          : undefined
      }
    >
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
        {showPending ? (
          <ChatStream {...streamProps}>
            {renderMessages()}
            <UserMessage content={pendingMessage!.text} user={user} />
            <ThinkingState />
          </ChatStream>
        ) : !conversationId ? (
          <ChatEmpty
            project={project}
            onPick={handleSend}
            disabled={chatBlock !== null}
          />
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
          disabled={pending || chatBlock !== null}
          showThinkingHint={pending}
          error={
            chatBlock || !sendError
              ? null
              : "전송에 실패했어요. 입력을 그대로 두었으니 다시 시도해 주세요."
          }
          notice={blockNotice}
        />
      </div>
      {showPanel && (
        <RelatedGraphPanel
          data={subgraphQuery.data}
          isLoading={subgraphQuery.isLoading}
          isError={subgraphQuery.isError}
          onRetry={() => subgraphQuery.refetch()}
          selectedNodeId={selectedNodeId}
          emphasizedId={hoveredNodeId}
          onSelectNode={setSelectedNodeId}
          onHoverNode={setHoveredNodeId}
          onAddToChat={handleAddToChat}
          onResizeStart={startResize}
          onClose={() => setPanelOpen(false)}
        />
      )}
    </div>
  );
}
