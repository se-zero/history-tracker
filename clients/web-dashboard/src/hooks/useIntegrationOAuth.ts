import { useMutation } from "@tanstack/react-query";

import { getJiraAuthorizeUrl, getSlackAuthorizeUrl } from "@/api/integrations";

// Slack 동의 화면으로 이동하는 mutation. 조회가 아니라 side-effect(페이지 이동)라 queryKeys는 없다.
export function useSlackAuthorize(projectId: string) {
  return useMutation({
    mutationFn: () => getSlackAuthorizeUrl(projectId),
    onSuccess: (authorizeUrl) => {
      window.location.href = authorizeUrl;
    },
  });
}

// Jira 동의 화면으로 이동하는 mutation. provider가 아직 둘뿐이라 useSlackAuthorize와 추상 계층으로
// 합치지 않고 나란히 둔다 — 세 번째 provider 시점에 중복이 실제로 보이면 정리한다.
export function useJiraAuthorize(projectId: string) {
  return useMutation({
    mutationFn: () => getJiraAuthorizeUrl(projectId),
    onSuccess: (authorizeUrl) => {
      window.location.href = authorizeUrl;
    },
  });
}
