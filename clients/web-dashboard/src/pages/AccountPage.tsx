import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import axios from "axios";
import { useNavigate } from "react-router-dom";

import { ConnectedAppsCard } from "@/components/account/ConnectedAppsCard";
import { GitHubAccessCard } from "@/components/account/GitHubAccessCard";
import { Icons } from "@/components/Icons";
import { Field } from "@/components/ui/Field";
import { InlineError } from "@/components/ui/InlineError";
import { MonoChip } from "@/components/ui/MonoChip";
import { PlanCard } from "@/components/settings/PlanCard";
import { deleteAccount } from "@/api/auth";
import { useAuth } from "@/auth/AuthProvider";
import { useBillingSummary } from "@/hooks/useBilling";
import { formatInstant } from "@/lib/format";
import { PATHS } from "@/routes";

// 계정 단위 설정 — 프로젝트와 무관한 회원 탈퇴 등을 모은다.
export function AccountPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { user, logout } = useAuth();
  const billing = useBillingSummary();

  const [withdrawEmail, setWithdrawEmail] = useState("");

  const withdrawMutation = useMutation({
    mutationFn: deleteAccount,
    onSuccess: async () => {
      // 탈퇴 후 로컬 토큰을 비우고(이미 비활성화된 계정이라 logout API 실패는 무시됨) 랜딩으로
      await logout();
      queryClient.clear();
      navigate(PATHS.landing, { replace: true });
    },
  });

  const canWithdraw =
    !!user?.email &&
    withdrawEmail.trim().toLowerCase() === user.email.toLowerCase() &&
    !withdrawMutation.isPending;

  const subscription =
    billing.data?.planSource === "SUBSCRIPTION" ? billing.data.subscription : null;
  const withdrawNotice = subscription
    ? subscription.status === "past_due"
      ? "연체된 구독은 탈퇴하면 즉시 해지되고, 더 이상 결제되지 않습니다."
      : subscription.currentPeriodEndsAt
        ? `진행 중인 구독은 ${formatInstant(subscription.currentPeriodEndsAt)}에 해지되고 더 이상 결제되지 않습니다. 남은 기간 환불은 contact@why-code.com으로 요청해 주세요.`
        : "진행 중인 구독은 이번 결제 기간이 끝날 때 해지되고 더 이상 결제되지 않습니다. 남은 기간 환불은 contact@why-code.com으로 요청해 주세요."
    : null;
  const withdrawError =
    axios.isAxiosError(withdrawMutation.error) && withdrawMutation.error.response?.status === 502
      ? "구독 해지 처리에 실패해 탈퇴하지 못했어요. 잠시 후 다시 시도해 주세요."
      : "탈퇴 처리에 실패했어요. 다시 시도해 주세요.";

  return (
    <div className="sources-page">
      <h1 className="page-title">계정 설정</h1>
      <p className="page-sub">
        <MonoChip>{user?.email ?? "계정"}</MonoChip> · 플랜과 계정 관리.
      </p>

      <PlanCard />

      <GitHubAccessCard />

      <ConnectedAppsCard />

      {/* ─── 회원 탈퇴 (계정 전체) ─── */}
      <section
        className="source-card"
        style={{ borderColor: "var(--danger)", marginBottom: 16 }}
      >
        <div className="src-head">
          <div style={{ flex: 1 }}>
            <h4 style={{ color: "var(--danger)" }}>회원 탈퇴</h4>
            <div className="src-sub">
              계정과 모든 프로젝트·대화·연동이 삭제됩니다. 일정 기간 후 영구 삭제되며,
              그 전까지는 다시 로그인하면 복구할 수 있어요.
              {withdrawNotice ? ` ${withdrawNotice}` : ""}
            </div>
          </div>
        </div>

        <div className="connect-form" style={{ display: "block" }}>
          <Field
            label={
              <>
                확인을 위해 계정 이메일{" "}
                <span className="mono" style={{ color: "var(--danger)" }}>
                  {user?.email ?? "(이메일 없음)"}
                </span>
                을 입력하세요
              </>
            }
          >
            <input
              value={withdrawEmail}
              onChange={(e) => setWithdrawEmail(e.target.value)}
              placeholder={user?.email ?? ""}
              autoComplete="off"
            />
          </Field>
          {withdrawMutation.isError && (
            <InlineError>{withdrawError}</InlineError>
          )}
        </div>

        <div style={{ display: "flex", justifyContent: "flex-end" }}>
          <button
            className="btn"
            style={{
              background: canWithdraw ? "var(--danger)" : undefined,
              color: canWithdraw ? "white" : undefined,
              borderColor: canWithdraw ? "var(--danger)" : undefined,
            }}
            disabled={!canWithdraw}
            onClick={() => withdrawMutation.mutate()}
          >
            <Icons.Trash size={13} />
            {withdrawMutation.isPending ? "탈퇴 처리 중…" : "회원 탈퇴"}
          </button>
        </div>
      </section>
    </div>
  );
}
