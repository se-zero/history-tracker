import { useEffect, useRef, useState } from "react";

interface UseInViewOnceOptions {
  /** IntersectionObserver threshold. 기본 0.4 — 섹션이 40% 보이면 발동. */
  threshold?: number;
  /**
   * true(기본): 1회 발동 후 unobserve하고 그 상태를 유지한다(스크롤 리빌용).
   * false: 계속 관찰해 뷰포트 진입/이탈마다 inView를 갱신한다(히어로 루프 재생/정지처럼
   * "화면에 있는 동안만"이 필요한 경우).
   */
  once?: boolean;
}

// 랜딩 전용 공용 IntersectionObserver 훅 — 컴포넌트마다 관찰 로직을 중복 구현하지 않기 위해 하나로 둔다.
// prefers-reduced-motion이면 관찰 자체를 건너뛰고 즉시 inView=true를 준다 — 애니메이션 트리거용으로
// 쓰는 쪽에서 "발동 즉시 최종 상태"가 되게 하려는 목적이다(CSS의 reduced-motion 분기와 짝을 이룬다).
export function useInViewOnce<T extends Element>(options: UseInViewOnceOptions = {}) {
  const { threshold = 0.4, once = true } = options;
  const ref = useRef<T | null>(null);
  const [inView, setInView] = useState(false);

  useEffect(() => {
    const node = ref.current;
    if (!node) return;

    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      setInView(true);
      return;
    }

    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          setInView(true);
          if (once) observer.unobserve(node);
        } else if (!once) {
          setInView(false);
        }
      },
      { threshold },
    );
    observer.observe(node);
    return () => observer.disconnect();
  }, [once, threshold]);

  return { ref, inView };
}
