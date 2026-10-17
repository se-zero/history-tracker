import type { UiLang } from "@/i18n/language";

// 빈 화면과 intro 답변이 같은 추천 질문을 쓰도록 한곳에 둔다.
// 이 문장은 그대로 질문이 되므로 화면 언어를 따른다. 한국어 네 문장은 바꾸지 않는다.
const SUGGESTED = {
  ko: [
    { icon: "branch", text: "왜 이 코드가 이렇게 바뀌었어?" },
    { icon: "refactor", text: "최근 머지된 리팩토링 PR들을 정리해줘" },
    { icon: "fire", text: "지난 분기 가장 논쟁이 많았던 PR은?" },
    { icon: "people", text: "이 도메인을 가장 잘 아는 사람은?" },
  ],
  en: [
    { icon: "branch", text: "Why did this code change this way?" },
    { icon: "refactor", text: "Summarize the refactoring PRs merged recently" },
    { icon: "fire", text: "Which PR was most debated last quarter?" },
    { icon: "people", text: "Who knows this domain best?" },
  ],
} as const;

export type SuggestedIcon = (typeof SUGGESTED.ko)[number]["icon"];

export function suggestedQuestions(lang: UiLang) {
  return SUGGESTED[lang];
}

// 칩만 있고 텍스트가 없을 때 채우는 질문. 백엔드 content는 빈 값을 받지 않는다.
export function nodeOnlyQuestion(lang: UiLang): string {
  return lang === "en"
    ? "Explain the attached items."
    : "첨부한 항목에 대해 설명해줘.";
}
