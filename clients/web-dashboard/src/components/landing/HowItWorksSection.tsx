import { MiniGraph } from "@/components/landing/MiniGraph";
import { useInViewOnce } from "@/components/landing/useInViewOnce";
import {
  HOW_GRAPH_WIDTH,
  HOW_GRAPH_HEIGHT,
} from "@/lib/howItWorksGraph";
import type { MiniGraphLabel } from "@/lib/graphExplorerPreview";

// 작동 방식 3스텝 섹션 — 3단 가로 진행. 스텝 텍스트 위에 미니 그래프 시각화(MiniGraph)를 얹어
// "하나의 그래프가 단계적으로 완성되는 과정"을 보여준다. 번호/슬래시(01 /, 02 /, 03 /)는
// 라틴 기술 토큰이라 모노, 스텝 제목·본문은 한글이 섞이므로 절대 모노를 쓰지 않고 본문 서체로
// 조판한다(DESIGN.md 모노 스코프 규칙). stage는 STEPS 배열 순서(1-indexed)로 고정한다.
const STEPS = [
  {
    num: "01",
    title: "연결",
    body: "저장소와 워크스페이스를 연결한다. GitHub, Jira, Slack.",
  },
  {
    num: "02",
    title: "구축",
    body: "흩어진 기록 사이의 관계를 찾아 하나의 히스토리 그래프로 엮는다. 결정과 코드가 다시 이어진다.",
  },
  {
    num: "03",
    title: "질문",
    body: "자연어로 묻는다. 답과 함께, 근거가 된 노드와 경로가 돌아온다.",
  },
] as const;

// 스텝별 미니 그래프 mono 라벨(2026-07-25, 밀도 보강) — 개념 다이어그램이므로 스텝당 2~3개만.
// 라틴 기술 토큰만 쓰고(모노 스코프 규칙), 노드↔라벨 매핑은 기능 2(graphExplorerPreview.ts)와
// 일관되게 유지한다: n2=HT-64 · n8=#dev-search · n7=QueryService.java ·
// n3=WebhookDeliveryStore.java는 그대로 재사용, n11=PR #142만 신규(기능 2에는 n11 라벨이
// 없어 충돌하지 않는다). 서사: 01은 서로 다른 소스의 흩어진 기록 셋(이슈·채널·PR), 02는
// 그래프에 다시 이어지는 코드 파일들, 03은 점등 경로 서사(HT-64 → PR #142 → #dev-search) —
// 히어로 슬롯·기능 섹션과 같은 스토리 한 줄이다. dx/dy·anchorEnd는 기능 2의 검증된 값을
// 재사용하고, 전부 core로 둬 모바일 축소 규칙에서도 유지한다(라벨이 적어 겹칠 여지가 없다).
const STEP_LABELS: [MiniGraphLabel[], MiniGraphLabel[], MiniGraphLabel[]] = [
  [
    { nodeId: "n2", text: "HT-64", dx: -6, dy: 9, anchorEnd: true, core: true },
    { nodeId: "n8", text: "#dev-search", dx: 4, dy: -12, core: true },
    { nodeId: "n11", text: "PR #142", dx: 8, dy: 4, core: true },
  ],
  [
    { nodeId: "n7", text: "QueryService.java", dx: 8, dy: -9, core: true },
    { nodeId: "n3", text: "WebhookDeliveryStore.java", dx: 8, dy: -13, core: true },
  ],
  [
    { nodeId: "n2", text: "HT-64", dx: -6, dy: 9, anchorEnd: true, core: true },
    { nodeId: "n8", text: "#dev-search", dx: 4, dy: -12, emphasis: true, core: true },
    { nodeId: "n11", text: "PR #142", dx: 8, dy: 4, core: true },
  ],
];

// id="how" — 히어로 스크롤 큐(#how)의 앵커 대상.
export function HowItWorksSection() {
  return (
    <section className="lp-how" id="how">
      <div className="lp-how-inner">
        <div className="lp-how-header">
          <p className="lp-how-eyebrow">HOW IT WORKS</p>
          <h2 className="lp-how-headline">연결하고, 묻는다. 그 사이는 자동이다.</h2>
        </div>
        <ol className="lp-how-steps">
          {STEPS.map((step, i) => (
            <HowStep key={step.num} step={step} stage={(i + 1) as 1 | 2 | 3} />
          ))}
        </ol>
      </div>
    </section>
  );
}

// 스텝 하나 — IntersectionObserver로 1회 관찰해(threshold 0.4) 진입 시 is-played를 부여하고
// 곧바로 unobserve한다. is-played는 재생 후 계속 유지되며(리렌더돼도 남는다), 루프는 없다.
// 데스크톱에서는 세 스텝이 한 화면에 동시에 들어오므로 landing.css의 --step-base(스텝별
// 0/700/1500ms 기본 지연)가 01→02→03 순차 진행을 만든다 — 모바일은 세로 스택이라
// --step-base가 0으로 리셋되고, 각 스텝이 개별 진입할 때 바로 발동한다.
function HowStep({
  step,
  stage,
}: {
  step: (typeof STEPS)[number];
  stage: 1 | 2 | 3;
}) {
  const { ref, inView } = useInViewOnce<HTMLLIElement>({ threshold: 0.4 });

  return (
    <li className={`lp-how-step${inView ? " is-played" : ""}`} ref={ref}>
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
        <span className="lp-how-step-name">{step.title}</span>
      </h3>
      <p className="lp-how-step-body">{step.body}</p>
    </li>
  );
}
