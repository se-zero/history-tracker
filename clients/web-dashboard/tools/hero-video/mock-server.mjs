// 히어로 영상 촬영용 목 백엔드 — Node 내장 http만 사용한다(의존성 0).
// web-dashboard의 Vite dev proxy(VITE_API_PROXY)가 /api/* 를 여기로 그대로 넘긴다(경로 rewrite 없음).
import { createServer } from "node:http";
import { randomUUID } from "node:crypto";

import { getScenario } from "./scenario.mjs";

const PORT = Number(process.env.PORT) || 8099;
// 대화 생성/재질문 응답 전 "생각 중" 연출 지연(ms).
const THINK_DELAY_MS = 2000;

// HERO_LANG(기본 "ko")로 시나리오를 1회 해석한다 — 서버 수명 동안 언어는 바뀌지 않는다.
const HERO_LANG = process.env.HERO_LANG || "ko";
if (!["ko", "en"].includes(HERO_LANG)) {
  throw new Error(`알 수 없는 HERO_LANG 값: ${HERO_LANG}`);
}
const {
  INTEGRATIONS,
  PROJECT,
  RAIL_CONVERSATIONS,
  SCRIPTED_CONVERSATION_TITLE,
  USER,
  buildGraphData,
  buildSubgraphData,
  followupAssistantContent,
  followupAssistantMetadata,
  scriptedAssistantContent,
  scriptedAssistantMetadata,
} = getScenario(HERO_LANG);

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function nowIso() {
  return new Date().toISOString();
}

function sendJson(res, status, body) {
  const payload = JSON.stringify(body);
  res.writeHead(status, { "Content-Type": "application/json" });
  res.end(payload);
}

function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    let raw = "";
    req.on("data", (chunk) => (raw += chunk));
    req.on("end", () => {
      if (!raw) return resolve({});
      try {
        resolve(JSON.parse(raw));
      } catch (err) {
        reject(err);
      }
    });
    req.on("error", reject);
  });
}

function makeMessage(conversationId, role, content, metadata) {
  return {
    id: `msg-${randomUUID()}`,
    conversationId,
    role,
    content,
    metadata: metadata ?? {},
    createdAt: nowIso(),
  };
}

function toConversationSummary(detail) {
  const { id, projectId, userId, title, createdAt, updatedAt } = detail;
  return { id, projectId, userId, title, createdAt, updatedAt };
}

// ── 대화 인메모리 상태 — 레일 4건을 시드하고, 세션 중 생성분을 앞에 붙인다. ──
const conversations = new Map();
const order = [];
for (const rail of RAIL_CONVERSATIONS) {
  const detail = {
    id: rail.id,
    projectId: PROJECT.id,
    userId: USER.id,
    title: rail.title,
    createdAt: rail.createdAt,
    updatedAt: rail.updatedAt,
    hasMoreMessages: false,
    oldestCursor: null,
    messages: [
      makeMessage(rail.id, "USER", rail.question, {}),
      makeMessage(rail.id, "ASSISTANT", rail.answer, {}),
    ],
  };
  conversations.set(detail.id, detail);
  order.push(detail.id);
}

function createConversation(firstMessage) {
  const id = `conv-${randomUUID()}`;
  const question = firstMessage || "";
  const detail = {
    id,
    projectId: PROJECT.id,
    userId: USER.id,
    title: SCRIPTED_CONVERSATION_TITLE,
    createdAt: nowIso(),
    updatedAt: nowIso(),
    hasMoreMessages: false,
    oldestCursor: null,
    messages: [
      makeMessage(id, "USER", question, {}),
      makeMessage(id, "ASSISTANT", scriptedAssistantContent(), scriptedAssistantMetadata()),
    ],
  };
  conversations.set(id, detail);
  order.unshift(id);
  return detail;
}

// 재질문 경로는 영상에서 항상 후속 질문(클릭 변형의 "채팅에 추가" 뒤 질문) 하나뿐이라
// 각본을 고정으로 돌려준다 — focusEvidence가 와도 무시한다(요청 재현용이 아니라 촬영용 목).
function appendMessage(detail, content) {
  const userMessage = makeMessage(detail.id, "USER", content || "", {});
  const assistantMessage = makeMessage(
    detail.id,
    "ASSISTANT",
    followupAssistantContent(),
    followupAssistantMetadata(),
  );
  detail.messages.push(userMessage, assistantMessage);
  detail.updatedAt = nowIso();
  return { userMessage, assistantMessage };
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  const path = url.pathname;
  const method = req.method ?? "GET";

  try {
    // 도구 내부 진단용 — record.mjs가 "떠 있는 목 서버를 재사용해도 되는 언어인지" 대조한다
    // (HERO_LANG은 spawn 시에만 적용되므로, 이전 실행이 비정상 종료로 남긴 서버를 다른
    // 언어로 재사용하면 앱 크롬만 영어이고 답변은 한국어인 짬뽕 영상이 나온다 — 봇 리뷰 지적).
    // 앱은 /api/v1/* 만 호출하므로 이 경로는 앱 계약과 무관하다.
    if (method === "GET" && path === "/__hero-lang") {
      return sendJson(res, 200, { lang: HERO_LANG });
    }

    if (method === "GET" && path === "/api/v1/me") {
      return sendJson(res, 200, USER);
    }

    if (method === "POST" && path === "/api/v1/auth/refresh") {
      // 부트 silent refresh — 쿠키를 검사하지 않고 access만 돌려 촬영 세션을 연다.
      return sendJson(res, 200, {
        accessToken: `mock-access-${randomUUID()}`,
        tokenType: "Bearer",
        expiresIn: 3600,
      });
    }

    if (method === "POST" && path === "/api/v1/auth/logout") {
      res.writeHead(204);
      res.end();
      return;
    }

    if (method === "GET" && path === "/api/v1/projects") {
      return sendJson(res, 200, [PROJECT]);
    }

    const conversationsListMatch = path.match(
      /^\/api\/v1\/projects\/[^/]+\/conversations$/,
    );
    if (conversationsListMatch) {
      if (method === "GET") {
        const items = order.map((id) => toConversationSummary(conversations.get(id)));
        return sendJson(res, 200, { items, nextCursor: null });
      }
      if (method === "POST") {
        const body = await readJsonBody(req);
        // "생각 중" 동안 목록 refetch가 끼어도 레일에 미리 뜨지 않도록, 인메모리 등록은
        // 지연 뒤로 미룬다(실백엔드가 응답을 완성해야 커밋이 보이는 것과 같은 순서).
        await sleep(THINK_DELAY_MS);
        const detail = createConversation(body.message);
        return sendJson(res, 201, detail);
      }
    }

    const conversationDetailMatch = path.match(
      /^\/api\/v1\/projects\/[^/]+\/conversations\/([^/]+)$/,
    );
    if (conversationDetailMatch && method === "GET") {
      const detail = conversations.get(conversationDetailMatch[1]);
      if (!detail) return sendJson(res, 404, { message: "conversation not found" });
      return sendJson(res, 200, detail);
    }

    const messagesMatch = path.match(
      /^\/api\/v1\/projects\/[^/]+\/conversations\/([^/]+)\/messages$/,
    );
    if (messagesMatch && method === "POST") {
      const detail = conversations.get(messagesMatch[1]);
      if (!detail) return sendJson(res, 404, { message: "conversation not found" });
      const body = await readJsonBody(req);
      const exchange = appendMessage(detail, body.content);
      await sleep(THINK_DELAY_MS);
      return sendJson(res, 201, exchange);
    }

    const integrationsMatch = path.match(
      /^\/api\/v1\/projects\/[^/]+\/integrations$/,
    );
    if (integrationsMatch && method === "GET") {
      return sendJson(res, 200, INTEGRATIONS);
    }

    if (method === "GET" && /^\/api\/v1\/projects\/[^/]+\/graph$/.test(path)) {
      return sendJson(res, 200, buildGraphData());
    }

    if (
      method === "GET" &&
      /^\/api\/v1\/projects\/[^/]+\/graph\/activity$/.test(path)
    ) {
      return sendJson(res, 200, { state: "idle" });
    }

    if (
      method === "POST" &&
      /^\/api\/v1\/projects\/[^/]+\/graph\/subgraph$/.test(path)
    ) {
      const body = await readJsonBody(req);
      return sendJson(res, 200, buildSubgraphData(body.evidence));
    }

    console.warn(`[hero-mock] unhandled ${method} ${path}`);
    return sendJson(res, 404, { message: "unhandled by hero-video mock" });
  } catch (err) {
    console.error("[hero-mock] request failed", err);
    return sendJson(res, 500, { message: "hero-video mock server error" });
  }
});

server.listen(PORT, () => {
  console.log(`hero-video mock server listening on http://localhost:${PORT}`);
});
