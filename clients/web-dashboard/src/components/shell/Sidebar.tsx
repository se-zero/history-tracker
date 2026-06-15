import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";

import { Icons } from "@/components/Icons";
import { useAuth } from "@/auth/AuthProvider";
import { useTheme } from "@/theme/ThemeProvider";
import { ProjectSwitcher } from "./ProjectSwitcher";
import type { Conversation, Project, User } from "@/types/api";

type Route = "chat" | "sources" | "graph" | "settings";

interface Props {
  route: Route;
  onRouteChange: (route: Route) => void;
  user: User | null;
  project: Project | null;
  projects: Project[];
  onSwitchProject: (id: string) => void;
  onNewProject: () => void;
  conversations: Conversation[];
  activeConvoId: string | null;
  onSelectConvo: (id: string) => void;
  onNewConvo: () => void;
  onRenameConvo: (id: string, title: string) => void;
  onDeleteConvo: (id: string) => void;
}

function userInitials(user: User | null): string {
  if (!user?.displayName) return "?";
  const tokens = user.displayName.trim().split(/\s+/);
  if (tokens.length === 1) return tokens[0].slice(0, 2).toUpperCase();
  return (tokens[0][0] + tokens[tokens.length - 1][0]).toUpperCase();
}

function userHandle(user: User | null): string {
  if (!user) return "@unknown";
  if (user.email) {
    const local = user.email.split("@")[0];
    return `@${local}`;
  }
  return `@${user.providerUserId}`;
}

export function Sidebar({
  route,
  onRouteChange,
  user,
  project,
  projects,
  onSwitchProject,
  onNewProject,
  conversations,
  activeConvoId,
  onSelectConvo,
  onNewConvo,
  onRenameConvo,
  onDeleteConvo,
}: Props) {
  return (
    <aside className="sidebar">
      <div className="sidebar-section">
        <ProjectSwitcher
          project={project}
          projects={projects}
          onSwitch={onSwitchProject}
          onNewProject={onNewProject}
          onOpenSettings={() => onRouteChange("settings")}
        />
      </div>
      <div className="sidebar-divider" />
      <div
        className="sidebar-section"
        style={{ display: "flex", flexDirection: "column", gap: 2 }}
      >
        <NavItem
          icon={<Icons.Chat />}
          label="대화"
          active={route === "chat"}
          onClick={() => onRouteChange("chat")}
        />
        <NavItem
          icon={<Icons.Plug />}
          label="데이터 소스"
          active={route === "sources"}
          onClick={() => onRouteChange("sources")}
        />
        <NavItem
          icon={<Icons.Graph />}
          label="그래프 탐색"
          active={route === "graph"}
          onClick={() => onRouteChange("graph")}
        />
        <NavItem
          icon={<Icons.Settings />}
          label="설정"
          active={route === "settings"}
          onClick={() => onRouteChange("settings")}
        />
      </div>
      <div className="sidebar-divider" />
      <div className="sidebar-label">
        <span>대화</span>
        <button className="add-btn" title="새 대화" onClick={onNewConvo}>
          <Icons.Plus size={13} />
        </button>
      </div>
      <div className="convo-list">
        {conversations.map((c) => (
          <ConvoItem
            key={c.id}
            convo={c}
            active={activeConvoId === c.id}
            onSelect={onSelectConvo}
            onRename={onRenameConvo}
            onDelete={onDeleteConvo}
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
      </div>
      <UserMenu user={user} />
    </aside>
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

  const commitEdit = () => {
    setEditing(false);
    const next = draft.trim();
    if (next && next !== convo.title) onRename(convo.id, next);
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
              setEditing(false);
            }
          }}
          onBlur={commitEdit}
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

function UserMenu({ user }: { user: User | null }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const navigate = useNavigate();
  const { logout } = useAuth();
  const { theme, toggle } = useTheme();

  useEffect(() => {
    if (!open) return;
    const h = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", h);
    return () => document.removeEventListener("mousedown", h);
  }, [open]);

  const handleLogout = async () => {
    setOpen(false);
    await logout();
    navigate("/login", { replace: true });
  };

  return (
    <div className="user-card" style={{ position: "relative" }} ref={ref}>
      <div className="avatar">{userInitials(user)}</div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div className="user-name">{user?.displayName ?? "사용자"}</div>
        <div className="user-handle mono">{userHandle(user)}</div>
      </div>
      <button
        className="icon-btn"
        title="메뉴"
        onClick={() => setOpen((o) => !o)}
      >
        <Icons.More />
      </button>

      {open && (
        <div
          className="project-dropdown"
          role="menu"
          style={{
            top: "auto",
            bottom: "calc(100% + 6px)",
          }}
        >
          <button
            className="dropdown-item"
            onClick={() => {
              toggle();
              setOpen(false);
            }}
          >
            <span className="dropdown-icon">
              {theme === "dark" ? (
                <Icons.Sun size={13} />
              ) : (
                <Icons.Moon size={13} />
              )}
            </span>
            <span>{theme === "dark" ? "Light 모드로" : "Dark 모드로"}</span>
          </button>
          <div className="dropdown-divider" />
          <button className="dropdown-item" onClick={handleLogout}>
            <span className="dropdown-icon">
              <Icons.ArrowRight size={13} />
            </span>
            <span>로그아웃</span>
          </button>
        </div>
      )}
    </div>
  );
}

function NavItem({
  icon,
  label,
  active,
  onClick,
}: {
  icon: React.ReactNode;
  label: string;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <div className={"nav-item" + (active ? " active" : "")} onClick={onClick}>
      <span className="nav-icon">{icon}</span>
      {label}
    </div>
  );
}

function formatRelative(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime();
  const m = Math.floor(diff / 60_000);
  if (m < 1) return "방금";
  if (m < 60) return `${m}분 전`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}시간 전`;
  const d = Math.floor(h / 24);
  if (d < 7) return `${d}일 전`;
  return new Date(iso).toLocaleDateString("ko-KR");
}
