import { useMemo, type ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { Navigate, Outlet, useLocation, useNavigate, useParams } from "react-router-dom";

import { Sidebar } from "./Sidebar";
import { Topbar } from "./Topbar";
import { StatusView } from "@/components/StatusView";
import { listConversations } from "@/api/conversations";
import { listProjects } from "@/api/projects";
import { useAuth } from "@/auth/AuthProvider";
import type { Project } from "@/types/api";

type Route = "chat" | "sources" | "graph" | "settings";

function routeFromPath(pathname: string): Route {
  if (pathname.endsWith("/sources")) return "sources";
  if (pathname.endsWith("/graph")) return "graph";
  if (pathname.endsWith("/settings")) return "settings";
  return "chat";
}

function crumbsFor(project: Project, route: Route): string[] {
  switch (route) {
    case "sources":
      return [project.name, "데이터 소스"];
    case "graph":
      return [project.name, "그래프 탐색"];
    case "settings":
      return [project.name, "설정"];
    case "chat":
    default:
      return [project.name, "대화"];
  }
}

interface ShellContext {
  project: Project;
}

export function shellContext(): ShellContext {
  // placeholder so that useOutletContext can be typed in pages later
  throw new Error("shellContext() must not be called directly");
}

export function AppShell({ children }: { children?: ReactNode }) {
  const { user } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const params = useParams();
  const projectId = params.projectId;

  const projectsQuery = useQuery({
    queryKey: ["projects"],
    queryFn: listProjects,
  });

  const conversationsQuery = useQuery({
    queryKey: ["conversations", projectId],
    queryFn: () => listConversations(projectId!),
    enabled: Boolean(projectId),
  });

  const projects = projectsQuery.data ?? [];
  const project = useMemo(
    () => projects.find((p) => p.id === projectId) ?? null,
    [projects, projectId],
  );

  if (projectsQuery.isLoading) {
    return <StatusView tone="loading" description="프로젝트 불러오는 중…" fullPage />;
  }

  if (projectsQuery.isError) {
    return (
      <StatusView
        tone="error"
        title="프로젝트를 불러오지 못했어요"
        description="네트워크나 서버 상태를 확인한 뒤 새로고침해 주세요."
        action={
          <button
            className="btn btn-primary"
            onClick={() => window.location.reload()}
          >
            새로고침
          </button>
        }
        fullPage
      />
    );
  }

  if (projects.length === 0) {
    return <Navigate to="/onboarding" replace />;
  }

  if (!project) {
    return <Navigate to={`/projects/${projects[0].id}/chat`} replace />;
  }

  const route = routeFromPath(location.pathname);

  const handleRouteChange = (r: Route) => {
    navigate(`/projects/${project.id}/${r}`);
  };

  const handleSwitchProject = (id: string) => {
    navigate(`/projects/${id}/${route}`);
  };

  const handleSelectConvo = (cid: string) => {
    navigate(`/projects/${project.id}/chat/${cid}`);
  };

  const handleNewConvo = () => {
    navigate(`/projects/${project.id}/chat`);
  };

  const activeConvoId = params.conversationId ?? null;

  return (
    <div className="app">
      <Sidebar
        route={route}
        onRouteChange={handleRouteChange}
        user={user}
        project={project}
        projects={projects}
        onSwitchProject={handleSwitchProject}
        onNewProject={() => navigate("/onboarding")}
        conversations={conversationsQuery.data ?? []}
        activeConvoId={activeConvoId}
        onSelectConvo={handleSelectConvo}
        onNewConvo={handleNewConvo}
      />
      <div className="main">
        {route !== "graph" && <Topbar crumbs={crumbsFor(project, route)} />}
        {children ?? <Outlet context={{ project } satisfies ShellContext} />}
      </div>
    </div>
  );
}

