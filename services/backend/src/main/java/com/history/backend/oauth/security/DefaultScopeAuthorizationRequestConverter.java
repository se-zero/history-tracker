package com.history.backend.oauth.security;

import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2AuthorizationCodeRequestAuthenticationConverter;
import org.springframework.security.web.authentication.AuthenticationConverter;

// scope 없이 오는 인가 요청에 기본 scope(mcp:query)를 부여한다. 없으면 Spring이 빈 권한 토큰을
// 발급해 그 토큰으로 /mcp를 호출했을 때 403이 난다(사용자 결정 A안).
public class DefaultScopeAuthorizationRequestConverter implements AuthenticationConverter {

    public static final String DEFAULT_SCOPE = "mcp:query";

    private final OAuth2AuthorizationCodeRequestAuthenticationConverter delegate =
            new OAuth2AuthorizationCodeRequestAuthenticationConverter();

    @Override
    public Authentication convert(HttpServletRequest request) {
        Authentication authentication = delegate.convert(request);
        if (authentication instanceof OAuth2AuthorizationCodeRequestAuthenticationToken token && token.getScopes().isEmpty()) {
            return new OAuth2AuthorizationCodeRequestAuthenticationToken(
                    token.getAuthorizationUri(),
                    token.getClientId(),
                    (Authentication) token.getPrincipal(),
                    token.getRedirectUri(),
                    token.getState(),
                    Set.of(DEFAULT_SCOPE),
                    token.getAdditionalParameters());
        }
        return authentication;
    }
}
