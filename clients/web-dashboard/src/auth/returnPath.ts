const RETURN_PATH_KEY = "ht.return_path";

// 오픈 리다이렉트 방지 — "/"로 시작하고 "//"(스킴 상대 URL)로 시작하지 않고 "\"가 없는
// 같은 출처 상대 경로만 허용한다.
function isSafeReturnPath(path: string): boolean {
  return path.startsWith("/") && !path.startsWith("//") && !path.includes("\\");
}

// 로그인 전에 있던 경로를 저장한다 — GitHub 로그인으로 나가기 직전에 호출한다.
// sessionStorage 접근은 프라이빗 모드 등에서 막힐 수 있어 try/catch로 감싼다.
export function saveReturnPath(path: string): void {
  if (!isSafeReturnPath(path)) return;
  try {
    sessionStorage.setItem(RETURN_PATH_KEY, path);
  } catch {
    // 저장하지 못해도 로그인 콜백은 기본 경로("/")로 돌아간다.
  }
}

// 저장된 복귀 경로를 꺼내고 지운다. 로그인 콜백에서 한 번만 소비한다.
export function consumeReturnPath(): string | null {
  try {
    const path = sessionStorage.getItem(RETURN_PATH_KEY);
    sessionStorage.removeItem(RETURN_PATH_KEY);
    return path && isSafeReturnPath(path) ? path : null;
  } catch {
    return null;
  }
}
