package com.history.backend.oauth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

@DisplayName("McpAuthorizationRequestValidator: MCP 인가 요청 검증(redirect_uri·resource·scope)")
class McpAuthorizationRequestValidatorTest {

    private static final String RESOURCE_URL = "http://localhost:5173/mcp";
    private static final String AUTHORIZATION_URI = "http://localhost:5173/oauth2/authorize";
    private static final String CLIENT_ID = "mcp-client";
    private static final Authentication PRINCIPAL = new TestingAuthenticationToken("test-user", null);

    private final McpAuthorizationRequestValidator validator = new McpAuthorizationRequestValidator(RESOURCE_URL);

    // ── redirectUriMatches: 루프백은 포트 무시, 비루프백은 https 완전 일치만 ──

    @Test
    @DisplayName("루프백 호스트(localhost)는 포트가 달라도 일치")
    void redirectUriMatchesIgnoresPortForLoopbackHost() {
        boolean matches = McpAuthorizationRequestValidator.redirectUriMatches(
                "http://localhost:54321/callback", Set.of("http://localhost/callback"));

        assertThat(matches).isTrue();
    }

    @Test
    @DisplayName("루프백 호스트(127.0.0.1)도 포트가 달라도 일치")
    void redirectUriMatchesIgnoresPortForNumericLoopbackHost() {
        boolean matches = McpAuthorizationRequestValidator.redirectUriMatches(
                "http://127.0.0.1:8080/callback", Set.of("http://127.0.0.1/callback"));

        assertThat(matches).isTrue();
    }

    @Test
    @DisplayName("IPv6 루프백([::1])도 대괄호를 벗겨 포트 무시 일치")
    void redirectUriMatchesIgnoresPortForIpv6LoopbackHost() {
        boolean matches = McpAuthorizationRequestValidator.redirectUriMatches(
                "http://[::1]:9/callback", Set.of("http://[::1]/callback"));

        assertThat(matches).isTrue();
    }

    @Test
    @DisplayName("루프백이라도 scheme이 다르면 불일치")
    void redirectUriMatchesRejectsLoopbackSchemeMismatch() {
        boolean matches = McpAuthorizationRequestValidator.redirectUriMatches(
                "https://localhost:1/callback", Set.of("http://localhost/callback"));

        assertThat(matches).isFalse();
    }

    @Test
    @DisplayName("호스트가 없는 요청 URI(urn·상대 경로)는 예외 없이 불일치")
    void redirectUriMatchesRejectsHostlessRequestWithoutThrowing() {
        Set<String> registered = Set.of("http://localhost/callback");

        assertThat(McpAuthorizationRequestValidator.redirectUriMatches("urn:ietf:wg:oauth:2.0:oob", registered)).isFalse();
        assertThat(McpAuthorizationRequestValidator.redirectUriMatches("http:///callback", registered)).isFalse();
        assertThat(McpAuthorizationRequestValidator.redirectUriMatches("/callback", registered)).isFalse();
    }

    @Test
    @DisplayName("등록 URI에 호스트 없는 값이 섞여 있어도 나머지 루프백 URI로 정상 매칭")
    void redirectUriMatchesSkipsHostlessRegisteredUris() {
        boolean matches = McpAuthorizationRequestValidator.redirectUriMatches(
                "http://localhost:54321/callback",
                Set.of("urn:ietf:wg:oauth:2.0:oob", "http://localhost/callback"));

        assertThat(matches).isTrue();
    }

    @Test
    @DisplayName("루프백 요청 URI에 쿼리가 있으면 불일치")
    void redirectUriMatchesRejectsLoopbackRequestWithQueryString() {
        boolean matches = McpAuthorizationRequestValidator.redirectUriMatches(
                "http://localhost:54321/callback?code=abc", Set.of("http://localhost/callback"));

        assertThat(matches).isFalse();
    }

    @Test
    @DisplayName("루프백 요청 URI에 프래그먼트가 있으면 불일치")
    void redirectUriMatchesRejectsLoopbackRequestWithFragment() {
        boolean matches = McpAuthorizationRequestValidator.redirectUriMatches(
                "http://localhost:54321/callback#frag", Set.of("http://localhost/callback"));

        assertThat(matches).isFalse();
    }

    @Test
    @DisplayName("비루프백 http는 등록돼 있어도 불일치(https만 허용)")
    void redirectUriMatchesRejectsHttpNonLoopbackEvenWhenRegistered() {
        boolean matches = McpAuthorizationRequestValidator.redirectUriMatches(
                "http://example.com/cb", Set.of("http://example.com/cb"));

        assertThat(matches).isFalse();
    }

    @Test
    @DisplayName("비루프백 https는 완전히 같아야 일치")
    void redirectUriMatchesAcceptsExactHttpsMatch() {
        boolean matches = McpAuthorizationRequestValidator.redirectUriMatches(
                "https://claude.ai/callback", Set.of("https://claude.ai/callback"));

        assertThat(matches).isTrue();
    }

    @Test
    @DisplayName("비루프백 https는 경로가 다르면 불일치")
    void redirectUriMatchesRejectsHttpsPathMismatch() {
        boolean matches = McpAuthorizationRequestValidator.redirectUriMatches(
                "https://claude.ai/other", Set.of("https://claude.ai/callback"));

        assertThat(matches).isFalse();
    }

    // ── accept(): redirect_uri → resource(RFC 8707) → DEFAULT_SCOPE_VALIDATOR 순서로 체이닝 ──

    @Test
    @DisplayName("정상 요청(등록된 https redirect_uri·scope) → 통과")
    void acceptDoesNotThrowForValidHttpsRedirectAndRegisteredScope() {
        RegisteredClient client = registeredClient("https://claude.ai/callback");
        OAuth2AuthorizationCodeRequestAuthenticationContext context =
                context(client, "https://claude.ai/callback", Set.of("mcp:query"), Map.of());

        assertThatCode(() -> validator.accept(context)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("redirect_uri 생략 + 등록된 URI 1개 → 통과")
    void acceptDoesNotThrowWhenRedirectUriOmittedAndSingleRegistered() {
        RegisteredClient client = registeredClient("https://claude.ai/callback");
        OAuth2AuthorizationCodeRequestAuthenticationContext context =
                context(client, null, Set.of("mcp:query"), Map.of());

        assertThatCode(() -> validator.accept(context)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("redirect_uri 생략 + 등록된 URI 2개 이상 → invalid_request")
    void acceptThrowsInvalidRequestWhenRedirectUriOmittedAndMultipleRegistered() {
        RegisteredClient client = registeredClient("https://claude.ai/callback", "https://claude.ai/callback2");
        OAuth2AuthorizationCodeRequestAuthenticationContext context =
                context(client, null, Set.of("mcp:query"), Map.of());

        assertThatThrownBy(() -> validator.accept(context))
                .isInstanceOf(OAuth2AuthorizationCodeRequestAuthenticationException.class)
                .satisfies(exception -> {
                    OAuth2AuthorizationCodeRequestAuthenticationException authException =
                            (OAuth2AuthorizationCodeRequestAuthenticationException) exception;
                    assertThat(authException.getError().getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_REQUEST);
                    assertThat(authException.getAuthorizationCodeRequestAuthentication()).isNull();
                });
    }

    @Test
    @DisplayName("비루프백 http redirect_uri는 등록돼 있어도 invalid_request")
    void acceptThrowsInvalidRequestForNonLoopbackHttpRedirectEvenIfRegistered() {
        RegisteredClient client = registeredClient("http://example.com/cb");
        OAuth2AuthorizationCodeRequestAuthenticationContext context =
                context(client, "http://example.com/cb", Set.of("mcp:query"), Map.of());

        assertThatThrownBy(() -> validator.accept(context))
                .isInstanceOf(OAuth2AuthorizationCodeRequestAuthenticationException.class)
                .satisfies(exception -> {
                    OAuth2AuthorizationCodeRequestAuthenticationException authException =
                            (OAuth2AuthorizationCodeRequestAuthenticationException) exception;
                    assertThat(authException.getError().getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_REQUEST);
                    assertThat(authException.getAuthorizationCodeRequestAuthentication()).isNull();
                });
    }

    @Test
    @DisplayName("resource 파라미터가 리소스 URL과 같으면 통과")
    void acceptDoesNotThrowWhenResourceParameterMatches() {
        RegisteredClient client = registeredClient("https://claude.ai/callback");
        OAuth2AuthorizationCodeRequestAuthenticationContext context = context(
                client, "https://claude.ai/callback", Set.of("mcp:query"),
                Map.of(OAuth2ParameterNames.RESOURCE, RESOURCE_URL));

        assertThatCode(() -> validator.accept(context)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("resource 파라미터가 리소스 URL과 다르면 invalid_target(클라이언트로 리다이렉트)")
    void acceptThrowsInvalidTargetWhenResourceParameterMismatches() {
        RegisteredClient client = registeredClient("https://claude.ai/callback");
        OAuth2AuthorizationCodeRequestAuthenticationToken token = authorizationToken(
                "https://claude.ai/callback", Set.of("mcp:query"),
                Map.of(OAuth2ParameterNames.RESOURCE, "http://localhost:5173/other"));
        OAuth2AuthorizationCodeRequestAuthenticationContext context = contextOf(token, client);

        assertThatThrownBy(() -> validator.accept(context))
                .isInstanceOf(OAuth2AuthorizationCodeRequestAuthenticationException.class)
                .satisfies(exception -> {
                    OAuth2AuthorizationCodeRequestAuthenticationException authException =
                            (OAuth2AuthorizationCodeRequestAuthenticationException) exception;
                    assertThat(authException.getError().getErrorCode()).isEqualTo("invalid_target");
                    assertThat(authException.getAuthorizationCodeRequestAuthentication()).isSameAs(token);
                });
    }

    @Test
    @DisplayName("등록되지 않은 scope 요청 → invalid_scope(DEFAULT_SCOPE_VALIDATOR 체이닝)")
    void acceptThrowsInvalidScopeForUnregisteredScope() {
        RegisteredClient client = registeredClient("https://claude.ai/callback");
        OAuth2AuthorizationCodeRequestAuthenticationContext context =
                context(client, "https://claude.ai/callback", Set.of("unknown:scope"), Map.of());

        assertThatThrownBy(() -> validator.accept(context))
                .isInstanceOf(OAuth2AuthorizationCodeRequestAuthenticationException.class)
                .satisfies(exception -> {
                    OAuth2AuthorizationCodeRequestAuthenticationException authException =
                            (OAuth2AuthorizationCodeRequestAuthenticationException) exception;
                    assertThat(authException.getError().getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_SCOPE);
                });
    }

    // ── 헬퍼 ──

    private RegisteredClient registeredClient(String... redirectUris) {
        RegisteredClient.Builder builder = RegisteredClient.withId("client-id")
                .clientId(CLIENT_ID)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .scope("mcp:query");
        for (String redirectUri : redirectUris) {
            builder.redirectUri(redirectUri);
        }
        return builder.build();
    }

    private OAuth2AuthorizationCodeRequestAuthenticationToken authorizationToken(
            String redirectUri, Set<String> scopes, Map<String, Object> additionalParameters) {
        return new OAuth2AuthorizationCodeRequestAuthenticationToken(
                AUTHORIZATION_URI, CLIENT_ID, PRINCIPAL, redirectUri, "test-state", scopes, additionalParameters);
    }

    private OAuth2AuthorizationCodeRequestAuthenticationContext contextOf(
            OAuth2AuthorizationCodeRequestAuthenticationToken token, RegisteredClient client) {
        return OAuth2AuthorizationCodeRequestAuthenticationContext.with(token).registeredClient(client).build();
    }

    private OAuth2AuthorizationCodeRequestAuthenticationContext context(
            RegisteredClient client, String redirectUri, Set<String> scopes, Map<String, Object> additionalParameters) {
        return contextOf(authorizationToken(redirectUri, scopes, additionalParameters), client);
    }
}
