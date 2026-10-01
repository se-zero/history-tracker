package com.history.backend.oauth.security;

import java.io.IOException;

import com.history.backend.oauth.McpOAuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

// 미인증 GET /oauth2/authorize를 SPA 허용 화면으로 보낸다. 쿼리 원문을 그대로 실어 SPA가 같은 요청을 재현할 수 있게 한다.
@RequiredArgsConstructor
public class SpaConsentRedirectEntryPoint implements AuthenticationEntryPoint {

    private final McpOAuthProperties mcpOAuthProperties;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        String query = request.getQueryString();
        String location = query == null
                ? mcpOAuthProperties.consentUrl()
                : mcpOAuthProperties.consentUrl() + "?" + query;
        // sendRedirect를 쓰지 않는다: dev에서 Vite 프록시가 Host를 localhost:8080으로 바꿔 보내고
        // forward-headers-strategy: framework가 상대 리다이렉트를 요청 Host 기준 절대 주소로 풀 수 있어,
        // SPA가 없는 백엔드 주소로 튄다. issuer 기준 절대 주소를 직접 쓴다.
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader("Location", location);
    }
}
