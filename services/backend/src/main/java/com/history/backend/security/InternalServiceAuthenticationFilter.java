package com.history.backend.security;

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
import org.springframework.web.util.UrlPathHelper;

@Component
public class InternalServiceAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Internal-Service-Token";
    private static final String INTERNAL_PATH_PREFIX = "/api/v1/internal/";

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
        // getRequestURI()는 percent-encoding을 디코딩하지 않은 원문이라, Spring MVC가 컨트롤러를
        // 매칭할 때 쓰는 디코딩된 경로와 다를 수 있다(예: /api/v1/%69nternal/ → 필터는 못 알아보지만
        // 컨트롤러는 /internal/로 라우팅한다). UrlPathHelper로 같은 디코딩을 거쳐야 우회가 없다.
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        return !path.startsWith(INTERNAL_PATH_PREFIX);
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
