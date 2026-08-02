import { useEffect } from "react";
import { useMutation } from "@tanstack/react-query";

import { getJiraAuthorizeUrl, getSlackAuthorizeUrl } from "@/api/integrations";

// Slack 동의 화면으로 이동하는 mutation. 조회가 아니라 side-effect(페이지 이동)라 queryKeys는 없다.
export function useSlackAuthorize(projectId: string) {
  const mutation = useMutation({
    mutationFn: () => getSlackAuthorizeUrl(projectId),
    onSuccess: (authorizeUrl) => {
      window.location.href = authorizeUrl;
    },
  });

  // 동의 화면에서 브라우저 뒤로가기로 돌아오면 페이지가 bfcache로 복원되어(pageshow persisted)
  // mutation이 isSuccess로 남아 있을 수 있다 — 버튼 라벨이 "이동 중…"에 고정되지 않도록 리셋한다.
  useEffect(() => {
    const handlePageShow = (e: PageTransitionEvent) => {
      if (e.persisted) mutation.reset();
    };
    window.addEventListener("pageshow", handlePageShow);
    return () => window.removeEventListener("pageshow", handlePageShow);
  }, [mutation.reset]);

  return mutation;
}

// Jira 동의 화면으로 이동하는 mutation. provider가 아직 둘뿐이라 useSlackAuthorize와 추상 계층으로
// 합치지 않고 나란히 둔다 — 세 번째 provider 시점에 중복이 실제로 보이면 정리한다.
export function useJiraAuthorize(projectId: string) {
  const mutation = useMutation({
    mutationFn: () => getJiraAuthorizeUrl(projectId),
    onSuccess: (authorizeUrl) => {
      window.location.href = authorizeUrl;
    },
  });

  // 동의 화면에서 브라우저 뒤로가기로 돌아오면 페이지가 bfcache로 복원되어(pageshow persisted)
  // mutation이 isSuccess로 남아 있을 수 있다 — 버튼 라벨이 "이동 중…"에 고정되지 않도록 리셋한다.
  useEffect(() => {
    const handlePageShow = (e: PageTransitionEvent) => {
      if (e.persisted) mutation.reset();
    };
    window.addEventListener("pageshow", handlePageShow);
    return () => window.removeEventListener("pageshow", handlePageShow);
  }, [mutation.reset]);

  return mutation;
}
