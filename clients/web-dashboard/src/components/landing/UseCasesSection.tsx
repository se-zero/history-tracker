import { useLandingLanguage, type Localized } from "@/components/landing/LandingLanguageProvider";

// 섹션 헤드라인 소사전 — LandingHeader COPY 패턴.
const COPY: Localized<{ headline: string }> = {
  ko: { headline: "이런 걸 묻게 된다." },
  en: { headline: "The questions you'll find yourself asking." },
};

// 유스케이스 섹션 — 제품의 실제 빈 화면 추천 질문을 2×2 카드 그리드로 노출한다.
// 카드는 클릭 요소가 아니다(링크·버튼·hover 없음) — 정보 전달용 카드.
// 질문은 한글이라 모노 금지, 본문 서체 weight 600으로 강조한다(DESIGN.md 모노 스코프 규칙).
// node는 질문이 주로 가리키는 그래프 노드 타입 — 카드 점 색으로 쓴다(노드 지시이므로 노드 색).
// id는 안정 key용 — category가 번역 대상 문자열이라 그 자체를 key로 쓸 수 없다.
const USE_CASES: Array<{
  id: string;
  question: Localized<string>;
  category: Localized<string>;
  desc: Localized<string>;
  node: string;
}> = [
  {
    id: "code-archive",
    question: {
      ko: "왜 이 코드가 이렇게 바뀌었어?",
      en: "Why was this code changed this way?",
    },
    category: { ko: "코드 아카이브", en: "Code archive" },
    desc: {
      ko: "지금은 아무도 설명하지 못하는 결정의 출처를 되짚는다.",
      en: "Trace unexplained decisions back to their source.",
    },
    node: "commit",
  },
  {
    id: "onboarding",
    question: {
      ko: "이 도메인을 가장 잘 아는 사람은?",
      en: "Who knows this domain best?",
    },
    category: { ko: "온보딩", en: "Onboarding" },
    desc: {
      ko: "새로 합류한 사람이 물어볼 상대를 먼저 찾는다.",
      en: "New teammates find who to ask first.",
    },
    node: "person",
  },
  {
    id: "retrospective",
    question: {
      ko: "지난 분기 가장 논쟁이 많았던 PR은?",
      en: "Which PR sparked the most debate last quarter?",
    },
    category: { ko: "회고", en: "Retrospective" },
    desc: {
      ko: "리뷰가 길어진 지점을 찾아 다음 스프린트에 반영한다.",
      en: "Find where reviews ran long and feed it into the next sprint.",
    },
    node: "pr",
  },
  {
    id: "review",
    question: {
      ko: "최근 머지된 리팩토링 PR들을 정리해줘",
      en: "Summarize the recently merged refactoring PRs",
    },
    category: { ko: "리뷰", en: "Review" },
    desc: {
      ko: "흩어진 변경을 한 번에 훑는다.",
      en: "Skim scattered changes in one pass.",
    },
    node: "issue",
  },
];

export function UseCasesSection() {
  const { lang } = useLandingLanguage();

  return (
    <section className="lp-cases">
      <div className="lp-cases-inner">
        <div className="lp-cases-header">
          <p className="lp-cases-eyebrow">USE CASES</p>
          <h2 className="lp-cases-headline">{COPY[lang].headline}</h2>
        </div>
        <ul className="lp-cases-grid">
          {USE_CASES.map((item) => (
            <li className="lp-cases-card" key={item.id}>
              <h3 className="lp-cases-question">
                <span
                  className={`lp-cases-node-dot lp-cases-node-dot--${item.node}`}
                  aria-hidden="true"
                />
                "{item.question[lang]}"
              </h3>
              <p className="lp-cases-desc">
                <span className="lp-cases-category">{item.category[lang]}</span> — {item.desc[lang]}
              </p>
            </li>
          ))}
        </ul>
      </div>
    </section>
  );
}
