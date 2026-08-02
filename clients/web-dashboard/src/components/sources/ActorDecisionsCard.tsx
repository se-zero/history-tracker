import { useState } from "react";

import { InlineError } from "@/components/ui/InlineError";
import { useActorDecisions } from "@/hooks/useActors";
import { sourceNameSummary } from "./actorFormat";
import { useCollapsedRows } from "./useCollapsedRows";

export function ActorDecisionsCard({ projectId }: { projectId: string }) {
  const decisionsQuery = useActorDecisions(projectId);
  const decisions = decisionsQuery.data ?? [];
  const [listExpanded, setListExpanded] = useState(false);
  const { ref: listRef, maxHeight: listMaxHeight } = useCollapsedRows(4, decisions.length);

  return (
    <section className="actor-card">
      <div className="actor-head">
        <div>
          <h2>병합·분리 결정 이력</h2>
        </div>
      </div>

      {decisionsQuery.isLoading && <p className="actor-empty">결정 이력을 불러오는 중…</p>}
      {decisionsQuery.isError && <InlineError>결정 이력을 불러오지 못했어요.</InlineError>}
      {!decisionsQuery.isLoading && !decisionsQuery.isError && decisions.length === 0 && (
        <p className="actor-empty">아직 병합·분리 결정 이력이 없습니다.</p>
      )}

      {decisions.length > 0 && (
        <div className="actor-list">
          <div
            className={listExpanded ? "actor-list-rows" : "actor-list-rows actor-list-collapsed"}
            ref={listRef}
            style={!listExpanded && listMaxHeight !== undefined ? { maxHeight: listMaxHeight } : undefined}
          >
            {decisions.map((decision) => (
              <div className="actor-decision" key={decision.decisionId}>
                <div>
                  <strong>{decision.kind === "same" ? "수동 병합" : "분리"}</strong>
                  {/* 병합은 양방향(↔), 분리는 "남은 계정들 → 떨어져 나간 계정"의 방향(→)으로 읽는다 */}
                  <p>
                    {decision.aliasesA.map(sourceNameSummary).join(", ")}{" "}
                    {decision.kind === "same" ? "↔" : "→"}{" "}
                    {decision.aliasesB.map(sourceNameSummary).join(", ")}
                  </p>
                  {decision.kind === "distinct" && (
                    <span className="actor-decision-hint">분리된 계정은 자동으로 다시 합쳐지지 않습니다</span>
                  )}
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
