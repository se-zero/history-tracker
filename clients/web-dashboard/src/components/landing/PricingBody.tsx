import type { ReactNode } from "react";
import { Link } from "react-router-dom";

import { GITHUB_AUTHORIZE_URL } from "@/api/auth";
import { useAuth } from "@/auth/AuthProvider";
import { useLandingLanguage, type Localized } from "@/components/landing/LandingLanguageProvider";
import { PLAN_FEATURE_ROWS, PRO_MONTHLY_PRICE_KRW } from "@/lib/plans";
import { PATHS } from "@/routes";

// 비교표 첫 열(행 이름표) — plans.ts의 PlanFeatureRow는 free/pro 각자의 표시 문구만 갖고
// 공통 행 이름은 없다(PlanCard가 free/pro를 표로 묶지 않고 각자 나열하기 때문). 이 페이지만
// 표로 묶어 보여주므로 행 이름은 여기 로컬로 두고, 순서를 plans.ts의 PLAN_FEATURE_ROWS와
// 맞춘다("행 순서: 프로젝트 / 연동 소스 / 질의 / 증분 수집 / 정밀 재구축").
const FEATURE_ROW_NAMES: Localized<string>[] = [
  { ko: "프로젝트", en: "Projects" },
  { ko: "연동 소스", en: "Connected sources" },
  { ko: "질의", en: "Queries" },
  { ko: "증분 수집", en: "Incremental sync" },
  { ko: "정밀 재구축", en: "Precise rebuild" },
];

const COPY: Localized<{
  compareHeading: string;
  colFree: string;
  colPro: string;
  monthlyPriceLabel: string;
  freePriceValue: string;
  proPriceValue: string;
  notIncluded: string;
  startHeading: string;
  proPreparing: string;
  ctaStart: string;
  openApp: string;
  paymentNotice: ReactNode;
  relatedHeading: string;
  terms: string;
  refund: string;
  support: string;
}> = {
  ko: {
    compareHeading: "Free / Pro 비교",
    colFree: "Free",
    colPro: "Pro",
    monthlyPriceLabel: "월 요금",
    freePriceValue: "0원",
    proPriceValue: `${PRO_MONTHLY_PRICE_KRW.toLocaleString("ko-KR")}원 (부가세 포함)`,
    notIncluded: "—",
    startHeading: "시작하기",
    proPreparing: "Pro 구독은 준비 중입니다. 결제가 열리면 계정 설정에서 바로 전환할 수 있습니다.",
    ctaStart: "무료로 시작하기",
    openApp: "whycode 열기",
    paymentNotice: (
      <>
        결제·영수증·환불은 판매 대행자인 Paddle.com이 처리합니다. 자세한 조건은{" "}
        <Link to={PATHS.terms}>이용약관</Link>과 <Link to={PATHS.refund}>환불정책</Link>에서
        확인하세요.
      </>
    ),
    relatedHeading: "관련 문서",
    terms: "이용약관",
    refund: "환불정책",
    support: "지원",
  },
  en: {
    compareHeading: "Free vs Pro",
    colFree: "Free",
    colPro: "Pro",
    monthlyPriceLabel: "Monthly price",
    freePriceValue: "₩0",
    proPriceValue: `₩${PRO_MONTHLY_PRICE_KRW.toLocaleString("en-US")} per month (VAT included)`,
    notIncluded: "—",
    startHeading: "Get started",
    proPreparing:
      "Pro subscriptions are coming soon. Once billing opens, you'll be able to switch right from account settings.",
    ctaStart: "Start for free",
    openApp: "Open whycode",
    paymentNotice: (
      <>
        Billing, receipts, and refunds are handled by our reseller, Paddle.com. See the{" "}
        <Link to={PATHS.terms}>Terms of Service</Link> and{" "}
        <Link to={PATHS.refund}>Refund Policy</Link> for details.
      </>
    ),
    relatedHeading: "Related",
    terms: "Terms of Service",
    refund: "Refund Policy",
    support: "Support",
  },
};

// 요금 본문 — 조 번호 없는 짧은 산문(SupportBody·SlackBody와 같은 패턴). LegalLayout 안에서
// 렌더되므로 언어는 Provider에서 읽는다. 결제 버튼은 아직 없어(B2) Pro는 "준비 중" 안내로
// 대신한다.
export function PricingBody() {
  const { lang } = useLandingLanguage();
  const { status } = useAuth();
  const t = COPY[lang];

  return (
    <>
      <section className="lp-legal-section">
        <h2 className="lp-legal-heading">{t.compareHeading}</h2>
        <div className="lp-legal-table-scroll">
          <table className="lp-legal-table">
            <thead>
              <tr>
                <th></th>
                <th>{t.colFree}</th>
                <th>{t.colPro}</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td>{t.monthlyPriceLabel}</td>
                <td>{t.freePriceValue}</td>
                <td>{t.proPriceValue}</td>
              </tr>
              {PLAN_FEATURE_ROWS.map((row, index) => (
                <tr key={FEATURE_ROW_NAMES[index].ko}>
                  <td>{FEATURE_ROW_NAMES[index][lang]}</td>
                  <td>{row.free.included ? row.free.label[lang] : t.notIncluded}</td>
                  <td>{row.pro.included ? row.pro.label[lang] : t.notIncluded}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="lp-legal-section">
        <h2 className="lp-legal-heading">{t.startHeading}</h2>
        <p className="lp-legal-notice">{t.proPreparing}</p>
        <p>
          {status === "authenticated" ? (
            <Link className="lp-btn lp-btn--primary" to={PATHS.root}>{t.openApp}</Link>
          ) : (
            <a className="lp-btn lp-btn--primary" href={GITHUB_AUTHORIZE_URL}>{t.ctaStart}</a>
          )}
        </p>
        <p>{t.paymentNotice}</p>
      </section>

      <section className="lp-legal-section">
        <h2 className="lp-legal-heading">{t.relatedHeading}</h2>
        <ul>
          <li>
            <Link to={PATHS.terms}>{t.terms}</Link>
          </li>
          <li>
            <Link to={PATHS.refund}>{t.refund}</Link>
          </li>
          <li>
            <Link to={PATHS.support}>{t.support}</Link>
          </li>
        </ul>
      </section>
    </>
  );
}
