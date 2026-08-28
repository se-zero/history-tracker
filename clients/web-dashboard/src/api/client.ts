import axios, {
  AxiosError,
  AxiosHeaders,
  type InternalAxiosRequestConfig,
} from "axios";

import { tokenStorage } from "@/auth/tokenStorage";
import type { TokenResponse } from "@/types/api";

const baseURL = import.meta.env.VITE_API_BASE_URL ?? "/api/v1";

export const api = axios.create({
  baseURL,
});

export class UnauthorizedError extends Error {}

// 인터셉터 흐름 제어용 플래그.
//   _skipAuth   = Authorization 헤더 부착 안 함 (refresh 자체 요청용)
//   _skipRefresh = 401이어도 refresh 트리거 안 함 (무한 루프 방지)
//   _retried    = 이미 한 번 재시도된 요청 (두 번째 실패는 그대로 reject)
export type AuthAwareConfig = InternalAxiosRequestConfig & {
  _skipAuth?: boolean;
  _skipRefresh?: boolean;
  _retried?: boolean;
};

api.interceptors.request.use((config) => {
  const cfg = config as AuthAwareConfig;
  if (cfg._skipAuth) return cfg;
  const token = tokenStorage.getAccess();
  if (token) {
    if (!cfg.headers) cfg.headers = new AxiosHeaders();
    cfg.headers.set("Authorization", `Bearer ${token}`);
  }
  return cfg;
});

let refreshPromise: Promise<TokenResponse> | null = null;

export async function refreshSession(): Promise<TokenResponse> {
  if (refreshPromise) return refreshPromise;

  refreshPromise = (async () => {
    try {
      const { data } = await api.post<TokenResponse>(
        "/auth/refresh",
        null,
        { _skipAuth: true, _skipRefresh: true } as AuthAwareConfig,
      );
      tokenStorage.setAccess(data.accessToken);
      return data;
    } finally {
      refreshPromise = null;
    }
  })();

  return refreshPromise;
}

api.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const cfg = error.config as AuthAwareConfig | undefined;
    const status = error.response?.status;

    if (status === 401 && cfg && !cfg._retried && !cfg._skipRefresh) {
      cfg._retried = true;
      try {
        await refreshSession();
      } catch {
        tokenStorage.clear();
        return Promise.reject(new UnauthorizedError("refresh failed"));
      }
      const fresh = tokenStorage.getAccess();
      if (fresh && cfg.headers) {
        cfg.headers.set("Authorization", `Bearer ${fresh}`);
      }
      return api.request(cfg);
    }

    if (status === 401) {
      tokenStorage.clear();
      return Promise.reject(new UnauthorizedError("unauthorized"));
    }

    return Promise.reject(error);
  },
);
