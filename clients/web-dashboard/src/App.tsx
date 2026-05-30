import { Navigate, Route, Routes, useOutletContext } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";

import { AppShell } from "@/components/shell/AppShell";
import { AuthProvider, useAuth } from "@/auth/AuthProvider";
import { listProjects } from "@/api/projects";
import { AuthCallbackPage } from "@/pages/AuthCallbackPage";
import { ChatPage } from "@/pages/ChatPage";
import { GraphPage } from "@/pages/GraphPage";
import { LoginPage } from "@/pages/LoginPage";
import { OnboardingPage } from "@/pages/OnboardingPage";
import { SourcesPage } from "@/pages/SourcesPage";
import { SettingsPagePlaceholder } from "@/pages/placeholders";
import type { Project } from "@/types/api";

interface ShellContext {
  project: Project;
}

function useProject() {
  return useOutletContext<ShellContext>().project;
}

function AuthGate({ children }: { children: React.ReactNode }) {
  const { status } = useAuth();
  if (status === "loading") {
    return (
      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          height: "100vh",
          color: "var(--fg-muted)",
          fontSize: 13,
        }}
      >
        세션 확인 중…
      </div>
    );
  }
  if (status === "unauthenticated") {
    return <Navigate to="/login" replace />;
  }
  return <>{children}</>;
}

function ChatRoute() {
  return <ChatPage project={useProject()} />;
}
function SourcesRoute() {
  return <SourcesPage project={useProject()} />;
}
function GraphRoute() {
  return <GraphPage project={useProject()} />;
}
function SettingsRoute() {
  return <SettingsPagePlaceholder project={useProject()} />;
}

export default function App() {
  return (
    <AuthProvider>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/auth/callback" element={<AuthCallbackPage />} />
        <Route
          path="/onboarding"
          element={
            <AuthGate>
              <OnboardingPage />
            </AuthGate>
          }
        />
        <Route
          path="/projects/:projectId"
          element={
            <AuthGate>
              <AppShell />
            </AuthGate>
          }
        >
          <Route index element={<Navigate to="chat" replace />} />
          <Route path="chat" element={<ChatRoute />} />
          <Route path="chat/:conversationId" element={<ChatRoute />} />
          <Route path="sources" element={<SourcesRoute />} />
          <Route path="graph" element={<GraphRoute />} />
          <Route path="settings" element={<SettingsRoute />} />
        </Route>
        <Route path="/demo/graph" element={<DemoGraphRoute />} />
        <Route path="/" element={<RootRedirect />} />
        <Route path="*" element={<RootRedirect />} />
      </Routes>
    </AuthProvider>
  );
}

// 백엔드 없이 그래프 디자인만 확인할 때 쓰는 라우트.
function DemoGraphRoute() {
  const demoProject: Project = {
    id: "demo",
    ownerId: "demo",
    name: "Demo Project",
    description: "더미 그래프 데모",
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  };
  return <GraphPage project={demoProject} />;
}

function RootRedirect() {
  const { status } = useAuth();
  const projectsQuery = useQuery({
    queryKey: ["projects"],
    queryFn: listProjects,
    enabled: status === "authenticated",
  });

  if (status === "loading") return null;
  if (status === "unauthenticated") return <Navigate to="/login" replace />;
  if (projectsQuery.isLoading) return null;

  const projects = projectsQuery.data ?? [];
  if (projects.length === 0) return <Navigate to="/onboarding" replace />;
  return <Navigate to={`/projects/${projects[0].id}/chat`} replace />;
}
