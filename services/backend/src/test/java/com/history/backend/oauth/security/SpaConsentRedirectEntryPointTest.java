package com.history.backend.oauth.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import com.history.backend.oauth.McpOAuthProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;

@DisplayName("SpaConsentRedirectEntryPoint: 미인증 authorize를 허용 화면으로 302")
class SpaConsentRedirectEntryPointTest {

    private final SpaConsentRedirectEntryPoint entryPoint = new SpaConsentRedirectEntryPoint(
            new McpOAuthProperties("http://localhost:5173", "", Duration.ofHours(1), Duration.ofDays(30), "/oauth/consent"));

    @Test
    @DisplayName("302와 consentUrl + ? + 원본 쿼리를 그대로 Location에 싣는다(재인코딩 없음)")
    void redirectsToConsentUrlWithRawQueryUntouched() throws Exception {
        String rawQuery = "response_type=code&client_id=abc&redirect_uri=http%3A%2F%2Flocalhost%3A53421%2Fcallback"
                + "&scope=mcp%3Aquery&state=a+b%2Bc";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorize");
        request.setQueryString(rawQuery);
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new InsufficientAuthenticationException("unauthenticated"));

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getHeader("Location")).isEqualTo("http://localhost:5173/oauth/consent?" + rawQuery);
    }

    @Test
    @DisplayName("쿼리가 없으면 Location은 consentUrl 그대로(물음표 없음)")
    void redirectsToBareConsentUrlWhenQueryIsNull() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorize");
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new InsufficientAuthenticationException("unauthenticated"));

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getHeader("Location")).isEqualTo("http://localhost:5173/oauth/consent");
    }
}
