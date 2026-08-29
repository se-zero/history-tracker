package com.history.backend.notion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.notion.NotionProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("NotionClient: Notion API 호출")
class NotionClientTest {

    private static final String EXPECTED_BASIC_AUTH = "Basic " + Base64.getEncoder()
            .encodeToString("test-client-id:test-client-secret".getBytes(StandardCharsets.UTF_8));

    @Test
    @DisplayName("code 교환은 Basic auth + JSON 바디로 요청하고, 응답의 access/refresh token·워크스페이스 정보를 반환한다")
    void exchangeCodeSendsBasicAuthAndReturnsAuthorization() {
        NotionClientFixture fixture = fixture();

        fixture.server.expect(once(), requestTo("https://notion.test/v1/oauth/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", EXPECTED_BASIC_AUTH))
                .andExpect(header("Notion-Version", "2026-03-11"))
                .andExpect(jsonPath("$.grant_type").value("authorization_code"))
                .andExpect(jsonPath("$.code").value("auth-code"))
                .andExpect(jsonPath("$.redirect_uri").value("https://notion.test/callback"))
                .andRespond(withSuccess("""
                        {
                          "access_token": "access-token",
                          "refresh_token": "refresh-token",
                          "bot_id": "bot-1",
                          "workspace_id": "W1",
                          "workspace_name": "Acme"
                        }
                        """, MediaType.APPLICATION_JSON));

        NotionClient.NotionAuthorization result = fixture.client.exchangeCode("auth-code");

        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.refreshToken()).isEqualTo("refresh-token");
        assertThat(result.botId()).isEqualTo("bot-1");
        assertThat(result.workspaceId()).isEqualTo("W1");
        assertThat(result.workspaceName()).isEqualTo("Acme");
        fixture.server.verify();
    }

    @Test
    @DisplayName("HTTP 401 응답 → UnauthorizedException 발생")
    void exchangeCodeRejectsUnauthorizedAsUnauthorized() {
        NotionClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://notion.test/v1/oauth/token"))
                .andRespond(withUnauthorizedRequest());

        assertThatThrownBy(() -> fixture.client.exchangeCode("bad-code"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid Notion authorization code.");
        fixture.server.verify();
    }

    @Test
    @DisplayName("HTTP 5xx 응답 → BadGatewayException 발생 (폐기 아닌 일시 장애)")
    void exchangeCodeRejectsServerErrorAsBadGateway() {
        NotionClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://notion.test/v1/oauth/token"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> fixture.client.exchangeCode("auth-code"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("워크스페이스 정보 누락 응답 → BadGatewayException 발생 (선택 단계가 없어 여기가 유일한 출처다)")
    void exchangeCodeRejectsMissingWorkspaceInformation() {
        NotionClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://notion.test/v1/oauth/token"))
                .andRespond(withSuccess("""
                        {
                          "access_token": "access-token",
                          "bot_id": "bot-1"
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.exchangeCode("auth-code"))
                .isInstanceOf(BadGatewayException.class)
                .hasMessage("Notion OAuth response is missing workspace information.");
        fixture.server.verify();
    }

    @Test
    @DisplayName("폐기 요청은 access_token을 담아 Basic auth로 보내고, 성공하면 true를 반환한다")
    void revokeSendsAccessTokenAndReturnsTrueOnSuccess() {
        NotionClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://notion.test/v1/oauth/revoke"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", EXPECTED_BASIC_AUTH))
                .andExpect(jsonPath("$.token").value("access-token"))
                .andRespond(withSuccess());

        boolean result = fixture.client.revoke("access-token");

        assertThat(result).isTrue();
        fixture.server.verify();
    }

    @Test
    @DisplayName("폐기 요청이 실패해도 예외를 던지지 않고 false를 반환한다")
    void revokeReturnsFalseWhenRequestFails() {
        NotionClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://notion.test/v1/oauth/revoke"))
                .andRespond(withServerError());

        boolean result = fixture.client.revoke("access-token");

        assertThat(result).isFalse();
        fixture.server.verify();
    }

    private NotionClientFixture fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NotionClient client = new NotionClient(
                new NotionProperties(
                        "test-client-id",
                        "test-client-secret",
                        "https://notion.test/callback",
                        "https://notion.test/v1/oauth/authorize",
                        "https://notion.test/v1/oauth/token",
                        "https://notion.test/v1/oauth/revoke",
                        "https://notion.test/v1",
                        "2026-03-11"
                ),
                builder.build()
        );
        return new NotionClientFixture(client, server);
    }

    private record NotionClientFixture(NotionClient client, MockRestServiceServer server) {
    }
}
