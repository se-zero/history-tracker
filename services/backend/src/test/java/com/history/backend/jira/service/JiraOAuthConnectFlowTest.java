package com.history.backend.jira.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.integration.domain.Integration;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.service.IntegrationService;
import com.history.backend.jira.AtlassianProperties;
import com.history.backend.project.domain.Project;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("JiraOAuthConnectFlow: Atlassian 동의 URL 조립·연동")
class JiraOAuthConnectFlowTest {

    private static final UUID USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");

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

    private final IntegrationService integrationService = mock(IntegrationService.class);
    private final JiraOAuthConnectFlow flow = new JiraOAuthConnectFlow(atlassianProperties, integrationService);

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
    @DisplayName("사이트·프로젝트 선택이 남은 pending 연동 → confirmed=false")
    void connectReportsNotConfirmedWhileSelectionPending() {
        when(integrationService.connectJiraSite(USER_ID, PROJECT_ID, "auth-code"))
                .thenReturn(Integration.pendingSelection(project(), IntegrationProvider.JIRA, new byte[] {1, 2, 3}));

        assertThat(flow.connect(USER_ID, PROJECT_ID, "auth-code")).isFalse();
        verify(integrationService).connectJiraSite(USER_ID, PROJECT_ID, "auth-code");
    }

    @Test
    @DisplayName("재동의로 이전 선택이 자동 복원된 연동 → confirmed=true")
    void connectReportsConfirmedWhenAutoRestored() {
        Integration restored = Integration.pendingSelection(project(), IntegrationProvider.JIRA, new byte[] {1, 2, 3});
        restored.applySelections(java.util.Map.of("cloud_id", "cloud-1", "site_name", "acme", "project_key", "PROJ", "project_name", "Project"));
        when(integrationService.connectJiraSite(USER_ID, PROJECT_ID, "auth-code")).thenReturn(restored);

        assertThat(flow.connect(USER_ID, PROJECT_ID, "auth-code")).isTrue();
    }

    private Project project() {
        User owner = new User("github", "12345", "owner@example.com", "Owner", null);
        ReflectionTestUtils.setField(owner, "id", USER_ID);
        Project project = new Project(owner, "History Tracker", null);
        ReflectionTestUtils.setField(project, "id", PROJECT_ID);
        return project;
    }
}
