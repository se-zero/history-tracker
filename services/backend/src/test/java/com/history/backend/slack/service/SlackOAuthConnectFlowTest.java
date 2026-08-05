package com.history.backend.slack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.IntegrationService;
import com.history.backend.slack.SlackProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SlackOAuthConnectFlow: Slack 동의 URL 조립·연동")
class SlackOAuthConnectFlowTest {

    private static final UUID USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");

    private final SlackProperties slackProperties = new SlackProperties(
            "test-client-id",
            "test-client-secret",
            "https://slack.test/callback",
            "channels:read,groups:read",
            "https://slack.test/oauth/v2/authorize",
            "https://slack.test/api/oauth.v2.access",
            "https://slack.test/api/auth.revoke"
    );

    private final IntegrationService integrationService = mock(IntegrationService.class);
    private final SlackOAuthConnectFlow flow = new SlackOAuthConnectFlow(slackProperties, integrationService);

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
    @DisplayName("연동 후 confirmed=false — Slack은 선택 단계가 없어 '복원 완료' 배너 대상이 아니다")
    void connectDelegatesAndReportsNotAutoRestored() {
        boolean confirmed = flow.connect(USER_ID, PROJECT_ID, "auth-code");

        assertThat(confirmed).isFalse();
        verify(integrationService).connectSlackWorkspace(USER_ID, PROJECT_ID, "auth-code");
    }
}
