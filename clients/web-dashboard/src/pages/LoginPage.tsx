import { Icons } from "@/components/Icons";
import { GITHUB_AUTHORIZE_URL } from "@/api/auth";

export function LoginPage() {
  return (
    <div className="login-page">
      <div className="login-bg-grid" />
      <div className="login-card">
        <div className="logo" style={{ justifyContent: "center" }}>
          <span className="logo-mark" />
          <span style={{ fontSize: 16 }}>History Tracker</span>
        </div>
        <h1>코드 변경의 진짜 이유를 찾다</h1>
        <p className="tagline">
          GitHub · Jira · Slack을 하나의 지식 그래프로 연결.
          <br />
          자연어로 의사결정의 맥락을 묻고 답하세요.
        </p>
        <a className="gh-btn" href={GITHUB_AUTHORIZE_URL}>
          <Icons.GitHub />
          GitHub로 계속하기
        </a>
        <p className="login-foot">
          계속 진행하면 서비스 약관과 개인정보 처리방침에 동의하게 됩니다.
        </p>
      </div>
    </div>
  );
}
