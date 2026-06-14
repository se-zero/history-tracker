import { api } from "./client";
import { tokenStorage } from "@/auth/tokenStorage";
import type { TokenResponse, User } from "@/types/api";

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

export async function exchangeGitHubCode(params: {
  code: string;
  state?: string | null;
  installationId?: string | null;
}): Promise<TokenResponse> {
  const { data } = await api.get<TokenResponse>("/auth/github/callback", {
    params: {
      code: params.code,
      state: params.state || undefined,
      installation_id: params.installationId || undefined,
    },
  });
  return data;
}

export async function logout(): Promise<void> {
  const refreshToken = tokenStorage.getRefresh();
  if (refreshToken) {
    try {
      await api.post("/auth/logout", { refreshToken });
    } catch {
      // 백엔드 실패해도 로컬 토큰은 비워서 로그아웃 처리
    }
  }
  tokenStorage.clear();
}
