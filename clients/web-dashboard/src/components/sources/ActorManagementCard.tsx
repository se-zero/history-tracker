import { useEffect, useState } from "react";
import axios from "axios";

import { InlineError } from "@/components/ui/InlineError";
import {
  useActorDetail,
  useActors,
  useMergeActors,
  useRenameActor,
  useSplitActor,
} from "@/hooks/useActors";
import type { Actor, ActorAliasDetail } from "@/types/api";
import { aliasNameText, sourceLabel, sourceNameSummary } from "./actorFormat";
import { useCollapsedRows } from "./useCollapsedRows";

function actorLabel(actor: Actor) {
  // select <option>은 마크업을 못 담아 label/name을 다시 문자열로 합친다(모노 스코프는 적용 대상 아님).
  const summary = actor.sourceNames
    .map(sourceNameSummary)
    .map(({ label, name }) => (name ? `${label}: ${name}` : label))
    .join(", ");
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
      {alias.email && <span className="actor-meta mono">{alias.email}</span>}
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

// 합치기 폼과 분리 폼은 동시에 뜰 수 없다 — 유니온으로 묶어 구조적으로 배타를 보장한다.
type ActorPanel = { kind: "merge" } | { kind: "split"; uuid: string } | null;

export function ActorManagementCard({ projectId }: { projectId: string }) {
  const actorsQuery = useActors(projectId);
  const mergeMutation = useMergeActors(projectId);
  const renameMutation = useRenameActor(projectId);
  const splitMutation = useSplitActor(projectId);

  const actors = actorsQuery.data ?? [];
  const [panel, setPanel] = useState<ActorPanel>(null);
  const [uuidA, setUuidA] = useState("");
  const [uuidB, setUuidB] = useState("");
  const [splitAliases, setSplitAliases] = useState<string[]>([]);
  const [renameActorUuid, setRenameActorUuid] = useState<string | null>(null);
  const [renameName, setRenameName] = useState("");
  const [listExpanded, setListExpanded] = useState(false);
  const { ref: listRef, maxHeight: listMaxHeight } = useCollapsedRows(4, actors.length);

  const mergeOpen = panel?.kind === "merge";
  const splitActorUuid = panel?.kind === "split" ? panel.uuid : null;

  const detailAQuery = useActorDetail(projectId, mergeOpen && uuidA ? uuidA : null);
  const detailBQuery = useActorDetail(projectId, mergeOpen && uuidB ? uuidB : null);
  const splitDetailQuery = useActorDetail(projectId, splitActorUuid);

  const openMerge = () => {
    if (mergeOpen) {
      setPanel(null);
      return;
    }
    // 자동 선택 없이 "선택하세요"로 시작한다 — 실수로 엉뚱한 두 액터가 미리 골라진 채 합쳐지는 것 방지.
    setUuidA("");
    setUuidB("");
    mergeMutation.reset();
    setPanel({ kind: "merge" });
  };

  const openSplit = (actor: Actor) => {
    if (splitActorUuid === actor.uuid) {
      setPanel(null);
      return;
    }
    setSplitAliases([]);
    splitMutation.reset();
    setPanel({ kind: "split", uuid: actor.uuid });
  };

  const openRename = (actor: Actor) => {
    renameMutation.reset();
    setRenameActorUuid(actor.uuid);
    setRenameName(actor.name);
  };

  const splitTarget = actors.find((actor) => actor.uuid === splitActorUuid);
  const renameActor = actors.find((actor) => actor.uuid === renameActorUuid);

  // 병합·재조회 등으로 분리 대상 액터가 목록에서 사라지면 패널을 닫는다 —
  // 열어둔 채로 두면 존재하지 않는 액터의 상세를 계속 재조회(404)하게 된다.
  useEffect(() => {
    if (panel?.kind === "split" && !splitTarget) {
      setPanel(null);
    }
  }, [panel, splitTarget]);

  const splitAliasDetails = splitDetailQuery.data?.aliases ?? [];
  const splitRemainingAliases = splitAliasDetails.filter((alias) => !splitAliases.includes(alias.sourceId));
  const splitSelectedAliases = splitAliasDetails.filter((alias) => splitAliases.includes(alias.sourceId));
  const canMerge =
    uuidA !== "" &&
    uuidB !== "" &&
    uuidA !== uuidB &&
    actors.some((actor) => actor.uuid === uuidA) &&
    actors.some((actor) => actor.uuid === uuidB);
  const canSplit = splitSelectedAliases.length > 0 && splitRemainingAliases.length > 0;
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
          <div
            className={listExpanded ? "actor-list-rows" : "actor-list-rows actor-list-collapsed"}
            ref={listRef}
            style={!listExpanded && listMaxHeight !== undefined ? { maxHeight: listMaxHeight } : undefined}
          >
            {actors.map((actor) => (
              <div className="actor-row" key={actor.uuid}>
                <div className="actor-info">
                  <strong>{actor.name}</strong>
                  <span className="actor-meta">활동 {actor.activityCount}건</span>
                  <div className="actor-source-names">
                    {/* 같은 소스의 계정이 2개 병합된 액터는 항목이 소스명으로 겹친다 — index로 키 유일성 보장 */}
                    {actor.sourceNames.map((sourceName, index) => {
                      const { label, name } = sourceNameSummary(sourceName);
                      return (
                        <span className="actor-source-name" key={`${sourceName.source}-${index}`}>
                          <code>{label}</code>
                          {name && `: ${name}`}
                        </span>
                      );
                    })}
                  </div>
                </div>
                <div className="actor-row-actions">
                  {actor.sourceNames.length >= 2 && (
                    <button className="btn btn-ghost" onClick={() => openSplit(actor)}>
                      분리
                    </button>
                  )}
                  <button className="btn btn-ghost" onClick={() => openRename(actor)}>
                    이름 변경
                  </button>
                </div>
              </div>
            ))}
          </div>
          <button className="repo-toggle" type="button" onClick={() => setListExpanded((value) => !value)}>
            {listExpanded ? "닫기" : "펼치기"}
          </button>
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
            <button className="btn btn-ghost" type="button" onClick={() => { renameMutation.reset(); setRenameActorUuid(null); }}>취소</button>
            <button className="btn btn-primary" type="submit" disabled={!canRename || renameMutation.isPending}>
              {renameMutation.isPending ? "변경 중…" : "변경"}
            </button>
          </div>
        </form>
      )}

      {mergeOpen && (
        <form
          className="actor-form"
          onSubmit={(event) => {
            event.preventDefault();
            if (!canMerge || !window.confirm("두 액터를 같은 사람으로 합칠까요? 잘못 합쳤다면 분리로 되돌릴 수 있습니다.")) return;
            mergeMutation.mutate(
              { uuidA, uuidB },
              { onSuccess: () => setPanel(null) },
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
            <button className="btn btn-ghost" type="button" onClick={() => { mergeMutation.reset(); setPanel(null); }}>취소</button>
            <button className="btn btn-primary" type="submit" disabled={!canMerge || mergeMutation.isPending}>
              {mergeMutation.isPending ? "합치는 중…" : "합치기"}
            </button>
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
              { actorUuid: splitTarget.uuid, sourceIds: splitSelectedAliases.map((alias) => alias.sourceId) },
              { onSuccess: () => setPanel(null) },
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
            {splitAliasDetails.map((alias) => {
              const { label, name } = sourceNameSummary(alias);
              return (
                <label key={alias.sourceId}>
                  <input
                    type="checkbox"
                    checked={splitAliases.includes(alias.sourceId)}
                    onChange={(event) => setSplitAliases((current) =>
                      event.target.checked ? [...current, alias.sourceId] : current.filter((value) => value !== alias.sourceId),
                    )}
                  />
                  <code>{label}</code>
                  {name && `: ${name}`}
                  {alias.email && <span className="mono">{` · ${alias.email}`}</span>}
                </label>
              );
            })}
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
            <button className="btn btn-ghost" type="button" onClick={() => { splitMutation.reset(); setPanel(null); }}>취소</button>
            <button className="btn btn-primary" type="submit" disabled={!canSplit || splitMutation.isPending}>
              {splitMutation.isPending ? "분리 중…" : "분리"}
            </button>
          </div>
        </form>
      )}
    </section>
  );
}
