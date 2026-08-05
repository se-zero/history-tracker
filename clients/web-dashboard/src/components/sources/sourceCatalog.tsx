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

export interface SourceCatalogItem {
  id: string;
  name: string;
  description: string;
  Mark: ComponentType<{ size?: number; className?: string }>;
  // backend에 OAuth 연동이 실제로 붙은 소스만 true — 타일의 "연결" 버튼이 authorize를 호출한다.
  // false(미지원)면 버튼이 no-op으로 남는다.
  connectable?: boolean;
  // 연동 해제 다이얼로그에서 "무엇이 지워지는지" 구체적으로 알려줄 문구 — "데이터가 삭제됩니다"
  // 같은 뭉뚱그린 문구는 사용자가 무엇을 잃는지 판단할 수 없어 파괴적 동작의 고지로 부족하다.
  deletedData?: string;
}

// 데이터 소스 11종 카탈로그. 소스별 화면(타일·연동 행·해제 다이얼로그)은 모두 이 배열의
// filter+map으로만 렌더된다 — 새 소스는 backend 연동이 준비되면 여기에 항목을 더하고
// connectable·deletedData를 채우는 것으로 끝난다(공용 컴포넌트는 고치지 않는다).
export const sourceCatalog: SourceCatalogItem[] = [
  {
    id: "github",
    name: "GitHub",
    description: "코드 맥락",
    Mark: GithubMark,
    deletedData: "수집한 커밋·Pull Request·이슈와 그 그래프",
  },
  {
    id: "jira",
    name: "Jira",
    description: "이슈 맥락",
    Mark: JiraMark,
    connectable: true,
    deletedData: "수집한 이슈와 그 그래프",
  },
  {
    id: "slack",
    name: "Slack",
    description: "대화 맥락",
    Mark: SlackMark,
    connectable: true,
    deletedData: "수집한 채널 메시지·스레드와 그 그래프",
  },
  { id: "notion", name: "Notion", description: "문서 맥락", Mark: NotionMark },
  { id: "linear", name: "Linear", description: "이슈 맥락", Mark: LinearMark },
  { id: "asana", name: "Asana", description: "작업 맥락", Mark: AsanaMark },
  { id: "monday", name: "monday.com", description: "작업 맥락", Mark: MondayMark },
  { id: "clickup", name: "ClickUp", description: "작업 맥락", Mark: ClickUpMark },
  { id: "teams", name: "MS Teams", description: "대화 맥락", Mark: MicrosoftTeamsMark },
  { id: "google-chat", name: "Google Chat", description: "대화 맥락", Mark: GoogleChatMark },
  { id: "discord", name: "Discord", description: "대화 맥락", Mark: DiscordMark },
];

export function findSource(id: string | null | undefined): SourceCatalogItem | undefined {
  return sourceCatalog.find((source) => source.id === id);
}

// provider id를 사람이 읽는 이름으로 — 카탈로그에 없으면 id를 그대로 보여준다
export function sourceName(id: string | null | undefined): string {
  return findSource(id)?.name ?? id ?? "연동";
}
