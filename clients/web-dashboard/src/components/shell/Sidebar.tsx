import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";

import { Icons } from "@/components/Icons";
import { useAuth } from "@/auth/AuthProvider";
import { useTheme } from "@/theme/ThemeProvider";
import { ProjectSwitcher } from "./ProjectSwitcher";
import { ConversationList } from "./ConversationList";
import { userInitials } from "@/lib/format";
import type { Project, User } from "@/types/api";

type Route = "chat" | "sources" | "actors" | "graph" | "settings" | "account";

interface Props {
  route: Route;
  onRouteChange: (route: Route) => void;
  project: Project | null;
  projects: Project[];
  onSwitchProject: (id: string) => void;
  onNewProject: () => void;
  onReorderProjects: (orderedIds: string[]) => void;
  onOpenSearch: () => void;
}

// 단축키 힌트 표기 — Mac은 ⌘K, 그 외는 Ctrl+K
const SEARCH_SHORTCUT_LABEL = navigator.userAgent.includes("Mac")
  ? "⌘K"
  : "Ctrl+K";

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
  project,
  projects,
  onSwitchProject,
  onNewProject,
  onReorderProjects,
  onOpenSearch,
}: Props) {
  return (
    <aside className="sidebar">
      <div className="sidebar-section">
        <ProjectSwitcher
          project={project}
          projects={projects}
          onSwitch={onSwitchProject}
          onNewProject={onNewProject}
          onReorder={onReorderProjects}
        />
      </div>
      <div className="sidebar-divider" />
      <div
        className="sidebar-section"
        style={{ display: "flex", flexDirection: "column", gap: 2 }}
      >
        <div className="nav-item" onClick={onOpenSearch}>
          <span className="nav-icon">
            <Icons.Search />
          </span>
          검색
          <span className="nav-shortcut mono">{SEARCH_SHORTCUT_LABEL}</span>
        </div>
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
          icon={<Icons.People />}
          label="액터"
          active={route === "actors"}
          onClick={() => onRouteChange("actors")}
        />
        <NavItem
          icon={<Icons.Graph />}
          label="그래프 탐색"
          active={route === "graph"}
          onClick={() => onRouteChange("graph")}
        />
        <NavItem
          icon={<Icons.Settings />}
          label="현재 프로젝트 설정"
          active={route === "settings"}
          onClick={() => onRouteChange("settings")}
        />
      </div>
      <div className="sidebar-divider" />
      {project && <ConversationList projectId={project.id} />}
      <UserMenu onOpenAccount={() => onRouteChange("account")} />
    </aside>
  );
}

function UserMenu({ onOpenAccount }: { onOpenAccount: () => void }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const navigate = useNavigate();
  const { user, logout } = useAuth();
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
          <button
            className="dropdown-item"
            onClick={() => {
              onOpenAccount();
              setOpen(false);
            }}
          >
            <span className="dropdown-icon">
              <Icons.People size={13} />
            </span>
            <span>계정 설정</span>
          </button>
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
