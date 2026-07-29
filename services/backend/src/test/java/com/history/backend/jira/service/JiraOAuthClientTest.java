package com.history.backend.jira.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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
    @DisplayName("HTTP 오류 응답 → UnauthorizedException 발생")
    void exchangeCodeRejectsHttpErrorResponse() {
        JiraOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://atlassian.test/oauth/token"))
                .andRespond(withResourceNotFound());

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
        // null로 조용히 저장돼 2-b 토큰 갱신 시점에서야 "갱신 불가"로 드러난다.
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
    @DisplayName("사이트 목록 조회 HTTP 오류 응답 → UnauthorizedException 발생")
    void listAccessibleResourcesRejectsHttpErrorResponse() {
        JiraOAuthClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://atlassian.test/oauth/token/accessible-resources"))
                .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> fixture.client.listAccessibleResources("bad-token"))
                .isInstanceOf(UnauthorizedException.class);
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
                        "https://atlassian.test/ex/jira"
                ),
                builder.build()
        );
        return new JiraOAuthClientFixture(client, server);
    }

    private record JiraOAuthClientFixture(JiraOAuthClient client, MockRestServiceServer server) {
    }
}
