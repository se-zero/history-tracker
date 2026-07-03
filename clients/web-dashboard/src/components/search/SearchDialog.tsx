import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";

import { Icons } from "@/components/Icons";
import {
  SEARCH_MIN_QUERY_LENGTH,
  useDebouncedValue,
  useSearchConversations,
  useSearchGraphNodes,
} from "@/hooks/useSearch";
import { formatRelative } from "@/lib/format";
import { NODE_TYPE_INFO, type GraphNode } from "@/types/graph";
import type { ConversationSearchItem, Project } from "@/types/api";

// 키보드 내비게이션(↑↓·Enter)을 위해 대화·노드 결과를 하나의 평면 목록으로 다룬다.
type ResultRow =
  | { kind: "conversation"; conversation: ConversationSearchItem }
  | { kind: "node"; node: GraphNode };

interface Props {
  project: Project;
  onClose: () => void;
}

// 통합 검색(⌘K) 다이얼로그 — 대화(backend)와 그래프 노드(ai-engine 프록시)를 병렬 검색한다.
// 열릴 때마다 마운트돼 입력·포커스 상태가 초기화된다 (AppShell에서 조건부 렌더).
export function SearchDialog({ project, onClose }: Props) {
  const navigate = useNavigate();
  const [input, setInput] = useState("");
  const q = useDebouncedValue(input.trim());
  const listRef = useRef<HTMLDivElement>(null);

  const conversationsQuery = useSearchConversations(project.id, q);
  const nodesQuery = useSearchGraphNodes(project.id, q);

  const ready = q.length >= SEARCH_MIN_QUERY_LENGTH;
  // 질의가 최소 길이 아래로 줄면 쿼리가 비활성화되고 이전 데이터가 남을 수 있어 ready로 가린다
  const conversations = (ready && conversationsQuery.data) || [];
  const nodes = (ready && nodesQuery.data) || [];

  const rows = useMemo<ResultRow[]>(
    () => [
      ...conversations.map(
        (conversation) => ({ kind: "conversation", conversation }) as const,
      ),
      ...nodes.map((node) => ({ kind: "node", node }) as const),
    ],
    [conversations, nodes],
  );

  const [activeIndex, setActiveIndex] = useState(0);
  useEffect(() => setActiveIndex(0), [q, rows.length]);

  // 활성 항목이 목록 스크롤 밖으로 나가면 따라간다
  useEffect(() => {
    listRef.current
      ?.querySelector('[data-active="true"]')
      ?.scrollIntoView({ block: "nearest" });
  }, [activeIndex]);

  const select = (row: ResultRow) => {
    if (row.kind === "conversation") {
      navigate(`/projects/${project.id}/chat/${row.conversation.id}`);
    } else {
      // 검색된 노드는 그래프 화면(최근 top-N)에 없을 수 있어 노드 자체를 state로 넘긴다
      navigate(`/projects/${project.id}/graph`, {
        state: { searchNode: row.node },
      });
    }
    onClose();
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setActiveIndex((i) => Math.min(i + 1, rows.length - 1));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setActiveIndex((i) => Math.max(i - 1, 0));
    } else if (e.key === "Enter") {
      e.preventDefault();
      const row = rows[activeIndex];
      if (row) select(row);
    } else if (e.key === "Escape") {
      e.preventDefault();
      onClose();
    }
  };

  const searching =
    ready && (conversationsQuery.isFetching || nodesQuery.isFetching);

  return (
    <div className="search-overlay" onMouseDown={onClose}>
      <div
        className="search-dialog"
        role="dialog"
        aria-label="통합 검색"
        onMouseDown={(e) => e.stopPropagation()}
      >
        <div className="search-input-row">
          <Icons.Search size={15} />
          <input
            className="search-input"
            autoFocus
            value={input}
            placeholder="대화·그래프 검색…"
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
          ) : rows.length === 0 ? (
            <div className="search-empty">
              {searching ? "검색 중…" : "검색 결과가 없습니다"}
            </div>
          ) : (
            <>
              {conversations.length > 0 && (
                <div className="search-section-label">대화</div>
              )}
              {conversations.map((conversation, index) => (
                <ConversationRow
                  key={conversation.id}
                  conversation={conversation}
                  active={activeIndex === index}
                  onHover={() => setActiveIndex(index)}
                  onSelect={() =>
                    select({ kind: "conversation", conversation })
                  }
                />
              ))}
              {nodes.length > 0 && (
                <div className="search-section-label">그래프</div>
              )}
              {nodes.map((node, index) => (
                <NodeRow
                  key={node.id}
                  node={node}
                  active={activeIndex === conversations.length + index}
                  onHover={() => setActiveIndex(conversations.length + index)}
                  onSelect={() => select({ kind: "node", node })}
                />
              ))}
            </>
          )}
          {ready && nodesQuery.isError && (
            <div className="search-footnote">
              그래프 검색을 지금은 사용할 수 없어요
            </div>
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

function NodeRow({
  node,
  active,
  onHover,
  onSelect,
}: {
  node: GraphNode;
  active: boolean;
  onHover: () => void;
  onSelect: () => void;
}) {
  const info = NODE_TYPE_INFO[node.type] ?? NODE_TYPE_INFO.code;
  return (
    <div
      className={"search-item" + (active ? " active" : "")}
      data-active={active || undefined}
      onMouseMove={onHover}
      onClick={onSelect}
    >
      <span className="search-node-dot" style={{ background: info.cssVar }} />
      <div className="search-item-body">
        <div className="search-item-title">{node.title}</div>
        {node.snippet && node.snippet !== node.title && (
          <div className="search-item-snippet">{node.snippet}</div>
        )}
      </div>
      <span className="search-item-meta mono">
        {info.label}
        {node.meta ? ` · ${node.meta}` : ""}
      </span>
    </div>
  );
}
