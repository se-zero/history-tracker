import { useEffect } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";

import { decideOAuthConsent, fetchOAuthConsentPreview } from "@/api/oauth";
import { isHttpUrl } from "@/lib/url";
import { queryKeys } from "./queryKeys";

// backend가 돌려주는 redirectTo는 검증된 redirect_uri(루프백·https)나 우리 인가 엔드포인트
// (/oauth2/authorize?…)뿐이지만, 최상위 이동 싱크라 스킴 가드를 한 겹 더 둔다 — 프로덕션 CSP
// (script-src 'self')가 javascript: 이동을 막는 것에만 기대지 않기 위해서다.
function isSafeRedirect(url: string): boolean {
  return isHttpUrl(url) || (url.startsWith("/") && !url.startsWith("//"));
}

// MCP 클라이언트가 요청한 앱·권한·복귀 주소 미리보기. 400(요청 자체가 잘못됨)은 재시도해도
// 같은 결과라 retry를 끈다.
export function useOAuthConsentPreview(query: string) {
  return useQuery({
    queryKey: queryKeys.oauthConsentPreview(query),
    queryFn: () => fetchOAuthConsentPreview(query),
    retry: false,
  });
}

// 허용/거부 결정 — 성공하면 backend가 돌려준 주소로 최상위 네비게이션한다.
export function useOAuthConsentDecision() {
  const mutation = useMutation({
    mutationFn: async ({ query, approved }: { query: string; approved: boolean }) => {
      const result = await decideOAuthConsent(query, approved);
      if (!isSafeRedirect(result.redirectTo)) {
        throw new Error("unsafe redirectTo from consent decision");
      }
      return result;
    },
    onSuccess: ({ redirectTo }) => {
      window.location.href = redirectTo;
    },
  });

  // 이동한 곳(에이전트의 localhost 콜백)이 이미 닫혀 브라우저 오류가 뜨고 뒤로가기로 돌아오면
  // 페이지가 bfcache로 복원되어(pageshow persisted) mutation이 pending/success로 남는다 —
  // 두 버튼이 "이동 중…"에 고정되지 않도록 리셋한다(useIntegrationOAuth.ts와 같은 이유).
  useEffect(() => {
    const handlePageShow = (e: PageTransitionEvent) => {
      if (e.persisted) mutation.reset();
    };
    window.addEventListener("pageshow", handlePageShow);
    return () => window.removeEventListener("pageshow", handlePageShow);
  }, [mutation.reset]);

  return mutation;
}
