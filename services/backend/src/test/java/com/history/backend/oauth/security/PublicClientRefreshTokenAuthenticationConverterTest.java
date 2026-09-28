package com.history.backend.oauth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;

// grant_type=refresh_token + client_id만 오는 교환 요청은 Spring 기본 PublicClientAuthenticationConverter가
// 받지 않는다(그 컨버터는 code_verifier가 있는 authorization_code 요청만 공개 클라이언트로 인정한다).
@DisplayName("PublicClientRefreshTokenAuthenticationConverter: refresh_token 교환 요청을 공개 클라이언트 인증으로 변환")
class PublicClientRefreshTokenAuthenticationConverterTest {

    private final PublicClientRefreshTokenAuthenticationConverter converter =
            new PublicClientRefreshTokenAuthenticationConverter();

    @Test
    @DisplayName("grant_type=refresh_token + client_id → OAuth2ClientAuthenticationToken(NONE), additionalParameters에 client_id는 빠짐")
    void convertsRefreshTokenGrantToPublicClientToken() {
        MockHttpServletRequest request = tokenRequest(
                "grant_type", "refresh_token",
                "refresh_token", "abc",
                "client_id", "mcp-client");

        Authentication result = converter.convert(request);

        assertThat(result).isInstanceOf(OAuth2ClientAuthenticationToken.class);
        OAuth2ClientAuthenticationToken token = (OAuth2ClientAuthenticationToken) result;
        assertThat(token.getPrincipal()).isEqualTo("mcp-client");
        assertThat(token.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.NONE);
        assertThat(token.getAdditionalParameters())
                .containsEntry("grant_type", "refresh_token")
                .containsEntry("refresh_token", "abc")
                .doesNotContainKey("client_id");
    }

    @Test
    @DisplayName("grant_type=authorization_code → null(다른 컨버터 몫)")
    void returnsNullForAuthorizationCodeGrant() {
        MockHttpServletRequest request = tokenRequest(
                "grant_type", "authorization_code",
                "code", "auth-code",
                "code_verifier", "verifier",
                "client_id", "mcp-client");

        assertThat(converter.convert(request)).isNull();
    }

    @Test
    @DisplayName("refresh_token grant인데 client_id가 없으면 null")
    void returnsNullWhenClientIdMissing() {
        MockHttpServletRequest request = tokenRequest(
                "grant_type", "refresh_token",
                "refresh_token", "abc");

        assertThat(converter.convert(request)).isNull();
    }

    @Test
    @DisplayName("client_id가 2개면 invalid_request")
    void throwsInvalidRequestForDuplicateClientId() {
        MockHttpServletRequest request = tokenRequest("grant_type", "refresh_token", "refresh_token", "abc");
        request.addParameter("client_id", "mcp-client");
        request.addParameter("client_id", "another-client");

        assertThatThrownBy(() -> converter.convert(request))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(exception -> assertThat(((OAuth2AuthenticationException) exception).getError().getErrorCode())
                        .isEqualTo(OAuth2ErrorCodes.INVALID_REQUEST));
    }

    @Test
    @DisplayName("refresh_token grant + client_secret 동봉 → null(다른 인증 방식 몫)")
    void returnsNullWhenClientSecretPresent() {
        MockHttpServletRequest request = tokenRequest(
                "grant_type", "refresh_token",
                "refresh_token", "abc",
                "client_id", "mcp-client",
                "client_secret", "should-not-happen");

        assertThat(converter.convert(request)).isNull();
    }

    @Test
    @DisplayName("refresh_token grant + client_assertion 동봉 → null(다른 인증 방식 몫)")
    void returnsNullWhenClientAssertionPresent() {
        MockHttpServletRequest request = tokenRequest(
                "grant_type", "refresh_token",
                "refresh_token", "abc",
                "client_id", "mcp-client",
                "client_assertion", "jwt-assertion");

        assertThat(converter.convert(request)).isNull();
    }

    @Test
    @DisplayName("refresh_token grant + Authorization 헤더 동봉 → null(다른 인증 방식 몫)")
    void returnsNullWhenAuthorizationHeaderPresent() {
        MockHttpServletRequest request = tokenRequest(
                "grant_type", "refresh_token",
                "refresh_token", "abc",
                "client_id", "mcp-client");
        request.addHeader("Authorization", "Basic bWNwLWNsaWVudDpzZWNyZXQ=");

        assertThat(converter.convert(request)).isNull();
    }

    // ── 헬퍼 ──

    private MockHttpServletRequest tokenRequest(String... keyValuePairs) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/token");
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            request.addParameter(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return request;
    }
}
