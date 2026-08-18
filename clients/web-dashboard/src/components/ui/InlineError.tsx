import type { CSSProperties, ReactNode } from "react";

// 폼·카드 안에 인라인으로 붙는 danger 톤 안내문. 여백 등 변형은 style로 덮어쓴다.
export function InlineError({
  children,
  style,
  role,
}: {
  children: ReactNode;
  style?: CSSProperties;
  role?: string;
}) {
  return (
    <div role={role} style={{ color: "var(--danger)", fontSize: 12, ...style }}>
      {children}
    </div>
  );
}
