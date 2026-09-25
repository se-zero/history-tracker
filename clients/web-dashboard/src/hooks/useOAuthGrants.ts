import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { listOAuthGrants, revokeOAuthGrant } from "@/api/oauthGrants";
import { queryKeys } from "./queryKeys";

// 계정 페이지 "연결된 앱" 카드 — 내가 승인한 MCP 클라이언트 OAuth 동의 목록.
export function useOAuthGrants() {
  return useQuery({
    queryKey: queryKeys.oauthGrants(),
    queryFn: listOAuthGrants,
  });
}

// 연결 끊기. 성공하면 목록에서 사라져야 하므로 grants만 무효화한다.
export function useRevokeOAuthGrant() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (id: string) => revokeOAuthGrant(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.oauthGrants() });
    },
  });
}
