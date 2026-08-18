import { createContext, useContext, useEffect, useState, type ReactNode } from "react";

export type Theme = "dark" | "light";

const STORAGE_KEY = "ht.theme";

function readInitialTheme(): Theme {
  if (typeof window === "undefined") return "dark";
  const saved = window.localStorage.getItem(STORAGE_KEY);
  if (saved === "dark" || saved === "light") return saved;
  // 시스템 prefers-color-scheme 존중
  if (window.matchMedia?.("(prefers-color-scheme: light)").matches) return "light";
  return "dark";
}

interface ThemeContextValue {
  theme: Theme;
  setTheme: (t: Theme) => void;
  toggle: () => void;
}

const ThemeContext = createContext<ThemeContextValue | null>(null);

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setThemeState] = useState<Theme>(readInitialTheme);

  // 렌더 중에 적용한다. effect로 미루면, 렌더 단계에서 getComputedStyle로 CSS 토큰을
  // 읽는 자식(그래프 캔버스가 그렇다)이 아직 바뀌지 않은 직전 테마의 값을 읽는다.
  // 그 값이 새 테마 키로 캐시되면 테마를 바꿔도 색이 따라오지 않는다.
  // DOM 속성 하나만 건드리는 멱등 연산이라 렌더 중 실행해도 안전하다.
  if (typeof document !== "undefined") {
    document.documentElement.dataset.theme = theme;
  }

  useEffect(() => {
    window.localStorage.setItem(STORAGE_KEY, theme);
  }, [theme]);

  return (
    <ThemeContext.Provider
      value={{
        theme,
        setTheme: setThemeState,
        toggle: () => setThemeState((t) => (t === "dark" ? "light" : "dark")),
      }}
    >
      {children}
    </ThemeContext.Provider>
  );
}

export function useTheme(): ThemeContextValue {
  const ctx = useContext(ThemeContext);
  if (!ctx) throw new Error("useTheme must be used inside ThemeProvider");
  return ctx;
}
