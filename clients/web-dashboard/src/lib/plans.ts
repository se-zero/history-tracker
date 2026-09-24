import type { Localized } from "@/components/landing/LandingLanguageProvider";

// Free / Pro 요금·기능 목록의 단일 출처. 계정 설정의 PlanCard와 공개 요금 페이지(/pricing)가
// 같은 값을 읽는다 — 두 곳이 따로 적으면 표시 한도가 실제와 달라질 수 있다(Paddle 심사·분쟁
// 모두에 문제가 된다).
//
// 한도의 원본은 backend PlanService다(FREE_QUERY_LIMIT=10, 프로젝트 1개, GITHUB/SLACK/JIRA
// 각 1회, 증분 수집·정밀 재구축은 PAID 전용) — 그 값이 바뀌면 여기도 함께 고친다.

// Pro 월 구독료(원, 부가세 포함). 결제 화면(B2)이 열리기 전까지는 표시 전용 상수다.
export const PRO_MONTHLY_PRICE_KRW = 14900;

interface PlanFeatureCell {
  // PlanCard가 그대로 쓰는 문구 — 글자 하나 바뀌면 화면 표시가 달라진다.
  label: Localized<string>;
  included: boolean;
  // 요금표(/pricing) 칸에 들어갈 짧은 값. included가 false인 칸은 표에서 "—"로 대신하므로
  // 값을 두지 않는다(옵셔널) — 빈 문자열로 채우면 "값이 있는데 비어 있다"와 구분이 안 된다.
  value?: Localized<string>;
}

export interface PlanFeatureRow {
  // 요금표 행 이름표. plans.ts가 유일한 출처라 PricingBody가 별도 이름 목록을 만들 필요가
  // 없고, 행을 추가·삭제해도 이름이 함께 따라온다(인덱스로만 짝짓던 예전 방식은 두 배열의
  // 길이·순서가 어긋나면 조용히 undefined를 렌더할 위험이 있었다).
  name: Localized<string>;
  free: PlanFeatureCell;
  pro: PlanFeatureCell;
}

// 행 순서: 프로젝트 / 연동 소스 / 질의 / 증분 수집 / 정밀 재구축.
// label의 한국어 문구는 PlanCard가 쓰던 기존 FREE_FEATURES·PAID_FEATURES 문자열과 글자 하나
// 다르지 않다 — PlanCard가 이 상수의 `.ko`(label)로 목록을 만들어 쓰므로 화면 표시가 그대로
// 유지된다. value는 요금표 전용이라 label과 다를 수 있다(예: Pro의 연동 소스는 label "모든
// 소스 연동" / value "모든 소스" — 표 칸은 짧게, PlanCard 문구는 그대로 둔다).
export const PLAN_FEATURE_ROWS: PlanFeatureRow[] = [
  {
    name: { ko: "프로젝트", en: "Projects" },
    free: {
      label: { ko: "프로젝트 1개", en: "1 project" },
      included: true,
      value: { ko: "1개", en: "1" },
    },
    pro: {
      label: { ko: "프로젝트 무제한", en: "Unlimited projects" },
      included: true,
      value: { ko: "무제한", en: "Unlimited" },
    },
  },
  {
    name: { ko: "연동 소스", en: "Connected sources" },
    free: {
      label: { ko: "GitHub · Slack · Jira 각 1회", en: "GitHub, Slack, Jira — 1 each" },
      included: true,
      value: { ko: "GitHub · Slack · Jira 각 1회", en: "GitHub, Slack, Jira — 1 each" },
    },
    pro: {
      label: { ko: "모든 소스 연동", en: "All sources" },
      included: true,
      value: { ko: "모든 소스", en: "All sources" },
    },
  },
  {
    name: { ko: "질의", en: "Queries" },
    free: {
      label: { ko: "질의 10회", en: "10 queries" },
      included: true,
      value: { ko: "10회", en: "10" },
    },
    pro: {
      label: { ko: "질의 무제한", en: "Unlimited queries" },
      included: true,
      value: { ko: "무제한", en: "Unlimited" },
    },
  },
  {
    name: { ko: "새 활동 자동 수집(증분 수집)", en: "Automatic sync of new activity" },
    free: { label: { ko: "증분 수집", en: "Incremental sync" }, included: false },
    pro: {
      label: { ko: "증분 수집", en: "Incremental sync" },
      included: true,
      value: { ko: "포함", en: "Included" },
    },
  },
  {
    name: { ko: "AI 검증 재구축(정밀 재구축)", en: "AI-verified rebuild" },
    free: { label: { ko: "정밀 재구축", en: "Precise rebuild" }, included: false },
    pro: {
      label: { ko: "정밀 재구축", en: "Precise rebuild" },
      included: true,
      value: { ko: "포함", en: "Included" },
    },
  },
];
