import type { User } from "@/types/api";

// 표시 이름에서 아바타용 이니셜 2자를 뽑는다. 단일 토큰은 앞 2글자, 복수면 첫·끝 토큰의 머리글자.
export function userInitials(user: User | null): string {
  if (!user?.displayName) return "?";
  const tokens = user.displayName.trim().split(/\s+/);
  if (tokens.length === 1) return tokens[0].slice(0, 2).toUpperCase();
  return (tokens[0][0] + tokens[tokens.length - 1][0]).toUpperCase();
}

// "방금 / N분 전 / N시간 전 / N일 전", 일주일 이상은 날짜로.
export function formatRelative(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime();
  const m = Math.floor(diff / 60_000);
  if (m < 1) return "방금";
  if (m < 60) return `${m}분 전`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}시간 전`;
  const d = Math.floor(h / 24);
  if (d < 7) return `${d}일 전`;
  return new Date(iso).toLocaleDateString("ko-KR");
}

// 절대 시각을 로컬 시간 기준 "YYYY-MM-DD HH:mm"으로. DESIGN.md 모노 스코프상 타임스탬프는
// 라틴 기술 토큰이라 로캘 문자열이 아닌 고정 포맷을 쓴다. 파싱 실패 시 원본을 그대로 돌려준다.
export function formatTimestamp(iso: string): string {
  try {
    const d = new Date(iso);
    const pad = (n: number) => String(n).padStart(2, "0");
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  } catch {
    return iso;
  }
}
