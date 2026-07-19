import { useEffect, useMemo, useState, type ReactNode } from "react";
import { Navigate, Outlet, useLocation, useNavigate, useParams } from "react-router-dom";

import { Sidebar } from "./Sidebar";
import { Topbar } from "./Topbar";
import { SearchDialog } from "@/components/search/SearchDialog";
import { StatusView } from "@/components/StatusView";
import { useProjects, useReorderProjects } from "@/hooks/useProjects";
import type { Project } from "@/types/api";

type Route = "chat" | "sources" | "actors" | "graph" | "settings" | "account";

function routeFromPath(pathname: string): Route {
  if (pathname.endsWith("/sources")) return "sources";
  if (pathname.endsWith("/actors")) return "actors";
  if (pathname.endsWith("/graph")) return "graph";
  if (pathname.endsWith("/settings")) return "settings";
  if (pathname.endsWith("/account")) return "account";
  return "chat";
}

function crumbsFor(project: Project, route: Route): string[] {
  switch (route) {
    case "sources":
      return [project.name, "데이터 소스"];
    case "actors":
      return [project.name, "액터"];
    case "graph":
      return [project.name, "그래프 탐색"];
    case "settings":
      return [project.name, "설정"];
    case "account":
      // 계정 설정은 프로젝트 단위가 아니라 계정 단위라 프로젝트명을 붙이지 않는다.
      return ["계정 설정"];
    case "chat":
    default:
      return [project.name, "대화"];
  }
}

interface ShellContext {
  project: Project;
}

export function AppShell({ children }: { children?: ReactNode }) {
  const navigate = useNavigate();
  const location = useLocation();
  const params = useParams();
  const projectId = params.projectId;

  const projectsQuery = useProjects();
  const reorderProjects = useReorderProjects();

  // 통합 검색(⌘K/Ctrl+K) — 다이얼로그는 열릴 때만 마운트해 입력 상태를 매번 초기화한다
  const [searchOpen, setSearchOpen] = useState(false);
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setSearchOpen((open) => !open);
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, []);

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
    // 빈 목록이지만 아직 refetch 중이면(예: 생성 직후 stale [] 캐시) 결과를 기다린다.
    // 곧바로 /onboarding으로 보내면 방금 만든 프로젝트로의 이동이 되튕긴다.
    if (projectsQuery.isFetching) {
      return <StatusView tone="loading" description="프로젝트 불러오는 중…" fullPage />;
    }
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

  return (
    <div className="app">
      <Sidebar
        route={route}
        onRouteChange={handleRouteChange}
        project={project}
        projects={projects}
        onSwitchProject={handleSwitchProject}
        onNewProject={() => navigate("/onboarding")}
        onReorderProjects={(orderedIds) => reorderProjects.mutate(orderedIds)}
        onOpenSearch={() => setSearchOpen(true)}
      />
      <div className="main">
        {route !== "graph" && <Topbar crumbs={crumbsFor(project, route)} />}
        {children ?? <Outlet context={{ project } satisfies ShellContext} />}
      </div>
      {searchOpen && (
        <SearchDialog project={project} onClose={() => setSearchOpen(false)} />
      )}
    </div>
  );
}
