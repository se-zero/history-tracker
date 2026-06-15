import { useEffect, useMemo, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useParams } from "react-router-dom";

import { Icons } from "@/components/Icons";
import { StatusView } from "@/components/StatusView";
import {
  createConversation,
  getConversation,
  sendMessage,
} from "@/api/conversations";
import { useAuth } from "@/auth/AuthProvider";
import type {
  ConversationDetail,
  Message,
  MessageMetadata,
  Project,
  User,
} from "@/types/api";

// TODO(backend): highlightNodes가 실리면 그래프 하이라이트 연동. 그래프 노드 매핑은 Phase 4.
const SUGGESTED = [
  { icon: "branch", text: "왜 이 코드가 이렇게 바뀌었어?" },
  { icon: "refactor", text: "최근 머지된 리팩토링 PR들을 정리해줘" },
  { icon: "fire", text: "지난 분기 가장 논쟁이 많았던 PR은?" },
  { icon: "people", text: "이 도메인을 가장 잘 아는 사람은?" },
] as const;

export function ChatPage({ project }: { project: Project }) {
  const { conversationId } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { user } = useAuth();

  const conversationQuery = useQuery({
    queryKey: ["conversation", project.id, conversationId],
    queryFn: () => getConversation(project.id, conversationId!),
    enabled: Boolean(conversationId),
  });

  const messages = conversationQuery.data?.messages ?? [];

  // 전송 직후 화면을 즉시 전환하기 위해 방금 보낸 메시지를 낙관적으로 들고 있는다. 응답이 오면 비운다.
  const [pendingMessage, setPendingMessage] = useState<string | null>(null);

  const createMutation = useMutation({
    mutationFn: (firstMessage: string) =>
      createConversation(project.id, firstMessage),
    onSuccess: (detail) => {
      queryClient.invalidateQueries({ queryKey: ["conversations", project.id] });
      queryClient.setQueryData(
        ["conversation", project.id, detail.id],
        detail,
      );
      setPendingMessage(null);
      navigate(`/projects/${project.id}/chat/${detail.id}`, { replace: true });
    },
    onError: () => setPendingMessage(null),
  });

  const sendMutation = useMutation({
    mutationFn: (content: string) =>
      sendMessage(project.id, conversationId!, content),
    onSuccess: (exchange) => {
      // 응답 쌍을 캐시에 바로 반영해 낙관적 메시지를 비울 때 공백이 생기지 않게 한다.
      queryClient.setQueryData<ConversationDetail>(
        ["conversation", project.id, conversationId],
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
      queryClient.invalidateQueries({ queryKey: ["conversations", project.id] });
      setPendingMessage(null);
    },
    onError: () => setPendingMessage(null),
  });

  const pending = createMutation.isPending || sendMutation.isPending;

  const handleSend = (text: string) => {
    const trimmed = text.trim();
    if (!trimmed || pending) return;
    setPendingMessage(trimmed);
    if (conversationId) {
      sendMutation.mutate(trimmed);
    } else {
      createMutation.mutate(trimmed);
    }
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
          disabled={pending}
          onSend={handleSend}
          showThinkingHint={pending}
        />
      </div>
    </div>
  );
}

function ChatStream({ children }: { children: React.ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (ref.current) ref.current.scrollTop = ref.current.scrollHeight;
  }, [children]);
  return (
    <div className="chat-stream" ref={ref}>
      <div className="chat-inner">{children}</div>
    </div>
  );
}

// =============== Message ===============

function MessageItem({ message, user }: { message: Message; user: User | null }) {
  if (message.role === "USER") {
    return <UserMessage content={message.content} user={user} />;
  }
  return <AssistantMessage message={message} />;
}

function UserMessage({
  content,
  user,
}: {
  content: string;
  user: User | null;
}) {
  return (
    <div className="msg user">
      <div className="msg-avatar">{userInitials(user)}</div>
      <div className="msg-body">
        <div className="msg-role">{user?.displayName ?? "나"}</div>
        <div className="msg-content">
          <p style={{ whiteSpace: "pre-wrap" }}>{content}</p>
        </div>
      </div>
    </div>
  );
}

function AssistantMessage({ message }: { message: Message }) {
  const structured = useMemo(
    () => extractStructured(message.metadata),
    [message.metadata],
  );
  // structured 응답은 summary/evidence/unknown_aspects를 카드·목록으로 분리 렌더한다.
  // message.content는 이 구조를 풀어 쓴 markdown 텍스트라 structured가 있으면 사용하지 않는다.
  const summary = structured?.summary ?? message.content;
  const unknownAspects = structured?.unknownAspects ?? [];
  const evidence = structured?.evidence ?? [];

  return (
    <div className="msg assistant">
      <div className="msg-avatar">
        <Icons.Sparkle size={14} />
      </div>
      <div className="msg-body">
        <div className="msg-role">History Tracker</div>
        <div className="msg-content">
          <p style={{ whiteSpace: "pre-wrap" }}>{summary}</p>
        </div>

        {unknownAspects.length > 0 && (
          <ul className="unknown-aspects">
            {unknownAspects.map((aspect, i) => (
              <li key={i}>{aspect}</li>
            ))}
          </ul>
        )}

        {evidence.length > 0 && (
          <div className="citation-cards">
            {evidence.map((e, i) => (
              <div key={i} className="cite-card" style={{ cursor: "default" }}>
                <span className="cite-idx">#{i + 1}</span>
                <span className="cite-body">
                  <div className="cite-meta">
                    <span>{e.type}</span>
                    <span>·</span>
                    <span className="mono" style={{ fontSize: 10.5 }}>
                      {e.id}
                    </span>
                    {e.author && (
                      <>
                        <span>·</span>
                        <span>{e.author}</span>
                      </>
                    )}
                    {e.occurredAt && (
                      <>
                        <span>·</span>
                        <span>{e.occurredAt.slice(0, 10)}</span>
                      </>
                    )}
                  </div>
                  <div className="cite-snippet">{e.quote}</div>
                </span>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

interface Evidence {
  type: string;
  id: string;
  quote: string;
  author: string | null;
  occurredAt?: string;
}

interface StructuredAnswer {
  summary?: string;
  evidence: Evidence[];
  unknownAspects: string[];
}

function extractStructured(metadata: MessageMetadata | null | undefined): StructuredAnswer | null {
  if (!metadata) return null;
  const structured = metadata.structured as
    | { summary?: string; evidence?: Evidence[]; unknown_aspects?: string[] }
    | undefined;
  if (!structured) return null;
  return {
    summary: structured.summary,
    evidence: structured.evidence ?? [],
    unknownAspects: structured.unknown_aspects ?? [],
  };
}

// =============== Thinking ===============

function ThinkingState() {
  return (
    <div className="msg assistant">
      <div className="msg-avatar">
        <Icons.Sparkle size={14} />
      </div>
      <div className="msg-body">
        <div className="msg-role">History Tracker</div>
        <div className="thinking">
          <span className="spinner" />
          <span>처리 중…</span>
        </div>
      </div>
    </div>
  );
}

// =============== Empty ===============

function ChatEmpty({
  project,
  onPick,
}: {
  project: Project;
  onPick: (text: string) => void;
}) {
  const iconMap = {
    branch: Icons.Branch,
    refactor: Icons.Refactor,
    fire: Icons.Fire,
    people: Icons.People,
  } as const;
  return (
    <div className="chat-empty">
      <span className="logo-mark" />
      <h2>무엇을 알아볼까요?</h2>
      <p>
        {project.name}에 대해 아래 추천 질문으로 시작하거나, 직접 자연어로 물어보세요.
      </p>
      <div className="suggest-grid">
        {SUGGESTED.map((s, i) => {
          const Ic = iconMap[s.icon];
          return (
            <button
              key={i}
              className="suggest-card"
              onClick={() => onPick(s.text)}
            >
              <span className="sg-icon">
                <Ic size={14} />
              </span>
              <span>{s.text}</span>
            </button>
          );
        })}
      </div>
    </div>
  );
}

// =============== Composer ===============

function Composer({
  project,
  disabled,
  onSend,
  showThinkingHint,
}: {
  project: Project;
  disabled: boolean;
  onSend: (text: string) => void;
  showThinkingHint: boolean;
}) {
  const [draft, setDraft] = useState("");
  const taRef = useRef<HTMLTextAreaElement>(null);

  const submit = () => {
    onSend(draft);
    setDraft("");
  };

  const onKey = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      submit();
    }
  };

  return (
    <div className="composer">
      <div className="composer-inner">
        <div className="composer-box">
          <textarea
            ref={taRef}
            placeholder={`${project.name}에 무엇이든 물어보세요. Shift+Enter로 줄바꿈`}
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={onKey}
            rows={1}
            disabled={disabled}
          />
          <div className="composer-actions">
            <div className="spacer" />
            <button
              className="btn btn-primary"
              onClick={submit}
              disabled={disabled || !draft.trim()}
              style={{ padding: "6px 10px" }}
            >
              <Icons.Send size={13} />
              <span style={{ fontSize: 12 }}>전송</span>
            </button>
          </div>
        </div>
        <div className="composer-foot">
          <span>
            <span className="kbd">Enter</span> 전송
          </span>
          <span>
            <span className="kbd">Shift</span>+<span className="kbd">Enter</span> 줄바꿈
          </span>
          {showThinkingHint && <span>응답을 생성 중…</span>}
        </div>
      </div>
    </div>
  );
}

function userInitials(user: User | null): string {
  if (!user?.displayName) return "?";
  const tokens = user.displayName.trim().split(/\s+/);
  if (tokens.length === 1) return tokens[0].slice(0, 2).toUpperCase();
  return (tokens[0][0] + tokens[tokens.length - 1][0]).toUpperCase();
}
