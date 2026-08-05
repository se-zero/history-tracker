import { useEffect } from "react";
import { useMutation } from "@tanstack/react-query";

import { getAuthorizeUrl } from "@/api/integrations";

// OAuth 동의 화면으로 이동하는 mutation — provider는 mutate 인자로 받아 인스턴스 하나로
// 모든 소스를 감당한다(타일 그리드처럼 여러 provider를 다루는 곳에서 훅 개수를 고정하기 위함).
// 어느 provider로 이동 중인지는 mutation.variables로 판별한다.
// 조회가 아니라 side-effect(페이지 이동)라 queryKeys는 없다.
export function useIntegrationAuthorize(projectId: string) {
  const mutation = useMutation({
    mutationFn: (provider: string) => getAuthorizeUrl(projectId, provider),
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
