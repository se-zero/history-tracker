import { useMemo } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

import type { Message } from "@/types/api";
import { extractStructured } from "./messageStructured";

// 그래프 패널이 열렸을 때 모든 답변 카드에 주어진다.
// - 카드 hover: onHoverCard로 ChatPage가 그래프를 그 답변으로 전환(비활성)하거나 노드를 강조(활성)한다.
// - 활성 답변일 때만 seeds가 채워져 클릭(NodeDetail)·선택 연동이 활성화된다.
export interface CitationLink {
  panelOpen: boolean;
  isActive: boolean;
  seeds: (string | null)[];
  selectedNodeId: string | null;
  // 그래프에서 노드를 hover하면 대응 카드를 역방향으로 강조한다.
  hoveredNodeId: string | null;
  // 카드 클릭 — 정보 표시/전환·토글은 ChatPage가 상태를 보고 결정한다.
  onCardClick: (index: number) => void;
  onHoverCard: (index: number) => void;
  onLeaveCard: () => void;
}

export function MessageItem({
  message,
  citation,
}: {
  message: Message;
  citation?: CitationLink;
}) {
  if (message.role === "USER") {
    return <UserMessage content={message.content} />;
  }
  return <AssistantMessage message={message} citation={citation} />;
}

export function UserMessage({ content }: { content: string }) {
  return (
    <div className="msg user">
      <div className="msg-body">
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
      <div className="msg-body">
        {/* 답변별 신뢰도 신호 — composer의 상시 고지와 달리 이 답이 약할 때만 뜬다.
            판정은 ai-engine이 실제 호출된 도구로 한다(LLM 자기신고 아님). */}
        {structured?.answerMode === "exploratory" && (
          <div className="msg-exploratory" role="note">
            전용 조회 경로가 없어 그래프를 직접 탐색해 답했습니다. 근거 연결이 약하거나 일부가
            추론일 수 있습니다.
          </div>
        )}
        {/* assistant 응답은 markdown으로 렌더링한다(유저 메시지는 원문 그대로 pre-wrap 유지). */}
        <div className="msg-content markdown">
          <ReactMarkdown remarkPlugins={[remarkGfm]}>{summary}</ReactMarkdown>
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
              const selected = nodeId != null && nodeId === citation!.selectedNodeId;
              // 노드 hover로 강조된 카드(선택 카드는 selected 강조가 우선).
              const emphasized =
                nodeId != null && !selected && nodeId === citation!.hoveredNodeId;
              // 클릭 가능: 패널이 닫혀 있으면 열기, 열려 있으면 다른 답변 카드는 그래프 전환,
              // 현재 답변 카드는 노드로 해석됐을 때만(정보 토글).
              const clickable =
                !!citation &&
                (!citation.panelOpen || !citation.isActive || nodeId != null);
              // hover 연동은 패널이 열렸을 때만(닫힌 패널엔 강조할 그래프가 없음).
              const hoverable = !!citation && citation.panelOpen;
              return (
              <div
                key={i}
                className={
                  "cite-card" +
                  (selected ? " selected" : "") +
                  (emphasized ? " emphasized" : "")
                }
                style={{ cursor: clickable ? "pointer" : "default" }}
                onMouseEnter={hoverable ? () => citation!.onHoverCard(i) : undefined}
                onMouseLeave={hoverable ? () => citation!.onLeaveCard() : undefined}
                // 카드 클릭 → 정보(NodeDetail), 같은 카드 다시 → 그래프(토글). 닫힌 패널은 열며 표시.
                onClick={clickable ? () => citation!.onCardClick(i) : undefined}
              >
                <span className="cite-idx">#{i + 1}</span>
                <span className="cite-body">
                  <div className="cite-meta">
                    <span>{e.type}</span>
                    <span>·</span>
                    <span className="mono cite-id">{e.id}</span>
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
