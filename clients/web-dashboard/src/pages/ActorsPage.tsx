import { ActorManagementCard } from "@/components/sources/ActorManagementCard";
import { MonoChip } from "@/components/ui/MonoChip";
import type { Project } from "@/types/api";

export function ActorsPage({ project }: { project: Project }) {
  return (
    <div className="sources-page">
      <h1 className="page-title">액터</h1>
      <p className="page-sub">
        <MonoChip>{project.name}</MonoChip>{" "}
        · 여러 데이터 소스에서 수집된 사람 신원을 정리합니다.
      </p>

      <ActorManagementCard projectId={project.id} />
    </div>
  );
}
