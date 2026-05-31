import { useEffect, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";

import { Icons } from "@/components/Icons";
import { deleteProject, updateProject } from "@/api/projects";
import type { Project } from "@/types/api";

export function SettingsPage({ project }: { project: Project }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [name, setName] = useState(project.name);
  const [description, setDescription] = useState(project.description ?? "");
  const [confirmText, setConfirmText] = useState("");

  // 프로젝트 전환 시 입력값 초기화
  useEffect(() => {
    setName(project.name);
    setDescription(project.description ?? "");
    setConfirmText("");
  }, [project.id]);

  const updateMutation = useMutation({
    mutationFn: () =>
      updateProject(project.id, {
        name: name.trim(),
        description: description.trim() || undefined,
      }),
    onSuccess: (updated) => {
      queryClient.setQueryData(["projects"], (prev: Project[] | undefined) =>
        prev?.map((p) => (p.id === updated.id ? updated : p)),
      );
      queryClient.invalidateQueries({ queryKey: ["projects"] });
    },
  });

  const deleteMutation = useMutation({
    mutationFn: () => deleteProject(project.id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["projects"] });
      navigate("/", { replace: true });
    },
  });

  const dirty =
    name.trim() !== project.name ||
    description.trim() !== (project.description ?? "");
  const canSave = dirty && name.trim().length > 0 && !updateMutation.isPending;
  const canDelete = confirmText === project.name && !deleteMutation.isPending;

  return (
    <div className="sources-page">
      <h1 className="page-title">설정</h1>
      <p className="page-sub">
        <span
          className="mono"
          style={{
            background: "var(--surface-2)",
            padding: "1px 6px",
            borderRadius: 4,
            fontSize: 12,
          }}
        >
          {project.name}
        </span>{" "}
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
          <div className="field">
            <label>이름</label>
            <input
              value={name}
              maxLength={200}
              onChange={(e) => setName(e.target.value)}
              placeholder="Payments Platform"
            />
          </div>
          <div className="field">
            <label>설명 (선택)</label>
            <input
              value={description}
              maxLength={2000}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="결제 도메인 전반(주문, 정산, 환불)"
            />
          </div>
          {updateMutation.isError && (
            <div style={{ color: "var(--danger)", fontSize: 12 }}>
              저장에 실패했어요. 다시 시도해 주세요.
            </div>
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
        <MetaRow label="생성일" value={formatDate(project.createdAt)} />
        <MetaRow label="최근 수정" value={formatDate(project.updatedAt)} />
      </section>

      {/* ─── 위험 영역 ─── */}
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
          <div className="field">
            <label>
              확인을 위해 프로젝트 이름{" "}
              <span className="mono" style={{ color: "var(--danger)" }}>
                {project.name}
              </span>
              을 입력하세요
            </label>
            <input
              value={confirmText}
              onChange={(e) => setConfirmText(e.target.value)}
              placeholder={project.name}
            />
          </div>
          {deleteMutation.isError && (
            <div style={{ color: "var(--danger)", fontSize: 12 }}>
              삭제에 실패했어요. 다시 시도해 주세요.
            </div>
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

function formatDate(iso: string): string {
  try {
    return new Date(iso).toLocaleString("ko-KR");
  } catch {
    return iso;
  }
}
