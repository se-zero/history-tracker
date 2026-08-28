package com.history.backend.slack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.slack.SlackProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@DisplayName("SlackClient: Slack API 호출")
class SlackClientTest {

    @Test
    @DisplayName("Slack code 교환 성공 → 워크스페이스 정보와 access token 반환")
    void exchangeCodeReturnsWorkspaceAndAccessToken() {
        SlackClientFixture fixture = fixture();
        MultiValueMap<String, String> expectedForm = new LinkedMultiValueMap<>();
        expectedForm.add("client_id", "test-client-id");
        expectedForm.add("client_secret", "test-client-secret");
        expectedForm.add("code", "auth-code");
        expectedForm.add("redirect_uri", "https://slack.test/callback");

        fixture.server.expect(once(), requestTo("https://slack.test/api/oauth.v2.access"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formData(expectedForm))
                .andRespond(withSuccess("""
                        {
                          "ok": true,
                          "team": { "id": "T123", "name": "Acme" },
                          "authed_user": { "access_token": "xoxp-token" }
                        }
                        """, MediaType.APPLICATION_JSON));

        SlackClient.SlackWorkspace result = fixture.client.exchangeCode("auth-code");

        assertThat(result.id()).isEqualTo("T123");
        assertThat(result.name()).isEqualTo("Acme");
        assertThat(result.accessToken()).isEqualTo("xoxp-token");
        fixture.server.verify();
    }

    @Test
    @DisplayName("Slack ok:false 응답 → UnauthorizedException 발생")
    void exchangeCodeRejectsSlackErrorResponse() {
        SlackClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://slack.test/api/oauth.v2.access"))
                .andRespond(withSuccess("""
                        {
                          "ok": false,
                          "error": "invalid_code"
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.exchangeCode("bad-code"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid Slack authorization code.");
        fixture.server.verify();
    }

    @Test
    @DisplayName("HTTP 오류 응답 → UnauthorizedException 발생")
    void exchangeCodeRejectsHttpErrorResponse() {
        SlackClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://slack.test/api/oauth.v2.access"))
                .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> fixture.client.exchangeCode("bad-code"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid Slack authorization code.");
        fixture.server.verify();
    }

    @Test
    @DisplayName("authed_user.access_token 없는 응답(봇 토큰만 온 경우) → BadGatewayException 발생")
    void exchangeCodeRejectsMissingUserAccessToken() {
        SlackClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://slack.test/api/oauth.v2.access"))
                .andRespond(withSuccess("""
                        {
                          "ok": true,
                          "team": { "id": "T123", "name": "Acme" }
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.exchangeCode("auth-code"))
                .isInstanceOf(BadGatewayException.class)
                .hasMessage("Slack OAuth response is missing user access token.");
        fixture.server.verify();
    }

    @Test
    @DisplayName("워크스페이스 정보 누락 응답 → BadGatewayException 발생")
    void exchangeCodeRejectsMissingWorkspaceInformation() {
        SlackClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://slack.test/api/oauth.v2.access"))
                .andRespond(withSuccess("""
                        {
                          "ok": true,
                          "team": { "id": "", "name": "" },
                          "authed_user": { "access_token": "xoxp-token" }
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.exchangeCode("auth-code"))
                .isInstanceOf(BadGatewayException.class)
                .hasMessage("Slack OAuth response is missing workspace information.");
        fixture.server.verify();
    }

    @Test
    @DisplayName("token 폐기 성공(ok:true) → true 반환")
    void revokeReturnsTrueWhenSlackAcknowledgesOk() {
        SlackClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://slack.test/api/auth.revoke"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        { "ok": true }
                        """, MediaType.APPLICATION_JSON));

        boolean result = fixture.client.revoke("xoxp-token");

        assertThat(result).isTrue();
        fixture.server.verify();
    }

    @Test
    @DisplayName("token 폐기 HTTP 200이지만 ok:false 응답 → false 반환 (Slack은 실패도 200으로 응답한다)")
    void revokeReturnsFalseWhenSlackRespondsOkFalse() {
        SlackClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://slack.test/api/auth.revoke"))
                .andRespond(withSuccess("""
                        { "ok": false, "error": "already_revoked" }
                        """, MediaType.APPLICATION_JSON));

        boolean result = fixture.client.revoke("xoxp-token");

        assertThat(result).isFalse();
        fixture.server.verify();
    }

    @Test
    @DisplayName("token 폐기 요청이 실패해도 예외를 던지지 않고 false를 반환한다")
    void revokeReturnsFalseWhenRequestFails() {
        SlackClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://slack.test/api/auth.revoke"))
                .andRespond(withServerError());

        boolean result = fixture.client.revoke("xoxp-token");

        assertThat(result).isFalse();
        fixture.server.verify();
    }

    private SlackClientFixture fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SlackClient client = new SlackClient(
                new SlackProperties(
                        "test-client-id",
                        "test-client-secret",
                        "https://slack.test/callback",
                        "channels:read,groups:read,channels:history,groups:history,users:read,users:read.email",
                        "https://slack.test/oauth/v2/authorize",
                        "https://slack.test/api/oauth.v2.access",
                        "https://slack.test/api/auth.revoke"
                ),
                builder.build()
        );
        return new SlackClientFixture(client, server);
    }

    private record SlackClientFixture(SlackClient client, MockRestServiceServer server) {
    }
}
