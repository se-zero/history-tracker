import { useEffect, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";

import { Field } from "@/components/ui/Field";
import { InlineError } from "@/components/ui/InlineError";
import { MonoChip } from "@/components/ui/MonoChip";
import { DangerZone } from "@/components/settings/DangerZone";
import { updateProject } from "@/api/projects";
import { queryKeys } from "@/hooks/queryKeys";
import { uiText } from "@/i18n/language";
import type { Project } from "@/types/api";

export function SettingsPage({ project }: { project: Project }) {
  const queryClient = useQueryClient();

  const [name, setName] = useState(project.name);
  const [description, setDescription] = useState(project.description ?? "");

  // 프로젝트 전환 시 입력값 초기화
  useEffect(() => {
    setName(project.name);
    setDescription(project.description ?? "");
  }, [project.id]);

  const updateMutation = useMutation({
    mutationFn: () =>
      updateProject(project.id, {
        name: name.trim(),
        description: description.trim() || undefined,
      }),
    onSuccess: (updated) => {
      queryClient.setQueryData(queryKeys.projects(), (prev: Project[] | undefined) =>
        prev?.map((p) => (p.id === updated.id ? updated : p)),
      );
      queryClient.invalidateQueries({ queryKey: queryKeys.projects() });
    },
  });

  const dirty =
    name.trim() !== project.name ||
    description.trim() !== (project.description ?? "");
  const canSave = dirty && name.trim().length > 0 && !updateMutation.isPending;

  return (
    <div className="sources-page">
      <h1 className="page-title">{uiText("설정", "Settings")}</h1>
      <p className="page-sub">
        <MonoChip>{project.name}</MonoChip>{" "}
        {uiText("· 프로젝트 정보 수정과 삭제.", "· Edit or delete this project.")}
      </p>

      {/* ─── 프로젝트 정보 ─── */}
      <section className="source-card" style={{ marginBottom: 16 }}>
        <div className="src-head">
          <div style={{ flex: 1 }}>
            <h4>{uiText("프로젝트 정보", "Project details")}</h4>
            <div className="src-sub">{uiText("이름과 설명을 수정합니다.", "Change the name and description.")}</div>
          </div>
        </div>

        <div className="connect-form" style={{ display: "block" }}>
          <Field label={uiText("이름", "Name")}>
            <input
              value={name}
              maxLength={200}
              onChange={(e) => setName(e.target.value)}
              placeholder={uiText("예: Payments Platform", "Example: Payments Platform")}
            />
          </Field>
          <Field label={uiText("설명 (선택)", "Description (optional)")}>
            <input
              value={description}
              maxLength={2000}
              onChange={(e) => setDescription(e.target.value)}
              placeholder={uiText(
                "예: 결제 도메인 전반(주문, 정산, 환불)",
                "Example: the payments domain (orders, settlement, refunds)",
              )}
            />
          </Field>
          {updateMutation.isError && (
            <InlineError>
              {uiText("저장에 실패했어요. 다시 시도해 주세요.", "Couldn't save. Try again.")}
            </InlineError>
          )}
          {updateMutation.isSuccess && !dirty && (
            <div style={{ color: "var(--success)", fontSize: 12 }}>
              {uiText("저장되었습니다.", "Saved.")}
            </div>
          )}
        </div>

        <div style={{ display: "flex", gap: 8, justifyContent: "flex-end" }}>
          <button
            className="btn btn-ghost"
            disabled={!dirty || updateMutation.isPending}
            onClick={() => {
              setName(project.name);
              setDescription(project.description ?? "");
              updateMutation.reset();
            }}
          >
            {uiText("되돌리기", "Revert")}
          </button>
          <button
            className="btn btn-primary"
            disabled={!canSave}
            onClick={() => updateMutation.mutate()}
          >
            {updateMutation.isPending
              ? uiText("저장 중…", "Saving…")
              : uiText("변경사항 저장", "Save changes")}
          </button>
        </div>
      </section>

      <DangerZone project={project} />
    </div>
  );
}
