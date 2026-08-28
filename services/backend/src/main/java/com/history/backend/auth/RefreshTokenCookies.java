package com.history.backend.auth;

import java.time.Duration;

import org.springframework.http.ResponseCookie;

// 로그인 refresh는 JS가 읽지 못하는 httpOnly 쿠키로만 내려보낸다. Path를 /api/v1/auth 로 좁혀
// 일반 API 호출에는 쿠키가 안 붙게 한다(CSRF 표면). SameSite=Lax + POST라 다른 사이트에서
// refresh/logout을 보내도 브라우저가 쿠키를 안 실어 준다.
public final class RefreshTokenCookies {

    public static final String NAME = "ht_refresh";
    public static final String PATH = "/api/v1/auth";

    private RefreshTokenCookies() {
    }

    public static ResponseCookie issue(String rawToken, boolean secure, Duration maxAge) {
        return base(rawToken, secure).maxAge(maxAge).build();
    }

    public static ResponseCookie clear(boolean secure) {
        return base("", secure).maxAge(0).build();
    }

    private static ResponseCookie.ResponseCookieBuilder base(String value, boolean secure) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path(PATH);
    }
}
