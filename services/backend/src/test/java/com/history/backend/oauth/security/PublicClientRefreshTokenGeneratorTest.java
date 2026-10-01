package com.history.backend.oauth.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.DefaultOAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;

// Spring 기본 OAuth2RefreshTokenGenerator는 authorization_code 교환에서 클라이언트가 공개 클라이언트(NONE)면
// refresh 토큰을 발급하지 않는다(isPublicClientForAuthorizationCodeGrant) — 우리는 결정 6(refresh 30일·회전)에
// 따라 그 조건을 따지지 않고 항상 발급해야 하므로 별도 생성기를 둔다.
@DisplayName("PublicClientRefreshTokenGenerator: 공개 클라이언트에도 항상 refresh 토큰 발급")
class PublicClientRefreshTokenGeneratorTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-06-15T03:00:00Z");
    private static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(30);
    private static final String ISSUER = "http://localhost:5173";

    private final PublicClientRefreshTokenGenerator generator =
            new PublicClientRefreshTokenGenerator(Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));

    @Test
    @DisplayName("REFRESH_TOKEN 타입 → URL-safe 값, issuedAt/expiresAt은 클라이언트 refreshTokenTimeToLive 기준")
    void generateReturnsUrlSafeTokenWithClientTokenSettings() {
        OAuth2TokenContext context = context(
                registeredClient(REFRESH_TOKEN_TTL), OAuth2TokenType.REFRESH_TOKEN,
                AuthorizationGrantType.REFRESH_TOKEN, refreshGrantAuthentication());

        OAuth2RefreshToken token = generator.generate(context);

        assertThat(token).isNotNull();
        assertThat(token.getTokenValue()).isNotBlank().matches("^[A-Za-z0-9_-]+$");
        assertThat(token.getIssuedAt()).isEqualTo(FIXED_INSTANT);
        assertThat(token.getExpiresAt()).isEqualTo(FIXED_INSTANT.plus(REFRESH_TOKEN_TTL));
    }

    @Test
    @DisplayName("호출마다 다른 토큰 값 발급")
    void generateProducesDifferentValueOnEachCall() {
        OAuth2TokenContext context = context(
                registeredClient(REFRESH_TOKEN_TTL), OAuth2TokenType.REFRESH_TOKEN,
                AuthorizationGrantType.REFRESH_TOKEN, refreshGrantAuthentication());

        OAuth2RefreshToken first = generator.generate(context);
        OAuth2RefreshToken second = generator.generate(context);

        assertThat(first.getTokenValue()).isNotEqualTo(second.getTokenValue());
    }

    @Test
    @DisplayName("ACCESS_TOKEN 타입 → null(다른 생성기 몫)")
    void generateReturnsNullForAccessTokenType() {
        OAuth2TokenContext context = context(
                registeredClient(REFRESH_TOKEN_TTL), OAuth2TokenType.ACCESS_TOKEN,
                AuthorizationGrantType.REFRESH_TOKEN, refreshGrantAuthentication());

        assertThat(generator.generate(context)).isNull();
    }

    @Test
    @DisplayName("REFRESH_TOKEN도 ACCESS_TOKEN도 아닌 임의 타입 → null")
    void generateReturnsNullForArbitraryTokenType() {
        OAuth2TokenContext context = context(
                registeredClient(REFRESH_TOKEN_TTL), new OAuth2TokenType("code"),
                AuthorizationGrantType.REFRESH_TOKEN, refreshGrantAuthentication());

        assertThat(generator.generate(context)).isNull();
    }

    // 결함 감지: 구현이 Spring 기본 OAuth2RefreshTokenGenerator에 위임하면 isPublicClientForAuthorizationCodeGrant가
    // true라서 null을 돌려주고 이 테스트가 실패한다.
    @Test
    @DisplayName("공개 클라이언트(NONE)의 authorization_code 교환에서도 발급 — Spring 기본 생성기가 거부하는 바로 그 조건")
    void generateIssuesRefreshTokenForPublicClientAuthorizationCodeExchange() {
        RegisteredClient client = registeredClient(REFRESH_TOKEN_TTL);
        OAuth2ClientAuthenticationToken clientAuthentication =
                new OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.NONE, null);
        Authentication authorizationGrant = new TestingAuthenticationToken(clientAuthentication, null);
        OAuth2TokenContext context = context(
                client, OAuth2TokenType.REFRESH_TOKEN, AuthorizationGrantType.AUTHORIZATION_CODE, authorizationGrant);

        assertThat(generator.generate(context)).isNotNull();
    }

    // ── 헬퍼 ──

    private OAuth2TokenContext context(
            RegisteredClient client, OAuth2TokenType tokenType, AuthorizationGrantType grantType,
            Authentication authorizationGrant) {
        return DefaultOAuth2TokenContext.builder()
                .registeredClient(client)
                .principal(new TestingAuthenticationToken("resource-owner", null))
                .authorizationServerContext(authorizationServerContext())
                .tokenType(tokenType)
                .authorizationGrantType(grantType)
                .authorizationGrant(authorizationGrant)
                .build();
    }

    private Authentication refreshGrantAuthentication() {
        return new TestingAuthenticationToken("refresh-grant", null);
    }

    private AuthorizationServerContext authorizationServerContext() {
        AuthorizationServerSettings settings = AuthorizationServerSettings.builder().issuer(ISSUER).build();
        return new AuthorizationServerContext() {
            @Override
            public String getIssuer() {
                return ISSUER;
            }

            @Override
            public AuthorizationServerSettings getAuthorizationServerSettings() {
                return settings;
            }
        };
    }

    private RegisteredClient registeredClient(Duration refreshTokenTtl) {
        return RegisteredClient.withId("client-id")
                .clientId("mcp-client")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("https://claude.ai/callback")
                .scope("mcp:query")
                .tokenSettings(TokenSettings.builder().refreshTokenTimeToLive(refreshTokenTtl).build())
                .build();
    }
}
