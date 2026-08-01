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
}

// 데이터 소스 11종 카탈로그. GitHub·Jira·Slack만 실제 연동(핸들러는 SourcesPage/SourceTileGrid가
// provider별로 배선)이 있고, 나머지 8개는 API가 붙기 전까지 자리만 잡아둔다. 타일 렌더는 이 배열의
// filter+map으로만 하므로, 새 소스가 추가되면 이 배열에 항목만 더하면 된다.
export const sourceCatalog: SourceCatalogItem[] = [
  { id: "github", name: "GitHub", description: "코드 맥락", Mark: GithubMark },
  { id: "jira", name: "Jira", description: "이슈 맥락", Mark: JiraMark },
  { id: "slack", name: "Slack", description: "대화 맥락", Mark: SlackMark },
  { id: "notion", name: "Notion", description: "문서 맥락", Mark: NotionMark },
  { id: "linear", name: "Linear", description: "이슈 맥락", Mark: LinearMark },
  { id: "asana", name: "Asana", description: "작업 맥락", Mark: AsanaMark },
  { id: "monday", name: "monday.com", description: "작업 맥락", Mark: MondayMark },
  { id: "clickup", name: "ClickUp", description: "작업 맥락", Mark: ClickUpMark },
  { id: "teams", name: "MS Teams", description: "대화 맥락", Mark: MicrosoftTeamsMark },
  { id: "google-chat", name: "Google Chat", description: "대화 맥락", Mark: GoogleChatMark },
  { id: "discord", name: "Discord", description: "대화 맥락", Mark: DiscordMark },
];
