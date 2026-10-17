import { useEffect, useRef, useState, type FormEvent } from "react";
import { useQueryClient } from "@tanstack/react-query";
import axios from "axios";

import { Icons } from "@/components/Icons";
import { BusyLabel } from "@/components/ui/BusyLabel";
import { Field } from "@/components/ui/Field";
import { InlineError } from "@/components/ui/InlineError";
import { LEGAL_CONTACT_EMAIL } from "@/components/landing/LegalLayout";
import { useAuth } from "@/auth/AuthProvider";
import {
  useBillingSummary,
  useDowngradeToFree,
  useOpenBillingPortal,
  useStartCheckout,
  useUpgradePlan,
} from "@/hooks/useBilling";
import { queryKeys } from "@/hooks/queryKeys";
import { getUiLang, uiText } from "@/i18n/language";
import { formatInstant } from "@/lib/format";
import { openPaddleCheckout } from "@/lib/paddle";
import { PLAN_FEATURE_ROWS, PRO_MONTHLY_PRICE_KRW } from "@/lib/plans";
import type { BillingSubscriptionView } from "@/api/billing";
import type { Plan } from "@/types/api";

function featureList(side: "free" | "pro") {
  return PLAN_FEATURE_ROWS.map((row) => {
    const cell = side === "free" ? row.free : row.pro;
    return { label: cell.label[getUiLang()], included: cell.included };
  });
}

function priceLabel() {
  return getUiLang() === "en"
    ? `₩${PRO_MONTHLY_PRICE_KRW.toLocaleString("en-US")}/month`
    : `월 ${PRO_MONTHLY_PRICE_KRW.toLocaleString("ko-KR")}원`;
}

type CheckoutPhase = "idle" | "opening" | "reflecting" | "done" | "timeout" | "error";

function planLabel(plan: Plan | undefined) {
  return plan === "PAID" ? "Pro" : "Free";
}

function upgradeErrorMessage(error: unknown) {
  if (!axios.isAxiosError(error)) {
    return uiText("전환에 실패했어요. 다시 시도해 주세요.", "Couldn't switch plans. Try again.");
  }
  if (error.response?.status === 403) return uiText("코드가 올바르지 않아요.", "That code isn't valid.");
  return uiText("전환에 실패했어요. 다시 시도해 주세요.", "Couldn't switch plans. Try again.");
}

function subscriptionCopy(subscription: BillingSubscriptionView) {
  if (subscription.status === "past_due") {
    return uiText("결제 수단을 확인하세요.", "Check your payment method.");
  }
  if (subscription.cancelScheduledAt) {
    const when = formatInstant(subscription.cancelScheduledAt);
    return uiText(`${when}에 해지 예정 — 그때까지 Pro`, `Cancels on ${when} — Pro until then`);
  }
  if (subscription.currentPeriodEndsAt) {
    const when = formatInstant(subscription.currentPeriodEndsAt);
    return uiText(`다음 결제일 ${when}`, `Next payment ${when}`);
  }
  return uiText("구독이 활성화되어 있습니다.", "The subscription is active.");
}

export function PlanCard() {
  const { user, refresh } = useAuth();
  const queryClient = useQueryClient();
  const billing = useBillingSummary();
  const upgrade = useUpgradePlan();
  const checkout = useStartCheckout();
  const portal = useOpenBillingPortal();
  const downgrade = useDowngradeToFree();
  const [code, setCode] = useState("");
  const [confirmDowngrade, setConfirmDowngrade] = useState(false);
  const [portalError, setPortalError] = useState<string | null>(null);
  const [checkoutPhase, setCheckoutPhase] = useState<CheckoutPhase>("idle");
  // refresh는 렌더마다 새 함수다. effect 의존성에 넣으면 2초마다 처음부터 다시 시작해
  // 30초 제한에 닿지 않는다.
  const refreshRef = useRef(refresh);
  refreshRef.current = refresh;

  useEffect(() => {
    if (checkoutPhase !== "reflecting") return;
    if (user?.plan === "PAID") setCheckoutPhase("done");
  }, [checkoutPhase, user?.plan]);

  useEffect(() => {
    if (checkoutPhase !== "reflecting") return;
    const started = Date.now();
    let timer = 0;
    const tick = () => {
      void refreshRef.current();
      void queryClient.invalidateQueries({ queryKey: queryKeys.billing() });
      if (Date.now() - started >= 30_000) {
        setCheckoutPhase((phase) => (phase === "reflecting" ? "timeout" : phase));
        return;
      }
      timer = window.setTimeout(tick, 2000);
    };
    timer = window.setTimeout(tick, 2000);
    return () => window.clearTimeout(timer);
  }, [checkoutPhase, queryClient]);

  if (!user) return null;

  const summary = billing.data;
  const isPaid = user.plan === "PAID";
  const betaEndsAt = !isPaid ? user.betaEndsAt : null;
  // 베타는 결제 상태 조회 결과와 무관하게 FREE + betaEndsAt만으로 정해진다.
  const mode = betaEndsAt
    ? "beta"
    : billing.isLoading
    ? isPaid
      ? "loading"
      : "free"
    : billing.isError
      ? isPaid
        ? "error"
        : "free"
      : summary?.planSource === "SUBSCRIPTION"
        ? "subscription"
        : summary?.planSource === "CODE"
          ? "code"
          : "free";

  const remaining = user.freeQueryRemaining;
  const queryLimit = user.freeQueryLimit;
  const used =
    remaining == null || queryLimit == null
      ? 0
      : Math.min(queryLimit, queryLimit - remaining);
  const usageRatio = remaining == null || queryLimit == null ? 0 : used / queryLimit;
  const features = mode === "free" ? featureList("free") : featureList("pro");
  const canSubmit = code.trim().length > 0 && !upgrade.isPending;
  const checkoutAvailable = summary?.checkoutAvailable === true;
  const subscription = summary?.subscription ?? null;

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    if (!canSubmit) return;
    upgrade.mutate(code.trim(), { onSuccess: () => setCode("") });
  };

  const onSubscribe = async () => {
    setCheckoutPhase("opening");
    try {
      const session = await checkout.mutateAsync();
      await openPaddleCheckout({
        token: session.clientToken,
        environment: session.environment,
        transactionId: session.transactionId,
        onEvent: (name) => {
          if (name === "checkout.completed") setCheckoutPhase("reflecting");
          else if (name === "checkout.error") setCheckoutPhase("error");
          else if (name === "checkout.closed") {
            setCheckoutPhase((phase) => (phase === "opening" ? "idle" : phase));
          }
        },
      });
    } catch {
      setCheckoutPhase("error");
    }
  };

  const openPortal = async (target: "overview" | "cancel") => {
    setPortalError(null);
    try {
      const { url } = await portal.mutateAsync(target);
      window.location.assign(url);
    } catch {
      setPortalError(uiText("포털을 열지 못했어요. 잠시 후 다시 시도해 주세요.", "Couldn't open the portal. Try again in a moment."));
    }
  };

  const subCopy =
    mode === "beta" && betaEndsAt
      ? uiText(
          `베타 기간 — 최대 ${formatInstant(betaEndsAt)}까지 Pro 기능 무료`,
          `Beta — Pro features free until ${formatInstant(betaEndsAt)}`,
        )
      : mode === "subscription" && subscription
      ? subscriptionCopy(subscription)
      : mode === "subscription"
        ? uiText("구독 상태가 곧 반영됩니다.", "Subscription status will show here shortly.")
        : mode === "code"
          ? uiText("전환 코드로 활성화된 Pro", "Pro activated with a code")
          : mode === "loading"
            ? uiText("플랜 정보를 불러오는 중…", "Loading plan…")
            : mode === "error"
              ? uiText("결제 정보를 불러오지 못했어요.", "Couldn't load billing details.")
              : remaining == null
                ? uiText("무료 한도가 적용 중입니다.", "The free limit is in effect.")
                : uiText(`질의 ${remaining}회 남음`, `${remaining} queries left`);

  return (
    <section className="source-card plan-card">
      <div className="src-head">
        <div className="src-head-main">
          <h4>{uiText("플랜", "Plan")}</h4>
          <div className="src-sub">{subCopy}</div>
        </div>
        <span className={`badge ${isPaid || mode === "beta" ? "accent" : ""}`}>
          {isPaid && <Icons.Sparkle size={11} />}
          {mode === "beta" ? uiText("베타", "Beta") : planLabel(user.plan)}
        </span>
      </div>

      {mode === "beta" && (
        <p className="plan-note">
          {uiText(
            "프로젝트는 최대 3개까지 만들 수 있고, 그 외 기능은 Pro와 같습니다. 베타는 운영 사정에 따라 일찍 끝날 수 있으며, 끝나면 무료 플랜으로 돌아가고 자동 결제는 없습니다.",
            "You can create up to 3 projects. Everything else matches Pro. The beta can end early, and when it does you return to the free plan with no automatic charge.",
          )}
        </p>
      )}

      {mode === "free" && remaining != null && queryLimit != null && (
        <div className="plan-usage">
          <div className="plan-usage-meta">
            <span>{uiText("질의 사용량", "Query usage")}</span>
            <span className="mono">
              {used} / {queryLimit}
            </span>
          </div>
          <div
            className="plan-usage-track"
            role="meter"
            aria-valuemin={0}
            aria-valuemax={queryLimit}
            aria-valuenow={used}
            aria-label={uiText("질의 사용량", "Query usage")}
          >
            <div
              className="plan-usage-fill"
              style={{ width: `${Math.max(0, Math.min(1, usageRatio)) * 100}%` }}
            />
          </div>
        </div>
      )}

      {mode !== "loading" && mode !== "error" && mode !== "beta" && (
        <ul className="plan-features">
          {features.map((feature) => (
            <li
              key={feature.label}
              className={`plan-feature ${feature.included ? "is-on" : "is-off"}`}
            >
              <span className="plan-feature-mark" aria-hidden="true">
                {feature.included ? <Icons.Check size={12} /> : null}
              </span>
              {feature.label}
            </li>
          ))}
        </ul>
      )}

      {mode === "subscription" && subscription && (
        <div className="plan-actions">
          <button
            type="button"
            className="btn btn-primary"
            disabled={portal.isPending}
            onClick={() => void openPortal("overview")}
          >
            {uiText("결제 수단·영수증", "Payment method and receipts")}
          </button>
          {subscription.cancelScheduledAt == null && (
            <button
              type="button"
              className="btn btn-ghost"
              disabled={portal.isPending}
              onClick={() => void openPortal("cancel")}
            >
              {uiText("구독 해지", "Cancel subscription")}
            </button>
          )}
        </div>
      )}
      {portalError && <InlineError>{portalError}</InlineError>}

      {mode === "code" && (
        <div className="plan-actions">
          <button
            type="button"
            className="btn btn-ghost"
            onClick={() => setConfirmDowngrade(true)}
          >
            {uiText("무료 플랜으로 내리기", "Move to the free plan")}
          </button>
        </div>
      )}

      {mode === "free" && (
        <div className="plan-upgrade">
          {checkoutAvailable ? (
            <>
              <div className="plan-upgrade-copy">
                <div className="plan-upgrade-title">{uiText("Pro 구독", "Subscribe to Pro")}</div>
                <p>{uiText(`${priceLabel()} (부가세 포함)`, `${priceLabel()} (VAT included)`)}</p>
              </div>
              <button
                type="button"
                className="btn btn-primary"
                // 결제를 마친 뒤(done·timeout)에도 막는다. 웹훅이 늦으면 서버는 아직 FREE·구독 없음으로
                // 보고 두 번째 거래를 만들어 주므로, 다시 누르면 실제로 두 번 결제된다.
                disabled={checkoutPhase !== "idle" && checkoutPhase !== "error"}
                onClick={() => void onSubscribe()}
              >
                <BusyLabel
                  busy={checkoutPhase === "opening" || checkoutPhase === "reflecting"}
                  label={uiText(`Pro 구독하기 · ${priceLabel()}`, `Subscribe to Pro · ${priceLabel()}`)}
                  busyLabel={
                    checkoutPhase === "reflecting"
                      ? uiText("결제 반영 중…", "Applying payment…")
                      : uiText("결제창 여는 중…", "Opening checkout…")
                  }
                />
              </button>
              {checkoutPhase === "done" && (
                <p className="plan-note">{uiText("Pro로 전환됐어요.", "You're on Pro now.")}</p>
              )}
              {checkoutPhase === "timeout" && (
                <p className="plan-note">
                  {getUiLang() === "en" ? (
                    <>
                      Payment finished. It can take a few minutes to show up. Don't pay again. If it
                      still doesn't appear, email{" "}
                      <a href={`mailto:${LEGAL_CONTACT_EMAIL}`}>{LEGAL_CONTACT_EMAIL}</a>.
                    </>
                  ) : (
                    <>
                      결제는 완료됐어요. 반영까지 몇 분 걸릴 수 있어요. 다시 결제하지 마시고, 반영이
                      계속 안 되면{" "}
                      <a href={`mailto:${LEGAL_CONTACT_EMAIL}`}>{LEGAL_CONTACT_EMAIL}</a>으로 알려
                      주세요.
                    </>
                  )}
                </p>
              )}
              {checkoutPhase === "error" && (
                <InlineError>
                  {uiText(
                    "결제창을 열지 못했어요. 잠시 후 다시 시도해 주세요.",
                    "Couldn't open checkout. Try again in a moment.",
                  )}
                </InlineError>
              )}
            </>
          ) : (
            <p className="plan-note">{uiText("Pro 구독은 준비 중입니다.", "Pro subscriptions aren't open yet.")}</p>
          )}
          {summary?.lastSubscriptionEndedAt && (
            <p className="plan-note">
              {uiText(
                `지난 구독은 ${formatInstant(summary.lastSubscriptionEndedAt)}에 종료됐어요.`,
                `The last subscription ended on ${formatInstant(summary.lastSubscriptionEndedAt)}.`,
              )}
            </p>
          )}
          <form onSubmit={onSubmit}>
            <div className="plan-upgrade-copy">
              <div className="plan-upgrade-title">{uiText("전환 코드", "Upgrade code")}</div>
              <p>
                {uiText(
                  "코드를 입력하면 한도가 바로 풀립니다.",
                  "Enter a code and the limit lifts right away.",
                )}
              </p>
            </div>
            <Field label={uiText("전환 코드", "Upgrade code")}>
              <div className="plan-upgrade-row">
                <input
                  className="mono"
                  value={code}
                  onChange={(event) => {
                    setCode(event.target.value);
                    upgrade.reset();
                  }}
                  placeholder={uiText("코드를 입력하세요", "Enter a code")}
                  autoComplete="off"
                  spellCheck={false}
                  disabled={upgrade.isPending}
                />
                <button className="btn btn-primary" type="submit" disabled={!canSubmit}>
                  <BusyLabel
                    busy={upgrade.isPending}
                    label={uiText("전환", "Apply")}
                    busyLabel={uiText("전환 중…", "Applying…")}
                  />
                </button>
              </div>
            </Field>
            {upgrade.isError && <InlineError>{upgradeErrorMessage(upgrade.error)}</InlineError>}
          </form>
        </div>
      )}

      {confirmDowngrade && (
        <div className="confirm-overlay" onMouseDown={() => setConfirmDowngrade(false)}>
          <div
            className="confirm-dialog"
            role="dialog"
            aria-modal="true"
            aria-label={uiText("무료 플랜으로 내리기", "Move to the free plan")}
            onMouseDown={(event) => event.stopPropagation()}
            onKeyDown={(event) => {
              if (event.key === "Escape") setConfirmDowngrade(false);
            }}
          >
            <h4 className="confirm-title">
              {uiText("무료 플랜으로 내릴까요?", "Move to the free plan?")}
            </h4>
            <ul className="confirm-points">
              <li>
                <span className="confirm-mark danger" aria-hidden />
                {uiText(
                  "증분 수집이 중단되고, 무료 질의 10회가 새로 시작됩니다.",
                  "Incremental collection stops, and the 10 free queries start over.",
                )}
              </li>
              <li>
                <span className="confirm-mark" aria-hidden />
                {uiText(
                  "다시 Pro로 올리려면 전환 코드가 필요합니다.",
                  "Moving back to Pro needs an upgrade code.",
                )}
              </li>
            </ul>
            {downgrade.isError && (
              <InlineError style={{ marginTop: 12 }}>
                {uiText("내리지 못했어요. 잠시 후 다시 시도해 주세요.", "Couldn't change the plan. Try again in a moment.")}
              </InlineError>
            )}
            <div className="confirm-actions">
              <button
                type="button"
                className="btn btn-ghost"
                onClick={() => setConfirmDowngrade(false)}
                disabled={downgrade.isPending}
              >
                {uiText("취소", "Cancel")}
              </button>
              <button
                type="button"
                className="btn btn-danger"
                autoFocus
                disabled={downgrade.isPending}
                onClick={() =>
                  downgrade.mutate(undefined, { onSuccess: () => setConfirmDowngrade(false) })
                }
              >
                {downgrade.isPending
                  ? uiText("내리는 중…", "Changing…")
                  : uiText("무료 플랜으로 내리기", "Move to the free plan")}
              </button>
            </div>
          </div>
        </div>
      )}
    </section>
  );
}
