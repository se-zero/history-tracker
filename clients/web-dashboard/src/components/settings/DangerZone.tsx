import { useEffect, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";

import { Icons } from "@/components/Icons";
import { Field } from "@/components/ui/Field";
import { InlineError } from "@/components/ui/InlineError";
import { deleteProject } from "@/api/projects";
import { queryKeys } from "@/hooks/queryKeys";
import { uiText } from "@/i18n/language";
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
      <section className="source-card source-card--danger">
        <div className="src-head">
          <div style={{ flex: 1 }}>
            <h4>{uiText("프로젝트 삭제", "Delete project")}</h4>
            <div className="src-sub">
              {uiText(
                "모든 대화·연동 정보가 영구 삭제됩니다. 되돌릴 수 없어요.",
                "All conversations and connections are deleted permanently. This can't be undone.",
              )}
            </div>
          </div>
        </div>

        <div className="connect-form" style={{ display: "block" }}>
          <Field
            label={
              <>
                {uiText("확인을 위해 프로젝트 이름 ", "Type the project name ")}
                <span className="mono text-danger">{project.name}</span>
                {uiText("을 입력하세요", " to confirm")}
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
            <InlineError>
              {uiText("삭제에 실패했어요. 다시 시도해 주세요.", "Couldn't delete. Try again.")}
            </InlineError>
          )}
        </div>

        <div style={{ display: "flex", justifyContent: "flex-end" }}>
          <button
            className="btn btn-danger-solid"
            disabled={!canDelete}
            onClick={() => deleteMutation.mutate()}
          >
            <Icons.Trash size={13} />
            {deleteMutation.isPending
              ? uiText("삭제 중…", "Deleting…")
              : uiText("프로젝트 영구 삭제", "Delete project permanently")}
          </button>
        </div>
      </section>
    </>
  );
}
