import type { MessageMetadata } from "@/types/api";

export interface Evidence {
  type: string;
  id: string;
  quote: string;
  author: string | null;
  occurredAt?: string;
  // 근거 노드의 소스 provider. 서버 표기가 두 갈래(대문자/소문자+언더스코어)라 markForSource에서
  // 정규화한다. 과거 메시지에는 없던 필드라 optional.
  source?: string | null;
}

// 답변이 어느 경로로 나왔는지 — ai-engine이 실제 호출된 도구를 보고 판정한다.
// exploratory = 전용 조회 경로가 없어 그래프를 직접 탐색한 답(근거가 약할 수 있음).
// 값이 없는 과거 메시지는 grounded로 취급한다(범용 조회 도입 전 응답).
export type AnswerMode = "grounded" | "exploratory";

export interface StructuredAnswer {
  summary?: string;
  evidence: Evidence[];
  unknownAspects: string[];
  answerMode: AnswerMode;
}

export function extractStructured(
  metadata: MessageMetadata | null | undefined,
): StructuredAnswer | null {
  if (!metadata) return null;
  const structured = metadata.structured as
    | {
        summary?: string;
        evidence?: Evidence[];
        unknown_aspects?: string[];
        answer_mode?: string;
      }
    | undefined;
  if (!structured) return null;
  return {
    summary: structured.summary,
    evidence: structured.evidence ?? [],
    unknownAspects: structured.unknown_aspects ?? [],
    answerMode: structured.answer_mode === "exploratory" ? "exploratory" : "grounded",
  };
}

// 근거 카드의 타입 표시 라벨 — 근거 표시 로직의 단일 출처.
// pull_request만 축약하고 나머지(commit/issue/message/document)와 모르는 값은 원문 그대로
// 보여준다. 한글로 번역하지 않는다(영문 유지가 결정 사항).
export function evidenceTypeLabel(type: string): string {
  return type === "pull_request" ? "PR" : type;
}
