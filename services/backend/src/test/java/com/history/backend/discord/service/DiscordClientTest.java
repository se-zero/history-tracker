package com.history.backend.discord.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.discord.DiscordProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@DisplayName("DiscordClient: Discord API 호출")
class DiscordClientTest {

    @Test
    @DisplayName("code 교환 성공 → refresh token과 길드 정보 반환 (access_token은 매핑하지 않는다)")
    void exchangeCodeReturnsRefreshTokenAndGuild() {
        DiscordClientFixture fixture = fixture();
        MultiValueMap<String, String> expectedForm = new LinkedMultiValueMap<>();
        expectedForm.add("client_id", "test-client-id");
        expectedForm.add("client_secret", "test-client-secret");
        expectedForm.add("grant_type", "authorization_code");
        expectedForm.add("code", "auth-code");
        expectedForm.add("redirect_uri", "https://discord.test/callback");

        fixture.server.expect(once(), requestTo("https://discord.test/api/v10/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formData(expectedForm))
                .andRespond(withSuccess("""
                        {
                          "token_type": "Bearer",
                          "access_token": "unused-access-token",
                          "expires_in": 604800,
                          "refresh_token": "refresh-token",
                          "scope": "identify",
                          "guild": { "id": "G1", "name": "Acme" }
                        }
                        """, MediaType.APPLICATION_JSON));

        DiscordClient.DiscordAuthorization result = fixture.client.exchangeCode("auth-code");

        assertThat(result.refreshToken()).isEqualTo("refresh-token");
        assertThat(result.guildId()).isEqualTo("G1");
        assertThat(result.guildName()).isEqualTo("Acme");
        fixture.server.verify();
    }

    @Test
    @DisplayName("HTTP 401 응답 → UnauthorizedException 발생")
    void exchangeCodeRejectsUnauthorizedAsUnauthorized() {
        DiscordClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://discord.test/api/v10/oauth2/token"))
                .andRespond(withUnauthorizedRequest());

        assertThatThrownBy(() -> fixture.client.exchangeCode("bad-code"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid Discord authorization code.");
        fixture.server.verify();
    }

    @Test
    @DisplayName("HTTP 5xx 응답 → BadGatewayException 발생 (폐기 아닌 일시 장애)")
    void exchangeCodeRejectsServerErrorAsBadGateway() {
        DiscordClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://discord.test/api/v10/oauth2/token"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> fixture.client.exchangeCode("auth-code"))
                .isInstanceOf(BadGatewayException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("refresh_token 누락 응답 → BadGatewayException 발생")
    void exchangeCodeRejectsMissingRefreshToken() {
        DiscordClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://discord.test/api/v10/oauth2/token"))
                .andRespond(withSuccess("""
                        {
                          "access_token": "unused-access-token",
                          "guild": { "id": "G1", "name": "Acme" }
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.exchangeCode("auth-code"))
                .isInstanceOf(BadGatewayException.class)
                .hasMessage("Discord OAuth response is missing refresh token.");
        fixture.server.verify();
    }

    @Test
    @DisplayName("guild 정보 누락 응답 → BadGatewayException 발생 (선택 단계가 없어 여기가 유일한 수집 대상 출처다)")
    void exchangeCodeRejectsMissingGuildInformation() {
        DiscordClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://discord.test/api/v10/oauth2/token"))
                .andRespond(withSuccess("""
                        {
                          "refresh_token": "refresh-token"
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.exchangeCode("auth-code"))
                .isInstanceOf(BadGatewayException.class)
                .hasMessage("Discord OAuth response is missing guild information.");
        fixture.server.verify();
    }

    @Test
    @DisplayName("grant 폐기 요청은 refresh token과 token_type_hint를 담아 보낸다")
    void revokeTokenSendsRefreshTokenHint() {
        DiscordClientFixture fixture = fixture();
        MultiValueMap<String, String> expectedForm = new LinkedMultiValueMap<>();
        expectedForm.add("client_id", "test-client-id");
        expectedForm.add("client_secret", "test-client-secret");
        expectedForm.add("token", "refresh-token");
        expectedForm.add("token_type_hint", "refresh_token");

        fixture.server.expect(once(), requestTo("https://discord.test/api/v10/oauth2/token/revoke"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formData(expectedForm))
                .andRespond(withSuccess());

        fixture.client.revokeToken("refresh-token");
        fixture.server.verify();
    }

    @Test
    @DisplayName("grant 폐기 요청이 실패해도 예외를 던지지 않는다 — 연동 해제 자체가 막히면 안 된다")
    void revokeTokenSwallowsFailure() {
        DiscordClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://discord.test/api/v10/oauth2/token/revoke"))
                .andRespond(withServerError());

        assertThatCode(() -> fixture.client.revokeToken("refresh-token")).doesNotThrowAnyException();
        fixture.server.verify();
    }

    @Test
    @DisplayName("길드 퇴장 요청은 봇 토큰으로 DELETE한다")
    void leaveGuildSendsBotAuthorizedDelete() {
        DiscordClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://discord.test/api/v10/users/@me/guilds/G1"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Authorization", "Bot test-bot-token"))
                .andRespond(withNoContent());

        fixture.client.leaveGuild("G1");
        fixture.server.verify();
    }

    @Test
    @DisplayName("길드 퇴장 요청이 실패해도 예외를 던지지 않는다 — 이미 강퇴됐거나 장애일 수 있다")
    void leaveGuildSwallowsFailure() {
        DiscordClientFixture fixture = fixture();
        fixture.server.expect(once(), requestTo("https://discord.test/api/v10/users/@me/guilds/G1"))
                .andRespond(withResourceNotFound());

        assertThatCode(() -> fixture.client.leaveGuild("G1")).doesNotThrowAnyException();
        fixture.server.verify();
    }

    private DiscordClientFixture fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DiscordClient client = new DiscordClient(
                new DiscordProperties(
                        "test-client-id",
                        "test-client-secret",
                        "https://discord.test/callback",
                        "test-bot-token",
                        "bot identify",
                        "66560",
                        "https://discord.test/oauth2/authorize",
                        "https://discord.test/api/v10/oauth2/token",
                        "https://discord.test/api/v10/oauth2/token/revoke",
                        "https://discord.test/api/v10"
                ),
                builder.build()
        );
        return new DiscordClientFixture(client, server);
    }

    private record DiscordClientFixture(DiscordClient client, MockRestServiceServer server) {
    }
}
