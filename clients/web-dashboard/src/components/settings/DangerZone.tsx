import { useEffect, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";

import { Icons } from "@/components/Icons";
import { Field } from "@/components/ui/Field";
import { InlineError } from "@/components/ui/InlineError";
import { deleteAccount } from "@/api/auth";
import { deleteProject } from "@/api/projects";
import { useAuth } from "@/auth/AuthProvider";
import { queryKeys } from "@/hooks/queryKeys";
import type { Project } from "@/types/api";

// 프로젝트 삭제 + 회원 탈퇴 — 되돌릴 수 없는 작업 모음.
export function DangerZone({ project }: { project: Project }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { user, logout } = useAuth();

  const [confirmText, setConfirmText] = useState("");
  const [withdrawEmail, setWithdrawEmail] = useState("");

  // 프로젝트 전환 시 입력값 초기화
  useEffect(() => {
    setConfirmText("");
    setWithdrawEmail("");
  }, [project.id]);

  const deleteMutation = useMutation({
    mutationFn: () => deleteProject(project.id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.projects() });
      navigate("/", { replace: true });
    },
  });

  const withdrawMutation = useMutation({
    mutationFn: deleteAccount,
    onSuccess: async () => {
      // 탈퇴 후 로컬 토큰을 비우고(이미 비활성화된 계정이라 logout API 실패는 무시됨) 로그인 화면으로
      await logout();
      queryClient.clear();
      navigate("/login", { replace: true });
    },
  });

  const canDelete = confirmText === project.name && !deleteMutation.isPending;
  const canWithdraw =
    !!user?.email &&
    withdrawEmail.trim().toLowerCase() === user.email.toLowerCase() &&
    !withdrawMutation.isPending;

  return (
    <>
      {/* ─── 프로젝트 삭제 ─── */}
      <section
        className="source-card"
        style={{ borderColor: "var(--danger)", marginBottom: 16 }}
      >
        <div className="src-head">
          <div style={{ flex: 1 }}>
            <h4 style={{ color: "var(--danger)" }}>프로젝트 삭제</h4>
            <div className="src-sub">
              모든 대화·연동 정보가 영구 삭제됩니다. 되돌릴 수 없어요.
            </div>
          </div>
        </div>

        <div className="connect-form" style={{ display: "block" }}>
          <Field
            label={
              <>
                확인을 위해 프로젝트 이름{" "}
                <span className="mono" style={{ color: "var(--danger)" }}>
                  {project.name}
                </span>
                을 입력하세요
              </>
            }
          >
            <input
              value={confirmText}
              onChange={(e) => setConfirmText(e.target.value)}
              placeholder={project.name}
            />
          </Field>
          {deleteMutation.isError && (
            <InlineError>삭제에 실패했어요. 다시 시도해 주세요.</InlineError>
          )}
        </div>

        <div style={{ display: "flex", justifyContent: "flex-end" }}>
          <button
            className="btn"
            style={{
              background: canDelete ? "var(--danger)" : undefined,
              color: canDelete ? "white" : undefined,
              borderColor: canDelete ? "var(--danger)" : undefined,
            }}
            disabled={!canDelete}
            onClick={() => deleteMutation.mutate()}
          >
            <Icons.Trash size={13} />
            {deleteMutation.isPending ? "삭제 중…" : "프로젝트 영구 삭제"}
          </button>
        </div>
      </section>

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
            <InlineError>탈퇴 처리에 실패했어요. 다시 시도해 주세요.</InlineError>
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
    </>
  );
}
