import type { ReactNode } from "react";
import { Link } from "react-router-dom";

import { GITHUB_AUTHORIZE_URL } from "@/api/auth";
import { useAuth } from "@/auth/AuthProvider";
import { useLandingLanguage, type Localized } from "@/components/landing/LandingLanguageProvider";
import { PATHS } from "@/routes";

const COPY: Localized<{
  whatHeading: string;
  whatItem1: string;
  whatItem2: string;
  readyHeading: string;
  readyBody: string;
  ctaOpenApp: string;
  ctaStart: string;
  connectHeading: string;
  claudeCodeHeading: string;
  claudeCodeStep1: string;
  claudeCodeCommand: string;
  claudeCodeStep2: ReactNode;
  codexHeading: string;
  codexStep1: ReactNode;
  codexToml: string;
  codexStep2: ReactNode;
  supportedClients: string;
  folderHeading: string;
  folderBody1: string;
  folderBody2: string;
  folderBody3: string;
  askHeading: string;
  askIntro: string;
  askExample1: string;
  askExample2: string;
  askExample3: string;
  askTip: string;
  askClaudeMdIntro: ReactNode;
  askClaudeMdLine: string;
  noteHeading: string;
  noteItem1: string;
  noteItem2: string;
  noteItem3: string;
  noteItem4: string;
  noteItem5: string;
  noteItem6: string;
  relatedHeading: string;
  privacy: string;
  support: string;
  terms: string;
}> = {
  ko: {
    whatHeading: "무엇을 할 수 있나",
    whatItem1:
      "특정 줄이 왜 바뀌었는지 물을 수 있습니다 — 에이전트가 git blame으로 커밋을 찾으면, whycode가 그 이유를 설명합니다.",
    whatItem2: '자연어로 변경 배경을 물을 수 있습니다. 예: "결제 재시도 로직 왜 바뀌었어?"',
    readyHeading: "준비",
    readyBody: "whycode 계정(GitHub 로그인)과, 소스를 연결해 수집까지 마친 프로젝트가 하나 이상 필요합니다.",
    ctaOpenApp: "앱 열기",
    ctaStart: "GitHub으로 시작",
    connectHeading: "클라이언트 연결",
    claudeCodeHeading: "Claude Code",
    claudeCodeStep1: "터미널에서 실행합니다.",
    claudeCodeCommand: "claude mcp add --transport http whycode https://why-code.com/mcp",
    claudeCodeStep2: (
      <>
        그 다음 Claude Code 세션에서 <code>/mcp</code>를 입력해 whycode를 선택하면 브라우저가 열립니다. 필요하면
        GitHub으로 로그인한 뒤 "허용"을 누릅니다. 이후 연결은 자동으로 갱신되고, 30일 넘게 사용하지 않은 경우에만
        다시 로그인합니다.
      </>
    ),
    codexHeading: "Codex",
    codexStep1: (
      <>
        <code>~/.codex/config.toml</code>에 아래를 추가합니다.
      </>
    ),
    codexToml: '[mcp_servers.whycode]\nurl = "https://why-code.com/mcp"\ntool_timeout_sec = 120',
    codexStep2: (
      <>
        그 다음 <code>codex mcp login whycode</code>를 실행합니다. <code>tool_timeout_sec</code>를 120으로 두는
        이유는, Codex 기본값인 60초로는 whycode 답변(보통 10~60초, 근거가 많으면 그 이상)이 끝나기 전에 시간
        초과가 날 수 있어서입니다.
      </>
    ),
    supportedClients: "지원하는 클라이언트는 현재 Claude Code와 Codex입니다.",
    folderHeading: "폴더와 프로젝트 연결하기",
    folderBody1:
      '에이전트를 여는 저장소 폴더마다 어느 whycode 프로젝트에 물을지 한 번 정해 둡니다. 그 폴더에서 에이전트에게 "whycode 프로젝트 연결해줘"라고 말하면 프로젝트 목록을 보여주고, 고른 프로젝트를 그 폴더(절대 경로) 기준으로 저장합니다.',
    folderBody2:
      "이 단계를 건너뛰고 바로 질문해도, 그때 물어봅니다. 프로젝트가 하나뿐이면 묻지 않고 자동으로 연결됩니다.",
    folderBody3: "다른 폴더나 다른 PC에서는 다시 정해야 합니다.",
    askHeading: "질문하는 법",
    askIntro: "별도 명령 없이 자연어로 묻습니다. 예:",
    askExample1: '"이 줄 왜 이렇게 바뀌었어?"',
    askExample2: '"이 함수의 재시도 횟수가 3인 이유가 뭐야?"',
    askExample3: '"결제 재시도 로직 왜 바뀌었어?"',
    askTip: '처음에는 "whycode로 찾아봐"처럼 이름을 붙이면 에이전트가 확실히 whycode를 사용합니다.',
    askClaudeMdIntro: (
      <>
        저장소의 <code>CLAUDE.md</code>나 <code>AGENTS.md</code>에 아래 한 줄을 붙여 두면 더 안정적으로 쓰입니다.
      </>
    ),
    askClaudeMdLine: "코드 변경의 이유나 의사결정 맥락이 궁금하면 whycode MCP 도구(explain_commit, ask)를 사용한다.",
    noteHeading: "알아 둘 것",
    noteItem1: "답변은 한국어로 옵니다.",
    noteItem2: "답변에는 보통 10~60초가 걸립니다.",
    noteItem3: "FREE 플랜의 질의 10회 한도에 에이전트에서 한 질문도 합산됩니다(대시보드·Slack과 같은 카운트).",
    noteItem4: "대화 내용은 저장되지 않습니다.",
    noteItem5: "아직 수집되지 않은 커밋에 대해 물으면, 답변이 그렇게 알려줍니다.",
    noteItem6: "연결을 끊으려면 whycode 계정 페이지에서 해제합니다.",
    relatedHeading: "관련 문서",
    privacy: "개인정보처리방침",
    support: "지원",
    terms: "이용약관",
  },
  en: {
    whatHeading: "What you can do",
    whatItem1: "Ask why a specific line changed — the agent finds the commit with git blame, and whycode explains the reason.",
    whatItem2: 'Ask about the background of a change in plain language. For example: "why did the payment retry logic change?"',
    readyHeading: "Before you start",
    readyBody: "You need a whycode account (GitHub sign-in) and at least one project with a source connected and collection complete.",
    ctaOpenApp: "Open app",
    ctaStart: "Start with GitHub",
    connectHeading: "Connect a client",
    claudeCodeHeading: "Claude Code",
    claudeCodeStep1: "Run this in your terminal.",
    claudeCodeCommand: "claude mcp add --transport http whycode https://why-code.com/mcp",
    claudeCodeStep2: (
      <>
        Then, in a Claude Code session, type <code>/mcp</code> and select whycode — a browser window opens. Sign
        in with GitHub if needed, then press "Allow". After that, the connection refreshes automatically; you only
        sign in again if you haven't used it for more than 30 days.
      </>
    ),
    codexHeading: "Codex",
    codexStep1: (
      <>
        Add this to <code>~/.codex/config.toml</code>.
      </>
    ),
    codexToml: '[mcp_servers.whycode]\nurl = "https://why-code.com/mcp"\ntool_timeout_sec = 120',
    codexStep2: (
      <>
        Then run <code>codex mcp login whycode</code>. <code>tool_timeout_sec</code> is set to 120 because
        Codex's default of 60 seconds can time out before a whycode answer finishes — answers usually take
        10-60 seconds, longer when there's more evidence to cite.
      </>
    ),
    supportedClients: "Claude Code and Codex are supported today.",
    folderHeading: "Connect a folder to a project",
    folderBody1:
      'For each repository folder you open the agent in, you pick which whycode project it should ask once. Tell the agent something like "connect a whycode project" in that folder — it shows your project list and saves the one you pick for that folder (by absolute path).',
    folderBody2:
      "Skip this and ask a question right away, and it will ask then instead. If you only have one project, it connects automatically without asking.",
    folderBody3: "You'll set it again in a different folder or on a different machine.",
    askHeading: "How to ask",
    askIntro: "Just ask in plain language — no special command. For example:",
    askExample1: '"why did this line change?"',
    askExample2: '"why does this function retry 3 times?"',
    askExample3: '"why did the payment retry logic change?"',
    askTip: 'The first time, naming it — like "look this up with whycode" — makes the agent reliably reach for whycode.',
    askClaudeMdIntro: (
      <>
        Adding this line to your repository's <code>CLAUDE.md</code> or <code>AGENTS.md</code> makes this more
        reliable.
      </>
    ),
    askClaudeMdLine: "When you're curious about the reason behind a code change or the decision context, use the whycode MCP tools (explain_commit, ask).",
    noteHeading: "Good to know",
    noteItem1: "Answers come back in Korean.",
    noteItem2: "Answers usually take 10-60 seconds.",
    noteItem3: "Questions asked from the agent count toward the FREE plan's 10-query limit — the same count as the dashboard and Slack.",
    noteItem4: "Conversations aren't stored.",
    noteItem5: "Ask about a commit that hasn't been collected yet, and the answer will say so.",
    noteItem6: "Disconnect from your whycode account page.",
    relatedHeading: "Related",
    privacy: "Privacy Policy",
    support: "Support",
    terms: "Terms of Service",
  },
};

// Mcp 본문 — SlackBody와 같은 패턴(조 번호 없는 짧은 산문). LegalLayout 안에서 렌더되므로 언어는
// Provider에서 읽는다. 준비 절의 실행 버튼은 SlackBody의 인증 상태 분기를 그대로 복제한 것이다.
export function McpBody() {
  const { lang } = useLandingLanguage();
  const { status } = useAuth();
  const t = COPY[lang];

  return (
    <>
      <section className="lp-legal-section">
        <h2 className="lp-legal-heading">{t.whatHeading}</h2>
        <ul>
          <li>{t.whatItem1}</li>
          <li>{t.whatItem2}</li>
        </ul>
      </section>

      <section className="lp-legal-section">
        <h2 className="lp-legal-heading">{t.readyHeading}</h2>
        <p>{t.readyBody}</p>
        <p>
          {status === "authenticated" ? (
            <Link className="lp-btn lp-btn--primary" to={PATHS.root}>{t.ctaOpenApp}</Link>
          ) : (
            <a className="lp-btn lp-btn--primary" href={GITHUB_AUTHORIZE_URL}>{t.ctaStart}</a>
          )}
        </p>
      </section>

      <section className="lp-legal-section">
        <h2 className="lp-legal-heading">{t.connectHeading}</h2>

        <h3 className="lp-legal-subheading">{t.claudeCodeHeading}</h3>
        <p>{t.claudeCodeStep1}</p>
        <pre className="lp-legal-pre">
          <code>{t.claudeCodeCommand}</code>
        </pre>
        <p>{t.claudeCodeStep2}</p>

        <h3 className="lp-legal-subheading">{t.codexHeading}</h3>
        <p>{t.codexStep1}</p>
        <pre className="lp-legal-pre">
          <code>{t.codexToml}</code>
        </pre>
        <p>{t.codexStep2}</p>

        <p className="lp-legal-note">{t.supportedClients}</p>
      </section>

      <section className="lp-legal-section">
        <h2 className="lp-legal-heading">{t.folderHeading}</h2>
        <p>{t.folderBody1}</p>
        <p>{t.folderBody2}</p>
        <p>{t.folderBody3}</p>
      </section>

      <section className="lp-legal-section">
        <h2 className="lp-legal-heading">{t.askHeading}</h2>
        <p>{t.askIntro}</p>
        <ul>
          <li>{t.askExample1}</li>
          <li>{t.askExample2}</li>
          <li>{t.askExample3}</li>
        </ul>
        <p>{t.askTip}</p>
        <p>{t.askClaudeMdIntro}</p>
        <pre className="lp-legal-pre">
          <code>{t.askClaudeMdLine}</code>
        </pre>
      </section>

      <section className="lp-legal-section">
        <h2 className="lp-legal-heading">{t.noteHeading}</h2>
        <ul>
          <li>{t.noteItem1}</li>
          <li>{t.noteItem2}</li>
          <li>{t.noteItem3}</li>
          <li>{t.noteItem4}</li>
          <li>{t.noteItem5}</li>
          <li>{t.noteItem6}</li>
        </ul>
      </section>

      <section className="lp-legal-section">
        <h2 className="lp-legal-heading">{t.relatedHeading}</h2>
        <ul>
          <li>
            <Link to={PATHS.privacy}>{t.privacy}</Link>
          </li>
          <li>
            <Link to={PATHS.support}>{t.support}</Link>
          </li>
          <li>
            <Link to={PATHS.terms}>{t.terms}</Link>
          </li>
        </ul>
      </section>
    </>
  );
}
