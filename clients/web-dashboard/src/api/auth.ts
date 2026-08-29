import { api, type AuthAwareConfig } from "./client";
import { tokenStorage } from "@/auth/tokenStorage";
import type { TokenResponse, User } from "@/types/api";

// 쿠키만 쓰는 인증 호출 — 만료된 access를 Authorization에 실으면 JWT 필터가
// 컨트롤러보다 먼저 401을 낸다(쿠키 삭제·로그인 교환이 막힘).
const skipAccess: AuthAwareConfig = { _skipAuth: true, _skipRefresh: true } as AuthAwareConfig;

export const GITHUB_AUTHORIZE_URL = `${
  import.meta.env.VITE_API_BASE_URL ?? "/api/v1"
}/auth/github/authorize`;

// 이미 OAuth 동의를 마친 사용자도 설치/리포지토리 선택 UI를 다시 띄우기 위한 GitHub App install URL.
export const GITHUB_INSTALL_URL = `${
  import.meta.env.VITE_API_BASE_URL ?? "/api/v1"
}/auth/github/install`;

export async function fetchMe(): Promise<User> {
  const { data } = await api.get<User>("/me");
  return data;
}

// 현재 버전 약관 동의를 기록한다. 바디 없음 — 성공 시 204.
export async function postConsent(): Promise<void> {
  await api.post("/me/consent");
}

// 공유 전환 코드로 FREE → PAID. 성공 시 204, 코드 불일치는 403.
export async function upgradePlan(code: string): Promise<void> {
  await api.post("/me/plan/upgrade", { code });
}

export async function exchangeGitHubCode(params: {
  code: string;
  state?: string | null;
  installationId?: string | null;
}): Promise<TokenResponse> {
  const { data } = await api.get<TokenResponse>("/auth/github/callback", {
    ...skipAccess,
    params: {
      code: params.code,
      state: params.state || undefined,
      installation_id: params.installationId || undefined,
    },
  });
  tokenStorage.setAccess(data.accessToken);
  return data;
}

// 회원 탈퇴 (soft delete + grace period). 호출 후 로컬 토큰을 비우고 랜딩으로 보낸다.
export async function deleteAccount(): Promise<void> {
  await api.delete("/me");
}

export async function logout(): Promise<void> {
  try {
    await api.post("/auth/logout", null, skipAccess);
  } catch {
    // 백엔드 실패해도 로컬 access는 비워서 로그아웃 처리. 쿠키 삭제는 Set-Cookie에 달렸다.
  }
  tokenStorage.clear();
}
