package com.history.backend.common.error;

// clearRefreshCookie는 AuthController에서만 의미가 있다 — 그 컨트롤러의 handleUnauthorized가
// true일 때만 ht_refresh를 Max-Age=0으로 지운다. 기본값은 false다: 이 예외는 GitHub 콜백
// 실패처럼 "새 세션을 못 만든다"는 뜻으로도 쓰이는데, 그때 브라우저가 이미 들고 있는 다른
// 유효한 refresh 쿠키(재설치 흐름 중일 수 있다)까지 지우면 안 된다. refresh 쿠키 자체가
// 무효(없음·만료·탈취 재사용)임이 확정된 지점에서만 명시적으로 true를 넘긴다.
public class UnauthorizedException extends RuntimeException {

    private final boolean clearRefreshCookie;

    public UnauthorizedException(String message) {
        this(message, false);
    }

    public UnauthorizedException(String message, boolean clearRefreshCookie) {
        super(message);
        this.clearRefreshCookie = clearRefreshCookie;
    }

    public boolean clearsRefreshCookie() {
        return clearRefreshCookie;
    }
}
