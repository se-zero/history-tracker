package com.history.backend.oauth.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenRevocationAuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

// Spring 폐기 프로바이더(final)는 토큰을 무효 표시하고 save까지만 해 연결 행이 남는다. 그 뒤 이 핸들러가
// 행을 지운다 — 한 트랜잭션이 아닌 2단계라 지우기가 실패해도 토큰은 이미 무효여서 안전하고, 행은 만료 정리로 사라진다.
// 모르는 토큰도 RFC 7009 §2.2대로 200이다. access 토큰 폐기도 연결 해제로 보고 행을 지운다("연결 = 행 하나").
@Slf4j
public class RevokedAuthorizationRemovingHandler implements AuthenticationSuccessHandler {

    private final OAuth2AuthorizationService authorizationService;

    public RevokedAuthorizationRemovingHandler(OAuth2AuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        if (authentication instanceof OAuth2TokenRevocationAuthenticationToken revocation
                && revocation.isAuthenticated()) {
            try {
                OAuth2Authorization authorization = authorizationService.findByToken(revocation.getToken(), null);
                if (authorization != null) {
                    authorizationService.remove(authorization);
                }
            } catch (RuntimeException e) {
                // 토큰은 이미 무효 표시됐고 RFC 7009상 응답은 항상 200이어야 한다 — DB 예외만이 아니라 어떤 런타임 예외도
                // 500으로 바꾸지 않는다. 토큰 값은 남기지 않는다.
                log.warn("폐기된 연결 행 삭제 실패: {}", e.getClass().getSimpleName());
            }
        }
        response.setStatus(HttpStatus.OK.value());
    }
}
