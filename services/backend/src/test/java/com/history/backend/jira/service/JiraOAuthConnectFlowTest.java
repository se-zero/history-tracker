package com.history.backend.jira.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.JiraCredential;
import com.history.backend.integration.service.JiraCredentialCodec;
import com.history.backend.integration.service.OAuthConnection;
import com.history.backend.jira.AtlassianProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("JiraOAuthConnectFlow: Atlassian 동의 URL 조립·code 교환")
class JiraOAuthConnectFlowTest {

    private final AtlassianProperties atlassianProperties = new AtlassianProperties(
            "test-atlassian-client-id",
            "test-atlassian-client-secret",
            "https://atlassian.test/callback",
            "read:jira-work read:jira-user offline_access",
            "https://atlassian.test/authorize",
            "https://atlassian.test/oauth/token",
            "https://atlassian.test/oauth/token/accessible-resources",
            "https://atlassian.test/oauth/revoke",
            "https://atlassian.test/ex/jira",
            Duration.ofMinutes(5)
    );

    private final JiraOAuthClient jiraOAuthClient = mock(JiraOAuthClient.class);
    private final JiraCredentialCodec jiraCredentialCodec = mock(JiraCredentialCodec.class);
    private final JiraOAuthConnectFlow flow =
            new JiraOAuthConnectFlow(atlassianProperties, jiraOAuthClient, jiraCredentialCodec);

    @Test
    void providerIsJira() {
        assertThat(flow.provider()).isEqualTo(IntegrationProvider.JIRA);
    }

    @Test
    @DisplayName("동의 URL은 Atlassian 전용 파라미터(audience·response_type·prompt)를 포함한다")
    void buildAuthorizeUrlAssemblesAtlassianParameters() {
        assertThat(flow.buildAuthorizeUrl("signed-state")).isEqualTo(
                "https://atlassian.test/authorize"
                        + "?audience=api.atlassian.com"
                        + "&client_id=test-atlassian-client-id"
                        + "&scope=read:jira-work%20read:jira-user%20offline_access"
                        + "&redirect_uri=https://atlassian.test/callback"
                        + "&state=signed-state"
                        + "&response_type=code"
                        + "&prompt=consent"
        );
    }

    @Test
    @DisplayName("code 교환 결과는 갱신용 refresh token·만료 시각을 담고, 수집 대상은 미정(pending)이다")
    void exchangeCodeReturnsRenewableCredentialPendingSelection() {
        when(jiraOAuthClient.exchangeCode("auth-code"))
                .thenReturn(new JiraOAuthClient.JiraTokens("atl-access-token", "atl-refresh-token", 3600L));
        ArgumentCaptor<JiraCredential> credentialCaptor = ArgumentCaptor.forClass(JiraCredential.class);
        when(jiraCredentialCodec.serialize(credentialCaptor.capture())).thenReturn("serialized-credential");

        OAuthConnection connection = flow.exchangeCode("auth-code");

        assertThat(connection.credential()).isEqualTo("serialized-credential");
        // 사이트·프로젝트는 동의 시점에 알 수 없다 — JiraSelectionFlow가 선언한 단계를 거쳐 확정된다
        assertThat(connection.externalRef()).isEmpty();
        assertThat(credentialCaptor.getValue().accessToken()).isEqualTo("atl-access-token");
        assertThat(credentialCaptor.getValue().refreshToken()).isEqualTo("atl-refresh-token");
        assertThat(credentialCaptor.getValue().expiresAt()).isAfter(Instant.now());
        // 암호화는 저장 정책(IntegrationService)의 몫이다 — flow는 직렬화까지만 한다
        verify(jiraCredentialCodec, never()).encrypt(any(JiraCredential.class));
    }
}
