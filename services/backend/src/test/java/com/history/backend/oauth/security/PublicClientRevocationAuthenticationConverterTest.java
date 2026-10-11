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

// 폐기 요청(POST /oauth2/revoke)은 grant_type이 없어 기존 공개 클라이언트 컨버터들이 받지 않는다.
// 공개 클라이언트(client_secret 없음)가 token + client_id만 보내는 폐기 요청만 NONE 인증으로 변환한다.
@DisplayName("PublicClientRevocationAuthenticationConverter: 토큰 폐기 요청을 공개 클라이언트 인증으로 변환")
class PublicClientRevocationAuthenticationConverterTest {

    private final PublicClientRevocationAuthenticationConverter converter =
            new PublicClientRevocationAuthenticationConverter("/oauth2/revoke");

    @Test
    @DisplayName("token + client_id → OAuth2ClientAuthenticationToken(NONE), additionalParameters에 client_id는 빠짐")
    void convertsRevocationRequestToPublicClientToken() {
        MockHttpServletRequest request = revokeRequest(
                "token", "abc",
                "token_type_hint", "refresh_token",
                "client_id", "mcp-client");

        Authentication result = converter.convert(request);

        assertThat(result).isInstanceOf(OAuth2ClientAuthenticationToken.class);
        OAuth2ClientAuthenticationToken token = (OAuth2ClientAuthenticationToken) result;
        assertThat(token.getPrincipal()).isEqualTo("mcp-client");
        assertThat(token.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.NONE);
        assertThat(token.getAdditionalParameters())
                .containsEntry("token", "abc")
                .containsEntry("token_type_hint", "refresh_token")
                .doesNotContainKey("client_id");
    }

    @Test
    @DisplayName("폐기 경로가 아닌 요청(/oauth2/token) → null")
    void returnsNullForOtherPath() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/token");
        request.addParameter("token", "abc");
        request.addParameter("client_id", "mcp-client");

        assertThat(converter.convert(request)).isNull();
    }

    @Test
    @DisplayName("client_id가 없으면 null")
    void returnsNullWhenClientIdMissing() {
        MockHttpServletRequest request = revokeRequest("token", "abc", "token_type_hint", "access_token");

        assertThat(converter.convert(request)).isNull();
    }

    @Test
    @DisplayName("client_id가 2개면 invalid_request")
    void throwsInvalidRequestForDuplicateClientId() {
        MockHttpServletRequest request = revokeRequest("token", "abc");
        request.addParameter("client_id", "mcp-client");
        request.addParameter("client_id", "another-client");

        assertThatThrownBy(() -> converter.convert(request))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(exception -> assertThat(((OAuth2AuthenticationException) exception).getError().getErrorCode())
                        .isEqualTo(OAuth2ErrorCodes.INVALID_REQUEST));
    }

    @Test
    @DisplayName("token이 없으면 null")
    void returnsNullWhenTokenMissing() {
        MockHttpServletRequest request = revokeRequest("client_id", "mcp-client");

        assertThat(converter.convert(request)).isNull();
    }

    @Test
    @DisplayName("client_secret 동봉 → null(다른 인증 방식 몫)")
    void returnsNullWhenClientSecretPresent() {
        MockHttpServletRequest request = revokeRequest(
                "token", "abc",
                "client_id", "mcp-client",
                "client_secret", "should-not-happen");

        assertThat(converter.convert(request)).isNull();
    }

    @Test
    @DisplayName("client_assertion 동봉 → null(다른 인증 방식 몫)")
    void returnsNullWhenClientAssertionPresent() {
        MockHttpServletRequest request = revokeRequest(
                "token", "abc",
                "client_id", "mcp-client",
                "client_assertion", "jwt-assertion");

        assertThat(converter.convert(request)).isNull();
    }

    @Test
    @DisplayName("Authorization 헤더(Basic) 동봉 → null(다른 인증 방식 몫)")
    void returnsNullWhenBasicAuthorizationHeaderPresent() {
        MockHttpServletRequest request = revokeRequest("token", "abc", "client_id", "mcp-client");
        request.addHeader("Authorization", "Basic bWNwLWNsaWVudDpzZWNyZXQ=");

        assertThat(converter.convert(request)).isNull();
    }

    @Test
    @DisplayName("Authorization 헤더(Bearer) 동봉 → null(다른 인증 방식 몫)")
    void returnsNullWhenBearerAuthorizationHeaderPresent() {
        MockHttpServletRequest request = revokeRequest("token", "abc", "client_id", "mcp-client");
        request.addHeader("Authorization", "Bearer some-token");

        assertThat(converter.convert(request)).isNull();
    }

    // ── 헬퍼 ──

    private MockHttpServletRequest revokeRequest(String... keyValuePairs) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/revoke");
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            request.addParameter(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return request;
    }
}
