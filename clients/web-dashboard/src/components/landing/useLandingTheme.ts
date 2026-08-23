import { useState } from "react";
import { flushSync } from "react-dom";

export type LandingTheme = "dark" | "light";

// 랜딩 계열 공개 페이지(랜딩·약관·개인정보처리방침)가 공유하는 테마 상태.
// 앱 ThemeProvider(documentElement의 data-theme)와는 독립이다 — data-theme을 각 페이지
// 최상위 `.lp` 래퍼에만 붙이고, 토큰 오버라이드도 .lp[data-theme="light"] 스코프(tokens.css)라
// 앱 라우트에 부작용이 없고 언마운트 정리도 필요 없다.
//
// 토글로 고른 테마는 localStorage에 영속한다(2026-08-24 사용자 지시 — 초기의 "비영속,
// 이동 시 다크 리셋"은 라이트가 팀 검토용 실험이던 시절의 결정이라, 언어 선택이 영속되는
// 지금은 테마만 리셋되면 일관성이 깨져 보인다). 패턴은 LandingLanguageProvider와 동일 —
// **명시 선택(토글)에서만 저장**하고 기본값(다크)은 저장하지 않는다. 키는 앱 테마(ht.theme,
// documentElement 스코프)와 별도인 ht.lp-theme — 키를 공유하면 랜딩 토글이 앱 테마까지
// 바꾸는 결합이 생긴다(두 스코프의 독립은 위 문단의 전제).
//
// 전환 크로스페이드는 View Transition API(document.startViewTransition)로 처리한다.
// 이전의 "래퍼 임시 클래스 + 전역 색 transition" 방식은 폐기 — 수천 요소에 transition을
// 일괄 부착하면 스타일 재계산 잼 중 클래스 제거가 미완주 transition을 취소하면서 Chromium이
// 해당 서브트리를 시작값(다크)에 동결시키는 버그가 실측됐다. View Transition은 스냅샷 합성
// 기반이라 요소별 transition이 없어 이 계열을 구조적으로 회피하고, 그래프 모션·레이아웃도
// 흔들리지 않는다(기본 크로스페이드 그대로, 커스텀 ::view-transition 규칙 없음).
const STORAGE_KEY = "ht.lp-theme";

function readInitialTheme(): LandingTheme {
  try {
    const saved = window.localStorage.getItem(STORAGE_KEY);
    if (saved === "dark" || saved === "light") return saved;
  } catch {
    // 프라이버시 모드 등에서 접근이 throw할 수 있다 — 기본값으로 넘어간다.
  }
  return "dark"; // 랜딩의 기본 정체성은 다크(DESIGN.md) — 시스템 선호는 보지 않는다.
}

export function useLandingTheme() {
  const [theme, setTheme] = useState<LandingTheme>(readInitialTheme);

  const toggleTheme = () => {
    const next: LandingTheme = theme === "dark" ? "light" : "dark";
    try {
      window.localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // 저장 실패해도 이번 세션의 표시는 정상 동작하므로 무시한다.
    }
    // reduced-motion 또는 미지원 브라우저: 크로스페이드 생략, 즉시 점프(DESIGN.md 모션 규칙).
    if (
      window.matchMedia("(prefers-reduced-motion: reduce)").matches ||
      !document.startViewTransition
    ) {
      setTheme(next);
      return;
    }
    document.startViewTransition(() => {
      // flushSync — 스냅샷 콜백이 끝나는 시점에 새 테마 DOM이 커밋되어 있어야 한다
      // (비동기 배칭에 맡기면 "변경 후" 스냅샷이 옛 테마를 찍는다).
      flushSync(() => setTheme(next));
    });
  };

  return { theme, toggleTheme };
}
