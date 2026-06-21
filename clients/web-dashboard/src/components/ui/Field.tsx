import type { ReactNode } from "react";

// label + 입력 + (선택) hint 로 구성되는 폼 필드 래퍼. 입력 요소는 children으로 그대로 받는다.
export function Field({
  label,
  hint,
  children,
}: {
  label: ReactNode;
  hint?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className="field">
      <label>{label}</label>
      {children}
      {hint && <span className="hint">{hint}</span>}
    </div>
  );
}
