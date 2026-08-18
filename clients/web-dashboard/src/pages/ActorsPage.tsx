import { ActorDecisionsCard } from "@/components/sources/ActorDecisionsCard";
import { ActorManagementCard } from "@/components/sources/ActorManagementCard";
import type { Project } from "@/types/api";

export function ActorsPage({ project }: { project: Project }) {
  return (
    <div className="sources-page">
      <h1 className="page-title">액터</h1>
      <p className="page-sub">여러 데이터 소스에서 수집된 사람 신원을 정리합니다.</p>

      <div className="actors-layout">
        <ActorManagementCard projectId={project.id} />
        <ActorDecisionsCard projectId={project.id} />
      </div>
    </div>
  );
}
