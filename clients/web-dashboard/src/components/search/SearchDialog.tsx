import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";

import { Icons } from "@/components/Icons";
import {
  SEARCH_MIN_QUERY_LENGTH,
  useDebouncedValue,
  useSearchConversations,
} from "@/hooks/useSearch";
import { formatRelative } from "@/lib/format";
import type { ConversationSearchItem, Project } from "@/types/api";

interface Props {
  project: Project;
  onClose: () => void;
}

// 대화 검색(⌘K) 다이얼로그 — 사용자와 AI가 주고받은 대화(제목·메시지 본문)를 검색한다.
// 열릴 때마다 마운트돼 입력·포커스 상태가 초기화된다 (AppShell에서 조건부 렌더).
export function SearchDialog({ project, onClose }: Props) {
  const navigate = useNavigate();
  const [input, setInput] = useState("");
  const q = useDebouncedValue(input.trim());
  const listRef = useRef<HTMLDivElement>(null);

  const conversationsQuery = useSearchConversations(project.id, q);

  const ready = q.length >= SEARCH_MIN_QUERY_LENGTH;
  // 질의가 최소 길이 아래로 줄면 쿼리가 비활성화되고 이전 데이터가 남을 수 있어 ready로 가린다
  const conversations = (ready && conversationsQuery.data) || [];

  const [activeIndex, setActiveIndex] = useState(0);
  useEffect(() => setActiveIndex(0), [q, conversations.length]);

  // 활성 항목이 목록 스크롤 밖으로 나가면 따라간다
  useEffect(() => {
    listRef.current
      ?.querySelector('[data-active="true"]')
      ?.scrollIntoView({ block: "nearest" });
  }, [activeIndex]);

  const select = (conversation: ConversationSearchItem) => {
    navigate(`/projects/${project.id}/chat/${conversation.id}`);
    onClose();
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setActiveIndex((i) => Math.min(i + 1, conversations.length - 1));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setActiveIndex((i) => Math.max(i - 1, 0));
    } else if (e.key === "Enter") {
      e.preventDefault();
      const conversation = conversations[activeIndex];
      if (conversation) select(conversation);
    } else if (e.key === "Escape") {
      e.preventDefault();
      onClose();
    }
  };

  const searching = ready && conversationsQuery.isFetching;

  return (
    <div className="search-overlay" onMouseDown={onClose}>
      <div
        className="search-dialog"
        role="dialog"
        aria-label="대화 검색"
        onMouseDown={(e) => e.stopPropagation()}
      >
        <div className="search-input-row">
          <Icons.Search size={15} />
          <input
            className="search-input"
            autoFocus
            value={input}
            placeholder="대화 검색…"
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
          />
          {searching && <span className="search-spinner" aria-hidden />}
        </div>

        <div className="search-results" ref={listRef}>
          {!ready ? (
            <div className="search-empty">
              {SEARCH_MIN_QUERY_LENGTH}글자 이상 입력하세요
            </div>
          ) : conversations.length === 0 ? (
            <div className="search-empty">
              {searching ? "검색 중…" : "검색 결과가 없습니다"}
            </div>
          ) : (
            conversations.map((conversation, index) => (
              <ConversationRow
                key={conversation.id}
                conversation={conversation}
                active={activeIndex === index}
                onHover={() => setActiveIndex(index)}
                onSelect={() => select(conversation)}
              />
            ))
          )}
        </div>
      </div>
    </div>
  );
}

function ConversationRow({
  conversation,
  active,
  onHover,
  onSelect,
}: {
  conversation: ConversationSearchItem;
  active: boolean;
  onHover: () => void;
  onSelect: () => void;
}) {
  return (
    <div
      className={"search-item" + (active ? " active" : "")}
      data-active={active || undefined}
      onMouseMove={onHover}
      onClick={onSelect}
    >
      <span className="search-item-icon">
        <Icons.Chat size={14} />
      </span>
      <div className="search-item-body">
        <div className="search-item-title">{conversation.title}</div>
        {conversation.snippet && (
          <div className="search-item-snippet">{conversation.snippet}</div>
        )}
      </div>
      <span className="search-item-meta">
        {formatRelative(conversation.updatedAt)}
      </span>
    </div>
  );
}
