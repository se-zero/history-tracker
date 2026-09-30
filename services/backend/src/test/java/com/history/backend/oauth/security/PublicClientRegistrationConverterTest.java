package com.history.backend.oauth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import com.history.backend.oauth.McpOAuthProperties;
import com.history.backend.oauth.service.McpRegisteredClientPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.OAuth2ClientRegistration;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

@DisplayName("PublicClientRegistrationConverter: DCR 등록 요청 → 공개 클라이언트 RegisteredClient")
class PublicClientRegistrationConverterTest {

    private final McpRegisteredClientPolicy policy =
            new McpRegisteredClientPolicy(new McpOAuthProperties("http://localhost:5173", "", Duration.ofHours(1), Duration.ofDays(30), "/oauth/consent"));

    private final PublicClientRegistrationConverter converter = new PublicClientRegistrationConverter(policy);

    @Test
    @DisplayName("정상 등록 요청 → NONE·PKCE·AC+RT·scope mcp:query·1h/30d/reuse false로 조립")
    void convertBuildsPublicMcpClient() {
        OAuth2ClientRegistration registration = baseRegistration().build();

        RegisteredClient client = converter.convert(registration);

        assertThat(client.getClientId()).isNotBlank();
        assertThat(client.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.NONE);
        assertThat(client.getClientSecret()).isNull();
        assertThat(client.getAuthorizationGrantTypes())
                .containsExactlyInAnyOrder(AuthorizationGrantType.AUTHORIZATION_CODE, AuthorizationGrantType.REFRESH_TOKEN);
        assertThat(client.getScopes()).containsExactly(McpRegisteredClientPolicy.SCOPE);
        assertThat(client.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(client.getClientSettings().isRequireAuthorizationConsent()).isFalse();
        assertThat(client.getTokenSettings().getAccessTokenTimeToLive()).isEqualTo(Duration.ofHours(1));
        assertThat(client.getTokenSettings().getRefreshTokenTimeToLive()).isEqualTo(Duration.ofDays(30));
        assertThat(client.getTokenSettings().isReuseRefreshTokens()).isFalse();
        assertThat(client.getClientName()).isEqualTo("Codex");
    }

    @Test
    @DisplayName("client_uri 클레임이 있으면 보관한다")
    void convertKeepsClientUriClaim() {
        OAuth2ClientRegistration registration = baseRegistration().claim("client_uri", "https://codex.example").build();

        RegisteredClient client = converter.convert(registration);

        assertThat(McpRegisteredClientPolicy.clientUriOf(client)).isEqualTo("https://codex.example");
    }

    @Test
    @DisplayName("token_endpoint_auth_method가 none이 아니면 invalid_client_metadata")
    void convertRejectsNonNoneAuthMethod() {
        OAuth2ClientRegistration registration = OAuth2ClientRegistration.builder()
                .clientName("Codex")
                .redirectUri("http://localhost/callback")
                .grantType("authorization_code")
                .grantType("refresh_token")
                .tokenEndpointAuthenticationMethod("client_secret_basic")
                .build();

        assertThatThrownBy(() -> converter.convert(registration))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(exception -> assertThat(((OAuth2AuthenticationException) exception).getError().getErrorCode())
                        .isEqualTo("invalid_client_metadata"));
    }

    @Test
    @DisplayName("token_endpoint_auth_method 미설정 → invalid_client_metadata")
    void convertRejectsMissingAuthMethod() {
        OAuth2ClientRegistration registration = OAuth2ClientRegistration.builder()
                .clientName("Codex")
                .redirectUri("http://localhost/callback")
                .grantType("authorization_code")
                .grantType("refresh_token")
                .build();

        assertThatThrownBy(() -> converter.convert(registration))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(exception -> assertThat(((OAuth2AuthenticationException) exception).getError().getErrorCode())
                        .isEqualTo("invalid_client_metadata"));
    }

    @Test
    @DisplayName("redirect_uris 없음 → invalid_redirect_uri")
    void convertRejectsMissingRedirectUri() {
        OAuth2ClientRegistration registration = OAuth2ClientRegistration.builder()
                .clientName("Codex")
                .grantType("authorization_code")
                .grantType("refresh_token")
                .tokenEndpointAuthenticationMethod("none")
                .build();

        assertThatThrownBy(() -> converter.convert(registration))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(exception -> assertThat(((OAuth2AuthenticationException) exception).getError().getErrorCode())
                        .isEqualTo(OAuth2ErrorCodes.INVALID_REDIRECT_URI));
    }

    @Test
    @DisplayName("등록 불가능한 redirect_uri(비루프백 http) → invalid_redirect_uri")
    void convertRejectsNonRegistrableRedirectUri() {
        OAuth2ClientRegistration registration = OAuth2ClientRegistration.builder()
                .clientName("Codex")
                .redirectUri("http://example.com/cb")
                .grantType("authorization_code")
                .grantType("refresh_token")
                .tokenEndpointAuthenticationMethod("none")
                .build();

        assertThatThrownBy(() -> converter.convert(registration))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(exception -> assertThat(((OAuth2AuthenticationException) exception).getError().getErrorCode())
                        .isEqualTo(OAuth2ErrorCodes.INVALID_REDIRECT_URI));
    }

    @Test
    @DisplayName("client_credentials를 추가로 요청해도 결과 그랜트는 AC+RT만")
    void convertIgnoresRequestedClientCredentialsGrant() {
        OAuth2ClientRegistration registration = baseRegistration().grantType("client_credentials").build();

        RegisteredClient client = converter.convert(registration);

        assertThat(client.getAuthorizationGrantTypes())
                .containsExactlyInAnyOrder(AuthorizationGrantType.AUTHORIZATION_CODE, AuthorizationGrantType.REFRESH_TOKEN);
    }

    @Test
    @DisplayName("openid scope를 요청해도 결과 scope는 mcp:query만")
    void convertIgnoresRequestedScope() {
        OAuth2ClientRegistration registration = baseRegistration().scope("openid").build();

        RegisteredClient client = converter.convert(registration);

        assertThat(client.getScopes()).containsExactly(McpRegisteredClientPolicy.SCOPE);
    }

    @Test
    @DisplayName("client_name이 101자면 invalid_client_metadata")
    void convertRejectsTooLongClientName() {
        OAuth2ClientRegistration registration = OAuth2ClientRegistration.builder()
                .clientName("a".repeat(101))
                .redirectUri("http://localhost/callback")
                .grantType("authorization_code")
                .grantType("refresh_token")
                .tokenEndpointAuthenticationMethod("none")
                .build();

        assertThatThrownBy(() -> converter.convert(registration))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(exception -> assertThat(((OAuth2AuthenticationException) exception).getError().getErrorCode())
                        .isEqualTo("invalid_client_metadata"));
    }

    @Test
    @DisplayName("client_name 없으면 결과 이름은 clientId와 같다(fallback)")
    void convertFallsBackClientNameToClientId() {
        OAuth2ClientRegistration registration = OAuth2ClientRegistration.builder()
                .redirectUri("http://localhost/callback")
                .grantType("authorization_code")
                .grantType("refresh_token")
                .tokenEndpointAuthenticationMethod("none")
                .build();

        RegisteredClient client = converter.convert(registration);

        assertThat(client.getClientName()).isEqualTo(client.getClientId());
    }

    // ── 헬퍼 ──

    private OAuth2ClientRegistration.Builder baseRegistration() {
        return OAuth2ClientRegistration.builder()
                .clientName("Codex")
                .redirectUri("http://localhost/callback")
                .grantType("authorization_code")
                .grantType("refresh_token")
                .tokenEndpointAuthenticationMethod("none");
    }
}
