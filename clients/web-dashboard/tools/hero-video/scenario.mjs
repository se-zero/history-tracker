// 히어로 영상 촬영용 각본 데이터 — mock-server.mjs가 돌려주는 모든 응답 JSON의 단일 출처다.
// 카피는 확정본이므로 여기서만 고치고, 서버 쪽은 이 모듈이 내보낸 값을 그대로 응답한다.

export const USER = {
  id: "user-alex",
  provider: "github",
  providerUserId: "alex.kim",
  email: "alex@payflow.dev",
  displayName: "Alex",
  avatarUrl: null,
};

export const PROJECT = {
  id: "project-payflow",
  ownerId: USER.id,
  name: "payflow",
  description: null,
  createdAt: "2026-06-01T00:00:00Z",
  updatedAt: "2026-08-22T10:00:00Z",
};

export const INTEGRATIONS = [
  {
    id: "integration-github",
    projectId: PROJECT.id,
    provider: "github",
    displayName: "payflow/payflow-api",
    metadata: { repositoryFullName: "payflow/payflow-api", branch: "main" },
    installationId: "1234567",
    createdAt: "2026-06-01T00:10:00Z",
    updatedAt: "2026-06-01T00:10:00Z",
    lastSyncedAt: "2026-08-22T09:00:00Z",
  },
  {
    id: "integration-jira",
    projectId: PROJECT.id,
    provider: "jira",
    displayName: "PAY 프로젝트",
    metadata: { cloudId: "mock-cloud-id", projectKey: "PAY" },
    installationId: null,
    createdAt: "2026-06-01T00:20:00Z",
    updatedAt: "2026-06-01T00:20:00Z",
    lastSyncedAt: "2026-08-22T09:00:00Z",
  },
  {
    id: "integration-slack",
    projectId: PROJECT.id,
    provider: "slack",
    displayName: "payflow 워크스페이스",
    metadata: { teamId: "T0MOCK", channel: "#dev-auth" },
    installationId: null,
    createdAt: "2026-06-01T00:30:00Z",
    updatedAt: "2026-06-01T00:30:00Z",
    lastSyncedAt: "2026-08-22T09:00:00Z",
  },
];

// ── 그래프(성좌 시드 + 배경 노드) ──────────────────────────────────────────
// 시드 3개는 evidence 순서와 그대로 대응한다(인용 카드 ↔ 노드 매핑, 점등 안무 순서).
const SEED_JIRA_ID = "node-jira-218";
const SEED_SLACK_ID = "node-slack-dev-auth";
const SEED_PR_ID = "node-pr-327";

const NODES = [
  {
    id: SEED_JIRA_ID,
    type: "jira",
    title: "세션 만료 정책 재검토",
    meta: "PAY-218",
    source: "jira",
    snippet: "공용 PC에서 로그아웃 없이 자리를 비우면 세션이 그대로 남는다는 보고로 시작된 이슈.",
    ref: { type: "issue", id: "PAY-218" },
  },
  {
    id: SEED_SLACK_ID,
    type: "slack",
    title: "#dev-auth",
    meta: "#dev-auth 스레드",
    source: "slack",
    snippet: "30분 + 사용 중 자동 연장으로 가자 — 보안과 UX를 둘 다 살린다.",
    ref: { type: "message", id: "dev-auth-1088" },
  },
  {
    id: SEED_PR_ID,
    type: "pr",
    title: "리프레시 토큰 회전 도입",
    meta: "PR #327",
    source: "github",
    snippet: "리프레시 토큰 회전 도입, 액세스 토큰 TTL 30분.",
    ref: { type: "pull_request", id: "327" },
  },
  // 배경 노드 — 점등 시 딤 처리되는 유기적 배경. ref는 질의 도구 대상이 아니므로 전부 null.
  {
    id: "node-commit-1",
    type: "commit",
    title: "토큰 회전 미들웨어 추가",
    meta: "a3f9c21",
    source: "github",
    snippet: "refresh 토큰 회전 시 기존 세션을 무효화하는 미들웨어 추가.",
    ref: null,
  },
  {
    id: "node-commit-2",
    type: "commit",
    title: "세션 TTL 설정 분리",
    meta: "e17b4a0",
    source: "github",
    snippet: "ACCESS_TOKEN_TTL을 환경변수로 분리, 기본값 30분.",
    ref: null,
  },
  {
    id: "node-commit-3",
    type: "commit",
    title: "토큰 회전 테스트 추가",
    meta: "f52d8c3",
    source: "github",
    snippet: "토큰 회전 시나리오에 대한 통합 테스트 추가.",
    ref: null,
  },
  {
    id: "node-code-1",
    type: "code",
    title: "auth/session.ts",
    meta: "auth/session.ts",
    source: "github",
    snippet: "export const ACCESS_TOKEN_TTL_MIN = 30;",
    ref: null,
  },
  {
    id: "node-code-2",
    type: "code",
    title: "middleware/refresh.ts",
    meta: "middleware/refresh.ts",
    source: "github",
    snippet: "리프레시 토큰 회전 로직과 세션 무효화 처리.",
    ref: null,
  },
  {
    id: "node-actor-emma",
    type: "actor",
    title: "Emma",
    meta: "보안팀",
    source: "jira",
    snippet: "PAY-218 이슈를 최초 보고.",
    ref: null,
  },
  {
    id: "node-actor-daniel",
    type: "actor",
    title: "Daniel",
    meta: "백엔드",
    source: "slack",
    snippet: "#dev-auth 스레드에서 절충안을 제시.",
    ref: null,
  },
  {
    id: "node-actor-sophie",
    type: "actor",
    title: "Sophie",
    meta: "백엔드",
    source: "github",
    snippet: "PR #327 작성자.",
    ref: null,
  },
  {
    id: "node-jira-201",
    type: "jira",
    title: "PAY-201 자동 로그아웃 개선",
    meta: "PAY-201",
    source: "jira",
    snippet: "장시간 미사용 세션 자동 로그아웃 정책 백로그.",
    ref: null,
  },
  {
    id: "node-jira-233",
    type: "jira",
    title: "PAY-233 세션 정책 문서화",
    meta: "PAY-233",
    source: "jira",
    snippet: "확정된 세션 정책을 온보딩 문서에 반영.",
    ref: null,
  },
];

const EDGES = [
  // 시드 체인 — 연속 시드 사이 실제 엣지가 있어야 점등 엣지 드로잉 안무가 나온다.
  [SEED_JIRA_ID, SEED_SLACK_ID],
  [SEED_SLACK_ID, SEED_PR_ID],
  // 배경 — 시드·배경이 자연스럽게 이어지는 유기적 서브그래프.
  [SEED_JIRA_ID, "node-actor-emma"],
  [SEED_SLACK_ID, "node-actor-daniel"],
  [SEED_PR_ID, "node-actor-sophie"],
  [SEED_PR_ID, "node-commit-1"],
  [SEED_PR_ID, "node-commit-2"],
  [SEED_PR_ID, "node-commit-3"],
  ["node-commit-1", "node-code-2"],
  ["node-commit-2", "node-code-1"],
  [SEED_JIRA_ID, "node-jira-201"],
  ["node-jira-201", "node-jira-233"],
  // 후속 질문 시드 체인(PR→commit-1→commit-2)이 전부 실제 엣지로 이어지게 한다.
  ["node-commit-1", "node-commit-2"],
];

const SEEDS = [SEED_JIRA_ID, SEED_SLACK_ID, SEED_PR_ID];
// 후속 질문("이 PR이 실제로 바꾼 동작을 정리해줘")의 시드 — evidence 순서(PR→commit-1→commit-2)와 대응.
const FOLLOWUP_SEEDS = [SEED_PR_ID, "node-commit-1", "node-commit-2"];

export function buildGraphData() {
  return { nodes: NODES, edges: EDGES };
}

// evidence에 커밋 a3f9c21(FOLLOWUP_EVIDENCE 2번)이 포함되면 후속 질문 시드를, 아니면
// 최초 질문 시드를 돌려준다 — 각 답변이 자기 근거에 맞는 서브그래프를 받도록 하는 결정적 분기.
export function buildSubgraphData(evidence) {
  const isFollowup = Array.isArray(evidence) && evidence.some((e) => e?.id === "a3f9c21");
  return { nodes: NODES, edges: EDGES, seeds: isFollowup ? FOLLOWUP_SEEDS : SEEDS };
}

// ── 신규 대화(각본 질문/답변) ──────────────────────────────────────────────
export const SCRIPTED_QUESTION = "로그인 세션이 왜 30분으로 줄어든 거야?";
export const SCRIPTED_CONVERSATION_TITLE = "로그인 세션 단축 경위";

const SCRIPTED_SUMMARY =
  '로그인 세션 단축은 `PAY-218`에서 보고된 공용 PC 세션 탈취 우려에서 시작됐습니다. 보안팀은 만료 15분을 요구했지만 잦은 재로그인에 대한 반대가 맞서면서, `#dev-auth` 스레드에서 "30분 + 사용 중 자동 연장"으로 합의됐습니다. 구현은 `PR #327`이 리프레시 토큰 회전을 도입하며 반영됐고, 이때 액세스 토큰 수명이 30분으로 확정됐습니다.';

const SCRIPTED_EVIDENCE = [
  {
    type: "issue",
    id: "PAY-218",
    source: "jira",
    author: "Emma",
    occurredAt: "2026-07-02T09:30:00Z",
    event_meaning: "issue_created",
    quote: "공용 PC에서 로그아웃 없이 자리를 비우면 세션이 그대로 남는다",
  },
  {
    type: "message",
    id: "dev-auth-1088",
    source: "slack",
    author: "Daniel",
    occurredAt: "2026-07-03T14:05:00Z",
    event_meaning: "decision_made",
    quote: "30분 + 사용 중 자동 연장으로 가자 — 보안과 UX를 둘 다 살린다",
  },
  {
    type: "pull_request",
    id: "#327",
    source: "github",
    author: "Sophie",
    occurredAt: "2026-07-05T11:20:00Z",
    event_meaning: "pr_merged",
    quote: "리프레시 토큰 회전 도입, 액세스 토큰 TTL 30분",
  },
];

// 답변 메시지의 content — structured가 있으면 프론트는 summary를 렌더에 쓰지만, content도
// 같은 markdown 텍스트로 채워 타입 계약(string)을 충족한다.
export function scriptedAssistantContent() {
  return SCRIPTED_SUMMARY;
}

export function scriptedAssistantMetadata() {
  return {
    structured: {
      summary: SCRIPTED_SUMMARY,
      evidence: SCRIPTED_EVIDENCE,
      unknown_aspects: [],
      answer_mode: "grounded",
    },
  };
}

// ── 후속 질문(클릭 변형 전용 두 번째 교환) ─────────────────────────────────
// "채팅에 추가"로 PR #327을 첨부한 뒤 묻는 후속 질문 — 시드가 PR→commit-1→commit-2로 바뀐다.
export const FOLLOWUP_QUESTION = "이 PR이 실제로 바꾼 동작을 정리해줘";

const FOLLOWUP_SUMMARY =
  '`PR #327`은 로그인 유지 방식을 두 갈래로 바꿨습니다. 액세스 토큰 수명을 30분으로 줄이는 대신, 사용 중에는 리프레시 토큰이 회전하며 세션이 자동 연장됩니다. 회전 시 이전 토큰은 즉시 무효화되어(`a3f9c21`) 탈취된 토큰의 재사용이 차단되고, 만료 시간은 설정으로 분리됐습니다(`e17b4a0`).';

const FOLLOWUP_EVIDENCE = [
  {
    type: "pull_request",
    id: "#327",
    source: "github",
    author: "Sophie",
    occurredAt: "2026-07-05T11:20:00Z",
    event_meaning: "pr_merged",
    quote: "리프레시 토큰 회전 도입, 액세스 토큰 TTL 30분",
  },
  {
    type: "commit",
    id: "a3f9c21",
    source: "github",
    author: "Sophie",
    occurredAt: "2026-07-04T15:40:00Z",
    event_meaning: "commit_pushed",
    quote: "refresh 토큰 회전 시 기존 세션을 무효화하는 미들웨어 추가",
  },
  {
    type: "commit",
    id: "e17b4a0",
    source: "github",
    author: "Sophie",
    occurredAt: "2026-07-04T16:10:00Z",
    event_meaning: "commit_pushed",
    quote: "ACCESS_TOKEN_TTL을 환경변수로 분리, 기본값 30분",
  },
];

export function followupAssistantContent() {
  return FOLLOWUP_SUMMARY;
}

export function followupAssistantMetadata() {
  return {
    structured: {
      summary: FOLLOWUP_SUMMARY,
      evidence: FOLLOWUP_EVIDENCE,
      unknown_aspects: [],
      answer_mode: "grounded",
    },
  };
}

// ── 사이드바 대화 레일(각본 밖 대화 4건, 최근순) ──────────────────────────
// 진행 초점은 신규 대화 시나리오라 각 레일 항목은 짧은 단문 교환 하나만 갖는다.
// 날짜는 절대값 대신 촬영 시점(Date.now()) 기준 상대 오프셋으로 파생시킨다 — 절대
// 날짜로 고정하면 재촬영 시점이 멀어질수록 "n일 전" 표기가 낡아 보인다.
const daysAgo = (n) => new Date(Date.now() - n * 24 * 60 * 60 * 1000).toISOString();

export const RAIL_CONVERSATIONS = [
  {
    id: "conv-payment-retry",
    title: "결제 실패 재시도 경위",
    createdAt: daysAgo(1),
    updatedAt: daysAgo(1),
    question: "결제 실패 시 재시도 로직이 왜 바뀌었어?",
    answer: "일시 네트워크 오류로 인한 결제 실패가 늘어나면서, 지수 백오프 기반 재시도가 도입됐습니다.",
  },
  {
    id: "conv-webhook-signature",
    title: "웹훅 서명 검증 도입",
    createdAt: daysAgo(2),
    updatedAt: daysAgo(2),
    question: "웹훅 서명 검증은 왜 추가된 거야?",
    answer: "위조된 웹훅 요청을 막기 위해 HMAC 서명 검증이 추가됐습니다.",
  },
  {
    id: "conv-settlement-delay",
    title: "정산 배치 지연 원인",
    createdAt: daysAgo(3),
    updatedAt: daysAgo(3),
    question: "정산 배치가 지연되는 원인이 뭐야?",
    answer: "대량 거래 집계 쿼리가 느려지면서 배치가 지연되고 있습니다.",
  },
  {
    id: "conv-notification-dup",
    title: "알림 중복 발송 수정",
    createdAt: daysAgo(5),
    updatedAt: daysAgo(5),
    question: "알림이 중복 발송되던 문제는 어떻게 고쳤어?",
    answer: "멱등키 기반 중복 제거 로직을 추가해 중복 발송을 막았습니다.",
  },
];
