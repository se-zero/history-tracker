import { createContext, useContext, useEffect, useState, type ReactNode } from "react";

import { fetchMe, logout as apiLogout } from "@/api/auth";
import { UnauthorizedError } from "@/api/client";
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

  const refresh = async () => {
    if (!tokenStorage.getAccess()) {
      setUser(null);
      setStatus("unauthenticated");
      return;
    }
    try {
      const me = await fetchMe();
      setUser(me);
      setStatus("authenticated");
    } catch (err) {
      if (!(err instanceof UnauthorizedError)) {
        console.warn("auth: /me failed, falling back to unauthenticated", err);
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
