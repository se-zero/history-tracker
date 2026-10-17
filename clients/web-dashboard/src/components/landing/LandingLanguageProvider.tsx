import { useEffect, useRef, type ReactNode } from "react";

import { useUiLanguage } from "@/i18n/language";

export type { Localized, UiLang as LandingLang } from "@/i18n/language";

// 공개 페이지가 쓰던 훅 이름. 상태는 루트 UiLanguageProvider 하나다.
export { useUiLanguage as useLandingLanguage } from "@/i18n/language";

// meta description의 영어판. 한국어판은 index.html에 정적으로 박혀 있고(문서 전체의 기본값),
// 여기엔 영어일 때 갈아끼울 문구만 둔다 — 같은 문장을 두 곳에 두면 한쪽만 고치게 된다.
// 문구는 랜딩 히어로의 en 서브카피(LandingHero.tsx COPY.en.sub)와 같은 주장을 유지한다.
const DESCRIPTION_EN =
  "Ties scattered commits, PRs, issues, conversations, and docs into one knowledge graph, " +
  "then answers why your code changed — with the evidence. " +
  "Connects 9 sources including GitHub, Jira, and Slack.";

// 랜딩 계열 공개 페이지(랜딩·약관·개인정보처리방침·지원·Slack 안내)가 문서 설명문을
// 보여주는 언어에 맞춘다. 언어 상태 자체는 루트 Provider가 소유한다 — 여기가 또 상태를
// 만들면 앱에서 고른 언어와 갈라진다.
export function LandingLanguageProvider({ children }: { children: ReactNode }) {
  const { lang } = useUiLanguage();

  // index.html의 정적 description을 처음 한 번만 담아 둔다(= 한국어 원문). 아래 effect가
  // 영어로 덮어쓴 뒤에도 되돌릴 원본이 필요한데, 매번 DOM에서 읽으면 이미 덮어쓴 영어를
  // "원본"으로 착각하게 된다. ko 문구를 이 파일에 복제하지 않는 이유이기도 하다 —
  // 한국어 문구의 단일 출처는 index.html 하나로 남는다.
  const koDescriptionRef = useRef<string | null>(null);

  // meta description만 공개 페이지에 한정한다. <html lang>은 루트가 맞추고, 이 페이지를
  // 떠나도 되돌리지 않는다. og:description·twitter:description은 크롤러가 JS를 돌리지
  // 않아 index.html만 보므로 건드리지 않는다.
  useEffect(() => {
    const meta = document.querySelector('meta[name="description"]');
    if (koDescriptionRef.current === null) {
      koDescriptionRef.current = meta?.getAttribute("content") ?? "";
    }
    const koDescription = koDescriptionRef.current;
    meta?.setAttribute("content", lang === "en" ? DESCRIPTION_EN : koDescription);

    return () => {
      meta?.setAttribute("content", koDescription);
    };
  }, [lang]);

  return children;
}
