import { useEffect, useState } from "react";

import { DisconnectIntegration } from "@/components/sources/DisconnectIntegration";
import { findSource, sourceName } from "@/components/sources/sourceCatalog";
import { BusyLabel } from "@/components/ui/BusyLabel";
import { Field } from "@/components/ui/Field";
import { InlineError } from "@/components/ui/InlineError";
import { useIntegrationAuthorize } from "@/hooks/useIntegrationOAuth";
import { useIntegrations } from "@/hooks/useIntegrations";
import {
  isStepReachable,
  useCompleteSelection,
  useSelectionOptionQueries,
  useSelectionSteps,
} from "@/hooks/useSelectionFlow";
import { formatTimestamp } from "@/lib/format";
import type { SelectionSubmission } from "@/api/integrations";

/**
 * OAuth로 붙는 소스의 "연동 중" 행 — GitHub(설치 기반)를 제외한 모든 소스가 이 카드 하나를 쓴다.
 *
 * pending(연동 대상 선택 필요) 상태면 provider가 선언한 선택 단계(steps)를 그대로 렌더한다.
 * 단계 수(1~4)·이름·선택적 단계 여부를 전혀 하드코딩하지 않으므로 새 소스가 추가돼도
 * 이 컴포넌트는 바뀌지 않는다. 미연결 상태는 "추가 가능" 타일(SourceTileGrid)이 맡는다.
 */
export function OAuthSourceCard({
  projectId,
  provider,
  oauthError,
}: {
  projectId: string;
  provider: string;
  oauthError?: string;
}) {
  const source = findSource(provider);
  const Mark = source?.Mark;

  const integrationsQuery = useIntegrations(projectId);
  const integration = integrationsQuery.data?.find((i) => i.provider === provider);
  const status = integration?.metadata?.["status"];
  // pending_project는 pending_selection의 옛 이름 — 이미 저장된 행이 남아 있어 함께 인정한다
  const pending = status === "pending_selection" || status === "pending_project";
  const connected = Boolean(integration) && !pending;

  const authorizeMutation = useIntegrationAuthorize(projectId);

  // 단계 key → 고른 값. 확정 요청 바디가 그대로 되는 shape이다.
  const [selections, setSelections] = useState<Record<string, SelectionSubmission>>({});
  const selectedValues = Object.fromEntries(
    Object.entries(selections).map(([key, submission]) => [key, submission.value]),
  );

  const stepsQuery = useSelectionSteps(projectId, provider, pending);
  const steps = stepsQuery.data ?? [];
  const optionQueries = useSelectionOptionQueries(
    projectId,
    provider,
    steps,
    selectedValues,
    pending,
  );

  // 필수 단계 후보가 정확히 1개면 자동 선택해 그 단계를 건너뛴다 — 고를 게 없는데 select로
  // 보이면 혼란만 준다(예: resource-level grant로 등록된 Atlassian 앱은 동의 화면에서 이미
  // 사이트를 골라 목록이 항상 1개다). 의존성 배열 없이 매 렌더 돌지만 이미 선택된 단계는
  // 건드리지 않아 한 단계당 한 번만 상태가 바뀐다.
  useEffect(() => {
    if (!pending) return;
    steps.forEach((step, index) => {
      const options = optionQueries[index]?.data;
      if (step.optional || !options || options.length !== 1 || selections[step.key]) return;
      const only = options[0];
      setSelections((prev) =>
        prev[step.key] ? prev : { ...prev, [step.key]: { value: only.value, label: only.label } },
      );
    });
  });

  // 단계 i의 선택이 바뀌면 그 뒤 단계의 선택을 모두 버린다 — 후보 목록이 앞 선택에 의존한다.
  const selectStep = (index: number, stepKey: string, value: string, label: string) => {
    setSelections((prev) => {
      const next: Record<string, SelectionSubmission> = {};
      for (const priorStep of steps.slice(0, index)) {
        if (prev[priorStep.key]) next[priorStep.key] = prev[priorStep.key];
      }
      if (value) next[stepKey] = { value, label };
      return next;
    });
  };

  const completeMutation = useCompleteSelection(projectId, provider);

  // 첫 단계 후보 조회가 실패(토큰 폐기·앱 접근 취소·네트워크 오류)하면 아무것도 고를 수 없어
  // "연결 완료"가 계속 disabled로 남는다 — 이 경우만 "재연결 필요"로 배지를 갈아탄다.
  const reconnectNeeded = pending && Boolean(optionQueries[0]?.isError);
  const requiredDone =
    steps.length > 0 && steps.every((step) => step.optional || Boolean(selections[step.key]));

  const badgeClass = connected ? "connected" : "warn";
  const badgeLabel = connected ? "연결됨" : reconnectNeeded ? "재연결 필요" : "선택 필요";

  const lastSyncedAt = integration?.lastSyncedAt
    ? formatTimestamp(integration.lastSyncedAt)
    : null;

  return (
    <div className={"source-row" + (pending ? " warn" : "")}>
      <div className="source-row-top">
        <div className="source-logo-chip">{Mark && <Mark size={20} />}</div>
        <div className="source-row-main">
          <div className="source-row-name">{sourceName(provider)}</div>
          <div className="source-row-sub">
            {connected
              ? integration?.displayName ?? source?.description ?? provider
              : "연동할 대상을 선택하세요"}
          </div>

          {oauthError && <InlineError style={{ marginTop: 10 }}>{oauthError}</InlineError>}

          {pending && (
            <>
              {authorizeMutation.isError && (
                <InlineError style={{ marginTop: 10 }}>
                  연결에 실패했어요. 잠시 후 다시 시도해 주세요.
                </InlineError>
              )}
              <div className="connect-form" style={{ marginTop: 12 }}>
                {stepsQuery.isError && (
                  <InlineError>선택 단계를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.</InlineError>
                )}
                {/* OAuth 콜백 직후에는 integrations가 먼저 도착하고 단계 선언(steps)이 한 왕복 뒤에
                    온다 — 그 사이를 비워 두면 빈 점선 상자가 깜빡인다. 자리 표시로 채워 둔다. */}
                {stepsQuery.isLoading && (
                  <div className="selection-loading">선택 단계를 불러오는 중…</div>
                )}
                {steps.map((step, index) => {
                  if (!isStepReachable(steps, index, selectedValues)) return null;
                  const query = optionQueries[index];
                  const options = query?.data ?? [];
                  // 자동 선택된 단일 후보 단계 — select 대신 정적 텍스트로 보여주되 이름은 계속
                  // 노출해 동의 화면에서 고른 대상이 맞는지 확인할 근거로 쓴다. 후보가 여러 개
                  // 돌아오는 상황이 되면 개수 분기로 자연히 select로 복귀한다.
                  const single = !step.optional && options.length === 1;
                  return (
                    <Field key={step.key} label={step.title + (step.optional ? " (선택)" : "")}>
                      {query?.isError ? (
                        index === 0 ? (
                          <InlineError>
                            {step.title} 목록을 불러오지 못했어요. 토큰이 만료됐거나 권한이 부족할 수 있어요.
                          </InlineError>
                        ) : (
                          <InlineError>{step.title} 목록을 불러오지 못했어요.</InlineError>
                        )
                      ) : single ? (
                        <div className="selection-value">{options[0].label}</div>
                      ) : (
                        <select
                          className="selection-select"
                          value={selections[step.key]?.value ?? ""}
                          onChange={(e) => {
                            const option = options.find((o) => o.value === e.target.value);
                            selectStep(index, step.key, e.target.value, option?.label ?? "");
                          }}
                          disabled={query?.isLoading}
                        >
                          <option value="" disabled={!step.optional}>
                            {query?.isLoading
                              ? "불러오는 중…"
                              : step.optional
                                ? "(건너뛰기)"
                                : "선택하세요"}
                          </option>
                          {options.map((option) => (
                            <option key={option.value} value={option.value}>
                              {option.label}
                            </option>
                          ))}
                        </select>
                      )}
                    </Field>
                  );
                })}
                {completeMutation.isError && (
                  <InlineError>연결을 완료하지 못했어요. 다시 시도해 주세요.</InlineError>
                )}
              </div>
            </>
          )}
        </div>
        <div className="source-row-status">
          <span className={"src-pill " + badgeClass}>
            <span className="dot" />
            {badgeLabel}
          </span>
          {lastSyncedAt && (
            <div className="source-row-synced">
              마지막 수집 <span className="mono">{lastSyncedAt}</span>
            </div>
          )}
        </div>
        {/* pending(대상 미선택) 상태에서도 해제할 수 있어야 한다 — 후보 조회가 막혀
            연결을 완료할 수 없을 때 빠져나갈 길이 이것뿐이다. */}
        <DisconnectIntegration projectId={projectId} provider={provider} />
      </div>

      {pending && (
        <div className="source-row-actions">
          <button
            className={"btn " + (reconnectNeeded ? "btn-primary" : "btn-ghost")}
            onClick={() => authorizeMutation.mutate(provider)}
            disabled={authorizeMutation.isPending || authorizeMutation.isSuccess}
            aria-busy={authorizeMutation.isPending || authorizeMutation.isSuccess}
          >
            <BusyLabel
              busy={authorizeMutation.isPending || authorizeMutation.isSuccess}
              label={reconnectNeeded ? "다시 연결" : "다시 인증하기"}
              busyLabel="이동 중…"
            />
          </button>
          <button
            className={"btn " + (requiredDone ? "btn-primary" : "btn-ghost")}
            onClick={() => completeMutation.mutate(selections)}
            disabled={!requiredDone || completeMutation.isPending}
            aria-busy={completeMutation.isPending}
          >
            <BusyLabel busy={completeMutation.isPending} label="연결 완료" busyLabel="연결 중…" />
          </button>
        </div>
      )}
    </div>
  );
}
