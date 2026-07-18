export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
}

export interface User {
  id: string;
  provider: string;
  providerUserId: string;
  email: string | null;
  displayName: string | null;
  avatarUrl: string | null;
}

export interface Project {
  id: string;
  ownerId: string;
  name: string;
  description: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface Conversation {
  id: string;
  projectId: string;
  userId: string | null;
  title: string;
  createdAt: string;
  updatedAt: string;
}

export type MessageRole = "USER" | "ASSISTANT" | "SYSTEM";

export interface Message {
  id: string;
  conversationId: string;
  role: MessageRole;
  content: string;
  metadata: MessageMetadata;
  createdAt: string;
}

export interface MessageMetadata {
  citations?: Array<{ idx: number; nodeId: string }>;
  highlightNodes?: string[];
  [key: string]: unknown;
}

export interface ConversationDetail extends Conversation {
  messages: Message[];
  // 최신 페이지 기준 더 오래된 메시지 존재 여부와, 그보다 older를 불러올 커서
  hasMoreMessages: boolean;
  oldestCursor: string | null;
}

export interface ConversationPage {
  items: Conversation[];
  nextCursor: string | null;
}

// 통합 검색 결과 대화 1건 — snippet은 매치 메시지 발췌(제목만 매치면 null)
export interface ConversationSearchItem {
  id: string;
  title: string;
  snippet: string | null;
  updatedAt: string;
}

export interface MessagePage {
  items: Message[];
  hasMore: boolean;
  nextCursor: string | null;
}

export interface MessageExchange {
  userMessage: Message;
  assistantMessage: Message;
}

export interface GitHubInstallation {
  id: string;
  installationId: number;
  accountType: "User" | "Organization" | string;
  accountLogin: string;
}

export interface GitHubRepository {
  id: number;
  name: string;
  full_name: string;
  owner: string;
  private: boolean;
  visibility: string;
  default_branch: string;
}

export interface Integration {
  id: string;
  projectId: string;
  provider: "github" | "slack" | "jira" | string;
  displayName: string | null;
  metadata: Record<string, unknown> | null;
  installationId: string | null;
  createdAt: string;
  updatedAt: string;
  // 마지막으로 새 데이터를 수집한 시각. 수집 이력이 없으면 null.
  lastSyncedAt: string | null;
}

// 하나의 사람으로 통합된 소스별 신원. aliases는 "GITHUB:login" 같은 안정 식별자다.
export interface Actor {
  uuid: string;
  name: string;
  aliases: string[];
  emails: string[];
  confidence: number;
  activityCount: number;
}

// 수동 병합(same)·분리(distinct) 이력. same만 스냅샷을 이용해 병합 취소할 수 있다.
export interface ActorDecision {
  decisionId: string;
  kind: "same" | "distinct";
  aliasesA: string[];
  aliasesB: string[];
  canonicalUuid: string | null;
  note: string;
  decidedAt: string;
}
