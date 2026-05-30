import { Icons } from "@/components/Icons";
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
          <div
            key={c.id}
            className={"convo-item" + (activeConvoId === c.id ? " active" : "")}
            onClick={() => onSelectConvo(c.id)}
          >
            <span className="convo-title">{c.title}</span>
            <span className="convo-time">{formatRelative(c.updatedAt)}</span>
          </div>
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
      <div className="user-card">
        <div className="avatar">{userInitials(user)}</div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div className="user-name">{user?.displayName ?? "사용자"}</div>
          <div className="user-handle mono">{userHandle(user)}</div>
        </div>
        <button className="icon-btn">
          <Icons.More />
        </button>
      </div>
    </aside>
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
