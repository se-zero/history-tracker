import type { MessageMetadata } from "@/types/api";

export interface Evidence {
  type: string;
  id: string;
  quote: string;
  author: string | null;
  occurredAt?: string;
}

export interface StructuredAnswer {
  summary?: string;
  evidence: Evidence[];
  unknownAspects: string[];
}

export function extractStructured(
  metadata: MessageMetadata | null | undefined,
): StructuredAnswer | null {
  if (!metadata) return null;
  const structured = metadata.structured as
    | { summary?: string; evidence?: Evidence[]; unknown_aspects?: string[] }
    | undefined;
  if (!structured) return null;
  return {
    summary: structured.summary,
    evidence: structured.evidence ?? [],
    unknownAspects: structured.unknown_aspects ?? [],
  };
}
