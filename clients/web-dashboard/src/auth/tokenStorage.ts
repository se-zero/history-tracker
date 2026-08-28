const LEGACY_ACCESS_KEY = "ht.access_token";
const LEGACY_REFRESH_KEY = "ht.refresh_token";

let accessToken: string | null = null;

function dropLegacyStorage() {
  try {
    localStorage.removeItem(LEGACY_ACCESS_KEY);
    localStorage.removeItem(LEGACY_REFRESH_KEY);
  } catch {
    // 저장소 차단 브라우저(Brave, 쿠키 전체 차단 등)에서 throw할 수 있다 — 모듈 최상위에서
    // 부르므로 여기서 잡지 않으면 정적 import 체인 전체가 실패해 앱이 백지가 된다.
    // access는 메모리만 쓰므로 레거시 키 삭제 실패는 세션에 영향이 없다.
  }
}

dropLegacyStorage();

// access는 메모리만 — 새로고침하면 쿠키 refresh로 다시 받는다. refresh 원문은 JS가 갖지 않는다.
export const tokenStorage = {
  getAccess(): string | null {
    return accessToken;
  },
  setAccess(token: string): void {
    accessToken = token;
  },
  clear(): void {
    accessToken = null;
    dropLegacyStorage();
  },
};
