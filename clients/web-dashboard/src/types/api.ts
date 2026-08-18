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

// 대화 검색 결과 1건 — snippet은 매치 메시지 발췌(제목만 매치면 null)
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

// provider가 선언한 연동 대상 선택 단계 — 단계 수(1~4)와 이름이 provider마다 달라
// 프론트는 이 선언을 그대로 렌더한다(단계 수·이름 하드코딩 금지).
export interface SelectionStep {
  // 선택 값이 저장될 external_ref 키 (예: cloud_id)
  key: string;
  // 화면에 보여줄 단계 이름 (예: "사이트")
  title: string;
  // ClickUp의 folder처럼 건너뛰어도 다음 단계로 갈 수 있는 단계
  optional: boolean;
}

// 한 단계의 후보 하나 — value를 제출하고 label을 사람에게 보여준다
export interface SelectionOption {
  value: string;
  label: string;
}

export interface Integration {
  id: string;
  projectId: string;
  // union에 string을 더하면 union이 무너져 검사되지 않는다 — 자동완성만 얻고 안전망은 없는
  // 형태였어서 정직하게 string으로 둔다. 연결 가능 여부·해제 고지는 sourceCatalog가 타입으로 강제한다.
  provider: string;
  displayName: string | null;
  metadata: Record<string, unknown> | null;
  installationId: string | null;
  createdAt: string;
  updatedAt: string;
  // 마지막으로 새 데이터를 수집한 시각. 수집 이력이 없으면 null.
  lastSyncedAt: string | null;
}

// 목록에서 보여줄 소스별 표시 이름 — "이 둘이 같은 사람인가" 판단 재료. 이메일·계정ID는 상세 조회로 뺀다.
export interface ActorSourceName {
  source: string;
  name: string | null;
  erased: string | null;
}

// 하나의 사람으로 통합된 액터. 소스별 세부(이메일·계정ID)는 getActorDetail로 지연 조회한다.
export interface Actor {
  uuid: string;
  name: string;
  activityCount: number;
  sourceNames: ActorSourceName[];
}

// 액터 상세 — 병합/분리 폼에서 alias 단위 판단 재료(이메일·계정ID 포함)로 지연 조회한다.
export interface ActorAliasDetail {
  sourceId: string;
  source: string;
  name: string | null;
  email: string | null;
  erased: string | null;
}

export interface ActorDetail {
  uuid: string;
  name: string;
  aliases: ActorAliasDetail[];
}

// 수동 병합(same)·분리(distinct) 이력. same만 스냅샷을 이용해 병합 취소할 수 있다.
// aliasesA/aliasesB는 목록의 sourceNames와 같은 shape이라 sourceNameSummary로 그대로 렌더한다.
export interface ActorDecision {
  decisionId: string;
  kind: "same" | "distinct";
  aliasesA: ActorSourceName[];
  aliasesB: ActorSourceName[];
  canonicalUuid: string | null;
  note: string;
  decidedAt: string;
}
