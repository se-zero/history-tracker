package com.history.backend.slack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.OAuthConnection;
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
            "https://slack.test/oauth/v2/authorize",
            "https://slack.test/api/oauth.v2.access",
            "https://slack.test/api/auth.revoke"
    );

    private final SlackClient slackClient = mock(SlackClient.class);
    private final SlackOAuthConnectFlow flow = new SlackOAuthConnectFlow(slackProperties, slackClient);

    @Test
    void providerIsSlack() {
        assertThat(flow.provider()).isEqualTo(IntegrationProvider.SLACK);
    }

    @Test
    @DisplayName("동의 URL은 client_id·user_scope·redirect_uri·state를 담는다")
    void buildAuthorizeUrlAssemblesSlackParameters() {
        assertThat(flow.buildAuthorizeUrl("signed-state")).isEqualTo(
                "https://slack.test/oauth/v2/authorize"
                        + "?client_id=test-client-id"
                        + "&user_scope=channels:read,groups:read"
                        + "&redirect_uri=https://slack.test/callback"
                        + "&state=signed-state"
        );
    }

    @Test
    @DisplayName("code 교환 결과는 토큰과 workspace 참조 — 선택 단계가 없어 동의만으로 대상이 정해진다")
    void exchangeCodeReturnsTokenWithWorkspaceReference() {
        when(slackClient.exchangeCode("auth-code"))
                .thenReturn(new SlackClient.SlackWorkspace("T123", "Acme", "xoxp-token"));

        OAuthConnection connection = flow.exchangeCode("auth-code");

        assertThat(connection.credential()).isEqualTo("xoxp-token");
        assertThat(connection.externalRef()).containsOnly(
                Map.entry(SlackOAuthConnectFlow.WORKSPACE_ID, "T123"),
                Map.entry(SlackOAuthConnectFlow.WORKSPACE_NAME, "Acme"));
    }
}
