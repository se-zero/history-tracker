import type { ReactNode } from "react";
import { useLocation } from "react-router-dom";

import { GITHUB_AUTHORIZE_URL } from "@/api/auth";
import { saveReturnPath } from "@/auth/returnPath";

// "연결 확인" — GitHub 로그인을 다시 거쳐 설치 목록을 새로 받는다. 로그인 콜백은 복귀 경로가 없으면 항상
// "/"로 보내므로, 프로젝트가 있는 사용자는 누른 화면이 아니라 첫 프로젝트의 채팅으로 떨어진다.
// 그래서 나가기 직전에 지금 화면을 복귀 경로로 남겨 같은 화면으로 돌아오게 한다.
export function GitHubRecheckLink({
  className,
  children,
}: {
  className?: string;
  children: ReactNode;
}) {
  const location = useLocation();
  return (
    <a
      className={className}
      href={GITHUB_AUTHORIZE_URL}
      onClick={() => saveReturnPath(location.pathname + location.search)}
    >
      {children}
    </a>
  );
}
