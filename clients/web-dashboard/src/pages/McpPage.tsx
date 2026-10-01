import { LegalLayout } from "@/components/landing/LegalLayout";
import { McpBody } from "@/components/landing/McpBody";

// Mcp — 공개 라우트(/mcp/setup). /mcp가 아니라 /mcp/setup인 이유: /mcp는 코딩 에이전트가
// 접속하는 백엔드의 MCP 엔드포인트라서 nginx가 그쪽으로 라우팅한다. 셸만 LegalLayout을 빌린다.
export function McpPage() {
  return (
    <LegalLayout
      title={{ ko: "코딩 에이전트에서 쓰는 방법", en: "whycode in your coding agent" }}
      summary={{
        ko: (
          <>
            Claude Code·Codex에 whycode를 한 번 연결하면, 코드를 보고 있는 그 자리에서 "이 코드 왜 이렇게
            바뀌었어?"를 물을 수 있습니다. 연결된 프로젝트의 이슈·PR·대화 기록에서 근거를 찾아 답합니다.
          </>
        ),
        en: (
          <>
            Connect whycode to Claude Code or Codex once, and you can ask "why did this code change?" right
            where you're reading it. whycode finds the answer in the connected project's issues, PRs, and
            conversation history.
          </>
        ),
      }}
    >
      <McpBody />
    </LegalLayout>
  );
}
