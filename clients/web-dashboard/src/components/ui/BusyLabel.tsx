import type { ReactNode } from "react";

// 기본 라벨은 항상 렌더돼 버튼 폭을 결정한다(busy여도 visibility만 숨김 — DOM에서 사라지지
// 않으므로 폭이 유지된다). busy일 때는 같은 grid 셀에 겹친 스피너가 그 자리를 대신 채우는데,
// 스피너가 어떤 라벨보다 항상 좁아 버튼 폭이 절대 넓어지지 않는다 — "더 넓은 라벨 기준으로
// 폭을 예약"하던 이전 방식과 달리 폭 예약 자체가 없다. 스크린리더에는 busyLabel 문구를
// sr-only로 병행 노출한다.
export function BusyLabel({
  busy,
  label,
  busyLabel,
}: {
  busy: boolean;
  label: ReactNode;
  busyLabel: ReactNode;
}) {
  return (
    <span className="busy-label">
      <span className={busy ? "busy-label-hidden" : undefined}>{label}</span>
      {busy && (
        <>
          <span className="busy-spinner" aria-hidden="true" />
          <span className="sr-only">{busyLabel}</span>
        </>
      )}
    </span>
  );
}
