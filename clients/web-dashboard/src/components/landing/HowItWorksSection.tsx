import { useLandingLanguage, type Localized, type LandingLang } from "@/components/landing/LandingLanguageProvider";
import { MiniGraph } from "@/components/landing/MiniGraph";
import { useInViewOnce } from "@/components/landing/useInViewOnce";
import {
  HOW_GRAPH_WIDTH,
  HOW_GRAPH_HEIGHT,
} from "@/lib/howItWorksGraph";
import type { MiniGraphLabel } from "@/lib/graphExplorerPreview";

// 섹션 헤드라인 소사전 — LandingHeader COPY 패턴.
const COPY: Localized<{ headline: string }> = {
  ko: { headline: "연결하고, 묻는다. 그 사이는 자동이다." },
  en: { headline: "Connect and ask. Everything in between is automatic." },
};

// 작동 방식 3스텝 섹션 — 3단 가로 진행. 스텝 텍스트 위에 미니 그래프 시각화(MiniGraph)를 얹어
// "하나의 그래프가 단계적으로 완성되는 과정"을 보여준다. 번호/슬래시(01 /, 02 /, 03 /)는
// 라틴 기술 토큰이라 모노, 스텝 제목·본문은 한글이 섞이므로 절대 모노를 쓰지 않고 본문 서체로
// 조판한다(DESIGN.md 모노 스코프 규칙). stage는 STEPS 배열 순서(1-indexed)로 고정한다.
// title/body만 언어별로 갈리고 배열 자체는 단일 출처다(언어별 배열 2벌 금지).
const STEPS: Array<{ num: string; title: Localized<string>; body: Localized<string> }> = [
  {
    num: "01",
    title: { ko: "연결", en: "Connect" },
    body: {
      ko: "저장소와 워크스페이스를 연결한다. 코드, 티켓, 대화, 문서.",
      en: "Connect your repositories and workspaces. Code, tickets, conversations, docs.",
    },
  },
  {
    num: "02",
    title: { ko: "구축", en: "Build" },
    body: {
      ko: "흩어진 기록 사이의 관계를 찾아 하나의 히스토리 그래프로 엮는다. 결정과 코드가 다시 이어진다.",
      en: "Finds the relationships between scattered records and weaves them into one history graph. Decisions and code are linked again.",
    },
  },
  {
    num: "03",
    title: { ko: "질문", en: "Ask" },
    body: {
      ko: "자연어로 묻는다. 답과 함께, 근거가 된 노드와 경로가 돌아온다.",
      en: "Ask in natural language. The answer comes back with the nodes and paths behind it.",
    },
  },
];

// 스텝별 미니 그래프 mono 라벨(2026-07-25, 밀도 보강) — 개념 다이어그램이므로 스텝당 2~3개만.
// 라틴 기술 토큰만 쓰고(모노 스코프 규칙), 노드↔라벨 매핑은 기능 2(graphExplorerPreview.ts)와
// 일관되게 유지한다: n2=PAY-64 · n8=#dev-pay · n11=PR #142는 그대로 재사용(기능 2에는 n11
// 라벨이 없어 충돌하지 않는다). n7·n3은 payflow(TS 스택)에 맞춰 각각 payments/router.ts ·
// webhooks/delivery.ts로 갱신했다(가장 긴 라벨이 기존 25자에서 21자로 짧아져 겹칠 여지가
// 오히려 줄었다). 서사: 01은 서로 다른 소스의
// 흩어진 기록 셋(이슈·채널·PR), 02는 그래프에 다시 이어지는 코드 파일들, 03은 점등 경로
// 서사(PAY-64 → PR #142 → #dev-pay) — 히어로 슬롯·기능 섹션과 같은 스토리 한 줄이다.
// dx/dy·anchorEnd는 기능 2의 검증된 값을 재사용하고, 전부 core로 둬 모바일 축소 규칙에서도
// 유지한다(라벨이 적어 겹칠 여지가 없다).
const STEP_LABELS: [MiniGraphLabel[], MiniGraphLabel[], MiniGraphLabel[]] = [
  [
    { nodeId: "n2", text: "PAY-64", dx: -6, dy: 9, anchorEnd: true, core: true },
    { nodeId: "n8", text: "#dev-pay", dx: 4, dy: -12, core: true },
    { nodeId: "n11", text: "PR #142", dx: 8, dy: 4, core: true },
  ],
  [
    { nodeId: "n7", text: "payments/router.ts", dx: 8, dy: -9, core: true },
    { nodeId: "n3", text: "webhooks/delivery.ts", dx: 8, dy: -13, core: true },
  ],
  [
    { nodeId: "n2", text: "PAY-64", dx: -6, dy: 9, anchorEnd: true, core: true },
    { nodeId: "n8", text: "#dev-pay", dx: 4, dy: -12, emphasis: true, core: true },
    { nodeId: "n11", text: "PR #142", dx: 8, dy: 4, core: true },
  ],
];

// id="how" — 히어로 스크롤 큐(#how)의 앵커 대상.
// 안무 트리거(2026-07-26 7차): 스텝별 개별 IO가 아니라 3단 행(<ol>) 전체를 하나의
// IntersectionObserver로 관찰하고, 발동 시 세 스텝 모두에 is-played를 부여한다 — 01→02→03
// 순차는 landing.css의 --step-base delay 체인이 만든다. "세 컷이 하나의 안무"라는 요구의
// 구조적 보장: 트리거가 하나뿐이라 어떤 컷도 따로 발동하거나 빠질 수 없다. threshold 0.3:
// 데스크톱(행 높이 ≈ 뷰포트의 ⅓)에서는 행이 하단에 충분히 걸쳤을 때, 모바일(행이 뷰포트보다
// 큰 세로 스택)에서는 01 스텝이 대부분 보였을 때 발동하는 절충값. 모바일도 같은 delay
// 체인을 타므로(7차에 모바일 --step-base 리셋 제거) 02·03이 화면 밖에서 먼저 재생될 수
// 있지만, 재생 후 최종 상태가 유지돼 콘텐츠 손실은 없다(행 단위 단일 안무를 우선한 절충).
export function HowItWorksSection() {
  const { ref, inView } = useInViewOnce<HTMLOListElement>({ threshold: 0.3 });
  const { lang } = useLandingLanguage();

  return (
    <section className="lp-how" id="how">
      <div className="lp-how-inner">
        <div className="lp-how-header">
          <p className="lp-how-eyebrow">HOW IT WORKS</p>
          <h2 className="lp-how-headline">{COPY[lang].headline}</h2>
        </div>
        <ol className="lp-how-steps" ref={ref}>
          {STEPS.map((step, i) => (
            <HowStep
              key={step.num}
              step={step}
              stage={(i + 1) as 1 | 2 | 3}
              played={inView}
              lang={lang}
            />
          ))}
        </ol>
      </div>
    </section>
  );
}

// 스텝 하나 — is-played는 부모 행 관찰(위 useInViewOnce)에서 일괄 부여된다. 재생 후 계속
// 유지되며(리렌더돼도 남는다), 루프는 없다.
function HowStep({
  step,
  stage,
  played,
  lang,
}: {
  step: (typeof STEPS)[number];
  stage: 1 | 2 | 3;
  played: boolean;
  lang: LandingLang;
}) {
  return (
    <li className={`lp-how-step${played ? " is-played" : ""}`}>
      <div className="lp-how-viz">
        {/* labelAspect: .lp-how-viz는 뷰박스 비율 그대로의 컨테이너라 레터박스가 0 —
            MiniGraph의 cqw 라벨 위치 공식이 그대로 성립한다. */}
        <MiniGraph
          stage={stage}
          labels={STEP_LABELS[stage - 1]}
          labelAspect={HOW_GRAPH_WIDTH / HOW_GRAPH_HEIGHT}
        />
      </div>
      <h3 className="lp-how-step-title">
        <span className="lp-how-step-num">{step.num} /</span>
        <span className="lp-how-step-name">{step.title[lang]}</span>
      </h3>
      <p className="lp-how-step-body">{step.body[lang]}</p>
    </li>
  );
}
