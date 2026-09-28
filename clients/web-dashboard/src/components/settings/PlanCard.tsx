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
import { formatInstant } from "@/lib/format";
import { openPaddleCheckout } from "@/lib/paddle";
import { PLAN_FEATURE_ROWS, PRO_MONTHLY_PRICE_KRW } from "@/lib/plans";
import type { BillingSubscriptionView } from "@/api/billing";
import type { Plan } from "@/types/api";

const FREE_FEATURES = PLAN_FEATURE_ROWS.map((row) => ({
  label: row.free.label.ko,
  included: row.free.included,
}));

const PAID_FEATURES = PLAN_FEATURE_ROWS.map((row) => ({
  label: row.pro.label.ko,
  included: row.pro.included,
}));

const PRICE_LABEL = `월 ${PRO_MONTHLY_PRICE_KRW.toLocaleString("ko-KR")}원`;

type CheckoutPhase = "idle" | "opening" | "reflecting" | "done" | "timeout" | "error";

function planLabel(plan: Plan | undefined) {
  return plan === "PAID" ? "Pro" : "Free";
}

function upgradeErrorMessage(error: unknown) {
  if (!axios.isAxiosError(error)) return "전환에 실패했어요. 다시 시도해 주세요.";
  if (error.response?.status === 403) return "코드가 올바르지 않아요.";
  return "전환에 실패했어요. 다시 시도해 주세요.";
}

function subscriptionCopy(subscription: BillingSubscriptionView) {
  if (subscription.status === "past_due") return "결제 수단을 확인하세요.";
  if (subscription.cancelScheduledAt) {
    return `${formatInstant(subscription.cancelScheduledAt)}에 해지 예정 — 그때까지 Pro`;
  }
  if (subscription.currentPeriodEndsAt) {
    return `다음 결제일 ${formatInstant(subscription.currentPeriodEndsAt)}`;
  }
  return "구독이 활성화되어 있습니다.";
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
  const mode = billing.isLoading
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
  const features = mode === "free" ? FREE_FEATURES : PAID_FEATURES;
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
      setPortalError("포털을 열지 못했어요. 잠시 후 다시 시도해 주세요.");
    }
  };

  const subCopy =
    mode === "subscription" && subscription
      ? subscriptionCopy(subscription)
      : mode === "subscription"
        ? "구독 상태가 곧 반영됩니다."
        : mode === "code"
          ? "전환 코드로 활성화된 Pro"
          : mode === "loading"
            ? "플랜 정보를 불러오는 중…"
            : mode === "error"
              ? "결제 정보를 불러오지 못했어요."
              : remaining == null
                ? "무료 한도가 적용 중입니다."
                : `질의 ${remaining}회 남음`;

  return (
    <section className="source-card plan-card">
      <div className="src-head">
        <div className="src-head-main">
          <h4>플랜</h4>
          <div className="src-sub">{subCopy}</div>
        </div>
        <span className={`badge ${isPaid ? "accent" : ""}`}>
          {isPaid && <Icons.Sparkle size={11} />}
          {planLabel(user.plan)}
        </span>
      </div>

      {mode === "free" && remaining != null && queryLimit != null && (
        <div className="plan-usage">
          <div className="plan-usage-meta">
            <span>질의 사용량</span>
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
            aria-label="질의 사용량"
          >
            <div
              className="plan-usage-fill"
              style={{ width: `${Math.max(0, Math.min(1, usageRatio)) * 100}%` }}
            />
          </div>
        </div>
      )}

      {mode !== "loading" && mode !== "error" && (
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
            결제 수단·영수증
          </button>
          {subscription.cancelScheduledAt == null && (
            <button
              type="button"
              className="btn btn-ghost"
              disabled={portal.isPending}
              onClick={() => void openPortal("cancel")}
            >
              구독 해지
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
            무료 플랜으로 내리기
          </button>
        </div>
      )}

      {mode === "free" && (
        <div className="plan-upgrade">
          {checkoutAvailable ? (
            <>
              <div className="plan-upgrade-copy">
                <div className="plan-upgrade-title">Pro 구독</div>
                <p>{PRICE_LABEL} (부가세 포함)</p>
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
                  label={`Pro 구독하기 · ${PRICE_LABEL}`}
                  busyLabel={checkoutPhase === "reflecting" ? "결제 반영 중…" : "결제창 여는 중…"}
                />
              </button>
              {checkoutPhase === "done" && <p className="plan-note">Pro로 전환됐어요.</p>}
              {checkoutPhase === "timeout" && (
                <p className="plan-note">
                  결제는 완료됐어요. 반영까지 몇 분 걸릴 수 있어요. 다시 결제하지 마시고, 반영이
                  계속 안 되면{" "}
                  <a href={`mailto:${LEGAL_CONTACT_EMAIL}`}>{LEGAL_CONTACT_EMAIL}</a>으로 알려
                  주세요.
                </p>
              )}
              {checkoutPhase === "error" && (
                <InlineError>결제창을 열지 못했어요. 잠시 후 다시 시도해 주세요.</InlineError>
              )}
            </>
          ) : (
            <p className="plan-note">Pro 구독은 준비 중입니다.</p>
          )}
          {summary?.lastSubscriptionEndedAt && (
            <p className="plan-note">
              지난 구독은 {formatInstant(summary.lastSubscriptionEndedAt)}에 종료됐어요.
            </p>
          )}
          <form onSubmit={onSubmit}>
            <div className="plan-upgrade-copy">
              <div className="plan-upgrade-title">전환 코드</div>
              <p>코드를 입력하면 한도가 바로 풀립니다.</p>
            </div>
            <Field label="전환 코드">
              <div className="plan-upgrade-row">
                <input
                  value={code}
                  onChange={(event) => {
                    setCode(event.target.value);
                    upgrade.reset();
                  }}
                  placeholder="코드를 입력하세요"
                  autoComplete="off"
                  spellCheck={false}
                  disabled={upgrade.isPending}
                />
                <button className="btn btn-primary" type="submit" disabled={!canSubmit}>
                  <BusyLabel busy={upgrade.isPending} label="전환" busyLabel="전환 중…" />
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
            aria-label="무료 플랜으로 내리기"
            onMouseDown={(event) => event.stopPropagation()}
            onKeyDown={(event) => {
              if (event.key === "Escape") setConfirmDowngrade(false);
            }}
          >
            <h4 className="confirm-title">무료 플랜으로 내릴까요?</h4>
            <ul className="confirm-points">
              <li>
                <span className="confirm-mark danger" aria-hidden />
                증분 수집이 중단되고, 무료 질의 10회가 새로 시작됩니다.
              </li>
              <li>
                <span className="confirm-mark" aria-hidden />
                다시 Pro로 올리려면 전환 코드가 필요합니다.
              </li>
            </ul>
            {downgrade.isError && (
              <InlineError style={{ marginTop: 12 }}>
                내리지 못했어요. 잠시 후 다시 시도해 주세요.
              </InlineError>
            )}
            <div className="confirm-actions">
              <button
                type="button"
                className="btn btn-ghost"
                onClick={() => setConfirmDowngrade(false)}
                disabled={downgrade.isPending}
              >
                취소
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
                {downgrade.isPending ? "내리는 중…" : "무료 플랜으로 내리기"}
              </button>
            </div>
          </div>
        </div>
      )}
    </section>
  );
}
