import { useState } from "react";
import { flushSync } from "react-dom";

export type LandingTheme = "dark" | "light";

// 랜딩 계열 공개 페이지(랜딩·약관·개인정보처리방침)가 공유하는 테마 상태.
// 앱 ThemeProvider(documentElement의 data-theme)와는 독립이다 — data-theme을 각 페이지
// 최상위 `.lp` 래퍼에만 붙이고, 토큰 오버라이드도 .lp[data-theme="light"] 스코프(tokens.css)라
// 앱 라우트에 부작용이 없고 언마운트 정리도 필요 없다.
// 상태는 useState뿐 — localStorage/sessionStorage는 쓰지 않는다(페이지 이동 시 다크로 돌아간다).
//
// 전환 크로스페이드는 View Transition API(document.startViewTransition)로 처리한다.
// 이전의 "래퍼 임시 클래스 + 전역 색 transition" 방식은 폐기 — 수천 요소에 transition을
// 일괄 부착하면 스타일 재계산 잼 중 클래스 제거가 미완주 transition을 취소하면서 Chromium이
// 해당 서브트리를 시작값(다크)에 동결시키는 버그가 실측됐다. View Transition은 스냅샷 합성
// 기반이라 요소별 transition이 없어 이 계열을 구조적으로 회피하고, 그래프 모션·레이아웃도
// 흔들리지 않는다(기본 크로스페이드 그대로, 커스텀 ::view-transition 규칙 없음).
export function useLandingTheme() {
  const [theme, setTheme] = useState<LandingTheme>("dark");

  const toggleTheme = () => {
    const next: LandingTheme = theme === "dark" ? "light" : "dark";
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
