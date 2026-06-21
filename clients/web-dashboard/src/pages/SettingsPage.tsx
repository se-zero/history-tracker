import { useEffect, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";

import { Field } from "@/components/ui/Field";
import { InlineError } from "@/components/ui/InlineError";
import { MonoChip } from "@/components/ui/MonoChip";
import { DangerZone } from "@/components/settings/DangerZone";
import { updateProject } from "@/api/projects";
import { queryKeys } from "@/hooks/queryKeys";
import { formatDateTime } from "@/lib/format";
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
      <h1 className="page-title">설정</h1>
      <p className="page-sub">
        <MonoChip>{project.name}</MonoChip>{" "}
        · 프로젝트 정보 수정과 삭제.
      </p>

      {/* ─── 프로젝트 정보 ─── */}
      <section className="source-card" style={{ marginBottom: 16 }}>
        <div className="src-head">
          <div style={{ flex: 1 }}>
            <h4>프로젝트 정보</h4>
            <div className="src-sub">이름과 설명을 수정합니다.</div>
          </div>
        </div>

        <div className="connect-form" style={{ display: "block" }}>
          <Field label="이름">
            <input
              value={name}
              maxLength={200}
              onChange={(e) => setName(e.target.value)}
              placeholder="예: Payments Platform"
            />
          </Field>
          <Field label="설명 (선택)">
            <input
              value={description}
              maxLength={2000}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="예: 결제 도메인 전반(주문, 정산, 환불)"
            />
          </Field>
          {updateMutation.isError && (
            <InlineError>저장에 실패했어요. 다시 시도해 주세요.</InlineError>
          )}
          {updateMutation.isSuccess && !dirty && (
            <div style={{ color: "var(--success)", fontSize: 12 }}>
              저장되었습니다.
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
            되돌리기
          </button>
          <button
            className="btn btn-primary"
            disabled={!canSave}
            onClick={() => updateMutation.mutate()}
          >
            {updateMutation.isPending ? "저장 중…" : "변경사항 저장"}
          </button>
        </div>
      </section>

      {/* ─── 메타 정보 ─── */}
      <section className="source-card" style={{ marginBottom: 16 }}>
        <div className="src-head">
          <div style={{ flex: 1 }}>
            <h4>식별자</h4>
            <div className="src-sub">읽기 전용.</div>
          </div>
        </div>
        <MetaRow label="프로젝트 ID" value={project.id} mono />
        <MetaRow label="소유자 ID" value={project.ownerId} mono />
        <MetaRow label="생성일" value={formatDateTime(project.createdAt)} />
        <MetaRow label="최근 수정" value={formatDateTime(project.updatedAt)} />
      </section>

      <DangerZone project={project} />
    </div>
  );
}

function MetaRow({
  label,
  value,
  mono,
}: {
  label: string;
  value: string;
  mono?: boolean;
}) {
  return (
    <div
      style={{
        display: "flex",
        justifyContent: "space-between",
        alignItems: "center",
        padding: "8px 0",
        borderBottom: "1px solid var(--border)",
        fontSize: 13,
      }}
    >
      <span style={{ color: "var(--fg-muted)" }}>{label}</span>
      <span
        className={mono ? "mono" : undefined}
        style={{ fontSize: mono ? 12 : 13 }}
      >
        {value}
      </span>
    </div>
  );
}
