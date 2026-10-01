package com.history.backend.oauth.security;

import com.history.backend.oauth.service.ConsentTicketService;
import org.springframework.http.ResponseCookie;

// 동의 티켓은 JS가 읽지 못하는 httpOnly 쿠키로만 내려보낸다. Path를 /oauth2/authorize 로 좁혀
// 다른 API 호출에는 붙지 않게 하고, 브라우저가 authorize로 돌아올 때만 실린다.
public final class ConsentTicketCookies {

    public static final String NAME = "wc_oauth_ticket";
    public static final String PATH = "/oauth2/authorize";

    private ConsentTicketCookies() {
    }

    public static ResponseCookie issue(String ticket, boolean secure) {
        return base(ticket, secure).maxAge(ConsentTicketService.TTL).build();
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
