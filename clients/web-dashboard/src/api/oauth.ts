import { api } from "./client";
import type { OAuthConsentDecision, OAuthConsentPreview } from "@/types/api";

// query는 window.location.search에서 앞의 ?를 뗀 원본 쿼리 문자열 그대로다. backend가 이
// 문자열의 해시로 티켓을 검증하므로 정렬·디코딩·재인코딩하지 않고 그대로 넘긴다.
export async function fetchOAuthConsentPreview(
  query: string,
): Promise<OAuthConsentPreview> {
  const { data } = await api.get<OAuthConsentPreview>(
    `/oauth/consent/preview?${query}`,
  );
  return data;
}

export async function decideOAuthConsent(
  query: string,
  approved: boolean,
): Promise<OAuthConsentDecision> {
  const { data } = await api.post<OAuthConsentDecision>("/oauth/consent", {
    query,
    approved,
  });
  return data;
}
