import { ActorDecisionsCard } from "@/components/sources/ActorDecisionsCard";
import { ActorManagementCard } from "@/components/sources/ActorManagementCard";
import { uiText } from "@/i18n/language";
import type { Project } from "@/types/api";

export function ActorsPage({ project }: { project: Project }) {
  return (
    <div className="sources-page">
      <h1 className="page-title">{uiText("액터", "Actors")}</h1>
      <p className="page-sub">
        {uiText(
          "여러 데이터 소스에서 수집된 사람 신원을 정리합니다.",
          "Sort out the people collected from your data sources.",
        )}
      </p>

      <div className="actors-layout">
        <ActorManagementCard projectId={project.id} />
        <ActorDecisionsCard projectId={project.id} />
      </div>
    </div>
  );
}
