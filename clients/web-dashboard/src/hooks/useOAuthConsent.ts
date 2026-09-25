import { useMutation, useQuery } from "@tanstack/react-query";

import { decideOAuthConsent, fetchOAuthConsentPreview } from "@/api/oauth";
import { queryKeys } from "./queryKeys";

// MCP 클라이언트가 요청한 앱·권한·복귀 주소 미리보기. 400(요청 자체가 잘못됨)은 재시도해도
// 같은 결과라 retry를 끈다.
export function useOAuthConsentPreview(query: string) {
  return useQuery({
    queryKey: queryKeys.oauthConsentPreview(query),
    queryFn: () => fetchOAuthConsentPreview(query),
    retry: false,
  });
}

// 허용/거부 결정 — 성공하면 backend가 돌려준 주소로 최상위 네비게이션한다. 이 페이지는
// 돌아올 일이 없어(항상 페이지를 떠난다) useIntegrationOAuth.ts의 pageshow 리셋은 불필요하다.
export function useOAuthConsentDecision() {
  return useMutation({
    mutationFn: ({ query, approved }: { query: string; approved: boolean }) =>
      decideOAuthConsent(query, approved),
    onSuccess: ({ redirectTo }) => {
      window.location.href = redirectTo;
    },
  });
}
