import { useState } from "react";

import { InlineError } from "@/components/ui/InlineError";
import { useActorDecisions } from "@/hooks/useActors";
import { sourceNameSummary } from "./actorFormat";
import { useCollapsedRows } from "./useCollapsedRows";

export function ActorDecisionsCard({ projectId }: { projectId: string }) {
  const decisionsQuery = useActorDecisions(projectId);
  // distinct(자동 병합 방지)는 이력 카드에 노출하지 않는다 — same(수동 병합)만 보여준다.
  const mergeDecisions = decisionsQuery.data?.filter((decision) => decision.kind === "same") ?? [];
  const [listExpanded, setListExpanded] = useState(false);
  const { ref: listRef, maxHeight: listMaxHeight } = useCollapsedRows(4, mergeDecisions.length);

  return (
    <section className="actor-card">
      <div className="actor-head">
        <div>
          <h2>병합 결정 이력</h2>
        </div>
      </div>

      {decisionsQuery.isLoading && <p className="actor-empty">결정 이력을 불러오는 중…</p>}
      {decisionsQuery.isError && <InlineError>결정 이력을 불러오지 못했어요.</InlineError>}
      {!decisionsQuery.isLoading && !decisionsQuery.isError && mergeDecisions.length === 0 && (
        <p className="actor-empty">아직 병합 결정 이력이 없습니다.</p>
      )}

      {mergeDecisions.length > 0 && (
        <div className="actor-list">
          <div
            className={listExpanded ? "actor-list-rows" : "actor-list-rows actor-list-collapsed"}
            ref={listRef}
            style={!listExpanded && listMaxHeight !== undefined ? { maxHeight: listMaxHeight } : undefined}
          >
            {mergeDecisions.map((decision) => (
              <div className="actor-decision" key={decision.decisionId}>
                <div>
                  <strong>수동 병합</strong>
                  <p>
                    {decision.aliasesA.map(sourceNameSummary).join(", ")} ↔{" "}
                    {decision.aliasesB.map(sourceNameSummary).join(", ")}
                  </p>
                  {decision.note && <p className="actor-meta">{decision.note}</p>}
                </div>
              </div>
            ))}
          </div>
          <button className="repo-toggle" type="button" onClick={() => setListExpanded((value) => !value)}>
            {listExpanded ? "닫기" : "펼치기"}
          </button>
        </div>
      )}
    </section>
  );
}
