import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";

import { Icons } from "@/components/Icons";
import { useAuth } from "@/auth/AuthProvider";
import { useTheme } from "@/theme/ThemeProvider";
import { PATHS } from "@/routes";
import { ProjectSwitcher } from "./ProjectSwitcher";
import { ConversationList } from "./ConversationList";
import { useUiLanguage } from "@/i18n/language";
import { userInitials } from "@/lib/format";
import type { Project, User } from "@/types/api";

type Route =
  | "chat"
  | "sources"
  | "actors"
  | "graph"
  | "settings"
  | "account";

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
  const { lang } = useUiLanguage();
  const nav =
    lang === "en"
      ? {
          search: "Search",
          chat: "Chat",
          sources: "Sources",
          actors: "Actors",
          graph: "Review graph",
          settings: "Project settings",
        }
      : {
          search: "검색",
          chat: "대화",
          sources: "데이터 소스",
          actors: "액터",
          graph: "그래프 확인",
          settings: "현재 프로젝트 설정",
        };
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
          {nav.search}
          <span className="nav-shortcut mono">{SEARCH_SHORTCUT_LABEL}</span>
        </div>
        <NavItem
          icon={<Icons.Chat />}
          label={nav.chat}
          active={route === "chat"}
          onClick={() => onRouteChange("chat")}
        />
        <NavItem
          icon={<Icons.Plug />}
          label={nav.sources}
          active={route === "sources"}
          onClick={() => onRouteChange("sources")}
        />
        <NavItem
          icon={<Icons.People />}
          label={nav.actors}
          active={route === "actors"}
          onClick={() => onRouteChange("actors")}
        />
        <NavItem
          icon={<Icons.Sparkle />}
          label={nav.graph}
          active={route === "graph"}
          onClick={() => onRouteChange("graph")}
        />
        <NavItem
          icon={<Icons.Settings />}
          label={nav.settings}
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
  const { lang } = useUiLanguage();
  const { user, logout } = useAuth();
  const copy =
    lang === "en"
      ? {
          fallbackName: "User",
          menu: "Menu",
          toLight: "Switch to light",
          toDark: "Switch to dark",
          account: "Account",
          logout: "Log out",
        }
      : {
          fallbackName: "사용자",
          menu: "메뉴",
          toLight: "Light 모드로",
          toDark: "Dark 모드로",
          account: "계정 설정",
          logout: "로그아웃",
        };
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
    navigate(PATHS.landing, { replace: true });
  };

  return (
    <div className="user-card" style={{ position: "relative" }} ref={ref}>
      <div className="avatar">{userInitials(user)}</div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div className="user-name">{user?.displayName ?? copy.fallbackName}</div>
        <div className="user-handle mono">{userHandle(user)}</div>
      </div>
      <button
        className="icon-btn"
        title={copy.menu}
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
            <span>{theme === "dark" ? copy.toLight : copy.toDark}</span>
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
            <span>{copy.account}</span>
          </button>
          <button className="dropdown-item" onClick={handleLogout}>
            <span className="dropdown-icon">
              <Icons.ArrowRight size={13} />
            </span>
            <span>{copy.logout}</span>
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
