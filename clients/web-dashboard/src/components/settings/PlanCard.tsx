import { useState, type FormEvent } from "react";
import { useMutation } from "@tanstack/react-query";
import axios from "axios";

import { Icons } from "@/components/Icons";
import { BusyLabel } from "@/components/ui/BusyLabel";
import { Field } from "@/components/ui/Field";
import { InlineError } from "@/components/ui/InlineError";
import { upgradePlan } from "@/api/auth";
import { useAuth } from "@/auth/AuthProvider";
import { PLAN_FEATURE_ROWS } from "@/lib/plans";
import type { Plan } from "@/types/api";

// 앱은 한국어 고정 UI라 `.ko`만 읽는다 — 문구는 lib/plans.ts가 단일 출처이므로
// 요금 페이지(/pricing)와 여기가 갈라지지 않는다.
const FREE_FEATURES = PLAN_FEATURE_ROWS.map((row) => ({
  label: row.free.label.ko,
  included: row.free.included,
}));

const PAID_FEATURES = PLAN_FEATURE_ROWS.map((row) => ({
  label: row.pro.label.ko,
  included: row.pro.included,
}));

function planLabel(plan: Plan | undefined) {
  return plan === "PAID" ? "Pro" : "Free";
}

function errorMessage(error: unknown) {
  if (!axios.isAxiosError(error)) return "전환에 실패했어요. 다시 시도해 주세요.";
  const status = error.response?.status;
  if (status === 403) return "코드가 올바르지 않아요.";
  return "전환에 실패했어요. 다시 시도해 주세요.";
}

// 계정 플랜 현황과 유료 전환. 결제는 후순위라 공유 코드가 전환 수단이다.
export function PlanCard() {
  const { user, refresh } = useAuth();
  const [code, setCode] = useState("");

  const upgradeMutation = useMutation({
    mutationFn: () => upgradePlan(code.trim()),
    onSuccess: async () => {
      setCode("");
      await refresh();
    },
  });

  if (!user) return null;

  const isPaid = user.plan === "PAID";
  const remaining = user.freeQueryRemaining;
  const queryLimit = user.freeQueryLimit;
  const used =
    remaining == null || queryLimit == null
      ? 0
      : Math.min(queryLimit, queryLimit - remaining);
  const usageRatio = remaining == null || queryLimit == null ? 0 : used / queryLimit;
  const features = isPaid ? PAID_FEATURES : FREE_FEATURES;
  const canSubmit = code.trim().length > 0 && !upgradeMutation.isPending;

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    if (!canSubmit) return;
    upgradeMutation.mutate();
  };

  return (
    <section className="source-card plan-card">
      <div className="src-head">
        <div className="src-head-main">
          <h4>플랜</h4>
          <div className="src-sub">
            {isPaid
              ? "모든 기능이 열려 있습니다."
              : remaining == null
                ? "무료 한도가 적용 중입니다."
                : `질의 ${remaining}회 남음`}
          </div>
        </div>
        <span className={`badge ${isPaid ? "accent" : ""}`}>
          {isPaid && <Icons.Sparkle size={11} />}
          {planLabel(user.plan)}
        </span>
      </div>

      {!isPaid && remaining != null && queryLimit != null && (
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

      {isPaid ? null : (
        <form className="plan-upgrade" onSubmit={onSubmit}>
          <div className="plan-upgrade-copy">
            <div className="plan-upgrade-title">Pro로 전환</div>
            <p>코드를 입력하면 한도가 바로 풀립니다.</p>
          </div>
          <Field label="전환 코드">
            <div className="plan-upgrade-row">
              <input
                value={code}
                onChange={(event) => {
                  setCode(event.target.value);
                  upgradeMutation.reset();
                }}
                placeholder="코드를 입력하세요"
                autoComplete="off"
                spellCheck={false}
                disabled={upgradeMutation.isPending}
              />
              <button className="btn btn-primary" type="submit" disabled={!canSubmit}>
                <BusyLabel
                  busy={upgradeMutation.isPending}
                  label="전환"
                  busyLabel="전환 중…"
                />
              </button>
            </div>
          </Field>
          {upgradeMutation.isError && (
            <InlineError>{errorMessage(upgradeMutation.error)}</InlineError>
          )}
        </form>
      )}
    </section>
  );
}
