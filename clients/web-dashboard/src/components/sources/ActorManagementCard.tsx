import { useState } from "react";
import axios from "axios";

import { InlineError } from "@/components/ui/InlineError";
import {
  useActorDecisions,
  useActors,
  useMergeActors,
  useRenameActor,
  useRevokeActorDecision,
  useSplitActor,
  useUnmergeActors,
} from "@/hooks/useActors";
import type { Actor } from "@/types/api";

function actorLabel(actor: Actor) {
  return `${actor.name} · ${actor.aliases.join(", ") || actor.uuid}`;
}

function errorMessage(error: unknown, fallback: string) {
  if (!axios.isAxiosError(error)) return fallback;
  // backend 에러 응답은 사유를 message 필드에 담는다(ErrorResponse). detail은 ai-engine(FastAPI)
  // 컨벤션이라, 직접 노출되는 경우까지 대비해 message를 우선 보고 detail을 폴백으로 읽는다.
  const data = error.response?.data as { message?: unknown; detail?: unknown } | undefined;
  const reason = data?.message ?? data?.detail;
  return typeof reason === "string" && reason ? reason : fallback;
}

export function ActorManagementCard({ projectId }: { projectId: string }) {
  const actorsQuery = useActors(projectId);
  const decisionsQuery = useActorDecisions(projectId);
  const mergeMutation = useMergeActors(projectId);
  const renameMutation = useRenameActor(projectId);
  const splitMutation = useSplitActor(projectId);
  const unmergeMutation = useUnmergeActors(projectId);
  const revokeMutation = useRevokeActorDecision(projectId);

  const actors = actorsQuery.data ?? [];
  const [mergeOpen, setMergeOpen] = useState(false);
  const [sourceUuid, setSourceUuid] = useState("");
  const [targetUuid, setTargetUuid] = useState("");
  const [mergeName, setMergeName] = useState("");
  const [splitActorUuid, setSplitActorUuid] = useState<string | null>(null);
  const [splitAliases, setSplitAliases] = useState<string[]>([]);
  const [splitName, setSplitName] = useState("");
  const [renameActorUuid, setRenameActorUuid] = useState<string | null>(null);
  const [renameName, setRenameName] = useState("");
  const [showDecisions, setShowDecisions] = useState(false);

  const openMerge = () => {
    setSourceUuid(actors[0]?.uuid ?? "");
    setTargetUuid(actors[1]?.uuid ?? "");
    setMergeName(actors[0]?.name ?? "");
    setMergeOpen(true);
  };

  const openSplit = (actor: Actor) => {
    setSplitActorUuid(actor.uuid);
    setSplitAliases([]);
    setSplitName("");
  };

  const openRename = (actor: Actor) => {
    setRenameActorUuid(actor.uuid);
    setRenameName(actor.name);
  };

  const splitActor = actors.find((actor) => actor.uuid === splitActorUuid);
  const renameActor = actors.find((actor) => actor.uuid === renameActorUuid);
  const canMerge = sourceUuid !== "" && targetUuid !== "" && sourceUuid !== targetUuid;
  const canSplit = Boolean(
    splitActor && splitAliases.length > 0 && splitAliases.length < splitActor.aliases.length,
  );
  const canRename = Boolean(
    renameActor && renameName.trim() && renameName.trim() !== renameActor.name,
  );

  return (
    <section className="actor-card">
      <div className="actor-head">
        <div>
          <h2>액터 관리</h2>
          <p>여러 소스에서 수집된 동일인을 병합하고, 잘못된 자동 통합은 분리할 수 있습니다.</p>
        </div>
        <button className="btn btn-primary" onClick={openMerge} disabled={actors.length < 2}>
          액터 합치기
        </button>
      </div>

      {actorsQuery.isLoading && <p className="actor-empty">액터 정보를 불러오는 중…</p>}
      {actorsQuery.isError && (
        <InlineError>액터 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.</InlineError>
      )}
      {!actorsQuery.isLoading && !actorsQuery.isError && actors.length === 0 && (
        <p className="actor-empty">아직 수집된 액터가 없습니다.</p>
      )}

      {actors.length > 0 && (
        <div className="actor-list">
          {actors.map((actor) => (
            <div className="actor-row" key={actor.uuid}>
              <div className="actor-info">
                <strong>{actor.name}</strong>
                <span className="actor-meta">활동 {actor.activityCount}건 · 신뢰도 {Math.round(actor.confidence * 100)}%</span>
                <div className="actor-aliases">
                  {actor.aliases.map((alias) => <code key={alias}>{alias}</code>)}
                  {actor.emails.map((email) => <code key={email}>{email}</code>)}
                </div>
              </div>
              <div className="actor-row-actions">
                <button className="btn btn-ghost" onClick={() => openRename(actor)}>
                  이름 변경
                </button>
                <button
                  className="btn btn-ghost"
                  onClick={() => openSplit(actor)}
                  disabled={actor.aliases.length < 2}
                >
                  분리
                </button>
              </div>
            </div>
          ))}
        </div>
      )}

      {renameActor && (
        <form
          className="actor-form actor-rename-form"
          onSubmit={(event) => {
            event.preventDefault();
            if (!canRename || !window.confirm("액터 이름을 변경할까요?")) return;
            renameMutation.mutate(
              { actorUuid: renameActor.uuid, name: renameName.trim() },
              { onSuccess: () => setRenameActorUuid(null) },
            );
          }}
        >
          <h3>{renameActor.name} 이름 변경</h3>
          <label>
            새 이름
            <input
              value={renameName}
              onChange={(event) => setRenameName(event.target.value)}
              maxLength={200}
              autoFocus
            />
          </label>
          {renameMutation.isError && <InlineError>{errorMessage(renameMutation.error, "이름 변경에 실패했어요.")}</InlineError>}
          <div className="actor-form-actions">
            <button className="btn btn-primary" type="submit" disabled={!canRename || renameMutation.isPending}>
              {renameMutation.isPending ? "변경 중…" : "변경"}
            </button>
            <button className="btn btn-ghost" type="button" onClick={() => setRenameActorUuid(null)}>취소</button>
          </div>
        </form>
      )}

      {mergeOpen && (
        <form
          className="actor-form"
          onSubmit={(event) => {
            event.preventDefault();
            if (!canMerge || !window.confirm("두 액터를 같은 사람으로 합칠까요? 나중에 병합 취소로 되돌릴 수 있습니다.")) return;
            mergeMutation.mutate(
              { sourceUuid, targetUuid, name: mergeName.trim() },
              { onSuccess: () => setMergeOpen(false) },
            );
          }}
        >
          <h3>액터 합치기</h3>
          <p>같은 사람의 두 노드를 하나로 합치고, 합친 뒤의 이름을 정합니다.</p>
          <label>
            합칠 액터 ①
            <select value={sourceUuid} onChange={(event) => setSourceUuid(event.target.value)}>
              <option value="">선택하세요</option>
              {actors.map((actor) => <option value={actor.uuid} key={actor.uuid}>{actorLabel(actor)}</option>)}
            </select>
          </label>
          <label>
            합칠 액터 ②
            <select value={targetUuid} onChange={(event) => setTargetUuid(event.target.value)}>
              <option value="">선택하세요</option>
              {actors.map((actor) => <option value={actor.uuid} key={actor.uuid}>{actorLabel(actor)}</option>)}
            </select>
          </label>
          <label>
            합친 뒤 이름
            <input
              value={mergeName}
              onChange={(event) => setMergeName(event.target.value)}
              maxLength={200}
              placeholder="합쳐진 액터의 표시 이름"
              autoFocus
            />
          </label>
          {mergeMutation.isError && <InlineError>{errorMessage(mergeMutation.error, "합치기에 실패했어요.")}</InlineError>}
          <div className="actor-form-actions">
            <button className="btn btn-primary" type="submit" disabled={!canMerge || mergeMutation.isPending}>
              {mergeMutation.isPending ? "합치는 중…" : "합치기"}
            </button>
            <button className="btn btn-ghost" type="button" onClick={() => setMergeOpen(false)}>취소</button>
          </div>
        </form>
      )}

      {splitActor && (
        <form
          className="actor-form"
          onSubmit={(event) => {
            event.preventDefault();
            if (!canSplit || !window.confirm("선택한 alias를 새 액터로 분리할까요?")) return;
            splitMutation.mutate(
              { actorUuid: splitActor.uuid, sourceIds: splitAliases, name: splitName },
              { onSuccess: () => setSplitActorUuid(null) },
            );
          }}
        >
          <h3>{splitActor.name}에서 alias 분리</h3>
          <p>분리한 소스 신원은 자동으로 다시 병합되지 않도록 보호됩니다.</p>
          <div className="actor-alias-picker">
            {splitActor.aliases.map((alias) => (
              <label key={alias}>
                <input
                  type="checkbox"
                  checked={splitAliases.includes(alias)}
                  onChange={(event) => setSplitAliases((current) =>
                    event.target.checked ? [...current, alias] : current.filter((value) => value !== alias),
                  )}
                />
                {alias}
              </label>
            ))}
          </div>
          <label>
            새 액터 이름 <span>(선택)</span>
            <input value={splitName} onChange={(event) => setSplitName(event.target.value)} maxLength={200} />
          </label>
          {splitMutation.isError && <InlineError>{errorMessage(splitMutation.error, "분리에 실패했어요.")}</InlineError>}
          <div className="actor-form-actions">
            <button className="btn btn-primary" type="submit" disabled={!canSplit || splitMutation.isPending}>
              {splitMutation.isPending ? "분리 중…" : "분리"}
            </button>
            <button className="btn btn-ghost" type="button" onClick={() => setSplitActorUuid(null)}>취소</button>
          </div>
        </form>
      )}

      <div className="actor-decisions">
        <button className="repo-toggle" onClick={() => setShowDecisions((value) => !value)}>
          {showDecisions ? "결정 이력 접기" : "병합·분리 결정 이력"}
        </button>
        {showDecisions && (
          <div className="actor-decision-list">
            {decisionsQuery.isLoading && <p className="actor-empty">결정 이력을 불러오는 중…</p>}
            {decisionsQuery.isError && <InlineError>결정 이력을 불러오지 못했어요.</InlineError>}
            {!decisionsQuery.isLoading && !decisionsQuery.isError && decisionsQuery.data?.length === 0 && (
              <p className="actor-empty">아직 수동 결정 이력이 없습니다.</p>
            )}
            {decisionsQuery.data?.map((decision) => (
              <div className="actor-decision" key={decision.decisionId}>
                <div>
                  <strong>{decision.kind === "same" ? "수동 병합" : "자동 병합 방지"}</strong>
                  <p>{decision.aliasesA.join(", ")} ↔ {decision.aliasesB.join(", ")}</p>
                  {decision.note && <p className="actor-meta">{decision.note}</p>}
                </div>
                {decision.kind === "same" ? (
                  <button
                    className="btn btn-ghost"
                    disabled={unmergeMutation.isPending}
                    onClick={() => {
                      if (window.confirm("이 병합을 취소하고 이전 상태로 복원할까요?")) unmergeMutation.mutate(decision.decisionId);
                    }}
                  >
                    병합 취소
                  </button>
                ) : (
                  <button
                    className="btn btn-ghost"
                    disabled={revokeMutation.isPending}
                    onClick={() => {
                      if (window.confirm("이 분리 결정을 철회하고 자동 병합을 다시 허용할까요?")) revokeMutation.mutate(decision.decisionId);
                    }}
                  >
                    철회
                  </button>
                )}
              </div>
            ))}
            {(unmergeMutation.isError || revokeMutation.isError) && (
              <InlineError>{errorMessage(unmergeMutation.error ?? revokeMutation.error, "결정 변경에 실패했어요.")}</InlineError>
            )}
          </div>
        )}
      </div>
    </section>
  );
}
