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

// 목록 행 · select 라벨용 최소 요약. 라벨(라틴, 모노 유지 대상)과 이름(한글 가능, 본문 서체)을
// 분리 반환한다 — 호출부가 라벨만 <code>/.mono로 감싸도록 한다(DESIGN.md "모노의 스코프").
// 이름도 erased도 없는 소스는 name을 null로 둔다.
export function sourceNameSummary(sourceName: ActorSourceName) {
  const label = sourceLabel(sourceName.source);
  if (!sourceName.name && !sourceName.erased) return { label, name: null as string | null };
  return { label, name: aliasNameText(sourceName.name, sourceName.erased) as string | null };
}
