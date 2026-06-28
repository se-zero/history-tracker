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
}

export function ChatStream({
  children,
  conversationId,
  messages,
  pending,
  isLoadingOlder = false,
  olderError = false,
  onReachTop,
}: ChatStreamProps) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const topSentinel = useRef<HTMLDivElement>(null);

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
