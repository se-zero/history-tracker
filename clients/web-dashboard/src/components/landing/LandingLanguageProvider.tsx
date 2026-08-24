import { createContext, useContext, useEffect, useState, type ReactNode } from "react";

export type LandingLang = "ko" | "en";

// 랜딩 번역 사전을 위한 단일 출처 헬퍼 — 이후 묶음들이 각자 소사전을 만들 때 이 타입만 참조한다.
export type Localized<T> = Record<LandingLang, T>;

// localStorage 키 "ht.lang" — 앱 전역 관행(테마의 "ht.theme")을 따른 것이며, 후일 앱 자체
// i18n이 도입되면 이 키를 그대로 승계할 의도다(별도 키로 다시 나누지 않는다).
const STORAGE_KEY = "ht.lang";

function readInitialLang(): LandingLang {
  try {
    const saved = window.localStorage.getItem(STORAGE_KEY);
    if (saved === "ko" || saved === "en") return saved;
  } catch {
    // 프라이버시 모드 등에서 localStorage 접근이 throw할 수 있다 — 감지 신호로 넘어간다.
  }
  // 위치 신호(타임존·IP)는 절대 쓰지 않는다 — 로캘(표기 언어)과 타임존(어느 시각)은 독립된
  // 두 축이고, 위치로 언어를 추정하면 "뉴욕 거주 한국인"·"서울 출장 중인 미국인" 같은 흔한
  // 사례에서 언어가 틀린다(docs/i18n.md §1). 여기서 쓰는 신호는 브라우저가 보고하는
  // "읽고 싶은 언어" 하나뿐이다(§2 권고 — navigator.languages 우선, 명시 선택이 최우선).
  const browserLang = navigator.languages?.[0] ?? navigator.language ?? "en";
  return browserLang.startsWith("ko") ? "ko" : "en";
}

interface LandingLanguageContextValue {
  lang: LandingLang;
  setLang: (next: LandingLang) => void;
}

const LandingLanguageContext = createContext<LandingLanguageContextValue | null>(null);

// 랜딩 계열 공개 페이지(랜딩·약관·개인정보처리방침)가 쓰는 언어 상태. 페이지마다 Provider
// 인스턴스가 따로 뜨지만(useLandingTheme과 달리 전역 하나가 아니다), ht.lang 저장값 +
// 결정적 감지 로직을 공유하므로 랜딩→약관 이동처럼 컴포넌트 트리가 언마운트·재마운트돼도
// 같은 값을 다시 계산해 언어가 이어진다. useLandingTheme의 "페이지 이동 시 리셋"은 테마가
// 취향이라 매번 다시 골라도 무방하다는 전제였지만, 언어는 "이해할 수 있는 언어"라 이동할
// 때마다 초기화되면 사용성이 깨진다 — 그래서 이 비영속 패턴을 그대로 복제하지 않는다.
export function LandingLanguageProvider({ children }: { children: ReactNode }) {
  const [lang, setLangState] = useState<LandingLang>(readInitialLang);

  const setLang = (next: LandingLang) => {
    setLangState(next);
    // 영속은 명시적으로 고른 값에만 건다. useEffect로 lang이 바뀔 때마다 저장하면 감지
    // 결과(브라우저 언어)까지 영속으로 굳어버려, 다음 방문에 "명시 선택 > 브라우저 언어"
    // 우선순위(docs/i18n.md §2)가 무너진다 — 감지값과 선택값이 저장소 안에서 구분되지 않기 때문이다.
    try {
      window.localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // 저장 실패해도 이번 세션의 표시는 정상 동작하므로 무시한다.
    }
  };

  // <html lang>을 랜딩이 보여주는 언어에 동기화한다. cleanup에서 "ko"로 복원하는 이유는
  // index.html의 정적 기본값이 "ko"이고 앱(제품 본체) 라우트는 한국어로 고정돼 있어서다 —
  // 랜딩을 벗어난 뒤에도 영어가 남아 있으면 앱 쪽 lang 속성이 실제 언어와 어긋난다.
  useEffect(() => {
    document.documentElement.lang = lang;
    return () => {
      document.documentElement.lang = "ko";
    };
  }, [lang]);

  return (
    <LandingLanguageContext.Provider value={{ lang, setLang }}>
      {children}
    </LandingLanguageContext.Provider>
  );
}

// 언어 전환에는 useLandingTheme의 View Transition 크로스페이드를 쓰지 않는다 — 테마 전환은
// 색만 바뀌어 레이아웃이 그대로지만, 언어 전환은 문장 길이가 달라져 문서 전체가 리플로우된다.
// 리플로우 중인 스냅샷을 크로스페이드하면 두 레이아웃이 겹쳐 보이는 이중 노출 잔상이 생기므로,
// setState만으로 즉시 전환한다(위 setLang 참조 — 별도 분기 없이 기본 동작 그대로).
export function useLandingLanguage(): LandingLanguageContextValue {
  const ctx = useContext(LandingLanguageContext);
  if (!ctx) throw new Error("useLandingLanguage must be used inside LandingLanguageProvider");
  return ctx;
}
