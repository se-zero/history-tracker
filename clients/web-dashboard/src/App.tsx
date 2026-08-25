import { Navigate, Route, Routes, useOutletContext } from "react-router-dom";

import { AppShell } from "@/components/shell/AppShell";
import { ErrorBoundary } from "@/components/ErrorBoundary";
import { StatusView } from "@/components/StatusView";
import { AuthProvider, useAuth } from "@/auth/AuthProvider";
import { useProjects } from "@/hooks/useProjects";
import { AccountPage } from "@/pages/AccountPage";
import { ActorsPage } from "@/pages/ActorsPage";
import { AuthCallbackPage } from "@/pages/AuthCallbackPage";
import { ChatPage } from "@/pages/ChatPage";
import { GraphPage } from "@/pages/GraphPage";
import { LandingPage } from "@/pages/LandingPage";
import { NotFoundPage } from "@/pages/NotFoundPage";
import { OnboardingPage } from "@/pages/OnboardingPage";
import { PrivacyPage } from "@/pages/PrivacyPage";
import { SettingsPage } from "@/pages/SettingsPage";
import { SourcesPage } from "@/pages/SourcesPage";
import { TermsPage } from "@/pages/TermsPage";
import { PATHS } from "@/routes";
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
    return <StatusView tone="loading" description="세션 확인 중…" fullPage />;
  }
  if (status === "unauthenticated") {
    return <Navigate to={PATHS.landing} replace />;
  }
  return <>{children}</>;
}

function ChatRoute() {
  return <ChatPage project={useProject()} />;
}
function SourcesRoute() {
  return <SourcesPage project={useProject()} />;
}
function ActorsRoute() {
  return <ActorsPage project={useProject()} />;
}
function GraphRoute() {
  return <GraphPage project={useProject()} />;
}
function SettingsRoute() {
  return <SettingsPage project={useProject()} />;
}
// 계정 설정은 프로젝트 컨텍스트를 쓰지 않는다(계정 단위).
function AccountRoute() {
  return <AccountPage />;
}

export default function App() {
  return (
    <ErrorBoundary>
      <AuthProvider>
        <Routes>
          {/* 제품 소개·약관 페이지 — 인증 가드 없는 공개 라우트 */}
          <Route path={PATHS.landing} element={<LandingPage />} />
          <Route path={PATHS.terms} element={<TermsPage />} />
          <Route path={PATHS.privacy} element={<PrivacyPage />} />
          <Route path={PATHS.authCallback} element={<AuthCallbackPage />} />
          <Route
            path={PATHS.onboarding}
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
            <Route path="actors" element={<ActorsRoute />} />
            <Route path="graph" element={<GraphRoute />} />
            <Route path="settings" element={<SettingsRoute />} />
            <Route path="account" element={<AccountRoute />} />
            <Route path="*" element={<NotFoundPage />} />
          </Route>
          <Route path={PATHS.root} element={<RootRedirect />} />
          <Route path="*" element={<NotFoundPage />} />
        </Routes>
      </AuthProvider>
    </ErrorBoundary>
  );
}

function RootRedirect() {
  const { status } = useAuth();
  const projectsQuery = useProjects({ enabled: status === "authenticated" });

  if (status === "loading") {
    return <StatusView tone="loading" description="세션 확인 중…" fullPage />;
  }
  if (status === "unauthenticated") return <Navigate to={PATHS.landing} replace />;
  if (projectsQuery.isLoading) {
    return <StatusView tone="loading" description="프로젝트를 불러오는 중…" fullPage />;
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

  const projects = projectsQuery.data ?? [];
  if (projects.length === 0) return <Navigate to={PATHS.onboarding} replace />;
  return <Navigate to={`/projects/${projects[0].id}/chat`} replace />;
}
