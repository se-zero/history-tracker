import { useEffect, useRef } from "react";

export function ChatStream({ children }: { children: React.ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (ref.current) ref.current.scrollTop = ref.current.scrollHeight;
  }, [children]);
  return (
    <div className="chat-stream" ref={ref}>
      <div className="chat-inner">{children}</div>
    </div>
  );
}
