import type { ReactNode } from "react";

import { Icons } from "@/components/Icons";

type Tone = "loading" | "error" | "empty" | "info";

interface Props {
  tone?: Tone;
  title?: string;
  description?: ReactNode;
  action?: ReactNode;
  fullPage?: boolean;
}

const TONE_COLOR: Record<Tone, string> = {
  loading: "var(--fg-muted)",
  error: "var(--danger)",
  empty: "var(--fg-subtle)",
  info: "var(--fg-muted)",
};

function ToneIcon({ tone }: { tone: Tone }) {
  switch (tone) {
    case "error":
      return <Icons.X size={20} />;
    case "empty":
      return <Icons.Sparkle size={20} />;
    case "info":
      return <Icons.Sparkle size={20} />;
    case "loading":
    default:
      return <Spinner />;
  }
}

export function StatusView({
  tone = "info",
  title,
  description,
  action,
  fullPage = false,
}: Props) {
  return (
    <div
      style={{
        flex: 1,
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        justifyContent: "center",
        gap: 10,
        padding: 32,
        minHeight: fullPage ? "100vh" : undefined,
        color: TONE_COLOR[tone],
        textAlign: "center",
      }}
    >
      <ToneIcon tone={tone} />
      {title && (
        <h3
          style={{
            margin: 0,
            fontSize: 15,
            fontWeight: 600,
            color: "var(--fg)",
          }}
        >
          {title}
        </h3>
      )}
      {description && (
        <div style={{ fontSize: 13, maxWidth: 420, lineHeight: 1.5 }}>
          {description}
        </div>
      )}
      {action && <div style={{ marginTop: 6 }}>{action}</div>}
    </div>
  );
}

function Spinner() {
  return (
    <div
      style={{
        width: 18,
        height: 18,
        border: "2px solid var(--border)",
        borderTopColor: "var(--primary)",
        borderRadius: "50%",
        animation: "ht-spin 0.8s linear infinite",
      }}
    >
      <style>{`@keyframes ht-spin { to { transform: rotate(360deg); } }`}</style>
    </div>
  );
}
