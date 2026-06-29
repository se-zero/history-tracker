import { useEffect, useLayoutEffect, useRef } from "react";

import type { Message } from "@/types/api";

interface ChatStreamProps {
  children: React.ReactNode;
  conversationId?: string;
  messages: Message[];
  pending: boolean;
  isLoadingOlder?: boolean;
  olderError?: boolean;
  onReachTop?: () => void;
  // 화면 최상단에 보이는 assistant 답변의 id를 알린다 (그래프 패널이 따라온다).
  onActiveMessageChange?: (id: string | null) => void;
}

export function ChatStream({
  children,
  conversationId,
  messages,
  pending,
  isLoadingOlder = false,
  olderError = false,
  onReachTop,
  onActiveMessageChange,
}: ChatStreamProps) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const topSentinel = useRef<HTMLDivElement>(null);
  const activeIdRef = useRef<string | null>(null);

  const prevConvo = useRef<string | undefined>(undefined);
  const prevFirstId = useRef<string | undefined>(undefined);
  const prevCount = useRef(0);
  const prevPending = useRef(false);
  const prevScrollHeight = useRef(0);

  const firstId = messages[0]?.id;
  const count = messages.length;

  // 대화 진입·전송·응답은 하단 고정, older prepend는 늘어난 높이만큼 보정해 보던 위치를 유지한다.
  useLayoutEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    if (conversationId !== prevConvo.current) {
      el.scrollTop = el.scrollHeight;
    } else if (
      prevFirstId.current !== undefined &&
      firstId !== prevFirstId.current &&
      count > prevCount.current
    ) {
      // 위쪽에 메시지가 붙어 scrollHeight가 늘어난 만큼 내려, 보던 콘텐츠를 그대로 유지
      el.scrollTop += el.scrollHeight - prevScrollHeight.current;
    } else if (count > prevCount.current || (pending && !prevPending.current)) {
      el.scrollTop = el.scrollHeight;
    }
    prevConvo.current = conversationId;
    prevFirstId.current = firstId;
    prevCount.current = count;
    prevPending.current = pending;
    prevScrollHeight.current = el.scrollHeight;
  });

  // 화면 최상단에 보이는 assistant 답변을 추적한다 — 스크롤하면 그래프 패널이 그 답변을 따라온다.
  // rAF로 스로틀하고, DOM 순서상 컨테이너 상단을 아직 지나지 않은(bottom이 top보다 아래) 첫 답변을 고른다.
  useEffect(() => {
    const root = scrollRef.current;
    if (!root || !onActiveMessageChange) return;
    let raf = 0;
    const compute = () => {
      raf = 0;
      const rootTop = root.getBoundingClientRect().top;
      const els = root.querySelectorAll<HTMLElement>('[data-role="ASSISTANT"]');
      let activeId: string | null = null;
      for (let i = 0; i < els.length; i++) {
        if (els[i].getBoundingClientRect().bottom > rootTop + 8) {
          activeId = els[i].dataset.messageId ?? null;
          break;
        }
      }
      // 모든 답변이 위로 지나갔으면 마지막 답변을 유지한다.
      if (activeId === null && els.length > 0) {
        activeId = els[els.length - 1].dataset.messageId ?? null;
      }
      if (activeId !== activeIdRef.current) {
        activeIdRef.current = activeId;
        onActiveMessageChange(activeId);
      }
    };
    const onScroll = () => {
      if (raf) return;
      raf = requestAnimationFrame(compute);
    };
    root.addEventListener("scroll", onScroll, { passive: true });
    compute(); // 진입·메시지 변경 시 1회 초기화
    return () => {
      root.removeEventListener("scroll", onScroll);
      if (raf) cancelAnimationFrame(raf);
    };
  }, [onActiveMessageChange, messages]);

  // 상단 sentinel이 보이면(위로 스크롤 도달) 더 오래된 메시지 로드를 트리거한다.
  useEffect(() => {
    const root = scrollRef.current;
    const target = topSentinel.current;
    if (!root || !target || !onReachTop) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting) onReachTop();
      },
      { root, rootMargin: "200px 0px 0px 0px", threshold: 0 },
    );
    observer.observe(target);
    return () => observer.disconnect();
  }, [onReachTop]);

  return (
    <div className="chat-stream-wrap">
      {isLoadingOlder ? (
        <div className="chat-older-spinner">이전 메시지 불러오는 중…</div>
      ) : olderError ? (
        <div className="chat-older-error">이전 메시지 불러오기 실패</div>
      ) : null}
      <div className="chat-stream" ref={scrollRef}>
        <div ref={topSentinel} aria-hidden className="chat-top-sentinel" />
        <div className="chat-inner">{children}</div>
      </div>
    </div>
  );
}
