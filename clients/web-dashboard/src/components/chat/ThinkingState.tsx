import { useUiLanguage } from "@/i18n/language";

export function ThinkingState() {
  const { lang } = useUiLanguage();
  return (
    <div className="msg assistant">
      <div className="msg-body">
        <div className="thinking">
          <span className="spinner" />
          <span>{lang === "en" ? "Working…" : "처리 중…"}</span>
        </div>
      </div>
    </div>
  );
}
