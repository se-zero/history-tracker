package com.history.backend.oauth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import com.history.backend.oauth.McpOAuthProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

@DisplayName("McpRegisteredClientPolicy: CIMD·DCR 공통 강제값(NONE·PKCE·scope·TTL)")
class McpRegisteredClientPolicyTest {

    private final McpRegisteredClientPolicy policy =
            new McpRegisteredClientPolicy(new McpOAuthProperties("http://localhost:5173", "", Duration.ofHours(1), Duration.ofDays(30)));

    @Test
    @DisplayName("apply — 인증 방식·그랜트·scope·PKCE·동의·TTL·재사용 여부를 강제값으로 덧씌운다")
    void applyOverridesToMandatoryValues() {
        RegisteredClient client = policy.apply(baseBuilder(), null).build();

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
    }

    @Test
    @DisplayName("apply — clientUri가 https면 client_uri 설정에 보관")
    void applySetsClientUriSettingForHttps() {
        RegisteredClient client = policy.apply(baseBuilder(), "https://claude.ai").build();

        assertThat(McpRegisteredClientPolicy.clientUriOf(client)).isEqualTo("https://claude.ai");
    }

    @Test
    @DisplayName("apply — clientUri가 http(s)가 아니면 설정하지 않는다")
    void applyIgnoresNonHttpClientUriScheme() {
        RegisteredClient client = policy.apply(baseBuilder(), "javascript:alert(1)").build();

        assertThat(McpRegisteredClientPolicy.clientUriOf(client)).isNull();
    }

    @Test
    @DisplayName("apply — clientUri가 null이면 설정하지 않는다")
    void applyLeavesClientUriUnsetWhenNull() {
        RegisteredClient client = policy.apply(baseBuilder(), null).build();

        assertThat(McpRegisteredClientPolicy.clientUriOf(client)).isNull();
    }

    @Test
    @DisplayName("isRegistrableRedirectUri — localhost/127.0.0.1/[::1]의 http는 포트 무관 허용, https는 임의 호스트 허용")
    void isRegistrableRedirectUriAcceptsLocalhostAndLoopbackHttp() {
        assertThat(policy.isRegistrableRedirectUri("http://localhost/callback")).isTrue();
        assertThat(policy.isRegistrableRedirectUri("http://localhost:1234/callback")).isTrue();
        assertThat(policy.isRegistrableRedirectUri("http://127.0.0.1/cb")).isTrue();
        assertThat(policy.isRegistrableRedirectUri("http://[::1]/cb")).isTrue();
        assertThat(policy.isRegistrableRedirectUri("https://claude.ai/api/mcp/auth_callback")).isTrue();
    }

    @Test
    @DisplayName("isRegistrableRedirectUri — 비루프백 http·프래그먼트·상대경로·빈값·null·깨진 URI는 거부")
    void isRegistrableRedirectUriRejectsNonLocalHttpAndMalformedValues() {
        assertThat(policy.isRegistrableRedirectUri("http://example.com/cb")).isFalse();
        assertThat(policy.isRegistrableRedirectUri("https://x.com/cb#f")).isFalse();
        assertThat(policy.isRegistrableRedirectUri("/relative")).isFalse();
        assertThat(policy.isRegistrableRedirectUri("")).isFalse();
        assertThat(policy.isRegistrableRedirectUri(null)).isFalse();
        assertThat(policy.isRegistrableRedirectUri("::not a uri")).isFalse();
        // 호스트가 없는 http URI — getHost()가 null이라 예외 없이 false여야 한다
        assertThat(policy.isRegistrableRedirectUri("http:///cb")).isFalse();
        assertThat(policy.isRegistrableRedirectUri("http:cb")).isFalse();
    }

    @Test
    @DisplayName("sanitizeClientName — 앞뒤 공백을 trim한다")
    void sanitizeClientNameTrimsWhitespace() {
        assertThat(policy.sanitizeClientName("  Claude Code ", "fallback")).isEqualTo("Claude Code");
    }

    @Test
    @DisplayName("sanitizeClientName — null·공백이면 fallback을 반환한다")
    void sanitizeClientNameFallsBackWhenNullOrBlank() {
        assertThat(policy.sanitizeClientName(null, "fallback")).isEqualTo("fallback");
        assertThat(policy.sanitizeClientName("   ", "fallback")).isEqualTo("fallback");
    }

    @Test
    @DisplayName("sanitizeClientName — 101자면 IllegalArgumentException")
    void sanitizeClientNameRejectsTooLongName() {
        String tooLong = "a".repeat(101);

        assertThatThrownBy(() -> policy.sanitizeClientName(tooLong, "fallback")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("sanitizeClientName — 제어문자가 있으면 IllegalArgumentException")
    void sanitizeClientNameRejectsControlCharacters() {
        assertThatThrownBy(() -> policy.sanitizeClientName("a\u0000b", "fallback")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("sanitizeClientName — 정확히 100자는 통과")
    void sanitizeClientNameAcceptsExactly100Characters() {
        String exactly100 = "a".repeat(100);

        assertThat(policy.sanitizeClientName(exactly100, "fallback")).isEqualTo(exactly100);
    }

    // ── 헬퍼 — apply가 실제로 덮어쓰는지 확인하려고, 반대되는 값을 미리 채운 builder ──

    private RegisteredClient.Builder baseBuilder() {
        return RegisteredClient.withId("id")
                .clientId("c")
                .clientName("n")
                .redirectUri("http://localhost/cb")
                .clientSecret("basic-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("openid");
    }
}
