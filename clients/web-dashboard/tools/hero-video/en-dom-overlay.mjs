// 히어로 영상 "영어" 촬영 전용 DOM 치환기 — 실앱(src/)은 한국어 하드코딩이라 앱 코드를
// 건드리지 않고, 촬영 시에만 Playwright가 페이지에 이 모듈의 injector를 주입해 프레임 속
// 앱 크롬(사이드바·컴포저·카드 등)을 영어로 바꾼다.
//
// **경고 — 조용한 실패**: 아래 사전의 from 값은 실앱 컴포넌트에서 그대로 복사한 원문이다.
// 그 컴포넌트의 한국어 문구가 바뀌면 이 사전은 예외 없이 그냥 매치를 놓치고 넘어간다(치환
// 안 됨 = 에러 없음). 그래서 --lang en으로 재촬영할 때는 반드시 산출 mp4 프레임을 뽑아
// 한국어 글자가 하나도 남아 있지 않은지 눈으로 확인해야 한다(README 규칙 9).

// ── 텍스트 완전 일치 치환 사전 ──────────────────────────────────────────────
// trim 후 텍스트 노드 전체와 정확히 일치할 때만 치환한다(부분 문자열 치환 금지 —
// "대화"처럼 짧은 문자열이 다른 문구 안에 우연히 포함돼 오발하는 것을 막는다).
export const EN_CHAT_EMPTY_HEADING = "What would you like to know?";

const TEXT_ENTRIES = [
  // ChatEmpty.tsx
  { kind: "text", from: "무엇을 알아볼까요?", to: EN_CHAT_EMPTY_HEADING },
  {
    kind: "text",
    from: "에 대해 아래 추천 질문으로 시작하거나, 직접 자연어로 물어보세요.",
    to: " — start with a suggested question below, or just ask in your own words.",
  },
  { kind: "text", from: "왜 이 코드가 이렇게 바뀌었어?", to: "Why was this code changed this way?" },
  {
    kind: "text",
    from: "최근 머지된 리팩토링 PR들을 정리해줘",
    to: "Summarize the recently merged refactoring PRs",
  },
  {
    kind: "text",
    from: "지난 분기 가장 논쟁이 많았던 PR은?",
    to: "Which PR sparked the most debate last quarter?",
  },
  { kind: "text", from: "이 도메인을 가장 잘 아는 사람은?", to: "Who knows this domain best?" },
  // Composer.tsx
  { kind: "text", from: "전송", to: "Send" },
  {
    kind: "text",
    from: "AI가 생성한 답변이라 부정확할 수 있습니다 — 근거를 함께 확인하세요.",
    to: "AI-generated answers can be inaccurate — check the evidence alongside.",
  },
  // ThinkingState.tsx
  { kind: "text", from: "처리 중…", to: "Processing…" },
  // RelatedGraphPanel.tsx / ChatPage.tsx
  { kind: "text", from: "관련 그래프", to: "Related graph" },
  // NodeDetail.tsx
  { kind: "text", from: "채팅에 추가", to: "Add to chat" },
  // Sidebar.tsx
  { kind: "text", from: "검색", to: "Search" },
  { kind: "text", from: "대화", to: "Conversations" },
  { kind: "text", from: "데이터 소스", to: "Data sources" },
  { kind: "text", from: "액터", to: "Actors" },
  { kind: "text", from: "그래프 확인", to: "Graph view" },
  { kind: "text", from: "현재 프로젝트 설정", to: "Project settings" },
  { kind: "text", from: "사용자", to: "User" },
  // ProjectSwitcher.tsx — project.description이 없을 때의 폴백 라벨(목 서버 프로젝트는
  // description을 안 채워서 항상 이 폴백이 렌더된다).
  { kind: "text", from: "프로젝트", to: "Project" },
  // ConversationList.tsx
  { kind: "text", from: "아직 대화가 없습니다.", to: "No conversations yet." },
  // format.ts formatRelative() — "방금"은 관용 표기 "just now"로. 녹화 중 질문을 보내면
  // 레일에 새 대화가 이 표기로 바로 찍히므로(카메라 노출), "0 minutes ago" 같은 기계적
  // 풀어쓰기는 쓰지 않는다. 숫자 버킷(N분/시간/일 전)만 패턴 치환으로 풀어 쓴다.
  { kind: "text", from: "방금", to: "just now" },
];

// ── format.ts formatRelative() 산출 패턴(동적 숫자 포함) 치환 ───────────────
// "N일 전"류는 숫자가 매번 달라 완전 일치 사전으로 감당할 수 없다. 단수/복수는 주입 함수 쪽의
// pluralize()가 처리한다("1 day ago" / "2 days ago").
const PATTERN_ENTRIES = [
  { kind: "pattern", regexSource: "^(\\d+)분 전$", unit: "minute" },
  { kind: "pattern", regexSource: "^(\\d+)시간 전$", unit: "hour" },
  { kind: "pattern", regexSource: "^(\\d+)일 전$", unit: "day" },
];

// ── attr 치환 사전 ──────────────────────────────────────────────────────────
// Composer의 placeholder는 텍스트 노드가 아니라 textarea 속성이라 별도로 다룬다. 시나리오
// 전역에서 프로젝트명이 항상 "payflow"로 고정이라(scenario.mjs PROJECT.name) 원문을 그
// 값으로 굳혀 둔다 — 다른 프로젝트명을 쓰게 되면 이 엔트리도 함께 고쳐야 한다.
const ATTR_ENTRIES = [
  {
    kind: "attr",
    selector: ".composer textarea",
    attr: "placeholder",
    from: "payflow에 무엇이든 물어보세요. Shift+Enter로 줄바꿈",
    to: "Ask payflow anything. Shift+Enter for a new line",
  },
];

export const EN_OVERLAY_ENTRIES = [...TEXT_ENTRIES, ...PATTERN_ENTRIES, ...ATTR_ENTRIES];

// ── 페이지에 주입되는 자기완결 함수 ──────────────────────────────────────────
// context.addInitScript(installEnOverlay, EN_OVERLAY_ENTRIES)로 직렬화돼 브라우저에서
// 실행되므로 이 함수 밖의 어떤 값(모듈 스코프 변수 등)도 참조하지 않는다 — 인자로 받은
// entries만 쓴다.
export function installEnOverlay(entries) {
  const textMap = new Map();
  const patterns = [];
  const attrEntries = [];
  for (const e of entries) {
    if (e.kind === "text") textMap.set(e.from, e.to);
    else if (e.kind === "pattern") patterns.push({ re: new RegExp(e.regexSource), unit: e.unit });
    else if (e.kind === "attr") attrEntries.push(e);
  }

  function pluralize(n, unit) {
    return `${n} ${unit}${n === "1" ? "" : "s"} ago`;
  }

  // 치환 결과(예: "What would you like to know?")는 사전의 어떤 from과도 다시 매치되지
  // 않으므로, 같은 노드가 나중에 다시 이 함수를 통과해도(재적용) 재귀적으로 반복 치환되지
  // 않는다 — 무한 루프 걱정 없이 매 뮤테이션마다 안전하게 재실행할 수 있다.
  function translateTextNode(node) {
    const raw = node.nodeValue;
    if (!raw) return;
    const trimmed = raw.trim();
    if (!trimmed) return;
    const direct = textMap.get(trimmed);
    if (direct !== undefined) {
      node.nodeValue = direct;
      return;
    }
    for (const p of patterns) {
      const m = trimmed.match(p.re);
      if (m) {
        node.nodeValue = pluralize(m[1], p.unit);
        return;
      }
    }
  }

  function walk(root) {
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    let node;
    while ((node = walker.nextNode())) translateTextNode(node);
  }

  function applyAttrs() {
    for (const e of attrEntries) {
      document.querySelectorAll(e.selector).forEach((el) => {
        if (el.getAttribute(e.attr) === e.from) el.setAttribute(e.attr, e.to);
      });
    }
  }

  function run() {
    walk(document.body);
    applyAttrs();
  }

  // DOM 로드 후 TreeWalker 1패스(run) 다음, 이후 추가/변경되는 노드는 MutationObserver로
  // 재적용한다. attr 엔트리는 속성 변경 자체를 감시하지 않고(placeholder는 마운트 시 한 번만
  // 설정돼 별도 attribute observer가 불필요하다) 매 뮤테이션마다 함께 훑는다 — 비용이 작다.
  const observer = new MutationObserver((mutations) => {
    for (const m of mutations) {
      if (m.type === "characterData" && m.target.nodeType === Node.TEXT_NODE) {
        translateTextNode(m.target);
      } else if (m.type === "childList") {
        m.addedNodes.forEach((n) => {
          if (n.nodeType === Node.TEXT_NODE) translateTextNode(n);
          else if (n.nodeType === Node.ELEMENT_NODE) walk(n);
        });
      }
    }
    applyAttrs();
  });

  function start() {
    run();
    observer.observe(document.body, { childList: true, characterData: true, subtree: true });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start);
  } else {
    start();
  }
}
