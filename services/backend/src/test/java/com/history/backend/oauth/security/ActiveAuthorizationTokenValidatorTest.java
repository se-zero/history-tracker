package com.history.backend.oauth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

@ExtendWith(MockitoExtension.class)
class ActiveAuthorizationTokenValidatorTest {

    private static final String TOKEN_VALUE = "header.payload.signature";
    private static final String USER_ID = "fdd87bd0-3751-4336-a2db-c05d931c4f50";

    @Mock
    private OAuth2AuthorizationService authorizationService;

    @Test
    void validateSucceedsWhenAuthorizationRowExistsAndAccessTokenIsNotInvalidated() {
        when(authorizationService.findByToken(TOKEN_VALUE, OAuth2TokenType.ACCESS_TOKEN))
                .thenReturn(authorization(true, false));

        OAuth2TokenValidatorResult result = validator().validate(jwt());

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void validateFailsWithInvalidTokenWhenAuthorizationRowIsMissing() {
        when(authorizationService.findByToken(TOKEN_VALUE, OAuth2TokenType.ACCESS_TOKEN)).thenReturn(null);

        OAuth2TokenValidatorResult result = validator().validate(jwt());

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).extracting(OAuth2Error::getErrorCode).containsExactly("invalid_token");
    }

    @Test
    void validateFailsWithInvalidTokenWhenAccessTokenIsMarkedInvalidated() {
        when(authorizationService.findByToken(TOKEN_VALUE, OAuth2TokenType.ACCESS_TOKEN))
                .thenReturn(authorization(true, true));

        OAuth2TokenValidatorResult result = validator().validate(jwt());

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).extracting(OAuth2Error::getErrorCode).containsExactly("invalid_token");
    }

    @Test
    void validateFailsWhenAuthorizationRowHasNoAccessToken() {
        when(authorizationService.findByToken(TOKEN_VALUE, OAuth2TokenType.ACCESS_TOKEN))
                .thenReturn(authorization(false, false));

        OAuth2TokenValidatorResult result = validator().validate(jwt());

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).extracting(OAuth2Error::getErrorCode).containsExactly("invalid_token");
    }

    @Test
    void validateLooksUpByRawJwtStringAsAccessToken() {
        when(authorizationService.findByToken(TOKEN_VALUE, OAuth2TokenType.ACCESS_TOKEN))
                .thenReturn(authorization(true, false));

        validator().validate(jwt());

        verify(authorizationService).findByToken(TOKEN_VALUE, OAuth2TokenType.ACCESS_TOKEN);
    }

    // SUT 팩토리
    private ActiveAuthorizationTokenValidator validator() {
        return new ActiveAuthorizationTokenValidator(authorizationService);
    }

    private Jwt jwt() {
        Instant now = Instant.now();
        return Jwt.withTokenValue(TOKEN_VALUE)
                .header("alg", "RS256")
                .subject(USER_ID)
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofHours(1)))
                .build();
    }

    private OAuth2Authorization authorization(boolean withAccessToken, boolean invalidated) {
        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("validator-client-" + UUID.randomUUID())
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost/callback")
                .scope("mcp:query")
                .build();
        OAuth2Authorization.Builder builder = OAuth2Authorization.withRegisteredClient(client)
                .id(UUID.randomUUID().toString())
                .principalName(USER_ID)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(Set.of("mcp:query"));
        if (withAccessToken) {
            Instant now = Instant.now();
            OAuth2AccessToken accessToken = new OAuth2AccessToken(
                    OAuth2AccessToken.TokenType.BEARER, TOKEN_VALUE, now, now.plus(Duration.ofHours(1)), Set.of("mcp:query"));
            builder.token(accessToken, metadata -> {
                if (invalidated) {
                    metadata.put(OAuth2Authorization.Token.INVALIDATED_METADATA_NAME, true);
                }
            });
        }
        return builder.build();
    }
}
