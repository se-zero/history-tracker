package com.history.backend.oauth.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

// Spring 기본 OAuth2RefreshTokenGenerator는 authorization_code 교환에서 클라이언트가 공개 클라이언트(NONE)면
// refresh 토큰을 발급하지 않는다 — 결정 6(refresh 30일·회전)에 따라 그 조건 없이 항상 발급하는 생성기.
public class PublicClientRefreshTokenGenerator implements OAuth2TokenGenerator<OAuth2RefreshToken> {

    private final StringKeyGenerator keyGenerator =
            new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 96);
    private final Clock clock;

    public PublicClientRefreshTokenGenerator() {
        this(Clock.systemUTC());
    }

    PublicClientRefreshTokenGenerator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public OAuth2RefreshToken generate(OAuth2TokenContext context) {
        if (!OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType())) {
            return null;
        }
        Instant issuedAt = Instant.now(clock);
        Instant expiresAt = issuedAt.plus(context.getRegisteredClient().getTokenSettings().getRefreshTokenTimeToLive());
        return new OAuth2RefreshToken(keyGenerator.generateKey(), issuedAt, expiresAt);
    }
}
