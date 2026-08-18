import type { CSSProperties, ReactNode } from "react";

// surface-2 배경의 모노스페이스 칩 — 본문 안에서 프로젝트명 같은 식별자를 강조할 때.
const chipStyle: CSSProperties = {
  background: "var(--surface-2)",
  padding: "1px 6px",
  borderRadius: 4,
  fontSize: 12,
};

export function MonoChip({
  children,
  style,
}: {
  children: ReactNode;
  style?: CSSProperties;
}) {
  return (
    <span className="mono" style={style ? { ...chipStyle, ...style } : chipStyle}>
      {children}
    </span>
  );
}
