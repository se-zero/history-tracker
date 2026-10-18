import { createContext, useContext, useState, type ReactNode } from "react";

export type UiLang = "ko" | "en";

// 랜딩 사전과 앱 사전이 같은 모양을 쓰게 하는 헬퍼. 키는 ko·en 두 개뿐.
export type Localized<T> = Record<UiLang, T>;

// 랜딩이 이미 쓰던 키. 앱은 별도 키를 만들지 않는다(docs/i18n.md).
const STORAGE_KEY = "ht.lang";

// format.ts는 훅 밖에서 로캘을 읽는다. Provider 렌더 중에 이 값을 먼저 맞추면
// 같은 렌더의 자식이 새 언어로 시각을 그린다.
let currentLang: UiLang = readInitialLang();

export function getUiLang(): UiLang {
  return currentLang;
}

export function uiLocale(): "ko-KR" | "en-US" {
  return currentLang === "en" ? "en-US" : "ko-KR";
}

// 렌더 중에 호출한다. 한국어 문장은 그대로 두고 영어만 고른다.
export function uiText(ko: string, en: string): string {
  return currentLang === "en" ? en : ko;
}

function readInitialLang(): UiLang {
  if (typeof window === "undefined") return "ko";
  try {
    const saved = window.localStorage.getItem(STORAGE_KEY);
    if (saved === "ko" || saved === "en") return saved;
  } catch {
    // 프라이버시 모드 등에서 localStorage 접근이 throw할 수 있다 — 감지 신호로 넘어간다.
  }
  // 위치 신호(타임존·IP)는 쓰지 않는다. 브라우저가 보고하는 "읽고 싶은 언어"만 본다.
  // 감지 결과는 저장하지 않는다. 저장은 사용자가 메뉴에서 고른 값에만 한다.
  const browserLang = navigator.languages?.[0] ?? navigator.language ?? "en";
  return browserLang.toLowerCase().startsWith("ko") ? "ko" : "en";
}

interface UiLanguageContextValue {
  lang: UiLang;
  setLang: (next: UiLang) => void;
}

const UiLanguageContext = createContext<UiLanguageContextValue | null>(null);

export function UiLanguageProvider({ children }: { children: ReactNode }) {
  const [lang, setLangState] = useState<UiLang>(readInitialLang);
  currentLang = lang;

  const setLang = (next: UiLang) => {
    currentLang = next;
    setLangState(next);
    try {
      window.localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // 저장 실패해도 이번 세션의 표시는 유지된다.
    }
  };

  // <html lang>의 단일 소유자. 랜딩을 떠날 때 ko로 되돌리지 않는다 — 앱도 같은 언어다.
  // 검색 설명문(meta description)은 공개 페이지 쪽 효과만 건드린다.
  if (typeof document !== "undefined") {
    document.documentElement.lang = lang;
  }

  return (
    <UiLanguageContext.Provider value={{ lang, setLang }}>
      {children}
    </UiLanguageContext.Provider>
  );
}

export function useUiLanguage(): UiLanguageContextValue {
  const ctx = useContext(UiLanguageContext);
  if (!ctx) throw new Error("useUiLanguage must be used inside UiLanguageProvider");
  return ctx;
}
