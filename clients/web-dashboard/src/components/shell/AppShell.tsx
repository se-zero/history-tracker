import { useMemo, type ReactNode } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Navigate, Outlet, useLocation, useNavigate, useParams } from "react-router-dom";

import { Sidebar } from "./Sidebar";
import { Topbar } from "./Topbar";
import { StatusView } from "@/components/StatusView";
import {
  deleteConversation,
  updateConversationTitle,
} from "@/api/conversations";
import { useAuth } from "@/auth/AuthProvider";
import { queryKeys } from "@/hooks/queryKeys";
import { useConversations } from "@/hooks/useConversations";
import { useProjects } from "@/hooks/useProjects";
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

export function AppShell({ children }: { children?: ReactNode }) {
  const { user } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const params = useParams();
  const queryClient = useQueryClient();
  const projectId = params.projectId;

  const projectsQuery = useProjects();

  const conversationsQuery = useConversations(projectId);

  const renameConvoMutation = useMutation({
    mutationFn: ({ id, title }: { id: string; title: string }) =>
      updateConversationTitle(projectId!, id, title),
    onSuccess: (updated) => {
      queryClient.invalidateQueries({ queryKey: queryKeys.conversations(projectId) });
      queryClient.invalidateQueries({
        queryKey: queryKeys.conversation(projectId, updated.id),
      });
    },
  });

  const deleteConvoMutation = useMutation({
    mutationFn: (id: string) => deleteConversation(projectId!, id),
    onSuccess: (_data, id) => {
      queryClient.invalidateQueries({ queryKey: queryKeys.conversations(projectId) });
      queryClient.removeQueries({ queryKey: queryKeys.conversation(projectId, id) });
    },
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

  const handleSelectConvo = (cid: string) => {
    navigate(`/projects/${project.id}/chat/${cid}`);
  };

  const handleNewConvo = () => {
    navigate(`/projects/${project.id}/chat`);
  };

  const activeConvoId = params.conversationId ?? null;

  const handleRenameConvo = (id: string, title: string) => {
    renameConvoMutation.mutate({ id, title });
  };

  const handleDeleteConvo = (id: string) => {
    deleteConvoMutation.mutate(id, {
      onSuccess: () => {
        // 현재 보고 있던 대화를 지우면 빈 채팅 화면으로 이동
        if (activeConvoId === id) {
          navigate(`/projects/${project.id}/chat`, { replace: true });
        }
      },
    });
  };

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
        onRenameConvo={handleRenameConvo}
        onDeleteConvo={handleDeleteConvo}
      />
      <div className="main">
        {route !== "graph" && <Topbar crumbs={crumbsFor(project, route)} />}
        {children ?? <Outlet context={{ project } satisfies ShellContext} />}
      </div>
    </div>
  );
}

