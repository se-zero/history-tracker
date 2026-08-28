const LEGACY_ACCESS_KEY = "ht.access_token";
const LEGACY_REFRESH_KEY = "ht.refresh_token";

let accessToken: string | null = null;

function dropLegacyStorage() {
  localStorage.removeItem(LEGACY_ACCESS_KEY);
  localStorage.removeItem(LEGACY_REFRESH_KEY);
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
