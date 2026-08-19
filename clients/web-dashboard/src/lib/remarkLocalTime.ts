import type { Link, Nodes, Parents } from "mdast";
import { SKIP, visit } from "unist-util-visit";

import { formatInstant } from "./format";

// 답변 본문에 박힌 UTC ISO 타임스탬프를 뷰어의 현지 시간으로 바꿔 그리는 remark 플러그인.
//
// **왜 서버가 아니라 여기서 바꾸나**: ai-engine은 모든 시각을 UTC ISO로 정규화해 내보낸다
// (tools/queries/_common.py의 _event_time — 표기가 섞이면 타임라인 정렬이 뒤집힌다).
// 서버가 특정 타임존으로 문자열을 굳히면 그 순간 "모든 독자는 한국에 있다"가 DB에 영구히
// 새겨진다. 그래서 저장은 UTC 정준값 그대로 두고, 표시만 각자의 기기 타임존으로 옮긴다.
// 이 분업 덕에 eval 골든셋(UTC 기준으로 기대값이 적혀 있다)도 그대로 통과한다.
//
// 시스템 프롬프트가 모델에게 "본문에도 ISO 원문을 그대로 옮기고 날짜를 말로 풀어 쓰지 마라"고
// 지시하는 것이 이 플러그인의 전제다 — 모델이 "3월 12일"이라 쓰면 여기서 잡을 수 없어
// UTC 기준 날짜가 그대로 굳는다.

// 시각·타임존이 모두 있는 완전한 ISO-8601만 매칭한다.
// **날짜 단독(2026-03-12)은 의도적으로 제외한다** — 날짜는 instant가 아니라서 로컬로 옮기면
// 음수 오프셋 지역에서 하루가 밀린다(UTC 자정 근처 값이 전날로 표시됨).
const ISO_INSTANT =
  /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?(?:Z|[+-]\d{2}:?\d{2})/g;

// 인라인 코드 노드가 **통째로** 타임스탬프 하나일 때만 쓰는 앵커 버전.
// 모델이 시각을 백틱으로 감싸는 버릇이 있어(`2026-06-24T15:14:09.134Z`에 완료) 코드 노드를
// 전부 건너뛰면 같은 문장 안에서 어떤 시각은 변환되고 어떤 시각은 ISO로 남는다.
// 다만 코드 노드에 다른 내용이 섞여 있으면(커밋 메시지·JSON 조각 등) 인용 원문일 수 있으므로
// 손대지 않는다 — "노드 전체가 시각 하나"일 때만 모델의 표기 습관으로 보고 변환한다.
const ISO_INSTANT_ONLY = new RegExp(`^(?:${ISO_INSTANT.source})$`);

// 치환 노드는 emphasis에 hName을 씌워 <time>으로 렌더한다.
// - dateTime: 원본 UTC ISO (기계용 정준값 — 화면 표기가 로컬로 바뀌어도 여기 남는다)
// - title:    같은 시각의 초 단위 표기 (본문은 분까지만 보여주므로 hover로 보강)
function timeNode(iso: string): Nodes {
  return {
    type: "emphasis",
    data: {
      hName: "time",
      hProperties: { dateTime: iso, title: formatInstant(iso, { withSeconds: true }) },
    },
    children: [{ type: "text", value: formatInstant(iso) }],
  };
}

export function remarkLocalTime() {
  return (tree: Nodes) => {
    // 코드블록(code)은 방문하지 않는다 — 커밋 로그·JSON을 통째로 인용한 자리라 원문을 보존한다.
    // 인라인 코드는 위 ISO_INSTANT_ONLY 조건을 만족할 때만 예외적으로 변환한다.
    visit(tree, (node, index: number | undefined, parent: Parents | undefined) => {
      if (!parent || index === undefined) return;
      if (node.type !== "text" && node.type !== "inlineCode") return;
      // 코드블록(type: "code")은 이 가드에서 걸러진다 — 자식 text 노드가 없는 leaf라 내부도 안전하다.

      if (node.type === "inlineCode") {
        const value = node.value.trim();
        if (!ISO_INSTANT_ONLY.test(value)) return;
        parent.children.splice(index, 1, timeNode(value) as Parents["children"][number]);
        return [SKIP, index + 1];
      }

      const matches = [...node.value.matchAll(ISO_INSTANT)];
      if (matches.length === 0) return;

      // remark-gfm의 autolink literal(https://...2026-03-12T08:42:50Z...)은 표시 텍스트가 곧
      // href라, 그 안의 ISO를 바꾸면 링크 라벨만 변환되고 href는 원문 그대로 남아 어긋난다.
      // "이 매칭이 부모 링크의 url에도 그대로 들어있는가"로 자동 링크만 골라 건너뛴다 —
      // [텍스트](url) 처럼 표시 텍스트가 url과 무관한 명시 링크는 그대로 변환 대상이다.
      const parentUrl = parent.type === "link" ? (parent as Link).url : null;
      const isAutolinkLabel = (text: string) => parentUrl != null && parentUrl.includes(text);

      // 매칭 구간은 <time>으로, 사이사이 원문은 text로 남겨 한 노드를 여러 노드로 펼친다.
      const replacement: Nodes[] = [];
      let cursor = 0;
      let changed = false;
      for (const match of matches) {
        const start = match.index;
        if (isAutolinkLabel(match[0])) continue;
        changed = true;
        if (start > cursor) {
          replacement.push({ type: "text", value: node.value.slice(cursor, start) });
        }
        replacement.push(timeNode(match[0]));
        cursor = start + match[0].length;
      }
      if (!changed) return;
      if (cursor < node.value.length) {
        replacement.push({ type: "text", value: node.value.slice(cursor) });
      }

      parent.children.splice(index, 1, ...(replacement as Parents["children"]));
      // 방금 넣은 노드들을 다시 방문하지 않도록 건너뛴다 (남긴 text 조각의 재매칭 방지).
      return [SKIP, index + replacement.length];
    });
  };
}
