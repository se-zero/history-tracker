package com.history.backend.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.ConflictException;
import com.history.backend.integration.domain.Integration;
import com.history.backend.jira.AtlassianProperties;
import com.history.backend.project.domain.Project;
import com.history.backend.project.service.ProjectService;
import com.history.backend.slack.SlackProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("IntegrationOAuthService: OAuth 동의 URL 조립·콜백 처리")
class IntegrationOAuthServiceTest {

    private static final UUID USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");

    @Mock
    private ProjectService projectService;

    @Mock
    private OAuthStateService oauthStateService;

    @Mock
    private IntegrationService integrationService;

    private final SlackProperties slackProperties = new SlackProperties(
            "test-client-id",
            "test-client-secret",
            "https://slack.test/callback",
            "channels:read,groups:read",
            "https://slack.test/oauth/v2/authorize",
            "https://slack.test/api/oauth.v2.access"
    );

    private final AtlassianProperties atlassianProperties = new AtlassianProperties(
            "test-atlassian-client-id",
            "test-atlassian-client-secret",
            "https://atlassian.test/callback",
            "read:jira-work read:jira-user offline_access",
            "https://atlassian.test/authorize",
            "https://atlassian.test/oauth/token",
            "https://atlassian.test/oauth/token/accessible-resources",
            "https://atlassian.test/ex/jira",
            Duration.ofMinutes(5)
    );

    @Test
    @DisplayName("authorize URL 조립 — 소유권 확인 후 state 발급 포함")
    void buildSlackAuthorizeUrlIssuesStateAndAssemblesUrl() {
        IntegrationOAuthService service = service();
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(oauthStateService.issue(PROJECT_ID, USER_ID, "slack")).thenReturn("signed-state");

        String authorizeUrl = service.buildSlackAuthorizeUrl(USER_ID, PROJECT_ID);

        assertThat(authorizeUrl).isEqualTo(
                "https://slack.test/oauth/v2/authorize"
                        + "?client_id=test-client-id"
                        + "&user_scope=channels:read,groups:read"
                        + "&redirect_uri=https://slack.test/callback"
                        + "&state=signed-state"
        );
        verify(projectService).getProject(USER_ID, PROJECT_ID);
    }

    @Test
    @DisplayName("콜백 성공 시 워크스페이스 연동 완료 및 성공 outcome 반환")
    void completeSlackCallbackConnectsWorkspaceOnSuccess() {
        IntegrationOAuthService service = service();
        when(oauthStateService.verify("signed-state", "slack"))
                .thenReturn(new OAuthStateClaims(PROJECT_ID, USER_ID, "slack"));

        OAuthCallbackOutcome outcome = service.completeSlackCallback("auth-code", "signed-state", null);

        assertThat(outcome.projectId()).isEqualTo(PROJECT_ID);
        assertThat(outcome.provider()).isEqualTo("slack");
        assertThat(outcome.errorCode()).isNull();
        verify(integrationService).connectSlackWorkspace(USER_ID, PROJECT_ID, "auth-code");
    }

    @Test
    @DisplayName("state 검증 실패 → projectId 없이 invalid_state 반환, 연동 시도 안 함")
    void completeSlackCallbackRejectsInvalidState() {
        IntegrationOAuthService service = service();
        when(oauthStateService.verify("bad-state", "slack"))
                .thenThrow(new OAuthStateException("Invalid OAuth state signature."));

        OAuthCallbackOutcome outcome = service.completeSlackCallback("auth-code", "bad-state", null);

        assertThat(outcome.projectId()).isNull();
        assertThat(outcome.provider()).isEqualTo("slack");
        assertThat(outcome.errorCode()).isEqualTo("invalid_state");
        verify(integrationService, never()).connectSlackWorkspace(any(), any(), any());
    }

    @Test
    @DisplayName("Slack이 error=access_denied로 돌아온 경우 → access_denied, 연동 시도 안 함")
    void completeSlackCallbackReturnsAccessDeniedWhenUserDeclines() {
        IntegrationOAuthService service = service();
        when(oauthStateService.verify("signed-state", "slack"))
                .thenReturn(new OAuthStateClaims(PROJECT_ID, USER_ID, "slack"));

        OAuthCallbackOutcome outcome = service.completeSlackCallback(null, "signed-state", "access_denied");

        assertThat(outcome.projectId()).isEqualTo(PROJECT_ID);
        assertThat(outcome.errorCode()).isEqualTo("access_denied");
        verify(integrationService, never()).connectSlackWorkspace(any(), any(), any());
    }

    @Test
    @DisplayName("access_denied가 아닌 provider error → connect_failed, 연동 시도 안 함")
    void completeSlackCallbackReturnsConnectFailedForNonAccessDeniedProviderError() {
        IntegrationOAuthService service = service();
        when(oauthStateService.verify("signed-state", "slack"))
                .thenReturn(new OAuthStateClaims(PROJECT_ID, USER_ID, "slack"));

        OAuthCallbackOutcome outcome = service.completeSlackCallback(null, "signed-state", "invalid_scope");

        assertThat(outcome.projectId()).isEqualTo(PROJECT_ID);
        assertThat(outcome.errorCode()).isEqualTo("connect_failed");
        verify(integrationService, never()).connectSlackWorkspace(any(), any(), any());
    }

    @Test
    @DisplayName("이미 연동된 프로젝트 → already_connected")
    void completeSlackCallbackReturnsAlreadyConnectedOnConflict() {
        IntegrationOAuthService service = service();
        when(oauthStateService.verify("signed-state", "slack"))
                .thenReturn(new OAuthStateClaims(PROJECT_ID, USER_ID, "slack"));
        when(integrationService.connectSlackWorkspace(USER_ID, PROJECT_ID, "auth-code"))
                .thenThrow(new ConflictException("Slack integration already exists."));

        OAuthCallbackOutcome outcome = service.completeSlackCallback("auth-code", "signed-state", null);

        assertThat(outcome.projectId()).isEqualTo(PROJECT_ID);
        assertThat(outcome.errorCode()).isEqualTo("already_connected");
    }

    @Test
    @DisplayName("연동 처리 중 그 외 오류 → connect_failed")
    void completeSlackCallbackReturnsConnectFailedOnUnexpectedError() {
        IntegrationOAuthService service = service();
        when(oauthStateService.verify("signed-state", "slack"))
                .thenReturn(new OAuthStateClaims(PROJECT_ID, USER_ID, "slack"));
        when(integrationService.connectSlackWorkspace(USER_ID, PROJECT_ID, "auth-code"))
                .thenThrow(new BadGatewayException("Slack OAuth code exchange request failed."));

        OAuthCallbackOutcome outcome = service.completeSlackCallback("auth-code", "signed-state", null);

        assertThat(outcome.projectId()).isEqualTo(PROJECT_ID);
        assertThat(outcome.errorCode()).isEqualTo("connect_failed");
    }

    @Test
    @DisplayName("Jira authorize URL 조립 — Atlassian 전용 파라미터(audience·response_type·prompt) 포함")
    void buildJiraAuthorizeUrlIssuesStateAndAssemblesUrl() {
        IntegrationOAuthService service = service();
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(oauthStateService.issue(PROJECT_ID, USER_ID, "jira")).thenReturn("signed-state");

        String authorizeUrl = service.buildJiraAuthorizeUrl(USER_ID, PROJECT_ID);

        assertThat(authorizeUrl).isEqualTo(
                "https://atlassian.test/authorize"
                        + "?audience=api.atlassian.com"
                        + "&client_id=test-atlassian-client-id"
                        + "&scope=read:jira-work%20read:jira-user%20offline_access"
                        + "&redirect_uri=https://atlassian.test/callback"
                        + "&state=signed-state"
                        + "&response_type=code"
                        + "&prompt=consent"
        );
        verify(projectService).getProject(USER_ID, PROJECT_ID);
    }

    @Test
    @DisplayName("Jira 콜백 성공(사이트·프로젝트 선택 필요) 시 pending 완료 및 confirmed=false 반환")
    void completeJiraCallbackConnectsSiteOnSuccess() {
        IntegrationOAuthService service = service();
        when(oauthStateService.verify("signed-state", "jira"))
                .thenReturn(new OAuthStateClaims(PROJECT_ID, USER_ID, "jira"));
        Integration pendingIntegration = Integration.jiraPending(project(), new byte[] {1, 2, 3});
        when(integrationService.connectJiraSite(USER_ID, PROJECT_ID, "auth-code")).thenReturn(pendingIntegration);

        OAuthCallbackOutcome outcome = service.completeJiraCallback("auth-code", "signed-state", null);

        assertThat(outcome.projectId()).isEqualTo(PROJECT_ID);
        assertThat(outcome.provider()).isEqualTo("jira");
        assertThat(outcome.errorCode()).isNull();
        // 사이트·프로젝트를 아직 고르지 않은 pending 상태이므로 confirmed는 false다
        assertThat(outcome.confirmed()).isFalse();
        verify(integrationService).connectJiraSite(USER_ID, PROJECT_ID, "auth-code");
    }

    @Test
    @DisplayName("Jira 콜백 성공(자동 복원으로 이미 확정) 시 confirmed=true 반환")
    void completeJiraCallbackReturnsConfirmedTrueWhenAutoRestored() {
        IntegrationOAuthService service = service();
        when(oauthStateService.verify("signed-state", "jira"))
                .thenReturn(new OAuthStateClaims(PROJECT_ID, USER_ID, "jira"));
        Integration restoredIntegration = Integration.jiraPending(project(), new byte[] {1, 2, 3});
        restoredIntegration.completeJiraProject("cloud-1", "acme", "PROJ", "Project");
        when(integrationService.connectJiraSite(USER_ID, PROJECT_ID, "auth-code")).thenReturn(restoredIntegration);

        OAuthCallbackOutcome outcome = service.completeJiraCallback("auth-code", "signed-state", null);

        assertThat(outcome.projectId()).isEqualTo(PROJECT_ID);
        assertThat(outcome.errorCode()).isNull();
        assertThat(outcome.confirmed()).isTrue();
    }

    @Test
    @DisplayName("Jira state 검증 실패 → projectId 없이 invalid_state 반환, 연동 시도 안 함")
    void completeJiraCallbackRejectsInvalidState() {
        IntegrationOAuthService service = service();
        when(oauthStateService.verify("bad-state", "jira"))
                .thenThrow(new OAuthStateException("Invalid OAuth state signature."));

        OAuthCallbackOutcome outcome = service.completeJiraCallback("auth-code", "bad-state", null);

        assertThat(outcome.projectId()).isNull();
        assertThat(outcome.provider()).isEqualTo("jira");
        assertThat(outcome.errorCode()).isEqualTo("invalid_state");
        verify(integrationService, never()).connectJiraSite(any(), any(), any());
    }

    @Test
    @DisplayName("Jira 동의 거부(error=access_denied) → access_denied, 연동 시도 안 함")
    void completeJiraCallbackReturnsAccessDeniedWhenUserDeclines() {
        IntegrationOAuthService service = service();
        when(oauthStateService.verify("signed-state", "jira"))
                .thenReturn(new OAuthStateClaims(PROJECT_ID, USER_ID, "jira"));

        OAuthCallbackOutcome outcome = service.completeJiraCallback(null, "signed-state", "access_denied");

        assertThat(outcome.projectId()).isEqualTo(PROJECT_ID);
        assertThat(outcome.errorCode()).isEqualTo("access_denied");
        verify(integrationService, never()).connectJiraSite(any(), any(), any());
    }

    @Test
    @DisplayName("이미 확정된 Jira 연동에 재연결 시도 → already_connected")
    void completeJiraCallbackReturnsAlreadyConnectedOnConflict() {
        IntegrationOAuthService service = service();
        when(oauthStateService.verify("signed-state", "jira"))
                .thenReturn(new OAuthStateClaims(PROJECT_ID, USER_ID, "jira"));
        when(integrationService.connectJiraSite(USER_ID, PROJECT_ID, "auth-code"))
                .thenThrow(new ConflictException("Jira integration already exists."));

        OAuthCallbackOutcome outcome = service.completeJiraCallback("auth-code", "signed-state", null);

        assertThat(outcome.projectId()).isEqualTo(PROJECT_ID);
        assertThat(outcome.errorCode()).isEqualTo("already_connected");
    }

    private Project project() {
        User owner = new User("github", "12345", "owner@example.com", "Owner", null);
        ReflectionTestUtils.setField(owner, "id", USER_ID);
        Project project = new Project(owner, "History Tracker", null);
        ReflectionTestUtils.setField(project, "id", PROJECT_ID);
        return project;
    }

    private IntegrationOAuthService service() {
        return new IntegrationOAuthService(
                projectService,
                oauthStateService,
                slackProperties,
                atlassianProperties,
                integrationService
        );
    }
}
