import { api } from "./client";
import type { Integration, SelectionOption, SelectionStep } from "@/types/api";

// provider 식별자는 backend가 검증한다(모르는 값은 400/404) — 배포 시차로 프론트가 모르는
// provider가 올 수 있어 union으로 고정하지 않는다. 어떤 소스가 연결 가능한지는
// sourceCatalog 항목의 status가 정하고, 누락은 그쪽에서 컴파일 에러로 막는다.
export type IntegrationProvider = string;

export interface ConnectGitHubPayload {
  installationId: string;
  repositoryId: number;
  repositoryFullName: string;
  branch: string;
}

// 선택 확정 시 단계 하나에 제출하는 값 — value는 external_ref에 저장되고 label은 표시 이름으로 저장된다
export interface SelectionSubmission {
  value: string;
  label: string;
}

export async function listIntegrations(projectId: string): Promise<Integration[]> {
  const { data } = await api.get<Integration[]>(`/projects/${projectId}/integrations`);
  return data;
}

export async function connectGitHubRepository(
  projectId: string,
  payload: ConnectGitHubPayload,
): Promise<Integration> {
  const { data } = await api.post<Integration>(
    `/projects/${projectId}/integrations/github`,
    {
      installation_id: payload.installationId,
      repository_id: payload.repositoryId,
      repository_full_name: payload.repositoryFullName,
      branch: payload.branch,
    },
  );
  return data;
}

// 연동 해제 — 자격증명·수집 커서와 그 소스에서 수집한 그래프가 함께 삭제된다(되돌릴 수 없음).
export async function disconnectIntegration(
  projectId: string,
  provider: IntegrationProvider,
): Promise<void> {
  await api.delete(`/projects/${projectId}/integrations/${provider}`);
}

// OAuth 동의 화면 URL 조회. 프론트는 이 URL로 window.location.href를 대입해 이동한다 —
// <a href> 최상위 네비게이션에는 axios 인터셉터가 붙이는 JWT가 실리지 않아 이 엔드포인트는 JSON으로 받아야 한다.
export async function getAuthorizeUrl(
  projectId: string,
  provider: IntegrationProvider,
): Promise<string> {
  const { data } = await api.get<{ authorizeUrl: string }>(
    `/projects/${projectId}/integrations/${provider}/authorize`,
  );
  return data.authorizeUrl;
}

// provider가 선언한 연동 대상 선택 단계 — pending(선택 필요) 상태에서만 의미가 있다
export async function listSelectionSteps(
  projectId: string,
  provider: IntegrationProvider,
): Promise<SelectionStep[]> {
  const { data } = await api.get<SelectionStep[]>(
    `/projects/${projectId}/integrations/${provider}/selection/steps`,
  );
  return data;
}

// 한 단계의 후보 조회 — 앞 단계에서 고른 값들을 쿼리 파라미터로 함께 보낸다
// (자식 목록 조회에 부모 id가 필요하다: 예를 들어 Jira 프로젝트 목록에는 cloud_id).
export async function listSelectionOptions(
  projectId: string,
  provider: IntegrationProvider,
  stepKey: string,
  prior: Record<string, string>,
): Promise<SelectionOption[]> {
  const { data } = await api.get<SelectionOption[]>(
    `/projects/${projectId}/integrations/${provider}/selection/options`,
    { params: { step: stepKey, ...prior } },
  );
  return data;
}

// 선택 확정 — 모든 단계를 한 번에 제출한다(부분 저장 상태를 만들지 않는다).
// 건너뛴 선택적(optional) 단계는 빼고 보낸다. 필수 단계 누락 검증은 backend가 한다.
export async function completeSelection(
  projectId: string,
  provider: IntegrationProvider,
  selections: Record<string, SelectionSubmission>,
): Promise<Integration> {
  const { data } = await api.post<Integration>(
    `/projects/${projectId}/integrations/${provider}/selection`,
    { selections },
  );
  return data;
}
