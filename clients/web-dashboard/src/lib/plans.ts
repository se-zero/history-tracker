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
  label: Localized<string>;
  included: boolean;
}

export interface PlanFeatureRow {
  free: PlanFeatureCell;
  pro: PlanFeatureCell;
}

// 행 순서: 프로젝트 / 연동 소스 / 질의 / 증분 수집 / 정밀 재구축.
// 한국어 라벨은 PlanCard가 쓰던 기존 FREE_FEATURES·PAID_FEATURES 문자열과 글자 하나
// 다르지 않다 — PlanCard가 이 상수의 `.ko`로 목록을 만들어 쓰므로 화면 표시가 그대로 유지된다.
export const PLAN_FEATURE_ROWS: PlanFeatureRow[] = [
  {
    free: { label: { ko: "프로젝트 1개", en: "1 project" }, included: true },
    pro: { label: { ko: "프로젝트 무제한", en: "Unlimited projects" }, included: true },
  },
  {
    free: {
      label: { ko: "GitHub · Slack · Jira 각 1회", en: "GitHub, Slack, Jira — 1 each" },
      included: true,
    },
    pro: { label: { ko: "모든 소스 연동", en: "All sources" }, included: true },
  },
  {
    free: { label: { ko: "질의 10회", en: "10 queries" }, included: true },
    pro: { label: { ko: "질의 무제한", en: "Unlimited queries" }, included: true },
  },
  {
    free: { label: { ko: "증분 수집", en: "Incremental sync" }, included: false },
    pro: { label: { ko: "증분 수집", en: "Incremental sync" }, included: true },
  },
  {
    free: { label: { ko: "정밀 재구축", en: "Precise rebuild" }, included: false },
    pro: { label: { ko: "정밀 재구축", en: "Precise rebuild" }, included: true },
  },
];
