import { useEffect, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";

import { Icons } from "@/components/Icons";
import { Field } from "@/components/ui/Field";
import { InlineError } from "@/components/ui/InlineError";
import { deleteProject } from "@/api/projects";
import { queryKeys } from "@/hooks/queryKeys";
import type { Project } from "@/types/api";

// 프로젝트 삭제 — 되돌릴 수 없는 작업. 회원 탈퇴(계정 단위)는 계정 설정으로 분리됐다.
export function DangerZone({ project }: { project: Project }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [confirmText, setConfirmText] = useState("");

  // 프로젝트 전환 시 입력값 초기화
  useEffect(() => {
    setConfirmText("");
  }, [project.id]);

  const deleteMutation = useMutation({
    mutationFn: () => deleteProject(project.id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.projects() });
      navigate("/", { replace: true });
    },
  });

  const canDelete = confirmText === project.name && !deleteMutation.isPending;

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
    </>
  );
}
