import type { Project } from "@/types/api";

function Placeholder({ title, body }: { title: string; body: string }) {
  return (
    <div
      style={{
        flex: 1,
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        justifyContent: "center",
        gap: 8,
        padding: 32,
      }}
    >
      <h2 style={{ margin: 0 }}>{title}</h2>
      <p style={{ color: "var(--fg-muted)", margin: 0, textAlign: "center" }}>
        {body}
      </p>
    </div>
  );
}

export function SettingsPagePlaceholder({ project }: { project: Project }) {
  return (
    <Placeholder
      title={`${project.name} · 설정`}
      body="SettingsPage는 Phase 5에서 PUT/DELETE /projects/{id}와 연결됩니다."
    />
  );
}
