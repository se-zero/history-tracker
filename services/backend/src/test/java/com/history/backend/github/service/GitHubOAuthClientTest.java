package com.history.backend.github.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withForbiddenRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withTooManyRequests;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.stream.IntStream;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.github.GitHubAppProperties;
import com.history.backend.github.dto.GitHubAccessTokenResponse;
import com.history.backend.github.dto.GitHubRepositoryResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@DisplayName("GitHubOAuthClient: GitHub OAuth 사용자 인증 API 클라이언트")
class GitHubOAuthClientTest {

    private static final String EXPECTED_BASIC_AUTH = "Basic " + Base64.getEncoder()
            .encodeToString("client-id:client-secret".getBytes(StandardCharsets.UTF_8));
    private static final String GRANT_REVOKE_URL = "https://api.github.test/applications/client-id/grant";
    private static final String ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token";


    @Test
    @DisplayName("2xx 응답이면 ACCESSIBLE")
    void checkInstallationAccessReturnsAccessibleOnSuccessResponse() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.github.test/user/installations/98765/repositories"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer user-access-token"))
                .andRespond(withSuccess("""
                        { "repositories": [] }
                        """, MediaType.APPLICATION_JSON));

        GitHubOAuthClient.InstallationAccess result =
                fixture.client.checkInstallationAccess("user-access-token", 98765L);

        assertThat(result).isEqualTo(GitHubOAuthClient.InstallationAccess.ACCESSIBLE);
        fixture.server.verify();
    }

    @Test
    @DisplayName("403 응답이면 DENIED — 진짜 접근 없음")
    void checkInstallationAccessReturnsDeniedOnForbidden() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.github.test/user/installations/98765/repositories"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        GitHubOAuthClient.InstallationAccess result =
                fixture.client.checkInstallationAccess("user-access-token", 98765L);

        assertThat(result).isEqualTo(GitHubOAuthClient.InstallationAccess.DENIED);
        fixture.server.verify();
    }

    @Test
    @DisplayName("404 응답이면 DENIED — 진짜 접근 없음")
    void checkInstallationAccessReturnsDeniedOnNotFound() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.github.test/user/installations/98765/repositories"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        GitHubOAuthClient.InstallationAccess result =
                fixture.client.checkInstallationAccess("user-access-token", 98765L);

        assertThat(result).isEqualTo(GitHubOAuthClient.InstallationAccess.DENIED);
        fixture.server.verify();
    }

    @Test
    @DisplayName("500 응답이면 UNKNOWN — 접근 없음과 구분해 판단 보류")
    void checkInstallationAccessReturnsUnknownOnServerError() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.github.test/user/installations/98765/repositories"))
                .andRespond(withServerError());

        GitHubOAuthClient.InstallationAccess result =
                fixture.client.checkInstallationAccess("user-access-token", 98765L);

        assertThat(result).isEqualTo(GitHubOAuthClient.InstallationAccess.UNKNOWN);
        fixture.server.verify();
    }

    @Test
    @DisplayName("네트워크 예외(타임아웃 등)면 UNKNOWN — 접근 없음과 구분해 판단 보류")
    void checkInstallationAccessReturnsUnknownOnNetworkException() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.github.test/user/installations/98765/repositories"))
                .andRespond(request -> {
                    throw new IOException("Connection refused");
                });

        GitHubOAuthClient.InstallationAccess result =
                fixture.client.checkInstallationAccess("user-access-token", 98765L);

        assertThat(result).isEqualTo(GitHubOAuthClient.InstallationAccess.UNKNOWN);
        fixture.server.verify();
    }

    @Test
    @DisplayName("installation 목록 조회는 per_page=100으로 요청해 기본 30개 절단을 막는다")
    void fetchInstallationsRequestsWithPerPage100() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://api.github.test/user/installations?per_page=100"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer user-access-token"))
                .andRespond(withSuccess("""
                        { "installations": [] }
                        """, MediaType.APPLICATION_JSON));

        fixture.client.fetchInstallations("user-access-token");

        fixture.server.verify();
    }

    @Test
    @DisplayName("code 교환 성공 → form-urlencoded 요청, access/refresh·만료 필드 매핑")
    void exchangeCodeMapsRefreshAndExpiryFields() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://github.com/login/oauth/access_token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(exchangeCodeForm("auth-code")))
                .andRespond(withSuccess("""
                        {
                          "access_token": "ghu_test_access",
                          "token_type": "bearer",
                          "scope": "",
                          "refresh_token": "ghr_test",
                          "expires_in": 28800,
                          "refresh_token_expires_in": 15897600
                        }
                        """, MediaType.APPLICATION_JSON));

        GitHubAccessTokenResponse result = fixture.client.exchangeCode("auth-code");

        assertThat(result.accessToken()).isEqualTo("ghu_test_access");
        assertThat(result.tokenType()).isEqualTo("bearer");
        assertThat(result.scope()).isEqualTo("");
        assertThat(result.refreshToken()).isEqualTo("ghr_test");
        assertThat(result.expiresIn()).isEqualTo(28800L);
        assertThat(result.refreshTokenExpiresIn()).isEqualTo(15897600L);
        fixture.server.verify();
    }

    @Test
    @DisplayName("code 교환 응답에 access_token 누락 → UnauthorizedException")
    void exchangeCodeRejectsMissingAccessToken() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://github.com/login/oauth/access_token"))
                .andRespond(withSuccess("""
                        {
                          "refresh_token": "ghr_test",
                          "expires_in": 28800,
                          "refresh_token_expires_in": 15897600
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.exchangeCode("auth-code"))
                .isInstanceOf(UnauthorizedException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("code 교환 응답에 refresh_token 누락 → UnauthorizedException (Expire user tokens 필수)")
    void exchangeCodeRejectsMissingRefreshToken() {
        // Expire user authorization tokens가 꺼진 앱은 refresh_token을 주지 않는다.
        // access만 통과시키면 로그인 직후 저장할 refresh가 없어 갱신이 영구히 실패한다.
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://github.com/login/oauth/access_token"))
                .andRespond(withSuccess("""
                        {
                          "access_token": "ghu_test_access",
                          "token_type": "bearer",
                          "scope": "",
                          "expires_in": 28800,
                          "refresh_token_expires_in": 15897600
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.exchangeCode("auth-code"))
                .isInstanceOf(UnauthorizedException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("code 교환 응답의 refresh_token이 빈 문자열이면 UnauthorizedException")
    void exchangeCodeRejectsBlankRefreshToken() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://github.com/login/oauth/access_token"))
                .andRespond(withSuccess("""
                        {
                          "access_token": "ghu_test_access",
                          "token_type": "bearer",
                          "scope": "",
                          "refresh_token": "",
                          "expires_in": 28800,
                          "refresh_token_expires_in": 15897600
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.exchangeCode("auth-code"))
                .isInstanceOf(UnauthorizedException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("code 교환 응답의 refresh_token이 공백이면 UnauthorizedException")
    void exchangeCodeRejectsWhitespaceRefreshToken() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://github.com/login/oauth/access_token"))
                .andRespond(withSuccess("""
                        {
                          "access_token": "ghu_test_access",
                          "token_type": "bearer",
                          "scope": "",
                          "refresh_token": " ",
                          "expires_in": 28800,
                          "refresh_token_expires_in": 15897600
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.exchangeCode("auth-code"))
                .isInstanceOf(UnauthorizedException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("code 교환 응답에 expires_in 누락 → UnauthorizedException")
    void exchangeCodeRejectsMissingExpiresIn() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://github.com/login/oauth/access_token"))
                .andRespond(withSuccess("""
                        {
                          "access_token": "ghu_test_access",
                          "token_type": "bearer",
                          "scope": "",
                          "refresh_token": "ghr_test",
                          "refresh_token_expires_in": 15897600
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.exchangeCode("auth-code"))
                .isInstanceOf(UnauthorizedException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("code 교환 응답에 refresh_token_expires_in 누락 → UnauthorizedException")
    void exchangeCodeRejectsMissingRefreshTokenExpiresIn() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://github.com/login/oauth/access_token"))
                .andRespond(withSuccess("""
                        {
                          "access_token": "ghu_test_access",
                          "token_type": "bearer",
                          "scope": "",
                          "refresh_token": "ghr_test",
                          "expires_in": 28800
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.exchangeCode("auth-code"))
                .isInstanceOf(UnauthorizedException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh token 교환 성공 → form-urlencoded 요청, 회전된 access/refresh·만료 필드 매핑")
    void refreshReturnsRotatedTokensWithFormBody() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(ACCESS_TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(refreshForm("old-refresh-token")))
                .andRespond(withSuccess("""
                        {
                          "access_token": "ghu_new_access",
                          "token_type": "bearer",
                          "scope": "",
                          "refresh_token": "ghr_rotated",
                          "expires_in": 28800,
                          "refresh_token_expires_in": 15897600
                        }
                        """, MediaType.APPLICATION_JSON));

        GitHubAccessTokenResponse result = fixture.client.refresh("old-refresh-token");

        assertThat(result.accessToken()).isEqualTo("ghu_new_access");
        assertThat(result.refreshToken()).isEqualTo("ghr_rotated");
        assertThat(result.expiresIn()).isEqualTo(28800L);
        assertThat(result.refreshTokenExpiresIn()).isEqualTo(15897600L);
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh token 폐기(HTTP 400) → UnauthorizedException 발생")
    void refreshRejectsBadRequestAsUnauthorized() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(ACCESS_TOKEN_URL))
                .andRespond(withBadRequest());

        assertThatThrownBy(() -> fixture.client.refresh("revoked-refresh-token"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("invalid")
                .hasMessageContaining("revoked");
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh token 폐기(HTTP 401) → UnauthorizedException 발생")
    void refreshRejectsUnauthorizedAsUnauthorized() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(ACCESS_TOKEN_URL))
                .andRespond(withUnauthorizedRequest());

        assertThatThrownBy(() -> fixture.client.refresh("revoked-refresh-token"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("invalid")
                .hasMessageContaining("revoked");
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh token 폐기(HTTP 403) → UnauthorizedException 발생")
    void refreshRejectsForbiddenAsUnauthorized() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(ACCESS_TOKEN_URL))
                .andRespond(withForbiddenRequest());

        assertThatThrownBy(() -> fixture.client.refresh("revoked-refresh-token"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("invalid")
                .hasMessageContaining("revoked");
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh 중 HTTP 404 응답 → BadGatewayException 발생 (인증 실패가 아님)")
    void refreshRejectsNotFoundAsBadGateway() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(ACCESS_TOKEN_URL))
                .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> fixture.client.refresh("old-refresh-token"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh 중 HTTP 5xx 응답 → BadGatewayException 발생")
    void refreshRejectsHttpServerErrorResponse() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(ACCESS_TOKEN_URL))
                .andRespond(withServerError());

        assertThatThrownBy(() -> fixture.client.refresh("old-refresh-token"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh 중 HTTP 429(rate limit) 응답 → BadGatewayException 발생 (폐기 아닌 일시 장애)")
    void refreshRejectsTooManyRequestsAsBadGateway() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(ACCESS_TOKEN_URL))
                .andRespond(withTooManyRequests());

        assertThatThrownBy(() -> fixture.client.refresh("old-refresh-token"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh 중 네트워크 예외 → BadGatewayException 발생")
    void refreshRejectsNetworkExceptionAsBadGateway() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(ACCESS_TOKEN_URL))
                .andRespond(request -> {
                    throw new IOException("Connection refused");
                });

        assertThatThrownBy(() -> fixture.client.refresh("old-refresh-token"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh 응답에 access_token 누락 → BadGatewayException (인증 실패가 아님)")
    void refreshRejectsMissingAccessTokenAsBadGateway() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(ACCESS_TOKEN_URL))
                .andRespond(withSuccess("""
                        {
                          "refresh_token": "ghr_rotated",
                          "expires_in": 28800,
                          "refresh_token_expires_in": 15897600
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.refresh("old-refresh-token"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh 응답의 access_token이 빈 문자열이면 BadGatewayException")
    void refreshRejectsBlankAccessTokenAsBadGateway() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(ACCESS_TOKEN_URL))
                .andRespond(withSuccess("""
                        {
                          "access_token": "",
                          "refresh_token": "ghr_rotated",
                          "expires_in": 28800,
                          "refresh_token_expires_in": 15897600
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.refresh("old-refresh-token"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh 응답에 refresh_token 누락 → BadGatewayException (회전 누락 방지)")
    void refreshRejectsMissingRotatedRefreshTokenAsBadGateway() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(ACCESS_TOKEN_URL))
                .andRespond(withSuccess("""
                        {
                          "access_token": "ghu_new_access",
                          "expires_in": 28800,
                          "refresh_token_expires_in": 15897600
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.refresh("old-refresh-token"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh 응답의 refresh_token이 빈 문자열이면 BadGatewayException")
    void refreshRejectsBlankRefreshTokenAsBadGateway() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(ACCESS_TOKEN_URL))
                .andRespond(withSuccess("""
                        {
                          "access_token": "ghu_new_access",
                          "refresh_token": "",
                          "expires_in": 28800,
                          "refresh_token_expires_in": 15897600
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.refresh("old-refresh-token"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh 응답에 expires_in 누락 → BadGatewayException")
    void refreshRejectsMissingExpiresInAsBadGateway() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(ACCESS_TOKEN_URL))
                .andRespond(withSuccess("""
                        {
                          "access_token": "ghu_new_access",
                          "refresh_token": "ghr_rotated",
                          "refresh_token_expires_in": 15897600
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.refresh("old-refresh-token"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh 응답에 refresh_token_expires_in 누락 → BadGatewayException")
    void refreshRejectsMissingRefreshTokenExpiresInAsBadGateway() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(ACCESS_TOKEN_URL))
                .andRespond(withSuccess("""
                        {
                          "access_token": "ghu_new_access",
                          "refresh_token": "ghr_rotated",
                          "expires_in": 28800
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.refresh("old-refresh-token"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh HTTP 200 + error=bad_refresh_token → UnauthorizedException (GitHub은 실패도 200으로 줌)")
    void refreshRejectsHttp200BadRefreshTokenAsUnauthorized() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(ACCESS_TOKEN_URL))
                .andRespond(withSuccess("""
                        {
                          "error": "bad_refresh_token",
                          "error_description": "The refresh token passed is incorrect or expired."
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.refresh("revoked-refresh-token"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("invalid")
                .hasMessageContaining("revoked");
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh HTTP 200 + error=incorrect_client_credentials → BadGatewayException (자격증명 행을 지우면 안 됨)")
    void refreshRejectsHttp200IncorrectClientCredentialsAsBadGateway() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(ACCESS_TOKEN_URL))
                .andRespond(withSuccess("""
                        {
                          "error": "incorrect_client_credentials",
                          "error_description": "The client_id and/or client_secret passed are incorrect."
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.refresh("old-refresh-token"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("grant 폐기는 DELETE + Basic auth + access_token JSON, 2xx이면 true")
    void revokeGrantSendsDeleteWithBasicAuthAndReturnsTrueOnSuccess() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(GRANT_REVOKE_URL))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Authorization", EXPECTED_BASIC_AUTH))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.access_token").value("ghu_access"))
                .andRespond(withNoContent());

        boolean result = fixture.client.revokeGrant("ghu_access");

        assertThat(result).isTrue();
        fixture.server.verify();
    }

    @Test
    @DisplayName("grant 폐기 HTTP 404 → true (이미 없는 grant)")
    void revokeGrantReturnsTrueOnNotFound() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(GRANT_REVOKE_URL))
                .andRespond(withResourceNotFound());

        boolean result = fixture.client.revokeGrant("ghu_access");

        assertThat(result).isTrue();
        fixture.server.verify();
    }

    @Test
    @DisplayName("grant 폐기 HTTP 401 → true (이미 무효)")
    void revokeGrantReturnsTrueOnUnauthorized() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(GRANT_REVOKE_URL))
                .andRespond(withUnauthorizedRequest());

        boolean result = fixture.client.revokeGrant("ghu_access");

        assertThat(result).isTrue();
        fixture.server.verify();
    }

    @Test
    @DisplayName("grant 폐기 HTTP 403 → false (2xx/404/401만 성공으로 본다)")
    void revokeGrantReturnsFalseOnForbidden() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(GRANT_REVOKE_URL))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        boolean result = fixture.client.revokeGrant("ghu_access");

        assertThat(result).isFalse();
        fixture.server.verify();
    }

    @Test
    @DisplayName("grant 폐기 HTTP 5xx → false, 예외를 던지지 않는다")
    void revokeGrantReturnsFalseOnServerError() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(GRANT_REVOKE_URL))
                .andRespond(withServerError());

        boolean result = fixture.client.revokeGrant("ghu_access");

        assertThat(result).isFalse();
        fixture.server.verify();
    }

    @Test
    @DisplayName("grant 폐기 네트워크 예외 → false, 예외를 던지지 않는다")
    void revokeGrantReturnsFalseOnNetworkException() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(GRANT_REVOKE_URL))
                .andRespond(request -> {
                    throw new IOException("Connection refused");
                });

        boolean result = fixture.client.revokeGrant("ghu_access");

        assertThat(result).isFalse();
        fixture.server.verify();
    }

    @Test
    @DisplayName("사용자 설치 저장소 목록 — user token·Accept·페이지 쿼리로 단일 부분 페이지 반환")
    void fetchUserInstallationRepositoriesReturnsSinglePartialPage() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        "https://api.github.test/user/installations/98765/repositories?per_page=100&page=1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer user-access-token"))
                .andExpect(header("Accept", "application/vnd.github+json"))
                .andRespond(withSuccess(repositoriesJson(99), MediaType.APPLICATION_JSON));

        List<GitHubRepositoryResponse> result =
                fixture.client.fetchUserInstallationRepositories("user-access-token", 98765L);

        assertThat(result).hasSize(99);
        assertThat(result.get(0).id()).isEqualTo(1L);
        assertThat(result.get(0).fullName()).isEqualTo("acme/repo-1");
        fixture.server.verify();
    }

    @Test
    @DisplayName("사용자 설치 저장소 목록 — 페이지 크기 100 미만이 나올 때까지 조회")
    void fetchUserInstallationRepositoriesFetchesUntilPartialPage() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        "https://api.github.test/user/installations/98765/repositories?per_page=100&page=1"))
                .andRespond(withSuccess(repositoriesJson(100), MediaType.APPLICATION_JSON));
        fixture.server.expect(once(), requestTo(
                        "https://api.github.test/user/installations/98765/repositories?per_page=100&page=2"))
                .andRespond(withSuccess(repositoriesJson(50), MediaType.APPLICATION_JSON));

        List<GitHubRepositoryResponse> result =
                fixture.client.fetchUserInstallationRepositories("user-access-token", 98765L);

        assertThat(result).hasSize(150);
        fixture.server.verify();
    }

    @Test
    @DisplayName("사용자 설치 저장소 목록 HTTP 404 → 빈 목록 반환 (저장소 0개 설치의 사용자 토큰 조회 방어, 예외 아님)")
    void fetchUserInstallationRepositoriesReturnsEmptyListOnNotFound() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        "https://api.github.test/user/installations/98765/repositories?per_page=100&page=1"))
                .andRespond(withResourceNotFound());

        List<GitHubRepositoryResponse> result =
                fixture.client.fetchUserInstallationRepositories("user-access-token", 98765L);

        assertThat(result).isEmpty();
        fixture.server.verify();
    }

    @Test
    @DisplayName("사용자 설치 저장소 목록 HTTP 5xx → BadGatewayException (상태코드 포함, 403도 404로 바꾸지 않음)")
    void fetchUserInstallationRepositoriesWrapsGitHubErrorsAsBadGateway() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        "https://api.github.test/user/installations/98765/repositories?per_page=100&page=1"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> fixture.client.fetchUserInstallationRepositories("user-access-token", 98765L))
                .isInstanceOf(BadGatewayException.class)
                .hasMessageContaining("GitHub repository list request failed.")
                .hasMessageContaining("500");
        fixture.server.verify();
    }

    @Test
    @DisplayName("사용자 설치 저장소 목록 HTTP 403 → BadGatewayException (브랜치 목록처럼 NotFound로 바꾸지 않음)")
    void fetchUserInstallationRepositoriesWrapsForbiddenAsBadGateway() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        "https://api.github.test/user/installations/98765/repositories?per_page=100&page=1"))
                .andRespond(withForbiddenRequest());

        assertThatThrownBy(() -> fixture.client.fetchUserInstallationRepositories("user-access-token", 98765L))
                .isInstanceOf(BadGatewayException.class)
                .hasMessageContaining("GitHub repository list request failed.")
                .hasMessageContaining("403");
        fixture.server.verify();
    }

    @Test
    @DisplayName("사용자 설치 저장소 목록 네트워크 예외 → BadGatewayException")
    void fetchUserInstallationRepositoriesWrapsNetworkExceptionAsBadGateway() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        "https://api.github.test/user/installations/98765/repositories?per_page=100&page=1"))
                .andRespond(request -> {
                    throw new IOException("Connection refused");
                });

        assertThatThrownBy(() -> fixture.client.fetchUserInstallationRepositories("user-access-token", 98765L))
                .isInstanceOf(BadGatewayException.class)
                .hasMessageContaining("GitHub repository list request failed.");
        fixture.server.verify();
    }

    @Test
    @DisplayName("브랜치 목록 — user token·Accept·페이지 쿼리로 이름만 반환, name null은 건너뜀")
    void fetchRepositoryBranchesReturnsNamesAndSkipsNull() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        "https://api.github.test/repos/acme/widget/branches?per_page=100&page=1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer user-access-token"))
                .andExpect(header("Accept", "application/vnd.github+json"))
                .andRespond(withSuccess("""
                        [{"name":"main"},{"name":null},{"name":"develop"}]
                        """, MediaType.APPLICATION_JSON));

        List<String> result = fixture.client.fetchRepositoryBranches("user-access-token", "acme", "widget");

        assertThat(result).containsExactly("main", "develop");
        fixture.server.verify();
    }

    @Test
    @DisplayName("브랜치 목록 — 페이지 크기 100 미만이 나올 때까지 조회")
    void fetchRepositoryBranchesFetchesUntilPartialPage() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        "https://api.github.test/repos/acme/widget/branches?per_page=100&page=1"))
                .andRespond(withSuccess(branchesJson(100), MediaType.APPLICATION_JSON));
        fixture.server.expect(once(), requestTo(
                        "https://api.github.test/repos/acme/widget/branches?per_page=100&page=2"))
                .andRespond(withSuccess(branchesJson(3), MediaType.APPLICATION_JSON));

        List<String> result = fixture.client.fetchRepositoryBranches("user-access-token", "acme", "widget");

        assertThat(result).hasSize(103);
        assertThat(result.get(0)).isEqualTo("branch-1");
        fixture.server.verify();
    }

    @Test
    @DisplayName("브랜치 목록 HTTP 403 → NotFoundException (존재 여부 누설 최소화)")
    void fetchRepositoryBranchesConvertsForbiddenToNotFound() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        "https://api.github.test/repos/acme/widget/branches?per_page=100&page=1"))
                .andRespond(withForbiddenRequest());

        assertThatThrownBy(() -> fixture.client.fetchRepositoryBranches("user-access-token", "acme", "widget"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("GitHub repository not found.");
        fixture.server.verify();
    }

    @Test
    @DisplayName("브랜치 목록 HTTP 404 → NotFoundException")
    void fetchRepositoryBranchesConvertsNotFoundToNotFound() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        "https://api.github.test/repos/acme/widget/branches?per_page=100&page=1"))
                .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> fixture.client.fetchRepositoryBranches("user-access-token", "acme", "widget"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("GitHub repository not found.");
        fixture.server.verify();
    }

    @Test
    @DisplayName("브랜치 목록 HTTP 5xx → BadGatewayException")
    void fetchRepositoryBranchesWrapsServerErrorAsBadGateway() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        "https://api.github.test/repos/acme/widget/branches?per_page=100&page=1"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> fixture.client.fetchRepositoryBranches("user-access-token", "acme", "widget"))
                .isInstanceOf(BadGatewayException.class)
                .hasMessageContaining("GitHub branch list request failed.");
        fixture.server.verify();
    }

    @Test
    @DisplayName("브랜치 목록 네트워크 예외 → BadGatewayException")
    void fetchRepositoryBranchesWrapsNetworkExceptionAsBadGateway() {
        GitHubOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        "https://api.github.test/repos/acme/widget/branches?per_page=100&page=1"))
                .andRespond(request -> {
                    throw new IOException("Connection refused");
                });

        assertThatThrownBy(() -> fixture.client.fetchRepositoryBranches("user-access-token", "acme", "widget"))
                .isInstanceOf(BadGatewayException.class)
                .hasMessageContaining("GitHub branch list request failed.");
        fixture.server.verify();
    }

    // RestClient.Builder에 mock 서버를 바인딩하고, 그 빌더로 만든 client를 반환
    private GitHubOAuthClientFixture fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubOAuthClient client = new GitHubOAuthClient(properties(), builder.build());
        return new GitHubOAuthClientFixture(client, server);
    }

    private GitHubAppProperties properties() {
        return new GitHubAppProperties(
                "app-id",
                "history-tracker",
                "",
                "client-id",
                "client-secret",
                "http://localhost/api/v1/auth/github/callback",
                "",
                "https://github.com/login/oauth/authorize",
                "https://github.com/login/oauth/access_token",
                "https://api.github.test/user",
                "https://api.github.test/user/installations",
                "https://api.github.test/app/installations/{installation_id}/access_tokens",
                "https://api.github.test/installation/repositories",
                "https://api.github.test/repos/{owner}/{repo}/branches",
                "https://api.github.test/user/installations/{installation_id}/repositories",
                "https://api.github.test/users/{username}/installation",
                "https://api.github.test/applications/{client_id}/grant",
                "https://api.github.test/app/installations/{installation_id}",
                Duration.ofMinutes(5)
        );
    }

    private String repositoriesJson(int count) {
        List<String> repositories = IntStream.rangeClosed(1, count)
                .mapToObj(index -> """
                        {
                          "id": %d,
                          "name": "repo-%d",
                          "full_name": "acme/repo-%d",
                          "owner": {"login": "acme"},
                          "private": true,
                          "visibility": "private",
                          "default_branch": "main"
                        }
                        """.formatted(index, index, index))
                .toList();
        return "{\"repositories\":[" + String.join(",", repositories) + "]}";
    }

    private String branchesJson(int count) {
        List<String> branches = IntStream.rangeClosed(1, count)
                .mapToObj(index -> "{\"name\":\"branch-%d\"}".formatted(index))
                .toList();
        return "[" + String.join(",", branches) + "]";
    }

    private MultiValueMap<String, String> exchangeCodeForm(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", "client-id");
        form.add("client_secret", "client-secret");
        form.add("code", code);
        form.add("redirect_uri", "http://localhost/api/v1/auth/github/callback");
        return form;
    }

    private MultiValueMap<String, String> refreshForm(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", "client-id");
        form.add("client_secret", "client-secret");
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        return form;
    }

    private record GitHubOAuthClientFixture(GitHubOAuthClient client, MockRestServiceServer server) {
    }
}
