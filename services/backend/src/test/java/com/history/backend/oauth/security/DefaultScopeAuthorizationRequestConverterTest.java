package com.history.backend.oauth.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;

@DisplayName("DefaultScopeAuthorizationRequestConverter: scope 미지정 인가 요청에 기본 scope 부여")
class DefaultScopeAuthorizationRequestConverterTest {

    private static final String ISSUER = "http://localhost:5173";
    private static final String RESOURCE_URL = ISSUER + "/mcp";
    private static final AuthorizationServerSettings SETTINGS = AuthorizationServerSettings.builder().issuer(ISSUER).build();

    private final DefaultScopeAuthorizationRequestConverter converter = new DefaultScopeAuthorizationRequestConverter();

    // Spring 컨버터는 PAR(RFC 9126) 여부 판정을 위해 AuthorizationServerContextHolder를 항상 읽는다.
    // 실제 요청에서는 AuthorizationServerContextFilter가 채워 주지만, 필터 체인 밖에서 직접 부르는
    // 단위 테스트에서는 비어 있어 NPE가 나므로 여기서 세팅한다.
    @BeforeEach
    void setUpAuthorizationServerContext() {
        AuthorizationServerContextHolder.setContext(new AuthorizationServerContext() {
            @Override
            public String getIssuer() {
                return SETTINGS.getIssuer();
            }

            @Override
            public AuthorizationServerSettings getAuthorizationServerSettings() {
                return SETTINGS;
            }
        });
    }

    @AfterEach
    void resetAuthorizationServerContext() {
        AuthorizationServerContextHolder.resetContext();
    }

    @Test
    @DisplayName("scope 파라미터 없이 요청 → 기본 scope(mcp:query) 부여, 나머지 값은 보존")
    void convertAddsDefaultScopeWhenRequestOmitsScope() {
        MockHttpServletRequest request = authorizeGetRequest(
                "response_type", "code",
                "client_id", "mcp-client",
                "redirect_uri", "https://claude.ai/callback",
                "state", "state-123",
                "code_challenge", "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                "code_challenge_method", "S256",
                "resource", RESOURCE_URL);

        Authentication result = converter.convert(request);

        assertThat(result).isInstanceOf(OAuth2AuthorizationCodeRequestAuthenticationToken.class);
        OAuth2AuthorizationCodeRequestAuthenticationToken token = (OAuth2AuthorizationCodeRequestAuthenticationToken) result;
        assertThat(token.getScopes()).containsExactly("mcp:query");
        assertThat(token.getClientId()).isEqualTo("mcp-client");
        assertThat(token.getRedirectUri()).isEqualTo("https://claude.ai/callback");
        assertThat(token.getState()).isEqualTo("state-123");
        assertThat(token.getAdditionalParameters())
                .containsEntry("code_challenge", "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
                .containsEntry("code_challenge_method", "S256")
                .containsEntry("resource", RESOURCE_URL);
    }

    @Test
    @DisplayName("scope 파라미터가 이미 있으면 그대로 둔다")
    void convertPreservesExistingScope() {
        MockHttpServletRequest request = authorizeGetRequest(
                "response_type", "code",
                "client_id", "mcp-client",
                "redirect_uri", "https://claude.ai/callback",
                "scope", "foo bar",
                "code_challenge", "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                "code_challenge_method", "S256");

        Authentication result = converter.convert(request);

        OAuth2AuthorizationCodeRequestAuthenticationToken token = (OAuth2AuthorizationCodeRequestAuthenticationToken) result;
        assertThat(token.getScopes()).containsExactlyInAnyOrder("foo", "bar");
    }

    @Test
    @DisplayName("위임 결과가 null이면(grant 파라미터 없는 POST) null 그대로 반환")
    void convertReturnsNullWhenDelegateReturnsNull() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/authorize");

        Authentication result = converter.convert(request);

        assertThat(result).isNull();
    }

    // ── 헬퍼 — Spring 컨버터는 request.getParameterMap()과 getQueryString()을 둘 다 봐야 GET 파라미터를 읽는다 ──

    private MockHttpServletRequest authorizeGetRequest(String... keyValuePairs) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorize");
        StringBuilder queryString = new StringBuilder();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            String key = keyValuePairs[i];
            String value = keyValuePairs[i + 1];
            request.addParameter(key, value);
            if (queryString.length() > 0) {
                queryString.append('&');
            }
            queryString.append(key).append('=').append(value);
        }
        request.setQueryString(queryString.toString());
        return request;
    }
}
