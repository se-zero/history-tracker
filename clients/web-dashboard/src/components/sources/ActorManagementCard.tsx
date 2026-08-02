import { useState } from "react";
import axios from "axios";

import { InlineError } from "@/components/ui/InlineError";
import {
  useActorDecisions,
  useActorDetail,
  useActors,
  useMergeActors,
  useRenameActor,
  useRevokeActorDecision,
  useSplitActor,
  useUnmergeActors,
} from "@/hooks/useActors";
import type { Actor, ActorAliasDetail, ActorSourceName } from "@/types/api";

function sourceLabel(source: string) {
  switch (source) {
    case "GITHUB":
      return "GitHub";
    case "JIRA":
      return "Jira";
    case "SLACK":
      return "Slack";
    default:
      return source;
  }
}

// 이름·erased가 모두 없으면 "이름 없음"을 찍는 대신 무엇을 보여줘도 판단에 도움이 안 되니 생략한다.
function aliasNameText(name: string | null, erased: string | null) {
  if (erased) return "(삭제됨)";
  return name ?? "이름 없음";
}

// 목록 행 · select 라벨용 최소 요약. 이름도 erased도 없는 소스는 소스명만 남긴다.
function sourceNameSummary(sourceName: ActorSourceName) {
  if (!sourceName.name && !sourceName.erased) return sourceLabel(sourceName.source);
  return `${sourceLabel(sourceName.source)}: ${aliasNameText(sourceName.name, sourceName.erased)}`;
}

function actorLabel(actor: Actor) {
  const summary = actor.sourceNames.map(sourceNameSummary).join(", ");
  return summary ? `${actor.name} · ${summary}` : actor.name;
}

function errorMessage(error: unknown, fallback: string) {
  if (!axios.isAxiosError(error)) return fallback;
  // backend 에러 응답은 사유를 message 필드에 담는다(ErrorResponse). detail은 ai-engine(FastAPI)
  // 컨벤션이라, 직접 노출되는 경우까지 대비해 message를 우선 보고 detail을 폴백으로 읽는다.
  const data = error.response?.data as { message?: unknown; detail?: unknown } | undefined;
  const reason = data?.message ?? data?.detail;
  return typeof reason === "string" && reason ? reason : fallback;
}

// alias 상세(소스·이름·이메일) 한 줄 렌더 — 병합 비교 카드와 분리 미리보기가 공유한다.
function AliasDetailRow({ alias }: { alias: ActorAliasDetail }) {
  return (
    <div className="actor-alias-detail">
      <code>{sourceLabel(alias.source)}</code>
      <span>{aliasNameText(alias.name, alias.erased)}</span>
      {alias.email && <span className="actor-meta">{alias.email}</span>}
    </div>
  );
}

// 병합 폼에서 select로 고른 액터 한쪽의 alias 상세(소스·이름·이메일)를 보여주는 비교 카드.
function ActorCompareColumn({
  label,
  detailQuery,
}: {
  label: string;
  detailQuery: ReturnType<typeof useActorDetail>;
}) {
  if (detailQuery.isError) {
    return (
      <div className="actor-compare-col">
        <span className="actor-meta">{label} 정보를 불러오지 못했어요.</span>
      </div>
    );
  }
  if (detailQuery.isLoading) {
    return (
      <div className="actor-compare-col">
        <span className="actor-meta">{label} 불러오는 중…</span>
      </div>
    );
  }
  if (!detailQuery.data) {
    return (
      <div className="actor-compare-col">
        <span className="actor-meta">{label} 선택하면 상세가 표시됩니다.</span>
      </div>
    );
  }
  return (
    <div className="actor-compare-col">
      <strong>{detailQuery.data.name}</strong>
      {detailQuery.data.aliases.map((alias) => (
        <AliasDetailRow alias={alias} key={alias.sourceId} />
      ))}
    </div>
  );
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
  const [uuidA, setUuidA] = useState("");
  const [uuidB, setUuidB] = useState("");
  const [splitActorUuid, setSplitActorUuid] = useState<string | null>(null);
  const [splitAliases, setSplitAliases] = useState<string[]>([]);
  const [renameActorUuid, setRenameActorUuid] = useState<string | null>(null);
  const [renameName, setRenameName] = useState("");
  const [showDecisions, setShowDecisions] = useState(false);

  const detailAQuery = useActorDetail(projectId, mergeOpen && uuidA ? uuidA : null);
  const detailBQuery = useActorDetail(projectId, mergeOpen && uuidB ? uuidB : null);
  const splitDetailQuery = useActorDetail(projectId, splitActorUuid);

  const openMerge = () => {
    setUuidA(actors[0]?.uuid ?? "");
    setUuidB(actors[1]?.uuid ?? "");
    setMergeOpen(true);
  };

  const openSplit = (actor: Actor) => {
    setSplitActorUuid(actor.uuid);
    setSplitAliases([]);
  };

  const openRename = (actor: Actor) => {
    setRenameActorUuid(actor.uuid);
    setRenameName(actor.name);
  };

  const splitTarget = actors.find((actor) => actor.uuid === splitActorUuid);
  const renameActor = actors.find((actor) => actor.uuid === renameActorUuid);
  const splitAliasDetails = splitDetailQuery.data?.aliases ?? [];
  const splitRemainingAliases = splitAliasDetails.filter((alias) => !splitAliases.includes(alias.sourceId));
  const splitSelectedAliases = splitAliasDetails.filter((alias) => splitAliases.includes(alias.sourceId));
  const canMerge = uuidA !== "" && uuidB !== "" && uuidA !== uuidB;
  const canSplit = Boolean(
    splitDetailQuery.data &&
      splitAliases.length > 0 &&
      splitAliases.length < splitAliasDetails.length,
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
                <span className="actor-meta">활동 {actor.activityCount}건</span>
                <div className="actor-source-names">
                  {/* 같은 소스의 계정이 2개 병합된 액터는 항목이 소스명으로 겹친다 — index로 키 유일성 보장 */}
                  {actor.sourceNames.map((sourceName, index) => (
                    <code key={`${sourceName.source}-${index}`}>{sourceNameSummary(sourceName)}</code>
                  ))}
                </div>
              </div>
              <div className="actor-row-actions">
                <button className="btn btn-ghost" onClick={() => openRename(actor)}>
                  이름 변경
                </button>
                <button
                  className="btn btn-ghost"
                  onClick={() => openSplit(actor)}
                  disabled={actor.sourceNames.length < 2}
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
              { uuidA, uuidB },
              { onSuccess: () => setMergeOpen(false) },
            );
          }}
        >
          <h3>액터 합치기</h3>
          <p>
            같은 사람의 두 노드를 하나로 합칩니다. 활동량이 많은 쪽이 남고, 표시 이름은
            GitHub 프로필 이름이 있으면 그것을, 없으면 활동량이 가장 많은 소스의 이름을
            사용합니다. 이름을 직접 정하려면 병합 후 이름 변경을 쓰세요.
          </p>
          <label>
            합칠 액터 ①
            <select value={uuidA} onChange={(event) => setUuidA(event.target.value)}>
              <option value="">선택하세요</option>
              {actors.map((actor) => <option value={actor.uuid} key={actor.uuid}>{actorLabel(actor)}</option>)}
            </select>
          </label>
          <label>
            합칠 액터 ②
            <select value={uuidB} onChange={(event) => setUuidB(event.target.value)}>
              <option value="">선택하세요</option>
              {actors.map((actor) => <option value={actor.uuid} key={actor.uuid}>{actorLabel(actor)}</option>)}
            </select>
          </label>
          <div className="actor-compare">
            <ActorCompareColumn label="액터 ①" detailQuery={detailAQuery} />
            <ActorCompareColumn label="액터 ②" detailQuery={detailBQuery} />
          </div>
          {mergeMutation.isError && <InlineError>{errorMessage(mergeMutation.error, "합치기에 실패했어요.")}</InlineError>}
          <div className="actor-form-actions">
            <button className="btn btn-primary" type="submit" disabled={!canMerge || mergeMutation.isPending}>
              {mergeMutation.isPending ? "합치는 중…" : "합치기"}
            </button>
            <button className="btn btn-ghost" type="button" onClick={() => setMergeOpen(false)}>취소</button>
          </div>
        </form>
      )}

      {splitTarget && (
        <form
          className="actor-form"
          onSubmit={(event) => {
            event.preventDefault();
            if (!canSplit || !window.confirm("선택한 alias를 새 액터로 분리할까요?")) return;
            splitMutation.mutate(
              { actorUuid: splitTarget.uuid, sourceIds: splitAliases },
              { onSuccess: () => setSplitActorUuid(null) },
            );
          }}
        >
          <h3>{splitTarget.name}에서 alias 분리</h3>
          <p>
            분리한 소스 신원은 자동으로 다시 병합되지 않도록 보호됩니다. 새 액터의 이름은
            계정 이름에서 자동으로 정해집니다 — 바꾸려면 분리 후 이름 변경을 쓰세요.
          </p>
          {splitDetailQuery.isLoading && <p className="actor-empty">alias 정보를 불러오는 중…</p>}
          <div className="actor-alias-picker">
            {splitAliasDetails.map((alias) => (
              <label key={alias.sourceId}>
                <input
                  type="checkbox"
                  checked={splitAliases.includes(alias.sourceId)}
                  onChange={(event) => setSplitAliases((current) =>
                    event.target.checked ? [...current, alias.sourceId] : current.filter((value) => value !== alias.sourceId),
                  )}
                />
                {sourceLabel(alias.source)}: {aliasNameText(alias.name, alias.erased)}
                {alias.email ? ` · ${alias.email}` : ""}
              </label>
            ))}
          </div>
          <div className="actor-compare">
            <div className="actor-compare-col">
              <strong>남는 계정</strong>
              {splitRemainingAliases.map((alias) => (
                <AliasDetailRow alias={alias} key={alias.sourceId} />
              ))}
              {splitRemainingAliases.length === 0 && <span className="actor-meta">모든 alias가 분리됩니다.</span>}
            </div>
            <div className="actor-compare-col">
              <strong>새 액터로 분리</strong>
              {splitSelectedAliases.map((alias) => (
                <AliasDetailRow alias={alias} key={alias.sourceId} />
              ))}
              {splitSelectedAliases.length === 0 && <span className="actor-meta">분리할 alias를 선택하세요.</span>}
            </div>
          </div>
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
                  <p>
                    {decision.aliasesA.map(sourceNameSummary).join(", ")} ↔{" "}
                    {decision.aliasesB.map(sourceNameSummary).join(", ")}
                  </p>
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
