package com.history.backend.slack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.OAuthConnection;
import com.history.backend.integration.service.SlackCredential;
import com.history.backend.integration.service.SlackCredentialCodec;
import com.history.backend.slack.SlackProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SlackOAuthConnectFlow: Slack 동의 URL 조립·code 교환")
class SlackOAuthConnectFlowTest {

    private final SlackProperties slackProperties = new SlackProperties(
            "test-client-id",
            "test-client-secret",
            "https://slack.test/callback",
            "channels:read,groups:read",
            "commands",
            "https://slack.test/oauth/v2/authorize",
            "https://slack.test/api/oauth.v2.access",
            "https://slack.test/api/auth.revoke"
    );

    private final SlackClient slackClient = mock(SlackClient.class);
    private final SlackCredentialCodec codec = mock(SlackCredentialCodec.class);
    private final SlackOAuthConnectFlow flow = new SlackOAuthConnectFlow(slackProperties, slackClient, codec);

    @Test
    void providerIsSlack() {
        assertThat(flow.provider()).isEqualTo(IntegrationProvider.SLACK);
    }

    @Test
    @DisplayName("동의 URL은 client_id·봇 scope·user_scope·redirect_uri·state를 담는다")
    void buildAuthorizeUrlAssemblesSlackParameters() {
        assertThat(flow.buildAuthorizeUrl("signed-state")).isEqualTo(
                "https://slack.test/oauth/v2/authorize"
                        + "?client_id=test-client-id"
                        + "&scope=commands"
                        + "&user_scope=channels:read,groups:read"
                        + "&redirect_uri=https://slack.test/callback"
                        + "&state=signed-state"
        );
    }

    @Test
    @DisplayName("code 교환 결과 credential은 codec.serialize 출력(JSON), external_ref는 workspace + connected_user_id 포함")
    void exchangeCodeReturnsJsonCredentialWithWorkspaceAndConnectedUserId() {
        when(slackClient.exchangeCode("auth-code"))
                .thenReturn(new SlackClient.SlackWorkspace("T123", "Acme", "xoxp-token", null, "U456"));
        when(codec.serialize(new SlackCredential("xoxp-token", null)))
                .thenReturn("{\"user_token\":\"xoxp-token\",\"bot_token\":null}");

        OAuthConnection connection = flow.exchangeCode("auth-code");

        assertThat(connection.credential()).isEqualTo("{\"user_token\":\"xoxp-token\",\"bot_token\":null}");
        assertThat(connection.externalRef()).containsOnly(
                Map.entry(SlackOAuthConnectFlow.WORKSPACE_ID, "T123"),
                Map.entry(SlackOAuthConnectFlow.WORKSPACE_NAME, "Acme"),
                Map.entry(SlackOAuthConnectFlow.CONNECTED_USER_ID, "U456"));
    }

    @Test
    @DisplayName("credential은 JSON이어야 한다 — 평문 xoxp- 토큰이면 실패 (worker가 user_token 키를 읽을 수 없다)")
    void exchangeCodeCredentialIsNotPlainToken() {
        when(slackClient.exchangeCode("auth-code"))
                .thenReturn(new SlackClient.SlackWorkspace("T123", "Acme", "xoxp-token", null, "U456"));
        when(codec.serialize(new SlackCredential("xoxp-token", null)))
                .thenReturn("{\"user_token\":\"xoxp-token\",\"bot_token\":null}");

        OAuthConnection connection = flow.exchangeCode("auth-code");

        assertThat(connection.credential())
                .isNotEqualTo("xoxp-token")
                .contains("user_token");
    }

    @Test
    @DisplayName("bot 토큰이 있을 때 codec.serialize에 bot 토큰을 포함한 SlackCredential이 전달된다")
    void exchangeCodeIncludesBotTokenInCredentialWhenPresent() {
        when(slackClient.exchangeCode("auth-code"))
                .thenReturn(new SlackClient.SlackWorkspace("T123", "Acme", "xoxp-token", "xoxb-bot", "U456"));
        when(codec.serialize(new SlackCredential("xoxp-token", "xoxb-bot")))
                .thenReturn("{\"user_token\":\"xoxp-token\",\"bot_token\":\"xoxb-bot\"}");

        OAuthConnection connection = flow.exchangeCode("auth-code");

        assertThat(connection.credential()).isEqualTo("{\"user_token\":\"xoxp-token\",\"bot_token\":\"xoxb-bot\"}");
    }
}
