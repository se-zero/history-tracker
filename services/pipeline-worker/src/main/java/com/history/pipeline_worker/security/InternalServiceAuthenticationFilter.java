package com.history.pipeline_worker.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// pipeline-worker에는 Spring Security가 없어 Boot의 서블릿 필터 자동 등록으로 체인에 들어간다.
// GitHub webhook 경로(/api/v1/webhook/)는 보호 대상에서 제외한다 — GitHub 서버가 직접 호출해 우리
// 헤더를 붙일 수 없고, 대신 GitHubWebhookVerifier의 HMAC 서명 검증(fail-closed)이 이미 지킨다.
@Component
public class InternalServiceAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Internal-Service-Token";
    private static final String COLLECT_PATH_PREFIX = "/api/v1/collect/";
    private static final String RAW_PATH_PREFIX = "/api/v1/raw/";

    private final byte[] expectedToken;

    public InternalServiceAuthenticationFilter(
            @Value("${security.internal-service.token}") String token
    ) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("security.internal-service.token must be configured.");
        }
        this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith(COLLECT_PATH_PREFIX) && !uri.startsWith(RAW_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String providedToken = request.getHeader(HEADER_NAME);
        if (providedToken == null || !MessageDigest.isEqual(
                expectedToken,
                providedToken.getBytes(StandardCharsets.UTF_8)
        )) {
            response.sendError(HttpStatus.UNAUTHORIZED.value());
            return;
        }
        filterChain.doFilter(request, response);
    }
}
