import type { ReactNode } from "react";

import { useLandingLanguage, type Localized } from "@/components/landing/LandingLanguageProvider";

// 헤드라인 1행은 "git blame" 모노 스팬이 문장 안에 다른 위치·붙임 방식으로 들어가
// 언어별로 조판이 갈린다(한글은 조사가 바로 붙고, 영문은 문장 맨 앞에서 띄어 쓴다) —
// 그래서 ReactNode로 JSX 조각을 직접 사전에 넣는다.
const COPY: Localized<{
  headlineLine1: ReactNode;
  headlineLine2: string;
  bodyLine1: string;
  bodyLine2: string;
}> = {
  ko: {
    headlineLine1: (
      <>
        <span className="lp-problem-mono">git blame</span>은 누가 바꿨는지 알려준다.
      </>
    ),
    headlineLine2: "왜 바꿨는지는 아무도 모른다.",
    bodyLine1: "결정은 Slack에, 근거는 티켓에, 결과만 코드에 남는다.",
    bodyLine2: "셋 다 검색되지만, 그 사이의 관계는 어디에도 저장되지 않는다.",
  },
  en: {
    headlineLine1: (
      <>
        <span className="lp-problem-mono">git blame</span> tells you who changed it.
      </>
    ),
    headlineLine2: "Nobody remembers why.",
    bodyLine1: "Decisions live in Slack, reasoning in tickets — only the result reaches the code.",
    bodyLine2: "All three are searchable, but the connections between them are stored nowhere.",
  },
};

// 문제 정의 섹션 — 이미지·아이콘 없이 타이포그래피만으로 구성한다.
// "git blame"만 모노스페이스로 도드라져 보이게 해, DESIGN.md의 "기술 토큰은 모노" 규칙을
// 헤드라인 안에서 직접 보여준다.
export function ProblemSection() {
  const { lang } = useLandingLanguage();
  const t = COPY[lang];

  return (
    <section className="lp-problem">
      <div className="lp-problem-inner">
        <h2 className="lp-problem-headline">
          <span className="lp-problem-line">{t.headlineLine1}</span>
          <span className="lp-problem-line">{t.headlineLine2}</span>
        </h2>
        <p className="lp-problem-body">
          <span className="lp-problem-body-line">{t.bodyLine1}</span>
          <span className="lp-problem-body-line">{t.bodyLine2}</span>
        </p>
      </div>
    </section>
  );
}
