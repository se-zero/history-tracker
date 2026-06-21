import { Icons } from "@/components/Icons";

export function ThinkingState() {
  return (
    <div className="msg assistant">
      <div className="msg-avatar">
        <Icons.Sparkle size={14} />
      </div>
      <div className="msg-body">
        <div className="msg-role">History Tracker</div>
        <div className="thinking">
          <span className="spinner" />
          <span>처리 중…</span>
        </div>
      </div>
    </div>
  );
}
