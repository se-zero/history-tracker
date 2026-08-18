import { useLayoutEffect, useRef, useState } from "react";

// 접힌 목록의 높이를 "N번째 행의 실제 바닥"으로 실측한다 — 행 높이는 배지 줄바꿈·폰트에
// 따라 변해서 고정 px 추정은 마지막 행이 어중간하게 잘린다.
export function useCollapsedRows(visibleCount: number, itemCount: number) {
  const ref = useRef<HTMLDivElement>(null);
  const [maxHeight, setMaxHeight] = useState<number | undefined>(undefined);

  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const measure = () => {
      const rows = Array.from(el.children) as HTMLElement[];
      if (rows.length <= visibleCount) {
        setMaxHeight(undefined);
        return;
      }
      const last = rows[visibleCount - 1];
      setMaxHeight(last.offsetTop + last.offsetHeight);
    };
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(el);
    return () => observer.disconnect();
  }, [visibleCount, itemCount]);

  return { ref, maxHeight };
}
