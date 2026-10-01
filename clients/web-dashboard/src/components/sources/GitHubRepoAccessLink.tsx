import type { ReactNode } from "react";

import { GITHUB_INSTALL_URL } from "@/api/auth";

// GitHub App의 저장소·조직 접근을 바꾸는 GitHub 쪽 화면으로 보낸다. GitHub가 내 계정과 조직 목록을 보여주고
// 설치된 곳은 설정으로, 아닌 곳은 설치로 이어 준다. 새 탭으로 여는 이유는 지금 화면(온보딩 입력 등)을 그대로 두고
// GitHub에서 바꾼 뒤 돌아와 "연결 확인"을 누르는 흐름이기 때문이다.
export function GitHubRepoAccessLink({
  className = "btn btn-secondary",
  children = "GitHub에서 저장소·조직 추가",
}: {
  className?: string;
  children?: ReactNode;
}) {
  return (
    <a className={className} href={GITHUB_INSTALL_URL} target="_blank" rel="noopener noreferrer">
      {children}
    </a>
  );
}
