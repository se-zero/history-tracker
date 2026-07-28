import { useMutation } from "@tanstack/react-query";

import { getSlackAuthorizeUrl } from "@/api/integrations";

// Slack 동의 화면으로 이동하는 mutation. 조회가 아니라 side-effect(페이지 이동)라 queryKeys는 없다.
export function useSlackAuthorize(projectId: string) {
  return useMutation({
    mutationFn: () => getSlackAuthorizeUrl(projectId),
    onSuccess: (authorizeUrl) => {
      window.location.href = authorizeUrl;
    },
  });
}
