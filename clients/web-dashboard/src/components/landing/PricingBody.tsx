import type { ReactNode } from "react";
import { Link } from "react-router-dom";

import { GITHUB_AUTHORIZE_URL } from "@/api/auth";
import { useAuth } from "@/auth/AuthProvider";
import { useLandingLanguage, type Localized } from "@/components/landing/LandingLanguageProvider";
import { useCheckoutAvailability } from "@/hooks/useBilling";
import { formatInstant } from "@/lib/format";
import { PLAN_FEATURE_ROWS, PRO_MONTHLY_PRICE_KRW } from "@/lib/plans";
import { PATHS } from "@/routes";

const COPY: Localized<{
  compareHeading: string;
  colFree: string;
  colPro: string;
  monthlyPriceLabel: string;
  freePriceValue: string;
  proPriceValue: string;
  notIncluded: string;
  startHeading: string;
  betaNotice: (date: string) => string;
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
    betaNotice: (date) =>
      `한시적으로(최대 ${date}까지) 무료 플랜에서도 Pro 기능을 쓸 수 있습니다(프로젝트 최대 3개). 운영 사정에 따라 일찍 끝날 수 있으며, 끝나면 자동 결제 없이 무료 플랜 한도로 돌아갑니다.`,
    ctaStart: "무료로 시작하기",
    openApp: "whycode 열기",
    paymentNotice: (
      <>
        Pro 구독은 로그인한 뒤 계정 설정에서 할 수 있습니다.
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
    betaNotice: (date) =>
      `For a limited time (until ${date} at the latest), the Free plan includes Pro features (up to 3 projects). This may end earlier for operational reasons. When it ends, you return to the Free plan limits with no automatic charge.`,
    ctaStart: "Start for free",
    openApp: "Open whycode",
    paymentNotice: (
      <>
        You can subscribe to Pro from account settings after you sign in.
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
// 렌더되므로 언어는 Provider에서 읽는다. "시작하기"는 결제 스위치와 무관하게 앱으로 들어가는
// 버튼 하나와 결제 안내뿐이다 — 구독하는 곳은 계정 설정의 PlanCard 하나다. 스위치가 켜지면
// 구독 버튼이 더해져 앰버가 둘이 되던 것을 없애고, 결제를 열고 닫을 때 이 페이지를 고칠 일을
// 없앴다(2026-11-10, docs/billing.md §8-2).
export function PricingBody() {
  const { lang } = useLandingLanguage();
  const { status } = useAuth();
  const availability = useCheckoutAvailability();
  const t = COPY[lang];

  const betaEndsAt = availability.data?.betaEndsAt;

  return (
    <>
      <section className="lp-legal-section">
        {betaEndsAt && (
          <p className="lp-legal-notice">
            {t.betaNotice(formatInstant(betaEndsAt, lang === "en" ? { locale: "en-US" } : {}))}
          </p>
        )}
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
              {PLAN_FEATURE_ROWS.map((row) => (
                <tr key={row.name.ko}>
                  <td>{row.name[lang]}</td>
                  <td>{row.free.included && row.free.value ? row.free.value[lang] : t.notIncluded}</td>
                  <td>{row.pro.included && row.pro.value ? row.pro.value[lang] : t.notIncluded}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="lp-legal-section">
        <h2 className="lp-legal-heading">{t.startHeading}</h2>
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
