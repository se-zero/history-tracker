import type { CSSProperties } from "react";

import { GITHUB_AUTHORIZE_URL, GITHUB_INSTALL_URL } from "@/api/auth";

// GitHub App 설치가 하나도 없을 때의 빈 상태 — 온보딩 2단계와 데이터 소스의 GitHub 카드가 같이 쓴다.
// "연결 확인"(다시 로그인해 설치 목록을 새로 받는다)은 설치 뒤 다음으로 가는 유일한 수단이라 문장 속
// 링크가 아니라 버튼으로 둔다 — 전역 `a { color: inherit }` 때문에 문장 속 링크는 주변 글자와 똑같이
// 보여 새 사용자가 이 화면에서 멈췄다(#162). 앰버는 설치 하나에만 쓰고 연결 확인은 보조 버튼이다.
export function GitHubInstallEmptyState({ style }: { style?: CSSProperties }) {
  return (
    <div className="gh-install-empty" style={style}>
      <span>이 계정으로 GitHub App이 설치된 워크스페이스가 없어요.</span>
      <div className="gh-install-empty-actions">
        <a
          className="btn btn-primary"
          href={GITHUB_INSTALL_URL}
          target="_blank"
          rel="noopener noreferrer"
        >
          GitHub App 설치하기
        </a>
        <a className="btn btn-secondary" href={GITHUB_AUTHORIZE_URL}>
          연결 확인
        </a>
      </div>
      <span className="gh-install-empty-hint">
        설치를 마친 뒤 이 화면으로 돌아와 연결 확인을 눌러 주세요.
      </span>
    </div>
  );
}
