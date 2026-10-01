package com.history.backend.oauth.security;

import java.io.IOException;
import java.util.List;

import com.history.backend.oauth.service.ConsentTicketService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

// GET /oauth2/authorize의 티켓 쿠키를 소비해 그 사용자의 요청으로 인증한다. 인가 체인 전용으로 직접 붙이므로
// 빈으로 등록하지 않는다(@Component면 서블릿 컨테이너에도 자동 등록돼 모든 요청에 돈다).
@RequiredArgsConstructor
public class ConsentTicketAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZE_PATH = "/oauth2/authorize";

    private final ConsentTicketService consentTicketService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Cookie ticketCookie = isAuthorizeGet(request) ? findTicketCookie(request) : null;
        if (ticketCookie != null) {
            // 성공·실패와 무관하게 지운다 — 티켓은 1회용이라 남겨 둘 이유가 없다.
            response.addHeader(HttpHeaders.SET_COOKIE, ConsentTicketCookies.clear(request.isSecure()).toString());
            consentTicketService.consume(ticketCookie.getValue(), request.getQueryString())
                    .ifPresent(userId -> {
                        // principal은 반드시 String이다: Spring Authorization Server가 principal을
                        // oauth2_authorization.attributes에 Jackson으로 직렬화하므로, 커스텀 타입을 넣으면 역직렬화가 깨진다.
                        SecurityContext context = SecurityContextHolder.createEmptyContext();
                        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                                userId.toString(), null, List.of()));
                        SecurityContextHolder.setContext(context);
                    });
        }
        chain.doFilter(request, response);
    }

    private boolean isAuthorizeGet(HttpServletRequest request) {
        return "GET".equals(request.getMethod()) && AUTHORIZE_PATH.equals(request.getRequestURI());
    }

    private Cookie findTicketCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (ConsentTicketCookies.NAME.equals(cookie.getName())) {
                return cookie;
            }
        }
        return null;
    }
}
