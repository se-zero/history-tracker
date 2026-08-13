import type { ComponentType } from "react";

import {
  AsanaMark,
  ClickUpMark,
  DiscordMark,
  GithubMark,
  GoogleChatMark,
  JiraMark,
  LinearMark,
  MicrosoftTeamsMark,
  MondayMark,
  NotionMark,
  SlackMark,
} from "@/components/brand/BrandMarks";

interface SourceBase {
  id: string;
  name: string;
  description: string;
  Mark: ComponentType<{ size?: number; className?: string }>;
}

/**
 * 소스 항목 — `status`로 갈리는 판별 유니온이다.
 *
 * <p>연동이 붙었다고 선언(`status: "wired"`)하는 순간 연결 방식과 해제 고지를 **반드시** 같이 적어야
 * 컴파일이 통과한다. 둘 중 하나만 채운 반쪽 상태를 타입이 막는 것이 요점이다 — 예전에는 provider마다
 * 전용 카드 컴포넌트가 있어 반쪽 배선이 불가능했는데, 카드를 공용 하나로 합치면서 그 안전망이 사라졌다.
 * (backend `IntegrationResponse.displayName`의 exhaustive switch와 같은 역할이다.)</p>
 */
export type SourceCatalogItem = SourceBase &
  (
    | {
        status: "wired";
        // "oauth"만 타일의 연결 버튼이 authorize를 호출한다.
        // GitHub은 App 설치라 타일이 아니라 전용 카드(GitHubCard)로 붙인다.
        connect: "oauth" | "github-app";
        // 연동 해제 다이얼로그에서 "무엇이 지워지는지" 구체적으로 알려줄 문구 — "데이터가 삭제됩니다"
        // 같은 뭉뚱그린 문구는 사용자가 무엇을 잃는지 판단할 수 없어 파괴적 동작의 고지로 부족하다.
        deletedData: string;
        // 동의 승인만으로 provider 쪽에 생기는 부수효과 — 우리 응답과 무관하게 일어나므로 연결이
        // 실패로 끝나도 남을 수 있다(Discord: 동의 순간 봇이 서버에 들어간다). 그중 서버가 정리할
        // 수단이 없는 경우를 사용자에게 알리는 문구다. 부수효과가 없는 소스는 비워 둔다.
        consentSideEffect?: string;
      }
    // backend 연동이 아직 없는 소스 — 타일 자리만 잡는다.
    | { status: "planned" }
  );

// 데이터 소스 11종 카탈로그. 소스별 화면(타일·연동 행·해제 다이얼로그)은 모두 이 배열의
// filter+map으로만 렌더된다 — 새 소스는 backend 연동이 준비되면 여기 항목의 status를
// "wired"로 바꾸는 것으로 끝난다(공용 컴포넌트는 고치지 않는다).
export const sourceCatalog: SourceCatalogItem[] = [
  {
    id: "github",
    name: "GitHub",
    description: "코드 맥락",
    Mark: GithubMark,
    status: "wired",
    connect: "github-app",
    deletedData: "수집한 커밋·Pull Request·이슈와 그 그래프",
  },
  {
    id: "jira",
    name: "Jira",
    description: "이슈 맥락",
    Mark: JiraMark,
    status: "wired",
    connect: "oauth",
    deletedData: "수집한 이슈와 그 그래프",
  },
  {
    id: "slack",
    name: "Slack",
    description: "대화 맥락",
    Mark: SlackMark,
    status: "wired",
    connect: "oauth",
    deletedData: "수집한 채널 메시지·스레드와 그 그래프",
  },
  {
    id: "google-chat",
    name: "Google Chat",
    description: "대화 맥락",
    Mark: GoogleChatMark,
    status: "wired",
    connect: "oauth",
    deletedData: "수집한 스페이스 메시지·스레드와 그 그래프 연결",
  },
  {
    id: "discord",
    name: "Discord",
    description: "대화 맥락",
    Mark: DiscordMark,
    status: "wired",
    connect: "oauth",
    deletedData: "수집한 채널 메시지·스레드와 그 그래프, 그리고 서버에 추가된 봇",
    consentSideEffect:
      "방금 동의로 봇이 서버에 추가되었을 수 있어요. 사용하지 않으신다면 Discord 서버 설정에서 제거해 주세요.",
  },
  { id: "notion", name: "Notion", description: "문서 맥락", Mark: NotionMark, status: "planned" },
  {
    id: "linear",
    name: "Linear",
    description: "이슈 맥락",
    Mark: LinearMark,
    status: "wired",
    connect: "oauth",
    deletedData: "수집한 이슈와 그 그래프",
  },
  {
    id: "asana",
    name: "Asana",
    description: "작업 맥락",
    Mark: AsanaMark,
    status: "wired",
    connect: "oauth",
    deletedData: "수집한 작업(태스크)과 그 그래프",
  },
  { id: "monday", name: "monday.com", description: "작업 맥락", Mark: MondayMark, status: "planned" },
  {
    id: "clickup",
    name: "ClickUp",
    description: "작업 맥락",
    Mark: ClickUpMark,
    status: "wired",
    connect: "oauth",
    deletedData: "수집한 태스크와 그 그래프",
  },
  { id: "teams", name: "MS Teams", description: "대화 맥락", Mark: MicrosoftTeamsMark, status: "planned" },
];

export function findSource(id: string | null | undefined): SourceCatalogItem | undefined {
  return sourceCatalog.find((source) => source.id === id);
}

// provider id를 사람이 읽는 이름으로 — 카탈로그에 없으면 id를 그대로 보여준다
export function sourceName(id: string | null | undefined): string {
  return findSource(id)?.name ?? id ?? "연동";
}

// 타일의 "연결" 버튼이 authorize를 호출할 소스인지 (공용 코드에 provider 분기를 두지 않기 위한 조회)
export function isOAuthConnectable(source: SourceCatalogItem): boolean {
  return source.status === "wired" && source.connect === "oauth";
}

/**
 * 해제 다이얼로그에 쓸 "무엇이 지워지는지" 문구.
 *
 * <p>연동이 붙은 소스는 타입상 반드시 이 문구를 갖는다. 폴백은 **배포 시차용**이다 —
 * backend가 프론트보다 먼저 새 provider를 내보내 카탈로그에 없는 연동 행이 올 수 있고, 그때 파괴적
 * 동작의 다이얼로그가 빈 문구를 보이거나 터지는 것보다는 뭉뚱그려서라도 알리는 편이 낫다.
 * 카탈로그 항목을 빠뜨렸을 때의 도피로가 아니다(그건 타입이 먼저 막는다).</p>
 */
export function deletedDataOf(id: string | null | undefined): string {
  const source = findSource(id);
  return source?.status === "wired" ? source.deletedData : "수집한 데이터와 그 그래프";
}

// 동의만으로 생겨 서버가 정리하지 못하는 부수효과 안내 문구 (없으면 null)
export function consentSideEffectOf(id: string | null | undefined): string | null {
  const source = findSource(id);
  return source?.status === "wired" ? (source.consentSideEffect ?? null) : null;
}
