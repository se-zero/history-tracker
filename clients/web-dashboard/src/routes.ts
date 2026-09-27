// 최상위 라우트 경로의 단일 출처 — URL 체계 개편이 예정돼 있어 리터럴 산포를 막는다.
export const PATHS = {
  root: "/",
  // 로그인 후 첫 프로젝트의 계정 설정으로 보낸다. 공개 페이지가 아니라 사이트맵에 넣지 않는다.
  account: "/account",
  landing: "/landing",
  authCallback: "/auth/callback",
  onboarding: "/onboarding",
  terms: "/terms",
  privacy: "/privacy",
  support: "/support",
  slack: "/slack",
  pricing: "/pricing",
  refund: "/refund",
  mcpSetup: "/mcp/setup",
  oauthConsent: "/oauth/consent",
} as const;
