import { useEffect, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";

import { Icons } from "@/components/Icons";
import {
  useConversations,
  useDeleteConversation,
  useRenameConversation,
} from "@/hooks/useConversations";
import { formatRelative } from "@/lib/format";
import type { Conversation } from "@/types/api";

// 사이드바의 대화 목록 — 데이터·이름변경·삭제·내비게이션을 자체적으로 처리한다.
// (이전엔 AppShell이 들고 Sidebar로 prop drilling 하던 부분)
export function ConversationList({ projectId }: { projectId: string }) {
  const navigate = useNavigate();
  const { conversationId: activeConvoId } = useParams();

  const conversationsQuery = useConversations(projectId);
  const conversations = conversationsQuery.data ?? [];
  const { fetchNextPage, hasNextPage, isFetchingNextPage } = conversationsQuery;
  const renameMutation = useRenameConversation(projectId);
  const deleteMutation = useDeleteConversation(projectId);

  // 목록 하단 sentinel이 보이면 다음 페이지를 로드한다(아래로 무한 스크롤).
  const listRef = useRef<HTMLDivElement>(null);
  const sentinelRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const root = listRef.current;
    const target = sentinelRef.current;
    if (!root || !target) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting && hasNextPage && !isFetchingNextPage) {
          fetchNextPage();
        }
      },
      { root, rootMargin: "120px" },
    );
    observer.observe(target);
    return () => observer.disconnect();
  }, [hasNextPage, isFetchingNextPage, fetchNextPage]);

  const handleSelect = (id: string) => {
    navigate(`/projects/${projectId}/chat/${id}`);
  };

  const handleNew = () => {
    navigate(`/projects/${projectId}/chat`);
  };

  const handleRename = (id: string, title: string) => {
    renameMutation.mutate({ id, title });
  };

  const handleDelete = (id: string) => {
    deleteMutation.mutate(id, {
      onSuccess: () => {
        // 현재 보고 있던 대화를 지우면 빈 채팅 화면으로 이동
        if (activeConvoId === id) {
          navigate(`/projects/${projectId}/chat`, { replace: true });
        }
      },
    });
  };

  return (
    <>
      <div className="sidebar-label">
        <span>대화</span>
        <button className="add-btn" title="새 대화" onClick={handleNew}>
          <Icons.Plus size={13} />
        </button>
      </div>
      <div className="convo-list" ref={listRef}>
        {conversations.map((c) => (
          <ConvoItem
            key={c.id}
            convo={c}
            active={activeConvoId === c.id}
            onSelect={handleSelect}
            onRename={handleRename}
            onDelete={handleDelete}
          />
        ))}
        {conversations.length === 0 && (
          <div
            style={{
              padding: "16px 10px",
              fontSize: 12,
              color: "var(--fg-subtle)",
              textAlign: "center",
            }}
          >
            아직 대화가 없습니다.
          </div>
        )}
        <div ref={sentinelRef} aria-hidden className="convo-sentinel" />
      </div>
    </>
  );
}

function ConvoItem({
  convo,
  active,
  onSelect,
  onRename,
  onDelete,
}: {
  convo: Conversation;
  active: boolean;
  onSelect: (id: string) => void;
  onRename: (id: string, title: string) => void;
  onDelete: (id: string) => void;
}) {
  const [menuOpen, setMenuOpen] = useState(false);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(convo.title);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!menuOpen) return;
    const h = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setMenuOpen(false);
    };
    document.addEventListener("mousedown", h);
    return () => document.removeEventListener("mousedown", h);
  }, [menuOpen]);

  const startEdit = () => {
    setDraft(convo.title);
    setMenuOpen(false);
    setEditing(true);
  };

  // 저장은 Enter로 명시 확정할 때만. blur·Escape는 취소(원복)해 의도치 않은 이름 변경을 막는다.
  const commitEdit = () => {
    setEditing(false);
    const next = draft.trim();
    if (next && next !== convo.title) onRename(convo.id, next);
  };

  const cancelEdit = () => {
    setEditing(false);
    setDraft(convo.title);
  };

  const handleDelete = () => {
    setMenuOpen(false);
    if (window.confirm(`"${convo.title}" 대화를 삭제할까요? 되돌릴 수 없어요.`)) {
      onDelete(convo.id);
    }
  };

  if (editing) {
    return (
      <div className="convo-item editing">
        <input
          className="convo-edit-input"
          autoFocus
          value={draft}
          maxLength={200}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              e.preventDefault();
              commitEdit();
            } else if (e.key === "Escape") {
              e.preventDefault();
              cancelEdit();
            }
          }}
          onBlur={cancelEdit}
        />
      </div>
    );
  }

  return (
    <div
      className={"convo-item" + (active ? " active" : "")}
      onClick={() => onSelect(convo.id)}
      ref={ref}
    >
      <span className="convo-title">{convo.title}</span>
      <span className="convo-time">{formatRelative(convo.updatedAt)}</span>
      <button
        className="convo-menu-btn"
        title="대화 메뉴"
        onClick={(e) => {
          e.stopPropagation();
          setMenuOpen((o) => !o);
        }}
      >
        <Icons.More size={14} />
      </button>

      {menuOpen && (
        <div
          className="project-dropdown convo-dropdown"
          role="menu"
          onClick={(e) => e.stopPropagation()}
        >
          <button className="dropdown-item" onClick={startEdit}>
            <span className="dropdown-icon">
              <Icons.Pencil size={13} />
            </span>
            <span>이름 변경</span>
          </button>
          <button className="dropdown-item convo-delete" onClick={handleDelete}>
            <span className="dropdown-icon">
              <Icons.Trash size={13} />
            </span>
            <span>삭제</span>
          </button>
        </div>
      )}
    </div>
  );
}
