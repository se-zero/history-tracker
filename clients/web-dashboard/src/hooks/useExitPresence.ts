import { useState } from "react";

// 퇴장 키프레임 이름 — graph.css @keyframes detail-fade-out과 일치해야 한다(문자열 계약, 여기가 TS 단일 출처).
export const EXIT_ANIMATION = "detail-fade-out";

// 상세 패널(NodeDetail·ConstellationDetail) 공용 — value가 null이 돼도 마지막 값을 유지해
// 퇴장 애니메이션이 재생될 시간을 번다. 실제 언마운트(shown을 null로)는 onExitAnimationEnd에서 한다.
export function useExitPresence<T>(value: T | null) {
  const [shown, setShown] = useState(value);
  if (value && value !== shown) {
    // 렌더 중 상태 조정 — useEffect로 하면 한 프레임 이전 값이 비친다.
    setShown(value);
  }
  const exiting = !value && shown != null;
  const onExitAnimationEnd = (e: React.AnimationEvent) => {
    if (e.animationName === EXIT_ANIMATION) setShown(null);
  };
  return { shown, exiting, onExitAnimationEnd };
}
