import type { MessageMetadata } from "@/types/api";

export interface Evidence {
  type: string;
  id: string;
  quote: string;
  author: string | null;
  occurredAt?: string;
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
