package com.history.backend.discord.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import com.history.backend.discord.DiscordProperties;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.OAuthConnection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DiscordOAuthConnectFlow: Discord 동의 URL 조립·code 교환")
class DiscordOAuthConnectFlowTest {

    private final DiscordProperties discordProperties = new DiscordProperties(
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
    );

    private final DiscordClient discordClient = mock(DiscordClient.class);
    private final DiscordOAuthConnectFlow flow = new DiscordOAuthConnectFlow(discordProperties, discordClient);

    @Test
    void providerIsDiscord() {
        assertThat(flow.provider()).isEqualTo(IntegrationProvider.DISCORD);
    }

    @Test
    @DisplayName("동의 URL은 client_id·response_type·redirect_uri·scope·permissions·state를 담는다")
    void buildAuthorizeUrlAssemblesDiscordParameters() {
        assertThat(flow.buildAuthorizeUrl("signed-state")).isEqualTo(
                "https://discord.test/oauth2/authorize"
                        + "?client_id=test-client-id"
                        + "&response_type=code"
                        + "&redirect_uri=https://discord.test/callback"
                        + "&scope=bot%20identify"
                        + "&permissions=66560"
                        + "&state=signed-state"
        );
    }

    @Test
    @DisplayName("code 교환 결과는 refresh token과 길드 참조 — 선택 단계가 없어 동의 화면에서 고른 서버가 곧 대상이다")
    void exchangeCodeReturnsTokenWithGuildReference() {
        when(discordClient.exchangeCode("auth-code"))
                .thenReturn(new DiscordClient.DiscordAuthorization("refresh-token", "G1", "Acme"));

        OAuthConnection connection = flow.exchangeCode("auth-code");

        assertThat(connection.credential()).isEqualTo("refresh-token");
        assertThat(connection.externalRef()).containsOnly(
                Map.entry(DiscordOAuthConnectFlow.GUILD_ID, "G1"),
                Map.entry(DiscordOAuthConnectFlow.GUILD_NAME, "Acme"));
    }
}
