package com.history.backend.jira.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withForbiddenRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withTooManyRequests;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

import java.time.Duration;
import java.util.List;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.jira.AtlassianProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("JiraOAuthClient: Atlassian OAuth API 호출")
class JiraOAuthClientTest {

    @Test
    @DisplayName("code 교환 성공 → JSON body로 요청, 토큰 3종 반환")
    void exchangeCodeReturnsTokensWithJsonBody() {
        JiraOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://atlassian.test/oauth/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "grant_type": "authorization_code",
                          "client_id": "test-client-id",
                          "client_secret": "test-client-secret",
                          "code": "auth-code",
                          "redirect_uri": "https://atlassian.test/callback"
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "access_token": "atl-access-token",
                          "refresh_token": "atl-refresh-token",
                          "expires_in": 3600
                        }
                        """, MediaType.APPLICATION_JSON));

        JiraOAuthClient.JiraTokens result = fixture.client.exchangeCode("auth-code");

        assertThat(result.accessToken()).isEqualTo("atl-access-token");
        assertThat(result.refreshToken()).isEqualTo("atl-refresh-token");
        assertThat(result.expiresIn()).isEqualTo(3600L);
        fixture.server.verify();
    }

    @Test
    @DisplayName("HTTP 5xx 응답 → BadGatewayException 발생")
    void exchangeCodeRejectsHttpServerErrorResponse() {
        JiraOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://atlassian.test/oauth/token"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> fixture.client.exchangeCode("auth-code"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("HTTP 429(rate limit) 응답 → BadGatewayException 발생 (폐기 아닌 일시 장애)")
    void exchangeCodeRejectsTooManyRequestsAsBadGateway() {
        JiraOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://atlassian.test/oauth/token"))
                .andRespond(withTooManyRequests());

        assertThatThrownBy(() -> fixture.client.exchangeCode("auth-code"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("HTTP 404 응답 → BadGatewayException 발생 (폐기 아닌 일시 장애)")
    void exchangeCodeRejectsNotFoundAsBadGateway() {
        JiraOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://atlassian.test/oauth/token"))
                .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> fixture.client.exchangeCode("bad-code"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("HTTP 400 응답 → UnauthorizedException 발생")
    void exchangeCodeRejectsBadRequestAsUnauthorized() {
        JiraOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://atlassian.test/oauth/token"))
                .andRespond(withBadRequest());

        assertThatThrownBy(() -> fixture.client.exchangeCode("bad-code"))
                .isInstanceOf(UnauthorizedException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("HTTP 401 응답 → UnauthorizedException 발생")
    void exchangeCodeRejectsUnauthorizedAsUnauthorized() {
        JiraOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://atlassian.test/oauth/token"))
                .andRespond(withUnauthorizedRequest());

        assertThatThrownBy(() -> fixture.client.exchangeCode("bad-code"))
                .isInstanceOf(UnauthorizedException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("HTTP 403 응답 → UnauthorizedException 발생")
    void exchangeCodeRejectsForbiddenAsUnauthorized() {
        JiraOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://atlassian.test/oauth/token"))
                .andRespond(withForbiddenRequest());

        assertThatThrownBy(() -> fixture.client.exchangeCode("bad-code"))
                .isInstanceOf(UnauthorizedException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("access_token 누락 응답 → BadGatewayException 발생")
    void exchangeCodeRejectsMissingAccessToken() {
        JiraOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://atlassian.test/oauth/token"))
                .andRespond(withSuccess("""
                        {
                          "refresh_token": "atl-refresh-token",
                          "expires_in": 3600
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.exchangeCode("auth-code"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh_token 누락 응답 → BadGatewayException 발생")
    void exchangeCodeRejectsMissingRefreshToken() {
        // offline_access 스코프가 빠지면 Atlassian이 refresh_token 없이 응답한다. 여기서 막지 않으면
        // null로 조용히 저장돼 토큰 갱신 시점에서야 "갱신 불가"로 드러난다.
        JiraOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://atlassian.test/oauth/token"))
                .andRespond(withSuccess("""
                        {
                          "access_token": "atl-access-token",
                          "expires_in": 3600
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.exchangeCode("auth-code"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("expires_in 누락 응답 → BadGatewayException 발생")
    void exchangeCodeRejectsMissingExpiresIn() {
        // expiresIn이 박싱 Long이라 null이 그대로 넘어가면 IntegrationService의
        // Instant.now().plusSeconds(tokens.expiresIn())에서 자동 언박싱 NPE가 난다.
        JiraOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://atlassian.test/oauth/token"))
                .andRespond(withSuccess("""
                        {
                          "access_token": "atl-access-token",
                          "refresh_token": "atl-refresh-token"
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.exchangeCode("auth-code"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("접근 가능 사이트 목록 조회 성공 → cloudId·name·url 매핑")
    void listAccessibleResourcesReturnsSites() {
        JiraOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://atlassian.test/oauth/token/accessible-resources"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer atl-access-token"))
                .andRespond(withSuccess("""
                        [
                          { "id": "cloud-1", "name": "acme", "url": "https://acme.atlassian.net", "scopes": [] }
                        ]
                        """, MediaType.APPLICATION_JSON));

        List<JiraOAuthClient.JiraSite> result = fixture.client.listAccessibleResources("atl-access-token");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).cloudId()).isEqualTo("cloud-1");
        assertThat(result.get(0).name()).isEqualTo("acme");
        assertThat(result.get(0).url()).isEqualTo("https://acme.atlassian.net");
        fixture.server.verify();
    }

    @Test
    @DisplayName("접근 가능 사이트가 없으면 빈 목록 반환")
    void listAccessibleResourcesReturnsEmptyListWhenNoneAccessible() {
        JiraOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://atlassian.test/oauth/token/accessible-resources"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        List<JiraOAuthClient.JiraSite> result = fixture.client.listAccessibleResources("atl-access-token");

        assertThat(result).isEmpty();
        fixture.server.verify();
    }

    @Test
    @DisplayName("사이트 목록 조회 HTTP 5xx 응답 → BadGatewayException 발생")
    void listAccessibleResourcesRejectsHttpServerErrorResponse() {
        JiraOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://atlassian.test/oauth/token/accessible-resources"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> fixture.client.listAccessibleResources("atl-access-token"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("사이트 목록 조회 HTTP 429(rate limit) 응답 → BadGatewayException 발생 (폐기 아닌 일시 장애)")
    void listAccessibleResourcesRejectsTooManyRequestsAsBadGateway() {
        JiraOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://atlassian.test/oauth/token/accessible-resources"))
                .andRespond(withTooManyRequests());

        assertThatThrownBy(() -> fixture.client.listAccessibleResources("atl-access-token"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("사이트 목록 조회 HTTP 404 응답 → BadGatewayException 발생 (폐기 아닌 일시 장애)")
    void listAccessibleResourcesRejectsNotFoundAsBadGateway() {
        JiraOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://atlassian.test/oauth/token/accessible-resources"))
                .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> fixture.client.listAccessibleResources("bad-token"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("사이트 목록 조회 HTTP 400 응답 → UnauthorizedException 발생")
    void listAccessibleResourcesRejectsBadRequestAsUnauthorized() {
        JiraOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://atlassian.test/oauth/token/accessible-resources"))
                .andRespond(withBadRequest());

        assertThatThrownBy(() -> fixture.client.listAccessibleResources("bad-token"))
                .isInstanceOf(UnauthorizedException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("사이트 목록 조회 HTTP 401 응답 → UnauthorizedException 발생")
    void listAccessibleResourcesRejectsUnauthorizedAsUnauthorized() {
        JiraOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://atlassian.test/oauth/token/accessible-resources"))
                .andRespond(withUnauthorizedRequest());

        assertThatThrownBy(() -> fixture.client.listAccessibleResources("bad-token"))
                .isInstanceOf(UnauthorizedException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("사이트 목록 조회 HTTP 403 응답 → UnauthorizedException 발생")
    void listAccessibleResourcesRejectsForbiddenAsUnauthorized() {
        JiraOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://atlassian.test/oauth/token/accessible-resources"))
                .andRespond(withForbiddenRequest());

        assertThatThrownBy(() -> fixture.client.listAccessibleResources("bad-token"))
                .isInstanceOf(UnauthorizedException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh token 교환 성공 → JSON body로 요청, 회전된 새 토큰 3종 반환")
    void refreshReturnsRotatedTokensWithJsonBody() {
        JiraOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://atlassian.test/oauth/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "grant_type": "refresh_token",
                          "client_id": "test-client-id",
                          "client_secret": "test-client-secret",
                          "refresh_token": "old-refresh-token"
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "access_token": "new-access-token",
                          "refresh_token": "rotated-refresh-token",
                          "expires_in": 3600
                        }
                        """, MediaType.APPLICATION_JSON));

        JiraOAuthClient.JiraTokens result = fixture.client.refresh("old-refresh-token");

        assertThat(result.accessToken()).isEqualTo("new-access-token");
        // 응답에 담겨 오는 새 refresh token(회전) — 저장하지 않으면 다음 갱신이 영구 실패한다
        assertThat(result.refreshToken()).isEqualTo("rotated-refresh-token");
        assertThat(result.expiresIn()).isEqualTo(3600L);
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh token 갱신 중 HTTP 5xx 응답 → BadGatewayException 발생")
    void refreshRejectsHttpServerErrorResponse() {
        // Atlassian 일시 장애(5xx)를 토큰 폐기로 오판하면 JiraTokenService가 연동을 pending으로
        // 강등해 버린다 — 4xx(폐기)와 구분해야 한다.
        JiraOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://atlassian.test/oauth/token"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> fixture.client.refresh("old-refresh-token"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh 중 HTTP 429(rate limit) 응답 → BadGatewayException 발생 (폐기 아닌 일시 장애)")
    void refreshRejectsTooManyRequestsAsBadGateway() {
        // rate limit을 폐기로 오판하면 아직 유효한 refresh token인데도 연동이 pending으로 강등된다.
        JiraOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://atlassian.test/oauth/token"))
                .andRespond(withTooManyRequests());

        assertThatThrownBy(() -> fixture.client.refresh("old-refresh-token"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh 중 HTTP 404 응답 → BadGatewayException 발생 (폐기 아닌 일시 장애)")
    void refreshRejectsNotFoundAsBadGateway() {
        JiraOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://atlassian.test/oauth/token"))
                .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> fixture.client.refresh("revoked-refresh-token"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh token 폐기(HTTP 400) → UnauthorizedException 발생")
    void refreshRejectsBadRequestAsUnauthorized() {
        JiraOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://atlassian.test/oauth/token"))
                .andRespond(withBadRequest());

        assertThatThrownBy(() -> fixture.client.refresh("revoked-refresh-token"))
                .isInstanceOf(UnauthorizedException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh token 폐기(HTTP 401) → UnauthorizedException 발생")
    void refreshRejectsUnauthorizedAsUnauthorized() {
        JiraOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://atlassian.test/oauth/token"))
                .andRespond(withUnauthorizedRequest());

        assertThatThrownBy(() -> fixture.client.refresh("revoked-refresh-token"))
                .isInstanceOf(UnauthorizedException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh token 폐기(HTTP 403) → UnauthorizedException 발생")
    void refreshRejectsForbiddenAsUnauthorized() {
        JiraOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://atlassian.test/oauth/token"))
                .andRespond(withForbiddenRequest());

        assertThatThrownBy(() -> fixture.client.refresh("revoked-refresh-token"))
                .isInstanceOf(UnauthorizedException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh 응답에 refresh_token 누락 → BadGatewayException 발생")
    void refreshRejectsMissingRotatedRefreshToken() {
        JiraOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://atlassian.test/oauth/token"))
                .andRespond(withSuccess("""
                        {
                          "access_token": "new-access-token",
                          "expires_in": 3600
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.refresh("old-refresh-token"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh token 폐기 성공 → true 반환")
    void revokeReturnsTrueOnSuccess() {
        JiraOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://atlassian.test/oauth/revoke"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());

        boolean result = fixture.client.revoke("refresh-token");

        assertThat(result).isTrue();
        fixture.server.verify();
    }

    @Test
    @DisplayName("폐기 요청이 실패해도 예외를 던지지 않고 false를 반환한다 (이미 폐기된 토큰·Atlassian 장애를 해제 실패로 만들지 않는다)")
    void revokeReturnsFalseWhenRequestFails() {
        JiraOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://atlassian.test/oauth/revoke"))
                .andRespond(withServerError());

        boolean result = fixture.client.revoke("refresh-token");

        assertThat(result).isFalse();
        fixture.server.verify();
    }

    private JiraOAuthClientFixture fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        JiraOAuthClient client = new JiraOAuthClient(
                new AtlassianProperties(
                        "test-client-id",
                        "test-client-secret",
                        "https://atlassian.test/callback",
                        "read:jira-work read:jira-user offline_access",
                        "https://atlassian.test/authorize",
                        "https://atlassian.test/oauth/token",
                        "https://atlassian.test/oauth/token/accessible-resources",
                        "https://atlassian.test/oauth/revoke",
                        "https://atlassian.test/ex/jira",
                        Duration.ofMinutes(5)
                ),
                builder.build()
        );
        return new JiraOAuthClientFixture(client, server);
    }

    private record JiraOAuthClientFixture(JiraOAuthClient client, MockRestServiceServer server) {
    }
}
