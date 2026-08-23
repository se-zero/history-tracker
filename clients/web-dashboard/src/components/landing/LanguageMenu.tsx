import { useEffect, useRef, useState } from "react";

import { useLandingLanguage } from "@/components/landing/LandingLanguageProvider";
import type { LandingLang } from "@/components/landing/LandingLanguageProvider";

const OPTIONS: { value: LandingLang; label: string }[] = [
  { value: "ko", label: "한국어" },
  { value: "en", label: "English" },
];

// 언어 드롭다운 — shell/ProjectSwitcher의 open state + ref + document mousedown 외부 클릭
// 닫기 관행을 그대로 따른다. lang은 props가 아니라 useLandingLanguage()로 직접 읽는다.
export function LanguageMenu() {
  const { lang, setLang } = useLandingLanguage();
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

  // Escape로 닫고 포커스를 트리거로 되돌린다(팝오버 접근성 관례).
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
    <div className="lp-lang-wrap" ref={wrapRef}>
      <button
        type="button"
        ref={triggerRef}
        className={`lp-lang-trigger${open ? " open" : ""}`}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={lang === "ko" ? "언어 선택" : "Language"}
        onClick={() => setOpen((v) => !v)}
      >
        <span className="lp-lang-code">{lang.toUpperCase()}</span>
        <ChevronGlyph />
      </button>
      {open && (
        <div className="lp-lang-menu" role="menu">
          {OPTIONS.map((opt) => (
            <button
              key={opt.value}
              type="button"
              role="menuitemradio"
              aria-checked={lang === opt.value}
              className="lp-lang-item"
              onClick={() => {
                setLang(opt.value);
                setOpen(false);
                // 닫히면서 항목 버튼이 언마운트되므로 포커스를 트리거로 되돌린다(Escape 경로와 동일).
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

// 셰브론 — LandingHeader의 SunGlyph/MoonGlyph와 같은 인라인 SVG 관행(stroke, currentColor).
// 열림 회전은 CSS가 담당한다(`.lp-lang-trigger.open svg`) — 인라인 style로 두면 transition
// 선언(CSS)과 갈라져 짝이 안 보인다(이징 적용 단계에서 ProjectSwitcher 꺾쇠를 같은 이유로
// 클래스 분리한 전례).
function ChevronGlyph() {
  return (
    <svg
      width={12}
      height={12}
      viewBox="0 0 12 12"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.5}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="M2.5 4.5 6 8l3.5-3.5" />
    </svg>
  );
}

// 체크 마크 — 언어 드롭다운은 "라이브/실행/선택" 신호가 아니므로 앰버를 쓰지 않는다
// (DESIGN.md 액센트 규칙). ProjectSwitcher의 체크는 accent-ink를 쓰지만 그건 "선택됨"이 곧
// 프로젝트 전환이라는 실행 맥락이라 규칙에 부합하는 예외고, 언어 선택은 여기 해당하지
// 않으므로 중립색(현재 텍스트 색 상속)으로 둔다.
function CheckGlyph() {
  return (
    <svg
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
