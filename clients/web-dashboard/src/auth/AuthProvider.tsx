import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from "react";

import { fetchMe, logout as apiLogout } from "@/api/auth";
import { UnauthorizedError, refreshSession } from "@/api/client";
import { tokenStorage } from "@/auth/tokenStorage";
import type { User } from "@/types/api";

type AuthStatus = "loading" | "authenticated" | "unauthenticated";

interface AuthContextValue {
  status: AuthStatus;
  user: User | null;
  refresh: () => Promise<void>;
  logout: () => Promise<void>;
  setUnauthenticated: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>("loading");
  const [user, setUser] = useState<User | null>(null);
  // 콜백 로그인과 부트 silent refresh가 겹치면, 먼저 실패한 쪽이 나중에 성공한 세션을 지운다.
  const refreshGeneration = useRef(0);

  const refresh = async () => {
    const generation = ++refreshGeneration.current;
    try {
      if (!tokenStorage.getAccess()) {
        await refreshSession();
      }
      const me = await fetchMe();
      if (generation !== refreshGeneration.current) return;
      setUser(me);
      setStatus("authenticated");
    } catch (err) {
      if (generation !== refreshGeneration.current) return;
      if (!(err instanceof UnauthorizedError)) {
        console.warn("auth: session restore failed, falling back to unauthenticated", err);
      }
      tokenStorage.clear();
      setUser(null);
      setStatus("unauthenticated");
    }
  };

  useEffect(() => {
    refresh();
  }, []);

  const logout = async () => {
    await apiLogout();
    setUser(null);
    setStatus("unauthenticated");
  };

  return (
    <AuthContext.Provider
      value={{
        status,
        user,
        refresh,
        logout,
        setUnauthenticated: () => {
          tokenStorage.clear();
          setUser(null);
          setStatus("unauthenticated");
        },
      }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used inside AuthProvider");
  return ctx;
}
