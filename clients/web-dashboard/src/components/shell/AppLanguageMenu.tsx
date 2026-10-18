import { useEffect, useRef, useState } from "react";

import { Icons } from "@/components/Icons";
import { useUiLanguage, type UiLang } from "@/i18n/language";

const OPTIONS: { value: UiLang; label: string }[] = [
  { value: "ko", label: "한국어" },
  { value: "en", label: "English" },
];

// 앱 상단의 언어 메뉴. 고르는 규칙과 저장 키는 랜딩과 같고, 모양은 앱 토큰을 쓴다.
export function AppLanguageMenu() {
  const { lang, setLang } = useUiLanguage();
  const [open, setOpen] = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    const handleClick = (e: MouseEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", handleClick);
    return () => document.removeEventListener("mousedown", handleClick);
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const handleKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      setOpen(false);
      triggerRef.current?.focus();
    };
    document.addEventListener("keydown", handleKey);
    return () => document.removeEventListener("keydown", handleKey);
  }, [open]);

  return (
    <div className="app-lang-wrap" ref={wrapRef}>
      <button
        type="button"
        ref={triggerRef}
        className={`app-lang-trigger${open ? " open" : ""}`}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={lang === "ko" ? "언어 선택" : "Language"}
        onClick={() => setOpen((v) => !v)}
      >
        <span>{lang.toUpperCase()}</span>
        <Icons.ChevronDown size={12} className="app-lang-chevron" />
      </button>
      {open && (
        <div className="app-lang-menu" role="menu">
          {OPTIONS.map((opt) => (
            <button
              key={opt.value}
              type="button"
              role="menuitemradio"
              aria-checked={lang === opt.value}
              className="dropdown-item"
              onClick={() => {
                setLang(opt.value);
                setOpen(false);
                triggerRef.current?.focus();
              }}
            >
              <span>{opt.label}</span>
              {lang === opt.value && <CheckGlyph />}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

// 언어 선택은 실행이 아니라서 앰버를 쓰지 않는다. 체크는 글자색을 따른다.
function CheckGlyph() {
  return (
    <svg
      className="app-lang-check"
      width={13}
      height={13}
      viewBox="0 0 13 13"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.6}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="M2.5 6.8 5.2 9.5 10.5 3.5" />
    </svg>
  );
}
