import { useLayoutEffect, useRef } from "react";

import { Icons } from "@/components/Icons";
import { InlineError } from "@/components/ui/InlineError";
import type { Project } from "@/types/api";

export function Composer({
  project,
  value,
  onChange,
  onSubmit,
  disabled,
  showThinkingHint,
  error,
  notice,
}: {
  project: Project;
  value: string;
  onChange: (value: string) => void;
  onSubmit: () => void;
  disabled: boolean;
  showThinkingHint: boolean;
  error?: string | null;
  // 중립 톤 안내(차단 사유 등) — 에러(InlineError)와 구분되는 정보성 배너
  notice?: string | null;
}) {
  const taRef = useRef<HTMLTextAreaElement>(null);

  // 입력 내용에 맞춰 높이를 늘린다(ChatGPT처럼). max-height(200px)를 넘으면 CSS가 스크롤 처리.
  useLayoutEffect(() => {
    const ta = taRef.current;
    if (!ta) return;
    ta.style.height = "auto";
    ta.style.height = `${ta.scrollHeight}px`;
  }, [value]);

  const onKey = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      onSubmit();
    }
  };

  return (
    <div className="composer">
      <div className="composer-inner">
        {error && (
          <InlineError role="alert" style={{ marginBottom: 6 }}>
            {error}
          </InlineError>
        )}
        {notice && <div className="composer-notice">{notice}</div>}
        <div className="composer-box">
          <textarea
            ref={taRef}
            placeholder={`${project.name}에 무엇이든 물어보세요. Shift+Enter로 줄바꿈`}
            value={value}
            onChange={(e) => onChange(e.target.value)}
            onKeyDown={onKey}
            rows={1}
            disabled={disabled}
          />
          <div className="composer-actions">
            <div className="spacer" />
            <button
              className="btn btn-primary"
              onClick={onSubmit}
              disabled={disabled || !value.trim()}
              style={{ padding: "6px 10px" }}
            >
              <Icons.Send size={13} />
              <span style={{ fontSize: 12 }}>전송</span>
            </button>
          </div>
        </div>
        <div className="composer-foot">
          <span>
            <span className="kbd">Enter</span> 전송
          </span>
          <span>
            <span className="kbd">Shift</span>+<span className="kbd">Enter</span> 줄바꿈
          </span>
          {showThinkingHint && <span>응답을 생성 중…</span>}
        </div>
      </div>
    </div>
  );
}
