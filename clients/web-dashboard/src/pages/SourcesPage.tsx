import { MonoChip } from "@/components/ui/MonoChip";
import { GitHubCard } from "@/components/sources/GitHubCard";
import { IngestStatus } from "@/components/sources/IngestStatus";
import { JiraCard } from "@/components/sources/JiraCard";
import { OAuthResultBanner } from "@/components/sources/OAuthResultBanner";
import { SlackCard } from "@/components/sources/SlackCard";
import type { Project } from "@/types/api";

export function SourcesPage({ project }: { project: Project }) {
  return (
    <div className="sources-page">
      <h1 className="page-title">데이터 소스</h1>
      <p className="page-sub">
        <MonoChip>{project.name}</MonoChip>{" "}
        · 코드와 의사결정의 원본이 모이는 곳. GitHub은 필수, Jira와 Slack은 선택.
      </p>

      <OAuthResultBanner />

      <div className="source-grid">
        <GitHubCard projectId={project.id} />
        <JiraCard projectId={project.id} />
        <SlackCard projectId={project.id} />
      </div>

      <IngestStatus projectId={project.id} />
    </div>
  );
}
