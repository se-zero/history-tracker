// "작동 방식" 3스텝(MiniGraph)이 쓰는 라벨 타입의 정의처.
//
// 2026-08 재구축 이전에는 기능 2(그래프 탐색) 미리보기도 이 MiniGraph 좌표계(400x240
// 뷰박스·클러스터 A/B/C)를 확장해 썼지만, 실제 그래프 화면이 이슈/PR을 작업 단위로 삼는
// 작업 단위 뷰(WorkUnitCanvas.tsx)로 바뀐 뒤 "미리보기가 실제 화면과 너무 달라 완성도가
// 없어 보인다"는 피드백에 따라 그 시각 문법을 직접 옮긴 정적 SVG로 다시 그렸다
// (FeatureSections.tsx의 GraphExplorerPreview). 그 미리보기 전용 추가 노드·엣지·라벨
// 데이터는 이제 이 파일이 아니라 FeatureSections.tsx 안에 작업 단위 좌표로 직접 있다.
// MiniGraphLabel 타입만 HowItWorksSection.tsx·MiniGraph.tsx가 계속 참조하므로 여기 남긴다.

export interface MiniGraphLabel {
  /** 라벨이 붙는 노드 id — howItWorksGraph.ts에 정의된 노드 중 하나. */
  nodeId: string;
  text: string;
  /** 노드 점 기준 라벨 오프셋(실 px 고정값 — 그래프 자체는 뷰박스 스케일로 반응형이지만
      라벨은 DESIGN.md mono-label 12px 하한을 항상 지켜야 해서 그래프 스케일과 무관하게 CSS
      px로 고정한다. MiniGraph.tsx의 컨테이너 쿼리 단위(cqh/cqw) 위치 계산과 짝을 이룬다). */
  dx: number;
  dy: number;
  /** true면 텍스트 끝을 (노드 x + dx)에 맞춘다(좌측으로 뻗음) — 오른쪽에 다른 노드/엣지가
      붙어 있어 기본(우측으로 뻗는) 방향이 겹치는 라벨에 쓴다. */
  anchorEnd?: boolean;
  /** 앰버 점등 경로 위 라벨 하나만 text-primary로 살짝 밝힌다(선택된/점등 맥락). */
  emphasis?: boolean;
  /** 모바일(라벨 3~4개로 축소)에서도 유지하는 핵심 라벨인지. */
  core?: boolean;
  /** 캔버스 폭이 충분히 넓을 때만 보인다(1180px 미만에서 숨김) — anchorEnd로 텍스트가 길게
      왼쪽으로 뻗는 라벨은 캔버스가 좁아지면(2열 레이아웃의 좁은 구간, 768~1180px 근방)
      실 px 텍스트 폭이 캔버스 왼쪽 밖으로 밀려 잘린다(겹치거나 잘리면 라벨을 빼는 원칙). */
  wide?: boolean;
}
