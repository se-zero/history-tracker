import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import axios from "axios";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Navigate, useNavigate, useParams } from "react-router-dom";

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
import { queryKeys } from "@/hooks/queryKeys";
import {
  useConversation,
  useLoadOlderMessages,
} from "@/hooks/useConversations";
import { useGraph, useGraphActivity, useMessageSubgraph } from "@/hooks/useGraph";
import { useIntegrations } from "@/hooks/useIntegrations";
import type { ConversationDetail, Message, Project } from "@/types/api";
import type { AttachedNode, GraphNode, NodeRef } from "@/types/graph";

// 관련 그래프 패널 너비 한계(px). 채팅 영역이 너무 좁아지지 않게 드래그 시 동적 상한도 적용한다.
const MIN_PANEL_W = 280;
const MAX_PANEL_W = 680;
const clampPanelWidth = (w: number) =>
  Math.min(MAX_PANEL_W, Math.max(MIN_PANEL_W, w));

// node-only 전송(칩만 있고 텍스트 없음) 시 채울 기본 질문 — 백엔드 content가 @NotBlank라 빈 값 불가.
const NODE_ONLY_QUESTION = "첨부한 항목에 대해 설명해줘.";

export function ChatPage({ project }: { project: Project }) {
  const { conversationId } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const conversationQuery = useConversation(project.id, conversationId);
  const detail = conversationQuery.data;
  const messages = detail?.messages ?? [];

  // 없는 대화로 들어왔는지 — 다른 탭에 있는 동안 사이드바에서 지웠거나, 옛 주소를 다시 연 경우다.
  // 404만 본다. 네트워크·서버 오류는 다시 시도할 수 있게 에러 화면을 유지해야 한다.
  // effect로 이동시키지 않는 이유: effect는 화면이 그려진 뒤에 돌아 에러 카드가 한 프레임 스친다.
  // 렌더 분기에서 <Navigate>로 갈아타면 에러 카드가 그려질 자리 자체가 없다.
  const conversationGone =
    axios.isAxiosError(conversationQuery.error) &&
    conversationQuery.error.response?.status === 404;

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
  // 관련 그래프에서 첨부한 focus 노드 칩. 전송 시 ref만 focus_evidence로 실어 보낸다.
  const [attachedNodes, setAttachedNodes] = useState<AttachedNode[]>([]);
  // 칩은 특정 대화의 답변 그래프에서 나온 것 — 대화를 옮기면(라우트 전환으로 리마운트되지 않으므로) 비운다.
  // 이미 비어 있으면 같은 참조를 반환해 마운트 시·빈 상태에서의 불필요한 리렌더를 건너뛴다.
  useEffect(
    () => setAttachedNodes((prev) => (prev.length ? [] : prev)),
    [conversationId],
  );
  const [sendError, setSendError] = useState(false);
  // 방금 도착한 답변 — 도착 연출(페이드+상승)의 대상. 과거 대화 로드/prepend와 구분하는 유일한 신호다.
  // ignite는 그 중에서도 관련 그래프 점등 안무 재생 여부 — 도착 시점에 패널이 열려 있었는지의 스냅샷.
  const [fresh, setFresh] = useState<{
    conversationId: string;
    messageId: string;
    ignite: boolean;
  } | null>(null);
  // 다른 대화로 이동하면 fresh는 무효 — 그 대화로 돌아와도 재생하지 않는다(과거 로드 취급).
  useEffect(() => {
    setFresh((f) => (f && f.conversationId !== conversationId ? null : f));
  }, [conversationId]);

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
  // 활성 답변이 fresh였다가 다른 메시지로 바뀌는 전이에서만 ignite를 내린다. 도착 직후
  // (구 메시지가 활성인 상태) → fresh로 바뀌는 최초 전이는 오발동이 아니므로 걸러야 한다.
  // 로딩 중 스크롤로 fresh 답변을 떠났다가 한참 뒤 돌아오면 캐시 즉시 히트로 늦은 점등이
  // 재생되는 것을 막는다 — 떠나는 시점에 이미 ignite를 꺼 두면 나중에 돌아와도 재생 대상이 아니다.
  const prevActiveRef = useRef<string | null>(null);
  useEffect(() => {
    const prev = prevActiveRef.current;
    prevActiveRef.current = activeMessageId;
    if (!prev || prev === activeMessageId) return;
    setFresh((f) => (f && f.ignite && f.messageId === prev ? { ...f, ignite: false } : f));
  }, [activeMessageId]);

  const showPanel = panelOpen && !!conversationId;
  // 패널이 닫히면 점등 재생 대상에서 내린다 — 텍스트 도착 연출(fresh.messageId)은 그대로 두고
  // ignite만 끈다. 닫힌 채로 지나간 시간은 "나중에 열어 봄"이라 재생하지 않는다.
  useEffect(() => {
    if (showPanel) return;
    setFresh((f) => (f && f.ignite ? { ...f, ignite: false } : f));
  }, [showPanel]);
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
    const ref = node.ref;
    if (ref) {
      // 질의 도구 대상 노드 → 칩으로 첨부(ref로 dedupe). 근거 고정은 focus_evidence로 전달된다.
      setAttachedNodes((nodes) =>
        nodes.some((n) => n.ref.type === ref.type && n.ref.id === ref.id)
          ? nodes
          : [...nodes, { ref, label: node.title, nodeType: node.type }],
      );
    } else {
      // actor/code 등 ref 없는 노드는 텍스트 폴백 — 제목을 입력창에 삽입.
      setDraft((d) => (d.trim() ? d + " " + node.title : node.title));
    }
  };

  const handleRemoveNode = (ref: NodeRef) => {
    setAttachedNodes((nodes) =>
      nodes.filter((n) => !(n.ref.type === ref.type && n.ref.id === ref.id)),
    );
  };

  // GraphVis가 마운트 시점(재생 시작)에 1회 호출 — reduced-motion으로 건너뛴 경우도 포함한다.
  const handleIgniteConsumed = useCallback(
    () => setFresh((f) => (f ? { ...f, ignite: false } : f)),
    [],
  );

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
      <MessageItem
        key={m.id}
        message={m}
        citation={citationFor(m)}
        fresh={m.id === fresh?.messageId}
      />
    ));

  // 전송 실패 시 친 내용·첨부 노드가 사라지지 않도록 복구하고 에러를 표시한다.
  // 단, 응답 대기 중 다른 대화로 이동했으면(originCid ≠ 현재) 그 대화에 원래 대화의
  // 입력·에러를 흘리지 않는다 — pendingMessage 스코프와 동일한 규칙(이 경우 복구는 생략).
  // 그 사이 새로 입력/첨부한 내용이 있으면 덮어쓰지 않는다.
  const restoreOnError = (
    originCid: string | undefined,
    failedText: string,
    attached: AttachedNode[] = [],
  ) => {
    setPendingMessage(null);
    if (originCid !== conversationId) return;
    setDraft((current) => (current.trim() ? current : failedText));
    if (attached.length) {
      setAttachedNodes((current) => (current.length ? current : attached));
    }
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
      // 첫 교환의 답변에 도착 연출을 건다 — navigate와 같은 이벤트 배치라(React 18 배칭)
      // 아래 conversationId 불일치 해제 effect가 이 렌더 사이에 끼어들어 지우지 않는다.
      const lastAssistant = [...created.messages]
        .reverse()
        .find((m) => m.role === "ASSISTANT");
      if (lastAssistant) {
        // 신규 대화는 navigate와 함께 패널이 나타나므로 열림 설정값(panelOpen)을 스냅샷한다 —
        // 첫 답변도 점등 재생 대상이다.
        setFresh({
          conversationId: created.id,
          messageId: lastAssistant.id,
          ignite: panelOpen,
        });
      }
      navigate(`/projects/${project.id}/chat/${created.id}`, { replace: true });
    },
    // 신규 대화 경로의 origin은 대화 없음(undefined) — 그 화면을 벗어났으면 복구하지 않는다.
    onError: (_error, firstMessage) => restoreOnError(undefined, firstMessage),
  });

  const sendMutation = useMutation({
    // 대화 id를 변수로 함께 넘긴다 — 응답 대기 중 다른 대화로 이동해 conversationId가 바뀌어도
    // 응답이 원래 대화의 캐시에 정확히 반영되도록(엉뚱한 대화에 답변이 끼는 것 방지).
    // attached/restoreText는 전송 실패 시 칩·입력 복구용(요청 본문엔 focusEvidence만 실린다).
    mutationFn: ({
      cid,
      content,
      focusEvidence,
    }: {
      cid: string;
      content: string;
      focusEvidence: NodeRef[];
      attached: AttachedNode[];
      restoreText: string;
    }) =>
      sendMessage(
        project.id,
        cid,
        content,
        focusEvidence.length ? focusEvidence : undefined,
      ),
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
      // 응답 대기 중 다른 대화로 이동했으면 도착 연출을 걸지 않는다 — 돌아와서 보는 건
      // "과거 로드"로 취급해야 한다(cid는 요청 시점의 대화, conversationId는 현재 화면).
      if (cid === conversationId) {
        // 도착 시점에 패널이 실제로 열려 있었는지의 스냅샷 — 닫혀 있다가 나중에 열면 무연출.
        setFresh({
          conversationId: cid,
          messageId: exchange.assistantMessage.id,
          ignite: showPanel,
        });
      }
    },
    onError: (_error, { restoreText, attached, cid }) =>
      restoreOnError(cid, restoreText, attached),
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
    const attached = attachedNodes;
    // 텍스트가 비어도 첨부 노드가 있으면 전송한다(노드만으로 질문).
    if ((!trimmed && attached.length === 0) || pending || chatBlock) return;
    // node-only면 기본 질문으로 채운다 — 백엔드 content는 @NotBlank.
    const content = trimmed || NODE_ONLY_QUESTION;
    setSendError(false);
    // 다음 질문을 보내는 순간 직전 답변의 "도착 연출" 자격은 끝난다 — 남겨 두면 어떤
    // 이유로든 그 요소가 리마운트될 때 is-fresh 애니메이션이 다시 재생된다.
    setFresh(null);
    setPendingMessage({ conversationId, text: content });
    // 입력·칩은 즉시 비우되, 실패하면 restoreOnError로 되돌린다.
    setDraft("");
    setAttachedNodes([]);
    if (conversationId) {
      sendMutation.mutate({
        cid: conversationId,
        content,
        focusEvidence: attached.map((n) => n.ref),
        attached,
        restoreText: trimmed,
      });
    } else {
      // 신규 대화(첫 메시지)는 패널이 없어 첨부 노드가 없다.
      createMutation.mutate(content);
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
        {/* 대기 UI(낙관적 말풍선+스피너)는 별도 분기가 아니라 같은 ChatStream 안의 조건부
            자식으로 둔다 — pending 토글마다 자식 중첩 구조가 바뀌면(배열 단독 ↔ [배열, 말풍선,
            스피너]) 메시지 key의 내부 경로가 달라져 목록 전체가 리마운트되고, 직전 답변의
            is-fresh 애니메이션이 다시 재생된다(전송마다 전체 DOM 재생성이기도 하다).
            자식 슬롯을 [배열, 조건부, 조건부]로 고정해 메시지 요소를 보존한다. */}
        {showPending ||
        (conversationId &&
          !conversationQuery.isLoading &&
          !conversationGone &&
          !conversationQuery.isError) ? (
          <ChatStream {...streamProps}>
            {renderMessages()}
            {showPending && <UserMessage content={pendingMessage!.text} />}
            {showPending && <ThinkingState />}
          </ChatStream>
        ) : !conversationId ? (
          <ChatEmpty
            project={project}
            onPick={handleSend}
            disabled={chatBlock !== null}
          />
        ) : conversationQuery.isLoading ? (
          <StatusView tone="loading" description="메시지를 불러오는 중…" />
        ) : conversationGone ? (
          // 새 대화 화면으로 되돌린다 — 프로젝트를 옮겼다 돌아왔을 때와 같은 화면이라 사용자가
          // 따로 복구 동작을 할 필요가 없다. AppShell이 기억한 대화 id도 이 이동으로 함께 비워진다.
          <Navigate to={`/projects/${project.id}/chat`} replace />
        ) : (
          // 남는 경우는 조회 실패뿐 — 위 통합 분기 조건이 (로딩·삭제·에러 아님)을 소거한다.
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
          attachedNodes={attachedNodes}
          onRemoveNode={handleRemoveNode}
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
          ignite={!!fresh?.ignite && !!activeMessage && activeMessage.id === fresh.messageId}
          onIgniteConsumed={handleIgniteConsumed}
          activeMessageId={activeMessage?.id ?? null}
        />
      )}
    </div>
  );
}
