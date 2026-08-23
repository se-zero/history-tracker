import { useEffect, useState } from "react";

// 미디어 쿼리 매치 여부를 구독하는 공용 훅. 랜딩은 CSR 전용(SSR 없음)이라 초기 렌더에서
// 바로 window.matchMedia를 읽어도 하이드레이션 불일치가 없다 — lazy initializer로 첫
// 렌더부터 정확한 값을 준다(마운트 후 한 프레임 지나서야 맞는 값이 뜨는 깜빡임을 피한다).
// 리사이즈로 쿼리 상태가 실시간으로 바뀌는 것도 change 이벤트로 반영한다(뷰포트 경계를
// 넘나들 때 히어로 영상 ↔ DOM 목업 전환에 쓰인다).
export function useMatchMedia(query: string): boolean {
  const [matches, setMatches] = useState(() => window.matchMedia(query).matches);

  useEffect(() => {
    const mql = window.matchMedia(query);
    setMatches(mql.matches);
    const handler = (e: MediaQueryListEvent) => setMatches(e.matches);
    mql.addEventListener("change", handler);
    return () => mql.removeEventListener("change", handler);
  }, [query]);

  return matches;
}
