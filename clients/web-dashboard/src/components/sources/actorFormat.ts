import type { ActorSourceName } from "@/types/api";

// ActorManagementCard·ActorDecisionsCard가 공유하는 소스/alias 표기 헬퍼.
export function sourceLabel(source: string) {
  switch (source) {
    case "GITHUB":
      return "GitHub";
    case "JIRA":
      return "Jira";
    case "SLACK":
      return "Slack";
    default:
      return source;
  }
}

// 이름·erased가 모두 없으면 "이름 없음"을 찍는 대신 무엇을 보여줘도 판단에 도움이 안 되니 생략한다.
export function aliasNameText(name: string | null, erased: string | null) {
  if (erased) return "(삭제됨)";
  return name ?? "이름 없음";
}

// 목록 행 · select 라벨용 최소 요약. 이름도 erased도 없는 소스는 소스명만 남긴다.
export function sourceNameSummary(sourceName: ActorSourceName) {
  if (!sourceName.name && !sourceName.erased) return sourceLabel(sourceName.source);
  return `${sourceLabel(sourceName.source)}: ${aliasNameText(sourceName.name, sourceName.erased)}`;
}
