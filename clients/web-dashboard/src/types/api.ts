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
