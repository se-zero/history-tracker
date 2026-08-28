import { useState, type FormEvent } from "react";
import { useMutation } from "@tanstack/react-query";
import axios from "axios";

import { Icons } from "@/components/Icons";
import { BusyLabel } from "@/components/ui/BusyLabel";
import { Field } from "@/components/ui/Field";
import { InlineError } from "@/components/ui/InlineError";
import { upgradePlan } from "@/api/auth";
import { useAuth } from "@/auth/AuthProvider";
import type { Plan } from "@/types/api";

// PlanService.FREE_QUERY_LIMIT 과 같은 값 — 사용량 바 분모.
const FREE_QUERY_LIMIT = 10;

const FREE_FEATURES = [
  { label: "프로젝트 1개", included: true },
  { label: "GitHub · Slack · Jira 각 1회", included: true },
  { label: "질의 10회", included: true },
  { label: "증분 수집", included: false },
  { label: "정밀 재구축", included: false },
] as const;

const PAID_FEATURES = [
  { label: "프로젝트 무제한", included: true },
  { label: "모든 소스 연동", included: true },
  { label: "질의 무제한", included: true },
  { label: "증분 수집", included: true },
  { label: "정밀 재구축", included: true },
] as const;

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
  const used =
    remaining == null ? 0 : Math.min(FREE_QUERY_LIMIT, FREE_QUERY_LIMIT - remaining);
  const usageRatio = remaining == null ? 0 : used / FREE_QUERY_LIMIT;
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

      {!isPaid && remaining != null && (
        <div className="plan-usage">
          <div className="plan-usage-meta">
            <span>질의 사용량</span>
            <span className="mono">
              {used} / {FREE_QUERY_LIMIT}
            </span>
          </div>
          <div
            className="plan-usage-track"
            role="meter"
            aria-valuemin={0}
            aria-valuemax={FREE_QUERY_LIMIT}
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
