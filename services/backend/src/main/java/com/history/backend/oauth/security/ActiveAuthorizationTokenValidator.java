package com.history.backend.oauth.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;

// 연결 행이 살아 있는 access token만 통과시키는 검증기 — 철회·탈퇴·갱신 뒤 남은 JWT를 즉시 끊는다
// 만료 판정은 JwtTimestampValidator(시계 오차 허용)가 맡으므로 여기서 다시 하지 않는다.
public class ActiveAuthorizationTokenValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2TokenValidatorResult REVOKED = OAuth2TokenValidatorResult.failure(
            new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "The token has been revoked.", null));

    private final OAuth2AuthorizationService authorizationService;

    public ActiveAuthorizationTokenValidator(OAuth2AuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        OAuth2Authorization authorization =
                authorizationService.findByToken(jwt.getTokenValue(), OAuth2TokenType.ACCESS_TOKEN);
        if (authorization == null
                || authorization.getAccessToken() == null
                || authorization.getAccessToken().isInvalidated()) {
            return REVOKED;
        }
        return OAuth2TokenValidatorResult.success();
    }
}
