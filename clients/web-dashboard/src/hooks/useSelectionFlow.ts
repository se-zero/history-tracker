import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";

import {
  completeSelection,
  listSelectionOptions,
  listSelectionSteps,
  type SelectionSubmission,
} from "@/api/integrations";
import type { SelectionStep } from "@/types/api";
import { queryKeys } from "./queryKeys";

// provider가 선언한 선택 단계. pending(연동 대상 선택 필요) 상태일 때만 활성화한다 —
// 미연결 상태에서 호출하면 404다. 선언은 정적이라 자주 다시 가져올 이유가 없다.
export function useSelectionSteps(projectId: string, provider: string, enabled: boolean) {
  return useQuery({
    queryKey: queryKeys.selectionSteps(projectId, provider),
    queryFn: () => listSelectionSteps(projectId, provider),
    enabled,
    staleTime: Infinity,
  });
}

// index 단계에 도달했는지 — 앞선 "필수" 단계가 모두 선택됐으면 true. 선택적(optional) 단계는
// 건너뛰어도 다음 단계로 갈 수 있다(예: ClickUp에서 folder 없이 space 직속 list 선택).
export function isStepReachable(
  steps: SelectionStep[],
  index: number,
  selectedValues: Record<string, string>,
): boolean {
  return steps.slice(0, index).every((step) => step.optional || Boolean(selectedValues[step.key]));
}

// 앞 단계에서 고른 값만 추려 이 단계 후보 조회의 파라미터로 만든다(자식 목록에 부모 id가 필요하다).
// 선택적 단계를 건너뛰었으면 그 키가 빠진 채로 보내진다 — provider가 그 경우의 조회를 정의한다.
function priorSelections(
  steps: SelectionStep[],
  index: number,
  selectedValues: Record<string, string>,
): Record<string, string> {
  const prior: Record<string, string> = {};
  for (const step of steps.slice(0, index)) {
    const value = selectedValues[step.key];
    if (value) prior[step.key] = value;
  }
  return prior;
}

// 단계별 후보 조회를 한 번에 구독한다 — 단계 수가 provider마다 달라(1~4단) 훅 개수를 고정할 수
// 없어 배열형 useQueries를 쓴다. 반환 배열의 순서는 steps 순서와 같다.
export function useSelectionOptionQueries(
  projectId: string,
  provider: string,
  steps: SelectionStep[],
  selectedValues: Record<string, string>,
  enabled: boolean,
) {
  return useQueries({
    queries: steps.map((step, index) => {
      const prior = priorSelections(steps, index, selectedValues);
      return {
        queryKey: queryKeys.selectionOptions(projectId, provider, step.key, prior),
        queryFn: () => listSelectionOptions(projectId, provider, step.key, prior),
        enabled: enabled && isStepReachable(steps, index, selectedValues),
      };
    }),
  });
}

// 확정은 일반 POST라 Slack 콜백(302 전체 페이지 이동)과 달리 캐시가 저절로 비지 않는다 —
// 명시적으로 integrations를 invalidate해야 배지가 "연결됨"으로 갱신된다.
export function useCompleteSelection(projectId: string, provider: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (selections: Record<string, SelectionSubmission>) =>
      completeSelection(projectId, provider, selections),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.integrations(projectId) });
    },
  });
}
