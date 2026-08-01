// 최상위 라우트 경로의 단일 출처 — URL 체계 개편이 예정돼 있어 리터럴 산포를 막는다.
export const PATHS = {
  root: "/",
  landing: "/landing",
  authCallback: "/auth/callback",
  onboarding: "/onboarding",
  demoGraph: "/demo/graph",
} as const;
