import { useMemo } from "react";

import { Icons } from "@/components/Icons";
import { userInitials } from "@/lib/format";
import type { Message, User } from "@/types/api";
import { extractStructured } from "./messageStructured";

// 그래프 패널이 열렸을 때 모든 답변 카드에 주어진다.
// - 카드 hover: onHoverCard로 ChatPage가 그래프를 그 답변으로 전환(비활성)하거나 노드를 강조(활성)한다.
// - 활성 답변일 때만 seeds가 채워져 클릭(NodeDetail)·선택 연동이 활성화된다.
export interface CitationLink {
  isActive: boolean;
  seeds: (string | null)[];
  selectedNodeId: string | null;
  onSelectNode: (id: string | null) => void;
  onHoverCard: (index: number) => void;
  onLeaveCard: () => void;
}

export function MessageItem({
  message,
  user,
  citation,
}: {
  message: Message;
  user: User | null;
  citation?: CitationLink;
}) {
  if (message.role === "USER") {
    return <UserMessage content={message.content} user={user} />;
  }
  return <AssistantMessage message={message} citation={citation} />;
}

export function UserMessage({
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

function AssistantMessage({
  message,
  citation,
}: {
  message: Message;
  citation?: CitationLink;
}) {
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
    <div className="msg assistant" data-role="ASSISTANT" data-message-id={message.id}>
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
            {evidence.map((e, i) => {
              // 클릭(NodeDetail)·선택은 활성 답변에서 노드로 해석된 evidence만. hover는 패널이 열린 모든 카드에서 가능.
              const nodeId = citation?.isActive ? citation.seeds[i] ?? null : null;
              const linked = nodeId != null;
              const selected = linked && nodeId === citation!.selectedNodeId;
              return (
              <div
                key={i}
                className={"cite-card" + (selected ? " selected" : "")}
                style={{ cursor: linked ? "pointer" : "default" }}
                onMouseEnter={citation ? () => citation.onHoverCard(i) : undefined}
                onMouseLeave={citation ? () => citation.onLeaveCard() : undefined}
                onClick={linked ? () => citation!.onSelectNode(nodeId) : undefined}
              >
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
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
