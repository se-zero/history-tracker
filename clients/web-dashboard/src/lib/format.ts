import type { User } from "@/types/api";

// UI 언어 로캘의 단일 출처. 지금은 한국어 고정이고, 언어 분리(i18n) 도입 시 이 상수만
// 설정 훅으로 교체하면 아래 포맷터들이 함께 따라온다 — 호출부에 로캘을 흩뿌리지 않는다.
// **타임존은 이 상수와 무관하다** — 기기 설정이 따로 결정한다(docs/i18n.md).
export const UI_LOCALE = "ko-KR";

// 표시 이름에서 아바타용 이니셜 2자를 뽑는다. 단일 토큰은 앞 2글자, 복수면 첫·끝 토큰의 머리글자.
export function userInitials(user: User | null): string {
  if (!user?.displayName) return "?";
  const tokens = user.displayName.trim().split(/\s+/);
  if (tokens.length === 1) return tokens[0].slice(0, 2).toUpperCase();
  return (tokens[0][0] + tokens[tokens.length - 1][0]).toUpperCase();
}

// "방금 / N분 전 / N시간 전 / N일 전", 일주일 이상은 날짜로.
// i18n 대상: 아래 한국어 리터럴은 언어 분리 시 번역 테이블로 옮긴다(Intl.RelativeTimeFormat 후보).
export function formatRelative(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime();
  const m = Math.floor(diff / 60_000);
  if (m < 1) return "방금";
  if (m < 60) return `${m}분 전`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}시간 전`;
  const d = Math.floor(h / 24);
  if (d < 7) return `${d}일 전`;
  return new Date(iso).toLocaleDateString(UI_LOCALE);
}

// 절대 시각을 로컬 시간 기준 "YYYY-MM-DD HH:mm"으로. DESIGN.md 모노 스코프상 타임스탬프는
// 라틴 기술 토큰이라 로캘 문자열이 아닌 고정 포맷을 쓴다. 파싱 실패 시 원본을 그대로 돌려준다.
export function formatTimestamp(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

// 절대 시각을 문장 안에 녹는 자연어로. 답변 본문(산문)용이며, 인용 카드 같은 UI 크롬은
// 모노 고정 포맷(formatTimestamp)을 계속 쓴다.
//
// **로캘과 타임존은 별개다.** 로캘(표기 언어)은 UI 설정에서 오고, 타임존은 timeZone 옵션을
// 주지 않아 기기 설정이 그대로 적용된다 — "영어 UI를 쓰는 서울 사용자"도 자기 시각을 본다.
// 서버는 어떤 타임존도 가정하지 않고 UTC ISO 정준값만 보낸다(docs/tools.md).
//
// 기본은 분까지다(timeStyle: "short") — 초는 대화형 답변에서 정보 가치가 없다. 잘린 초가
// 필요한 자리(hover 툴팁)는 withSeconds로 같은 로캘·타임존의 정밀 표기를 얻는다.
//   ko-KR → "2026년 3월 12일 오후 5:42"      (withSeconds: "…오후 5:42:50")
//   en-US → "March 12, 2026 at 5:42 PM"      (withSeconds: "…at 5:42:50 PM")
export function formatInstant(
  iso: string,
  opts: { locale?: string; withSeconds?: boolean } = {},
): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return new Intl.DateTimeFormat(opts.locale ?? UI_LOCALE, {
    dateStyle: "long",
    timeStyle: opts.withSeconds ? "medium" : "short",
  }).format(d);
}
