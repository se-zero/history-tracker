package com.history.backend.oauth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

// grant_type=refresh_token + client_id로만 오는 요청을 등록된 공개 클라이언트로 확인한다.
// 다른 그랜트(예: authorization_code)나 다른 인증 방식의 토큰은 다른 프로바이더 몫이므로 null을 돌려준다.
@ExtendWith(MockitoExtension.class)
@DisplayName("PublicClientRefreshTokenAuthenticationProvider: refresh_token 교환 클라이언트 인증 확인")
class PublicClientRefreshTokenAuthenticationProviderTest {

    private static final String CLIENT_ID = "mcp-client";

    @Mock
    private RegisteredClientRepository registeredClientRepository;

    @Test
    @DisplayName("supports는 OAuth2ClientAuthenticationToken만 지원한다")
    void supportsOAuth2ClientAuthenticationTokenOnly() {
        PublicClientRefreshTokenAuthenticationProvider provider = provider();

        assertThat(provider.supports(OAuth2ClientAuthenticationToken.class)).isTrue();
        assertThat(provider.supports(UsernamePasswordAuthenticationToken.class)).isFalse();
    }

    @Test
    @DisplayName("NONE + refresh_token 그랜트 + 등록된 공개 클라이언트 → 인증된 토큰 반환")
    void authenticateReturnsAuthenticatedTokenWhenClientSupportsPublicRefreshFlow() {
        PublicClientRefreshTokenAuthenticationProvider provider = provider();
        RegisteredClient client = registeredClient(
                Set.of(ClientAuthenticationMethod.NONE),
                Set.of(AuthorizationGrantType.AUTHORIZATION_CODE, AuthorizationGrantType.REFRESH_TOKEN));
        when(registeredClientRepository.findByClientId(CLIENT_ID)).thenReturn(client);

        Authentication result = provider.authenticate(refreshTokenClientAuthentication());

        assertThat(result).isInstanceOf(OAuth2ClientAuthenticationToken.class);
        OAuth2ClientAuthenticationToken token = (OAuth2ClientAuthenticationToken) result;
        assertThat(token.isAuthenticated()).isTrue();
        assertThat(token.getRegisteredClient()).isSameAs(client);
        assertThat(token.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.NONE);
    }

    @Test
    @DisplayName("클라이언트 인증 방식이 NONE이 아니면 null(레포 조회 없음)")
    void authenticateReturnsNullForNonNoneClientAuthenticationMethod() {
        PublicClientRefreshTokenAuthenticationProvider provider = provider();
        OAuth2ClientAuthenticationToken input = new OAuth2ClientAuthenticationToken(
                CLIENT_ID, ClientAuthenticationMethod.CLIENT_SECRET_POST, "secret",
                Map.of("grant_type", "refresh_token"));

        assertThat(provider.authenticate(input)).isNull();
        verifyNoInteractions(registeredClientRepository);
    }

    @Test
    @DisplayName("grant_type이 refresh_token이 아니면 null(레포 조회 없음)")
    void authenticateReturnsNullForNonRefreshTokenGrant() {
        PublicClientRefreshTokenAuthenticationProvider provider = provider();
        OAuth2ClientAuthenticationToken input = new OAuth2ClientAuthenticationToken(
                CLIENT_ID, ClientAuthenticationMethod.NONE, null,
                Map.of("grant_type", "authorization_code"));

        assertThat(provider.authenticate(input)).isNull();
        verifyNoInteractions(registeredClientRepository);
    }

    @Test
    @DisplayName("등록되지 않은 client_id → invalid_client")
    void authenticateThrowsInvalidClientWhenClientNotFound() {
        PublicClientRefreshTokenAuthenticationProvider provider = provider();
        when(registeredClientRepository.findByClientId(CLIENT_ID)).thenReturn(null);

        assertThatThrownBy(() -> provider.authenticate(refreshTokenClientAuthentication()))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(exception -> assertThat(((OAuth2AuthenticationException) exception).getError().getErrorCode())
                        .isEqualTo(OAuth2ErrorCodes.INVALID_CLIENT));
    }

    @Test
    @DisplayName("등록된 클라이언트가 NONE 인증을 지원하지 않으면 invalid_client")
    void authenticateThrowsInvalidClientWhenClientDoesNotSupportNoneAuthentication() {
        PublicClientRefreshTokenAuthenticationProvider provider = provider();
        RegisteredClient client = registeredClient(
                Set.of(ClientAuthenticationMethod.CLIENT_SECRET_BASIC),
                Set.of(AuthorizationGrantType.AUTHORIZATION_CODE, AuthorizationGrantType.REFRESH_TOKEN));
        when(registeredClientRepository.findByClientId(CLIENT_ID)).thenReturn(client);

        assertThatThrownBy(() -> provider.authenticate(refreshTokenClientAuthentication()))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(exception -> assertThat(((OAuth2AuthenticationException) exception).getError().getErrorCode())
                        .isEqualTo(OAuth2ErrorCodes.INVALID_CLIENT));
    }

    @Test
    @DisplayName("등록된 클라이언트에 REFRESH_TOKEN 그랜트가 없으면 invalid_client")
    void authenticateThrowsInvalidClientWhenClientLacksRefreshTokenGrant() {
        PublicClientRefreshTokenAuthenticationProvider provider = provider();
        RegisteredClient client = registeredClient(
                Set.of(ClientAuthenticationMethod.NONE),
                Set.of(AuthorizationGrantType.AUTHORIZATION_CODE));
        when(registeredClientRepository.findByClientId(CLIENT_ID)).thenReturn(client);

        assertThatThrownBy(() -> provider.authenticate(refreshTokenClientAuthentication()))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(exception -> assertThat(((OAuth2AuthenticationException) exception).getError().getErrorCode())
                        .isEqualTo(OAuth2ErrorCodes.INVALID_CLIENT));
    }

    // ── 헬퍼 ──

    private PublicClientRefreshTokenAuthenticationProvider provider() {
        return new PublicClientRefreshTokenAuthenticationProvider(registeredClientRepository);
    }

    private OAuth2ClientAuthenticationToken refreshTokenClientAuthentication() {
        return new OAuth2ClientAuthenticationToken(
                CLIENT_ID, ClientAuthenticationMethod.NONE, null,
                Map.of("grant_type", "refresh_token", "refresh_token", "seed-token"));
    }

    private RegisteredClient registeredClient(
            Set<ClientAuthenticationMethod> authenticationMethods, Set<AuthorizationGrantType> grantTypes) {
        RegisteredClient.Builder builder = RegisteredClient.withId("client-id")
                .clientId(CLIENT_ID)
                .redirectUri("https://claude.ai/callback")
                .scope("mcp:query");
        authenticationMethods.forEach(builder::clientAuthenticationMethod);
        grantTypes.forEach(builder::authorizationGrantType);
        return builder.build();
    }
}
