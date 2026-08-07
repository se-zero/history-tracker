package com.history.backend.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.ConflictException;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.project.domain.Project;
import com.history.backend.project.service.ProjectService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * provider 공통 오케스트레이션만 검증한다 — state 검증, provider 에러 매핑, confirmed 전달.
 * 동의 URL 조립·code 교환은 provider별 OAuthConnectFlow 테스트가, 저장 정책은
 * IntegrationServiceTest가 담당한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IntegrationOAuthService: OAuth 동의 URL 발급·콜백 처리")
class IntegrationOAuthServiceTest {

    private static final UUID USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");

    @Mock
    private ProjectService projectService;

    @Mock
    private OAuthStateService oauthStateService;

    @Mock
    private OAuthConnectFlow slackFlow;

    @Mock
    private IntegrationService integrationService;

    @Test
    @DisplayName("authorize URL 발급 — 소유권 확인 후 state를 발급해 flow에 넘긴다")
    void buildAuthorizeUrlIssuesStateAndDelegatesToFlow() {
        IntegrationOAuthService service = service();
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(oauthStateService.issue(PROJECT_ID, USER_ID, "slack")).thenReturn("signed-state");
        when(slackFlow.buildAuthorizeUrl("signed-state")).thenReturn("https://slack.test/authorize?state=signed-state");

        String authorizeUrl = service.buildAuthorizeUrl(USER_ID, PROJECT_ID, IntegrationProvider.SLACK);

        assertThat(authorizeUrl).isEqualTo("https://slack.test/authorize?state=signed-state");
        verify(projectService).getProject(USER_ID, PROJECT_ID);
    }

    @Test
    @DisplayName("OAuth 흐름이 없는 provider → NotFound (라우트가 없던 것과 같게)")
    void buildAuthorizeUrlRejectsProviderWithoutFlow() {
        IntegrationOAuthService service = service();

        assertThatThrownBy(() -> service.buildAuthorizeUrl(USER_ID, PROJECT_ID, IntegrationProvider.GITHUB))
                .isInstanceOf(NotFoundException.class);
        verify(projectService, never()).getProject(any(), any());
    }

    @Test
    @DisplayName("콜백 성공 시 연동 완료 및 성공 outcome 반환")
    void completeCallbackConnectsOnSuccess() {
        IntegrationOAuthService service = service();
        when(oauthStateService.verify("signed-state", "slack"))
                .thenReturn(new OAuthStateClaims(PROJECT_ID, USER_ID, "slack"));
        when(integrationService.connectOAuth(USER_ID, PROJECT_ID, slackFlow, "auth-code"))
                .thenReturn(new IntegrationService.ConnectResult(null, false));

        OAuthCallbackOutcome outcome = service.completeCallback(
                IntegrationProvider.SLACK, "auth-code", "signed-state", null);

        assertThat(outcome.projectId()).isEqualTo(PROJECT_ID);
        assertThat(outcome.provider()).isEqualTo("slack");
        assertThat(outcome.errorCode()).isNull();
        assertThat(outcome.confirmed()).isFalse();
        verify(integrationService).connectOAuth(USER_ID, PROJECT_ID, slackFlow, "auth-code");
    }

    @Test
    @DisplayName("저장 결과가 자동 복원을 보고하면 confirmed=true로 전달한다")
    void completeCallbackPropagatesConfirmedFromConnectResult() {
        IntegrationOAuthService service = service();
        when(oauthStateService.verify("signed-state", "slack"))
                .thenReturn(new OAuthStateClaims(PROJECT_ID, USER_ID, "slack"));
        when(integrationService.connectOAuth(USER_ID, PROJECT_ID, slackFlow, "auth-code"))
                .thenReturn(new IntegrationService.ConnectResult(null, true));

        OAuthCallbackOutcome outcome = service.completeCallback(
                IntegrationProvider.SLACK, "auth-code", "signed-state", null);

        assertThat(outcome.errorCode()).isNull();
        assertThat(outcome.confirmed()).isTrue();
    }

    @Test
    @DisplayName("state 검증 실패 → projectId 없이 invalid_state 반환, 연동 시도 안 함")
    void completeCallbackRejectsInvalidState() {
        IntegrationOAuthService service = service();
        when(oauthStateService.verify("bad-state", "slack"))
                .thenThrow(new OAuthStateException("Invalid OAuth state signature."));

        OAuthCallbackOutcome outcome = service.completeCallback(
                IntegrationProvider.SLACK, "auth-code", "bad-state", null);

        assertThat(outcome.projectId()).isNull();
        assertThat(outcome.provider()).isEqualTo("slack");
        assertThat(outcome.errorCode()).isEqualTo("invalid_state");
        verify(integrationService, never()).connectOAuth(any(), any(), any(), any());
    }

    @Test
    @DisplayName("error=access_denied → access_denied, 연동 시도 안 함")
    void completeCallbackReturnsAccessDeniedWhenUserDeclines() {
        IntegrationOAuthService service = service();
        when(oauthStateService.verify("signed-state", "slack"))
                .thenReturn(new OAuthStateClaims(PROJECT_ID, USER_ID, "slack"));

        OAuthCallbackOutcome outcome = service.completeCallback(
                IntegrationProvider.SLACK, null, "signed-state", "access_denied");

        assertThat(outcome.projectId()).isEqualTo(PROJECT_ID);
        assertThat(outcome.errorCode()).isEqualTo("access_denied");
        verify(integrationService, never()).connectOAuth(any(), any(), any(), any());
    }

    @Test
    @DisplayName("access_denied가 아닌 provider error → connect_failed, 연동 시도 안 함")
    void completeCallbackReturnsConnectFailedForNonAccessDeniedProviderError() {
        IntegrationOAuthService service = service();
        when(oauthStateService.verify("signed-state", "slack"))
                .thenReturn(new OAuthStateClaims(PROJECT_ID, USER_ID, "slack"));

        OAuthCallbackOutcome outcome = service.completeCallback(
                IntegrationProvider.SLACK, null, "signed-state", "invalid_scope");

        assertThat(outcome.projectId()).isEqualTo(PROJECT_ID);
        assertThat(outcome.errorCode()).isEqualTo("connect_failed");
        verify(integrationService, never()).connectOAuth(any(), any(), any(), any());
    }

    @Test
    @DisplayName("이미 연동된 프로젝트 → already_connected")
    void completeCallbackReturnsAlreadyConnectedOnConflict() {
        IntegrationOAuthService service = service();
        when(oauthStateService.verify("signed-state", "slack"))
                .thenReturn(new OAuthStateClaims(PROJECT_ID, USER_ID, "slack"));
        when(integrationService.connectOAuth(USER_ID, PROJECT_ID, slackFlow, "auth-code"))
                .thenThrow(new ConflictException("Slack integration already exists."));

        OAuthCallbackOutcome outcome = service.completeCallback(
                IntegrationProvider.SLACK, "auth-code", "signed-state", null);

        assertThat(outcome.projectId()).isEqualTo(PROJECT_ID);
        assertThat(outcome.errorCode()).isEqualTo("already_connected");
    }

    @Test
    @DisplayName("연동 처리 중 그 외 오류 → connect_failed")
    void completeCallbackReturnsConnectFailedOnUnexpectedError() {
        IntegrationOAuthService service = service();
        when(oauthStateService.verify("signed-state", "slack"))
                .thenReturn(new OAuthStateClaims(PROJECT_ID, USER_ID, "slack"));
        when(integrationService.connectOAuth(USER_ID, PROJECT_ID, slackFlow, "auth-code"))
                .thenThrow(new BadGatewayException("Slack OAuth code exchange request failed."));

        OAuthCallbackOutcome outcome = service.completeCallback(
                IntegrationProvider.SLACK, "auth-code", "signed-state", null);

        assertThat(outcome.projectId()).isEqualTo(PROJECT_ID);
        assertThat(outcome.errorCode()).isEqualTo("connect_failed");
    }

    private Project project() {
        User owner = new User("github", "12345", "owner@example.com", "Owner", null);
        ReflectionTestUtils.setField(owner, "id", USER_ID);
        Project project = new Project(owner, "History Tracker", null);
        ReflectionTestUtils.setField(project, "id", PROJECT_ID);
        return project;
    }

    private IntegrationOAuthService service() {
        when(slackFlow.provider()).thenReturn(IntegrationProvider.SLACK);
        return new IntegrationOAuthService(
                projectService,
                oauthStateService,
                new OAuthConnectFlowRegistry(List.of(slackFlow)),
                integrationService
        );
    }
}
