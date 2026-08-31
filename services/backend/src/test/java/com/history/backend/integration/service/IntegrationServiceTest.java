package com.history.backend.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.common.crypto.CredentialCryptoService;
import com.history.backend.auth.domain.User;
import com.history.backend.auth.service.PlanService;
import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.BadRequestException;
import com.history.backend.common.error.ConflictException;
import com.history.backend.common.error.ForbiddenException;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.common.error.PlanLimitExceededException;
import com.history.backend.common.error.UnauthorizedException;
import com.history.backend.discord.service.DiscordClient;
import com.history.backend.discord.service.DiscordCredentialLifecycle;
import com.history.backend.discord.service.DiscordOAuthConnectFlow;
import com.history.backend.slack.service.SlackOAuthConnectFlow;
import com.history.backend.github.domain.GitHubInstallation;
import com.history.backend.github.dto.RepositoryResponse;
import com.history.backend.github.service.GitHubInstallationService;
import com.history.backend.github.service.InstallationTokenService;
import com.history.backend.googlechat.dto.GoogleChatSpaceListResponse;
import com.history.backend.googlechat.service.GoogleChatAccessTokenRefresher;
import com.history.backend.googlechat.service.GoogleChatClient;
import com.history.backend.googlechat.service.GoogleChatCredentialLifecycle;
import com.history.backend.googlechat.service.GoogleChatSelectionFlow;
import com.history.backend.graph.service.AiEngineGraphClient;
import com.history.backend.integration.domain.Integration;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.domain.SelectionStep;
import com.history.backend.integration.dto.IntegrationResponse;
import com.history.backend.integration.repository.IntegrationRepository;
import com.history.backend.jira.service.JiraAccessTokenRefresher;
import com.history.backend.jira.service.JiraClient;
import com.history.backend.jira.service.JiraCredentialLifecycle;
import com.history.backend.jira.service.JiraOAuthClient;
import com.history.backend.jira.service.JiraSelectionFlow;
import com.history.backend.project.domain.Project;
import com.history.backend.project.service.ProjectService;
import com.history.backend.shared.domain.Checkpoint;
import com.history.backend.shared.repository.CheckpointRepository;
import com.history.backend.slack.service.SlackClient;
import com.history.backend.slack.service.SlackCredentialLifecycle;
import com.history.backend.integration.service.SlackCredentialCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
@DisplayName("IntegrationService: 연동 생성·조회·해제")
class IntegrationServiceTest {

    private static final UUID OWNER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");
    private static final UUID PROJECT_ID_2 = UUID.fromString("a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1");
    private static final UUID INSTALLATION_ID = UUID.fromString("45b30a75-46d0-4402-b842-9e9c7d07e9ab");
    private static final UUID INTEGRATION_ID = UUID.fromString("2f0f1c2e-9a4e-4f0e-9d1a-6b0f8c3d7a55");
    private static final UUID INTEGRATION_ID_2 = UUID.fromString("3c3c3c3c-3c3c-3c3c-3c3c-3c3c3c3c3c3c");
    // 중립 값(pending_selection) 이전에 Jira가 쓰던 status 값
    private static final String LEGACY_PENDING_PROJECT = "pending_project";

    @Mock
    private IntegrationRepository integrationRepository;

    @Mock
    private CheckpointRepository checkpointRepository;

    @Mock
    private ProjectService projectService;

    @Mock
    private GitHubInstallationService gitHubInstallationService;

    @Mock
    private InstallationTokenService installationTokenService;

    @Mock
    private CredentialCryptoService credentialCryptoService;

    @Mock
    private SlackClient slackClient;

    @Mock
    private JiraOAuthClient jiraOAuthClient;

    @Mock
    private JiraClient jiraClient;

    @Mock
    private JiraCredentialCodec jiraCredentialCodec;

    @Mock
    private JiraTokenService jiraTokenService;

    @Mock
    private DiscordClient discordClient;

    @Mock
    private GoogleChatClient googleChatClient;

    @Mock
    private GoogleChatCredentialCodec googleChatCredentialCodec;

    @Mock
    private GoogleChatTokenService googleChatTokenService;

    @Mock
    private PipelineWorkerClient pipelineWorkerClient;

    @Mock
    private AiEngineGraphClient aiEngineGraphClient;

    // disconnect가 실제로 위임하는지만 별도로 고정하는 테스트에서 쓴다(serviceWithRevocationService) —
    // 나머지 disconnect 테스트는 service()가 물리는 실제 IntegrationRevocationService를 통해
    // provider 클라이언트까지 닿는 기존 동작을 그대로 검증한다.
    @Mock
    private IntegrationRevocationService revocationService;

    @Mock
    private PlanService planService;

    private final NoopTransactionManager transactionManager = new NoopTransactionManager();

    @Test
    @DisplayName("소유 프로젝트 연동 목록에 최신 동기화 시각 포함")
    void listIntegrationsReturnsIntegrationsWithLatestSyncTimeForOwnedProject() {
        IntegrationService service = service();
        Project project = project();
        Integration githubIntegration = Integration.github(project, installation(), 12345L, "acme/widget", "main");
        Instant syncedAt = Instant.parse("2026-06-15T03:00:00Z");
        Checkpoint olderCheckpoint = checkpoint(project, "github/github_commits",
                Instant.parse("2026-06-15T01:00:00Z"));
        Checkpoint newerCheckpoint = checkpoint(project, "github/github_pull_requests", syncedAt);
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project);
        when(integrationRepository.findAllByProject_IdOrderByCreatedAtDesc(PROJECT_ID))
                .thenReturn(List.of(githubIntegration));
        when(checkpointRepository.findAllByProject_Id(PROJECT_ID))
                .thenReturn(List.of(olderCheckpoint, newerCheckpoint));

        List<IntegrationResponse> result = service.listIntegrations(OWNER_ID, PROJECT_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).provider()).isEqualTo("github");
        assertThat(result.get(0).displayName()).isEqualTo("acme/widget");
        // provider별 여러 cursor_key 중 가장 최신 갱신 시각을 노출
        assertThat(result.get(0).lastSyncedAt()).isEqualTo(syncedAt);
    }

    @Test
    @DisplayName("체크포인트 없으면 동기화 시각 null 반환")
    void listIntegrationsReturnsNullSyncTimeWhenNoCheckpoint() {
        IntegrationService service = service();
        Project project = project();
        Integration githubIntegration = Integration.github(project, installation(), 12345L, "acme/widget", "main");
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project);
        when(integrationRepository.findAllByProject_IdOrderByCreatedAtDesc(PROJECT_ID))
                .thenReturn(List.of(githubIntegration));
        when(checkpointRepository.findAllByProject_Id(PROJECT_ID)).thenReturn(List.of());

        List<IntegrationResponse> result = service.listIntegrations(OWNER_ID, PROJECT_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).lastSyncedAt()).isNull();
    }

    @Test
    @DisplayName("소유 프로젝트·설치에 GitHub 연동 저장 성공")
    void connectGitHubRepositorySavesIntegrationForOwnedProjectAndInstallation() {
        IntegrationService service = service();
        Project project = project();
        GitHubInstallation installation = installation();
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project);
        when(gitHubInstallationService.getAccessibleInstallation(OWNER_ID, INSTALLATION_ID))
                .thenReturn(installation);
        when(gitHubInstallationService.findRepositories(OWNER_ID, INSTALLATION_ID))
                .thenReturn(List.of(visibleRepo()));
        when(integrationRepository.existsByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.GITHUB))
                .thenReturn(false);
        doAnswer(invocation -> {
            assertThat(transactionManager.transactionActive).isFalse();
            return "installation-token";
        }).when(installationTokenService).getInstallationAccessToken(INSTALLATION_ID);
        when(integrationRepository.saveAndFlush(any(Integration.class)))
                .thenAnswer(invocation -> {
                    assertThat(transactionManager.transactionActive).isTrue();
                    return invocation.getArgument(0);
                });
        doAnswer(invocation -> {
            assertThat(transactionManager.transactionActive).isFalse();
            return null;
        }).when(pipelineWorkerClient).triggerCollection(IntegrationProvider.GITHUB, PROJECT_ID);

        Integration result = service.connectGitHubRepository(
                OWNER_ID,
                PROJECT_ID,
                INSTALLATION_ID,
                12345L,
                "  acme/widget  ",
                "  main  "
        );

        assertThat(result.getProject()).isSameAs(project);
        assertThat(result.getInstallation()).isSameAs(installation);
        assertThat(result.getProvider()).isEqualTo(IntegrationProvider.GITHUB);
        assertThat(result.getGitHubRepositoryId()).isEqualTo(12345L);
        assertThat(result.getGitHubRepositoryFullName()).isEqualTo("acme/widget");
        assertThat(result.getGitHubBranch()).isEqualTo("main");
        verify(installationTokenService).getInstallationAccessToken(INSTALLATION_ID);
        verify(pipelineWorkerClient).triggerCollection(IntegrationProvider.GITHUB, PROJECT_ID);
        // 무료 티어 provider 연동 한도 검증 → 저장 성공 후 이력 기록까지 이어진다
        verify(planService).ensureProviderConnectable(OWNER_ID, IntegrationProvider.GITHUB);
        verify(planService).recordProviderConnected(OWNER_ID, IntegrationProvider.GITHUB);
        InOrder order = inOrder(
                projectService,
                planService,
                gitHubInstallationService,
                integrationRepository,
                installationTokenService
        );
        order.verify(projectService).getProject(OWNER_ID, PROJECT_ID);
        order.verify(planService).ensureProviderConnectable(OWNER_ID, IntegrationProvider.GITHUB);
        order.verify(gitHubInstallationService).getAccessibleInstallation(OWNER_ID, INSTALLATION_ID);
        order.verify(integrationRepository).existsByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.GITHUB);
        order.verify(gitHubInstallationService).findRepositories(OWNER_ID, INSTALLATION_ID);
        order.verify(installationTokenService).getInstallationAccessToken(INSTALLATION_ID);
    }

    @Test
    @DisplayName("무료 티어 사용자는 GitHub 연동 저장 시 증분 수집이 꺼진 채로 저장된다")
    void connectGitHubRepositoryDisablesIncrementalCollectionForFreeTierUser() {
        IntegrationService service = service();
        Project project = project();
        GitHubInstallation installation = installation();
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project);
        when(gitHubInstallationService.getAccessibleInstallation(OWNER_ID, INSTALLATION_ID))
                .thenReturn(installation);
        when(gitHubInstallationService.findRepositories(OWNER_ID, INSTALLATION_ID))
                .thenReturn(List.of(visibleRepo()));
        when(integrationRepository.existsByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.GITHUB))
                .thenReturn(false);
        when(installationTokenService.getInstallationAccessToken(INSTALLATION_ID))
                .thenReturn("installation-token");
        when(integrationRepository.saveAndFlush(any(Integration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(planService.isIncrementalEnabled(OWNER_ID)).thenReturn(false);

        Integration result = service.connectGitHubRepository(
                OWNER_ID,
                PROJECT_ID,
                INSTALLATION_ID,
                12345L,
                "acme/widget",
                "main"
        );

        assertThat(result.isIncrementalEnabled()).isFalse();
    }

    @Test
    @DisplayName("무료 티어 provider 연동 한도 초과 시 GitHub 연동 거부 — 저장·수집 트리거 모두 일어나지 않음")
    void connectGitHubRepositoryRejectsWhenProviderLimitExceeded() {
        IntegrationService service = service();
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        doThrow(new PlanLimitExceededException("Free plan only allows GitHub/Slack/Jira, one each."))
                .when(planService).ensureProviderConnectable(OWNER_ID, IntegrationProvider.GITHUB);

        assertThatThrownBy(() -> service.connectGitHubRepository(
                OWNER_ID,
                PROJECT_ID,
                INSTALLATION_ID,
                12345L,
                "acme/widget",
                "main"
        ))
                .isInstanceOf(PlanLimitExceededException.class);

        verify(integrationRepository, never()).saveAndFlush(any(Integration.class));
        verify(pipelineWorkerClient, never()).triggerCollection(any(), any());
    }

    @Test
    @DisplayName("설치 토큰 발급 실패 시 저장 트랜잭션 시작하지 않음")
    void connectGitHubRepositoryDoesNotStartSaveTransactionWhenInstallationTokenCannotBeIssued() {
        IntegrationService service = service();
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(gitHubInstallationService.getAccessibleInstallation(OWNER_ID, INSTALLATION_ID))
                .thenReturn(installation());
        when(gitHubInstallationService.findRepositories(OWNER_ID, INSTALLATION_ID))
                .thenReturn(List.of(visibleRepo()));
        when(integrationRepository.existsByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.GITHUB))
                .thenReturn(false);
        when(installationTokenService.getInstallationAccessToken(INSTALLATION_ID))
                .thenThrow(new IllegalStateException("GitHub token issuance failed."));

        assertThatThrownBy(() -> service.connectGitHubRepository(
                OWNER_ID,
                PROJECT_ID,
                INSTALLATION_ID,
                12345L,
                "acme/widget",
                "main"
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("GitHub token issuance failed.");

        assertThat(transactionManager.beginCount).isZero();
        assertThat(transactionManager.rollbackCount).isZero();
        verify(integrationRepository, never()).saveAndFlush(any(Integration.class));
        verify(pipelineWorkerClient, never()).triggerCollection(IntegrationProvider.GITHUB, PROJECT_ID);
    }

    @Test
    @DisplayName("프로젝트 생성 + GitHub 연동을 한 트랜잭션에서 저장하고 커밋 뒤 수집 트리거")
    void createProjectWithGitHubRepositoryCommitsProjectAndIntegrationTogether() {
        IntegrationService service = service();
        Project project = project();
        GitHubInstallation installation = installation();
        when(gitHubInstallationService.getAccessibleInstallation(OWNER_ID, INSTALLATION_ID))
                .thenReturn(installation);
        when(gitHubInstallationService.findRepositories(OWNER_ID, INSTALLATION_ID))
                .thenReturn(List.of(visibleRepo()));
        doAnswer(invocation -> {
            assertThat(transactionManager.transactionActive).isFalse();
            return "installation-token";
        }).when(installationTokenService).getInstallationAccessToken(INSTALLATION_ID);
        when(projectService.createProject(OWNER_ID, "History Tracker", "GraphRAG backend"))
                .thenAnswer(invocation -> {
                    // 프로젝트 생성이 연동 저장과 같은 트랜잭션 안이어야 함께 롤백된다
                    assertThat(transactionManager.transactionActive).isTrue();
                    return project;
                });
        when(integrationRepository.saveAndFlush(any(Integration.class)))
                .thenAnswer(invocation -> {
                    assertThat(transactionManager.transactionActive).isTrue();
                    return invocation.getArgument(0);
                });
        doAnswer(invocation -> {
            assertThat(transactionManager.transactionActive).isFalse();
            return null;
        }).when(pipelineWorkerClient).triggerCollection(IntegrationProvider.GITHUB, PROJECT_ID);

        Project result = service.createProjectWithGitHubRepository(
                OWNER_ID,
                "History Tracker",
                "GraphRAG backend",
                INSTALLATION_ID,
                12345L,
                "  acme/widget  ",
                "  main  "
        );

        assertThat(result).isSameAs(project);
        assertThat(transactionManager.beginCount).isEqualTo(1);
        assertThat(transactionManager.rollbackCount).isZero();
        ArgumentCaptor<Integration> captor = ArgumentCaptor.forClass(Integration.class);
        verify(integrationRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getProject()).isSameAs(project);
        assertThat(captor.getValue().getGitHubRepositoryFullName()).isEqualTo("acme/widget");
        assertThat(captor.getValue().getGitHubBranch()).isEqualTo("main");
        verify(pipelineWorkerClient).triggerCollection(IntegrationProvider.GITHUB, PROJECT_ID);
        // 무료 티어 provider 연동 한도 검증 → 저장 성공 후 이력 기록까지 이어진다 (이 경로는 계획상
        // 처음으로 이 검사가 붙는 자리다)
        verify(planService).ensureProviderConnectable(OWNER_ID, IntegrationProvider.GITHUB);
        verify(planService).recordProviderConnected(OWNER_ID, IntegrationProvider.GITHUB);
        InOrder createOrder = inOrder(gitHubInstallationService, planService, installationTokenService, projectService);
        createOrder.verify(gitHubInstallationService).getAccessibleInstallation(OWNER_ID, INSTALLATION_ID);
        createOrder.verify(planService).ensureProviderConnectable(OWNER_ID, IntegrationProvider.GITHUB);
        createOrder.verify(gitHubInstallationService).findRepositories(OWNER_ID, INSTALLATION_ID);
        createOrder.verify(installationTokenService).getInstallationAccessToken(INSTALLATION_ID);
        createOrder.verify(projectService).createProject(OWNER_ID, "History Tracker", "GraphRAG backend");
    }

    @Test
    @DisplayName("무료 티어 provider 연동 한도 초과 시 프로젝트+GitHub 연동 생성 거부 — 프로젝트도 만들어지지 않음")
    void createProjectWithGitHubRepositoryRejectsWhenProviderLimitExceeded() {
        IntegrationService service = service();
        when(gitHubInstallationService.getAccessibleInstallation(OWNER_ID, INSTALLATION_ID))
                .thenReturn(installation());
        doThrow(new PlanLimitExceededException("Free plan only allows GitHub/Slack/Jira, one each."))
                .when(planService).ensureProviderConnectable(OWNER_ID, IntegrationProvider.GITHUB);

        assertThatThrownBy(() -> service.createProjectWithGitHubRepository(
                OWNER_ID,
                "History Tracker",
                null,
                INSTALLATION_ID,
                12345L,
                "acme/widget",
                "main"
        ))
                .isInstanceOf(PlanLimitExceededException.class);

        assertThat(transactionManager.beginCount).isZero();
        verify(installationTokenService, never()).getInstallationAccessToken(any());
        verify(projectService, never()).createProject(any(), anyString(), any());
        verify(integrationRepository, never()).saveAndFlush(any(Integration.class));
        verify(pipelineWorkerClient, never()).triggerCollection(any(), any());
    }

    @Test
    @DisplayName("연동 저장이 실패하면 프로젝트 생성까지 롤백되고 수집 트리거도 하지 않음")
    void createProjectWithGitHubRepositoryRollsBackProjectWhenIntegrationSaveFails() {
        IntegrationService service = service();
        when(gitHubInstallationService.getAccessibleInstallation(OWNER_ID, INSTALLATION_ID))
                .thenReturn(installation());
        when(gitHubInstallationService.findRepositories(OWNER_ID, INSTALLATION_ID))
                .thenReturn(List.of(visibleRepo()));
        when(installationTokenService.getInstallationAccessToken(INSTALLATION_ID))
                .thenReturn("installation-token");
        when(projectService.createProject(OWNER_ID, "History Tracker", null)).thenReturn(project());
        when(integrationRepository.saveAndFlush(any(Integration.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> service.createProjectWithGitHubRepository(
                OWNER_ID,
                "History Tracker",
                null,
                INSTALLATION_ID,
                12345L,
                "acme/widget",
                "main"
        ))
                .isInstanceOf(ConflictException.class);

        assertThat(transactionManager.rollbackCount).isEqualTo(1);
        verify(pipelineWorkerClient, never()).triggerCollection(any(), any());
    }

    @Test
    @DisplayName("설치 토큰 발급 실패 시 프로젝트를 만들지 않음 — 트랜잭션 시작 전")
    void createProjectWithGitHubRepositoryDoesNotCreateProjectWhenInstallationTokenCannotBeIssued() {
        IntegrationService service = service();
        when(gitHubInstallationService.getAccessibleInstallation(OWNER_ID, INSTALLATION_ID))
                .thenReturn(installation());
        when(gitHubInstallationService.findRepositories(OWNER_ID, INSTALLATION_ID))
                .thenReturn(List.of(visibleRepo()));
        when(installationTokenService.getInstallationAccessToken(INSTALLATION_ID))
                .thenThrow(new IllegalStateException("GitHub token issuance failed."));

        assertThatThrownBy(() -> service.createProjectWithGitHubRepository(
                OWNER_ID,
                "History Tracker",
                null,
                INSTALLATION_ID,
                12345L,
                "acme/widget",
                "main"
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("GitHub token issuance failed.");

        assertThat(transactionManager.beginCount).isZero();
        verify(projectService, never()).createProject(any(), anyString(), any());
        verify(integrationRepository, never()).saveAndFlush(any(Integration.class));
        verify(pipelineWorkerClient, never()).triggerCollection(any(), any());
    }

    @Test
    @DisplayName("중복 GitHub 연동 거부")
    void connectGitHubRepositoryRejectsDuplicateGitHubProvider() {
        IntegrationService service = service();
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(gitHubInstallationService.getAccessibleInstallation(OWNER_ID, INSTALLATION_ID))
                .thenReturn(installation());
        when(integrationRepository.existsByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.GITHUB))
                .thenReturn(true);

        assertThatThrownBy(() -> service.connectGitHubRepository(
                OWNER_ID,
                PROJECT_ID,
                INSTALLATION_ID,
                12345L,
                "acme/widget",
                "main"
        ))
                .isInstanceOf(ConflictException.class)
                .hasMessage("GitHub integration already exists.");
        verify(gitHubInstallationService, never()).findRepositories(any(), any());
        verify(installationTokenService, never()).getInstallationAccessToken(any());
    }

    @Test
    @DisplayName("존재하지 않는 설치 정보를 NotFoundException으로 전파")
    void connectGitHubRepositoryPropagatesMissingInstallationAsNotFound() {
        IntegrationService service = service();
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(gitHubInstallationService.getAccessibleInstallation(OWNER_ID, INSTALLATION_ID))
                .thenThrow(new NotFoundException("GitHub installation not found."));

        assertThatThrownBy(() -> service.connectGitHubRepository(
                OWNER_ID,
                PROJECT_ID,
                INSTALLATION_ID,
                12345L,
                "acme/widget",
                "main"
        ))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("GitHub installation not found.");
    }

    @Test
    @DisplayName("GitHub 연동 시 유니크 제약 위반을 ConflictException으로 변환")
    void connectGitHubRepositoryConvertsUniqueConstraintViolationToConflict() {
        IntegrationService service = service();
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(gitHubInstallationService.getAccessibleInstallation(OWNER_ID, INSTALLATION_ID))
                .thenReturn(installation());
        when(gitHubInstallationService.findRepositories(OWNER_ID, INSTALLATION_ID))
                .thenReturn(List.of(visibleRepo()));
        when(integrationRepository.existsByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.GITHUB))
                .thenReturn(false);
        when(integrationRepository.saveAndFlush(any(Integration.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate integration"));

        assertThatThrownBy(() -> service.connectGitHubRepository(
                OWNER_ID,
                PROJECT_ID,
                INSTALLATION_ID,
                12345L,
                "acme/widget",
                "main"
        ))
                .isInstanceOf(ConflictException.class)
                .hasMessage("GitHub integration already exists.");
    }

    @Test
    @DisplayName("GitHub 연동 — 사용자 ACL 목록에 없는 저장소는 404, 저장·설치 토큰·수집 트리거 없음")
    void connectGitHubRepositoryRejectsRepositoryNotVisibleToUser() {
        IntegrationService service = service();
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(gitHubInstallationService.getAccessibleInstallation(OWNER_ID, INSTALLATION_ID))
                .thenReturn(installation());
        when(integrationRepository.existsByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.GITHUB))
                .thenReturn(false);
        when(gitHubInstallationService.findRepositories(OWNER_ID, INSTALLATION_ID))
                .thenReturn(List.of(visibleRepo(99999L, "acme/secret")));

        assertThatThrownBy(() -> service.connectGitHubRepository(
                OWNER_ID,
                PROJECT_ID,
                INSTALLATION_ID,
                12345L,
                "acme/widget",
                "main"
        ))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("GitHub repository not found.");

        verify(gitHubInstallationService).findRepositories(OWNER_ID, INSTALLATION_ID);
        verify(installationTokenService, never()).getInstallationAccessToken(any());
        verify(integrationRepository, never()).saveAndFlush(any(Integration.class));
        verify(pipelineWorkerClient, never()).triggerCollection(any(), any());
    }

    @Test
    @DisplayName("GitHub 연동 — repositoryId만 같고 fullName이 다르면 404")
    void connectGitHubRepositoryRejectsWhenFullNameDoesNotMatchVisibleRepository() {
        IntegrationService service = service();
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(gitHubInstallationService.getAccessibleInstallation(OWNER_ID, INSTALLATION_ID))
                .thenReturn(installation());
        when(integrationRepository.existsByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.GITHUB))
                .thenReturn(false);
        when(gitHubInstallationService.findRepositories(OWNER_ID, INSTALLATION_ID))
                .thenReturn(List.of(visibleRepo(12345L, "acme/other")));

        assertThatThrownBy(() -> service.connectGitHubRepository(
                OWNER_ID,
                PROJECT_ID,
                INSTALLATION_ID,
                12345L,
                "acme/widget",
                "main"
        ))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("GitHub repository not found.");

        verify(installationTokenService, never()).getInstallationAccessToken(any());
        verify(integrationRepository, never()).saveAndFlush(any(Integration.class));
    }

    @Test
    @DisplayName("GitHub 연동 — fullName만 같고 repositoryId가 다르면 404")
    void connectGitHubRepositoryRejectsWhenRepositoryIdDoesNotMatchVisibleRepository() {
        IntegrationService service = service();
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(gitHubInstallationService.getAccessibleInstallation(OWNER_ID, INSTALLATION_ID))
                .thenReturn(installation());
        when(integrationRepository.existsByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.GITHUB))
                .thenReturn(false);
        when(gitHubInstallationService.findRepositories(OWNER_ID, INSTALLATION_ID))
                .thenReturn(List.of(visibleRepo(99999L, "acme/widget")));

        assertThatThrownBy(() -> service.connectGitHubRepository(
                OWNER_ID,
                PROJECT_ID,
                INSTALLATION_ID,
                12345L,
                "acme/widget",
                "main"
        ))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("GitHub repository not found.");

        verify(installationTokenService, never()).getInstallationAccessToken(any());
        verify(integrationRepository, never()).saveAndFlush(any(Integration.class));
    }

    @Test
    @DisplayName("GitHub 연동 — 재로그인 필요(403)면 저장·설치 토큰·수집 트리거 없이 전파")
    void connectGitHubRepositoryPropagatesReauthorizationRequired() {
        IntegrationService service = service();
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(gitHubInstallationService.getAccessibleInstallation(OWNER_ID, INSTALLATION_ID))
                .thenReturn(installation());
        when(integrationRepository.existsByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.GITHUB))
                .thenReturn(false);
        when(gitHubInstallationService.findRepositories(OWNER_ID, INSTALLATION_ID))
                .thenThrow(new ForbiddenException("GitHub reauthorization required."));

        assertThatThrownBy(() -> service.connectGitHubRepository(
                OWNER_ID,
                PROJECT_ID,
                INSTALLATION_ID,
                12345L,
                "acme/widget",
                "main"
        ))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("GitHub reauthorization required.");

        verify(installationTokenService, never()).getInstallationAccessToken(any());
        verify(integrationRepository, never()).saveAndFlush(any(Integration.class));
        verify(pipelineWorkerClient, never()).triggerCollection(any(), any());
    }

    @Test
    @DisplayName("프로젝트+GitHub 생성 — 사용자 ACL 목록에 없으면 프로젝트·설치 토큰 없음")
    void createProjectWithGitHubRepositoryRejectsRepositoryNotVisibleToUser() {
        IntegrationService service = service();
        when(gitHubInstallationService.getAccessibleInstallation(OWNER_ID, INSTALLATION_ID))
                .thenReturn(installation());
        when(gitHubInstallationService.findRepositories(OWNER_ID, INSTALLATION_ID))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.createProjectWithGitHubRepository(
                OWNER_ID,
                "History Tracker",
                null,
                INSTALLATION_ID,
                12345L,
                "acme/widget",
                "main"
        ))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("GitHub repository not found.");

        verify(gitHubInstallationService).findRepositories(OWNER_ID, INSTALLATION_ID);
        verify(installationTokenService, never()).getInstallationAccessToken(any());
        verify(projectService, never()).createProject(any(), anyString(), any());
        verify(integrationRepository, never()).saveAndFlush(any(Integration.class));
        verify(pipelineWorkerClient, never()).triggerCollection(any(), any());
    }

    @Test
    @DisplayName("프로젝트+GitHub 생성 — 재로그인 필요(403)면 프로젝트·설치 토큰 없이 전파")
    void createProjectWithGitHubRepositoryPropagatesReauthorizationRequired() {
        IntegrationService service = service();
        when(gitHubInstallationService.getAccessibleInstallation(OWNER_ID, INSTALLATION_ID))
                .thenReturn(installation());
        when(gitHubInstallationService.findRepositories(OWNER_ID, INSTALLATION_ID))
                .thenThrow(new ForbiddenException("GitHub reauthorization required."));

        assertThatThrownBy(() -> service.createProjectWithGitHubRepository(
                OWNER_ID,
                "History Tracker",
                null,
                INSTALLATION_ID,
                12345L,
                "acme/widget",
                "main"
        ))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("GitHub reauthorization required.");

        verify(installationTokenService, never()).getInstallationAccessToken(any());
        verify(projectService, never()).createProject(any(), anyString(), any());
        verify(integrationRepository, never()).saveAndFlush(any(Integration.class));
    }

    @Test
    @DisplayName("선택 단계가 없는 provider — 교환 결과를 그대로 확정 저장하고 수집을 트리거한다")
    void connectOAuthSavesConfirmedIntegrationForProviderWithoutSelectionStep() {
        IntegrationService service = service();
        Project project = project();
        OAuthConnectFlow flow = connectFlow(IntegrationProvider.SLACK);
        byte[] encryptedCredential = new byte[] {1, 2, 3};
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project);
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.SLACK))
                .thenReturn(Optional.empty());
        when(flow.exchangeCode("auth-code")).thenReturn(new OAuthConnection(
                "xoxp-token", Map.of("workspace_id", "T123", "workspace_name", "Acme")));
        when(credentialCryptoService.encrypt("xoxp-token")).thenReturn(encryptedCredential);
        when(integrationRepository.saveAndFlush(any(Integration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doAnswer(invocation -> {
            assertThat(transactionManager.transactionActive).isFalse();
            return null;
        }).when(pipelineWorkerClient).triggerCollection(IntegrationProvider.SLACK, PROJECT_ID);

        IntegrationService.ConnectResult result = service.connectOAuth(OWNER_ID, PROJECT_ID, flow, "auth-code");

        Integration integration = result.integration();
        assertThat(integration.getProject()).isSameAs(project);
        assertThat(integration.getInstallation()).isNull();
        assertThat(integration.getProvider()).isEqualTo(IntegrationProvider.SLACK);
        assertThat(integration.isPendingSelection()).isFalse();
        // flow가 담아 넘긴 external_ref가 그대로 저장된다 (키 이름은 provider가 정한다)
        assertThat(integration.externalRefValue("workspace_id")).isEqualTo("T123");
        assertThat(integration.externalRefValue("workspace_name")).isEqualTo("Acme");
        assertThat(integration.getEncryptedCredential()).containsExactly(encryptedCredential);
        // 자동 복원 개념이 없는 provider는 "복원 완료" 배너 대상이 아니다
        assertThat(result.selectionRestored()).isFalse();
        verify(pipelineWorkerClient).triggerCollection(IntegrationProvider.SLACK, PROJECT_ID);
        // 무료 티어 provider 연동 한도 검증 → 저장 성공 후 이력 기록까지 이어진다
        verify(planService).ensureProviderConnectable(OWNER_ID, IntegrationProvider.SLACK);
        verify(planService).recordProviderConnected(OWNER_ID, IntegrationProvider.SLACK);
    }

    @Test
    @DisplayName("유료 사용자는 OAuth 연동 저장 시 증분 수집이 켜진 채로 저장된다")
    void connectOAuthEnablesIncrementalCollectionForPaidUser() {
        IntegrationService service = service();
        Project project = project();
        OAuthConnectFlow flow = connectFlow(IntegrationProvider.SLACK);
        byte[] encryptedCredential = new byte[] {1, 2, 3};
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project);
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.SLACK))
                .thenReturn(Optional.empty());
        when(flow.exchangeCode("auth-code")).thenReturn(new OAuthConnection(
                "xoxp-token", Map.of("workspace_id", "T123", "workspace_name", "Acme")));
        when(credentialCryptoService.encrypt("xoxp-token")).thenReturn(encryptedCredential);
        when(integrationRepository.saveAndFlush(any(Integration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(planService.isIncrementalEnabled(OWNER_ID)).thenReturn(true);

        IntegrationService.ConnectResult result = service.connectOAuth(OWNER_ID, PROJECT_ID, flow, "auth-code");

        assertThat(result.integration().isIncrementalEnabled()).isTrue();
    }

    @Test
    @DisplayName("무료 티어 provider 연동 한도 초과 시 거부 — code 교환도 저장도 일어나지 않음")
    void connectOAuthRejectsWhenProviderLimitExceeded() {
        IntegrationService service = service();
        OAuthConnectFlow flow = connectFlow(IntegrationProvider.SLACK);
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        doThrow(new PlanLimitExceededException("Free plan only allows GitHub/Slack/Jira, one each."))
                .when(planService).ensureProviderConnectable(OWNER_ID, IntegrationProvider.SLACK);

        assertThatThrownBy(() -> service.connectOAuth(OWNER_ID, PROJECT_ID, flow, "auth-code"))
                .isInstanceOf(PlanLimitExceededException.class);

        // 이미 확정된 연동 409 검사와 같은 이유 — 1회용 code를 낭비하지 않는다
        verify(flow, never()).exchangeCode(anyString());
        verify(integrationRepository, never()).saveAndFlush(any(Integration.class));
    }

    @Test
    @DisplayName("이미 확정된 연동 → 409, code 교환 호출 안 함")
    void connectOAuthRejectsAlreadyConnectedProviderWithoutExchangingCode() {
        IntegrationService service = service();
        OAuthConnectFlow flow = connectFlow(IntegrationProvider.SLACK);
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.SLACK))
                .thenReturn(Optional.of(Integration.oauth(
                        project(),
                        IntegrationProvider.SLACK,
                        Map.of("workspace_id", "T123", "workspace_name", "Acme"),
                        new byte[] {1, 2, 3})));

        assertThatThrownBy(() -> service.connectOAuth(OWNER_ID, PROJECT_ID, flow, "auth-code"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Slack integration already exists.");
        // 이미 연동된 프로젝트라면 1회용 code를 낭비하지 않는다
        verify(flow, never()).exchangeCode(anyString());
    }

    @Test
    @DisplayName("연동 저장 시 유니크 제약 위반을 ConflictException으로 변환")
    void connectOAuthConvertsUniqueConstraintViolationToConflict() {
        IntegrationService service = service();
        OAuthConnectFlow flow = connectFlow(IntegrationProvider.SLACK);
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.SLACK))
                .thenReturn(Optional.empty());
        when(flow.exchangeCode("auth-code")).thenReturn(new OAuthConnection(
                "xoxp-token", Map.of("workspace_id", "T123", "workspace_name", "Acme")));
        when(credentialCryptoService.encrypt("xoxp-token")).thenReturn(new byte[] {1, 2, 3});
        when(integrationRepository.saveAndFlush(any(Integration.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate integration"));

        assertThatThrownBy(() -> service.connectOAuth(OWNER_ID, PROJECT_ID, flow, "auth-code"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Slack integration already exists.");
    }

    // ─── connectSlackWorkspace (BYO xoxp 붙여넣기) ───────────────────────────────

    @Test
    @DisplayName("BYO Slack 연결 성공 — JSON 자격증명(평문 xoxp가 아님), external_ref 4키, "
            + "수집 트리거, plan ensure+record. 반환은 Integration")
    void connectSlackWorkspaceSavesJsonCredentialAndTriggersCollection() {
        IntegrationService service = service();
        Project project = project();
        byte[] encryptedCredential = new byte[] {1, 2, 3};
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project);
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.SLACK))
                .thenReturn(Optional.empty());
        when(slackClient.verifyToken("xoxp-user"))
                .thenReturn(new SlackClient.SlackVerifiedUser("T123", "Acme", "U123"));
        when(credentialCryptoService.encrypt(anyString())).thenReturn(encryptedCredential);
        when(integrationRepository.saveAndFlush(any(Integration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doAnswer(invocation -> {
            assertThat(transactionManager.transactionActive).isFalse();
            return null;
        }).when(pipelineWorkerClient).triggerCollection(IntegrationProvider.SLACK, PROJECT_ID);

        Integration integration = service.connectSlackWorkspace(OWNER_ID, PROJECT_ID, "  xoxp-user  ");

        assertThat(integration.getProject()).isSameAs(project);
        assertThat(integration.getProvider()).isEqualTo(IntegrationProvider.SLACK);
        assertThat(integration.isPendingSelection()).isFalse();
        assertThat(integration.externalRefValue(SlackOAuthConnectFlow.WORKSPACE_ID)).isEqualTo("T123");
        assertThat(integration.externalRefValue(SlackOAuthConnectFlow.WORKSPACE_NAME)).isEqualTo("Acme");
        assertThat(integration.externalRefValue(SlackOAuthConnectFlow.CONNECTED_USER_ID)).isEqualTo("U123");
        assertThat(integration.externalRefValue(SlackOAuthConnectFlow.CONNECT_METHOD)).isEqualTo("byo");
        assertThat(integration.getExternalRef().keySet()).containsExactlyInAnyOrder(
                SlackOAuthConnectFlow.WORKSPACE_ID,
                SlackOAuthConnectFlow.WORKSPACE_NAME,
                SlackOAuthConnectFlow.CONNECTED_USER_ID,
                SlackOAuthConnectFlow.CONNECT_METHOD);
        assertThat(integration.getEncryptedCredential()).containsExactly(encryptedCredential);

        ArgumentCaptor<String> encryptCaptor = ArgumentCaptor.forClass(String.class);
        verify(credentialCryptoService).encrypt(encryptCaptor.capture());
        String plaintext = encryptCaptor.getValue();
        assertThat(plaintext).isNotEqualTo("xoxp-user");
        assertThat(plaintext).isEqualTo(
                new SlackCredentialCodec(credentialCryptoService).serialize(new SlackCredential("xoxp-user", null)));

        verify(slackClient).verifyToken("xoxp-user");
        verifyNoMoreInteractions(slackClient);
        verify(pipelineWorkerClient).triggerCollection(IntegrationProvider.SLACK, PROJECT_ID);
        verify(planService).ensureProviderConnectable(OWNER_ID, IntegrationProvider.SLACK);
        verify(planService).recordProviderConnected(OWNER_ID, IntegrationProvider.SLACK);
    }

    @Test
    @DisplayName("이미 확정된 Slack 연동 → 409, verifyToken 호출 없음")
    void connectSlackWorkspaceRejectsAlreadyConnectedWithoutVerifyingToken() {
        IntegrationService service = service();
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.SLACK))
                .thenReturn(Optional.of(Integration.oauth(
                        project(),
                        IntegrationProvider.SLACK,
                        Map.of("workspace_id", "T123", "workspace_name", "Acme"),
                        new byte[] {1, 2, 3})));

        assertThatThrownBy(() -> service.connectSlackWorkspace(OWNER_ID, PROJECT_ID, "xoxp-user"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Slack integration already exists.");

        verifyNoInteractions(slackClient);
        verify(planService, never()).ensureProviderConnectable(any(), any());
        verify(credentialCryptoService, never()).encrypt(anyString());
        verify(integrationRepository, never()).saveAndFlush(any(Integration.class));
    }

    @Test
    @DisplayName("무료 티어 provider 연동 한도 초과 시 거부 — verifyToken도 저장도 일어나지 않음")
    void connectSlackWorkspaceRejectsWhenProviderLimitExceededWithoutVerifyingToken() {
        IntegrationService service = service();
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.SLACK))
                .thenReturn(Optional.empty());
        doThrow(new PlanLimitExceededException("Free plan only allows GitHub/Slack/Jira, one each."))
                .when(planService).ensureProviderConnectable(OWNER_ID, IntegrationProvider.SLACK);

        assertThatThrownBy(() -> service.connectSlackWorkspace(OWNER_ID, PROJECT_ID, "xoxp-user"))
                .isInstanceOf(PlanLimitExceededException.class);

        verify(slackClient, never()).verifyToken(anyString());
        verify(credentialCryptoService, never()).encrypt(anyString());
        verify(integrationRepository, never()).saveAndFlush(any(Integration.class));
        verify(planService, never()).recordProviderConnected(any(), any());
    }

    @Test
    @DisplayName("저장 경합 409여도 고객 BYO 토큰은 auth.revoke 하지 않는다")
    void connectSlackWorkspaceDoesNotRevokeCustomerTokenWhenSaveConflicts() {
        IntegrationService service = service();
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.SLACK))
                .thenReturn(Optional.empty());
        when(slackClient.verifyToken("xoxp-user"))
                .thenReturn(new SlackClient.SlackVerifiedUser("T123", "Acme", "U123"));
        when(credentialCryptoService.encrypt(anyString())).thenReturn(new byte[] {1, 2, 3});
        when(integrationRepository.saveAndFlush(any(Integration.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate integration"));

        assertThatThrownBy(() -> service.connectSlackWorkspace(OWNER_ID, PROJECT_ID, "xoxp-user"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Slack integration already exists.");

        verify(slackClient).verifyToken("xoxp-user");
        verify(slackClient, never()).revoke(any());
        verifyNoMoreInteractions(slackClient);
        verify(pipelineWorkerClient, never()).triggerCollection(any(), any());
        verify(planService, never()).recordProviderConnected(any(), any());
    }

    @Test
    @DisplayName("무효 Slack 토큰은 BadRequest(400) — JWT 401이 되면 프론트가 세션을 지운다")
    void connectSlackWorkspaceMapsInvalidTokenToBadRequest() {
        IntegrationService service = service();
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.SLACK))
                .thenReturn(Optional.empty());
        when(slackClient.verifyToken("xoxp-user"))
                .thenThrow(new UnauthorizedException("Invalid Slack token."));

        assertThatThrownBy(() -> service.connectSlackWorkspace(OWNER_ID, PROJECT_ID, "xoxp-user"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Invalid Slack token.");

        verify(slackClient).verifyToken("xoxp-user");
        verify(credentialCryptoService, never()).encrypt(anyString());
        verify(integrationRepository, never()).saveAndFlush(any(Integration.class));
        verify(pipelineWorkerClient, never()).triggerCollection(any(), any());
        verify(planService, never()).recordProviderConnected(any(), any());
    }

    @Test
    @DisplayName("저장 경합으로 409면 방금 교환한 자격증명을 폐기한다 — Discord는 grant 폐기 + 봇 길드 퇴장")
    void connectOAuthDiscardsExchangedCredentialWhenSaveConflicts() {
        IntegrationService service = service();
        OAuthConnectFlow flow = connectFlow(IntegrationProvider.DISCORD);
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.DISCORD))
                .thenReturn(Optional.empty());
        when(flow.exchangeCode("auth-code")).thenReturn(new OAuthConnection(
                "discord-refresh-token",
                Map.of(DiscordOAuthConnectFlow.GUILD_ID, "G1", DiscordOAuthConnectFlow.GUILD_NAME, "Acme")));
        when(credentialCryptoService.encrypt("discord-refresh-token")).thenReturn(new byte[] {1, 2, 3});
        when(integrationRepository.saveAndFlush(any(Integration.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate integration"));
        when(credentialCryptoService.decrypt(new byte[] {1, 2, 3})).thenReturn("discord-refresh-token");

        assertThatThrownBy(() -> service.connectOAuth(OWNER_ID, PROJECT_ID, flow, "auth-code"))
                .isInstanceOf(ConflictException.class);

        // 저장되지 않은 자격증명을 그대로 두면 provider 쪽에 grant가, Discord는 서버에 봇이 남는다
        verify(discordClient).revokeToken("discord-refresh-token");
        verify(discordClient).leaveGuild("G1");
        verify(pipelineWorkerClient, never()).triggerCollection(any(), any());
    }

    @Test
    @DisplayName("정리 실패가 원래의 409를 가리지 않는다 — 사용자는 실패 이유를 잃지 않아야 한다")
    void connectOAuthStillReportsConflictWhenDiscardFails() {
        IntegrationService service = service();
        OAuthConnectFlow flow = connectFlow(IntegrationProvider.DISCORD);
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.DISCORD))
                .thenReturn(Optional.empty());
        when(flow.exchangeCode("auth-code")).thenReturn(new OAuthConnection(
                "discord-refresh-token",
                Map.of(DiscordOAuthConnectFlow.GUILD_ID, "G1", DiscordOAuthConnectFlow.GUILD_NAME, "Acme")));
        when(credentialCryptoService.encrypt("discord-refresh-token")).thenReturn(new byte[] {1, 2, 3});
        when(integrationRepository.saveAndFlush(any(Integration.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate integration"));
        when(credentialCryptoService.decrypt(new byte[] {1, 2, 3}))
                .thenThrow(new IllegalStateException("decrypt failed"));

        assertThatThrownBy(() -> service.connectOAuth(OWNER_ID, PROJECT_ID, flow, "auth-code"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Discord integration already exists.");
    }

    @Test
    @DisplayName("선택 단계를 선언한 provider — 자격증명만 담은 pending 행으로 저장하고 수집은 미룬다")
    void connectOAuthSavesPendingIntegrationForProviderWithSelectionStep() {
        IntegrationService service = service();
        Project project = project();
        OAuthConnectFlow flow = connectFlow(IntegrationProvider.JIRA);
        byte[] encryptedCredential = new byte[] {7, 8, 9};
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project);
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn(Optional.empty());
        when(flow.exchangeCode("auth-code")).thenReturn(OAuthConnection.pendingSelection("jira-credential"));
        when(credentialCryptoService.encrypt("jira-credential")).thenReturn(encryptedCredential);
        when(integrationRepository.saveAndFlush(any(Integration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        IntegrationService.ConnectResult result = service.connectOAuth(OWNER_ID, PROJECT_ID, flow, "auth-code");

        Integration integration = result.integration();
        assertThat(integration.getProject()).isSameAs(project);
        assertThat(integration.getProvider()).isEqualTo(IntegrationProvider.JIRA);
        assertThat(integration.isPendingSelection()).isTrue();
        assertThat(integration.getEncryptedCredential()).containsExactly(encryptedCredential);
        assertThat(result.selectionRestored()).isFalse();
        // 확정 전이므로 초기 수집 트리거는 completeSelection의 책임이다
        verify(pipelineWorkerClient, never()).triggerCollection(any(), any());
        // 최초 연결의 pending 행에는 고른 값이 없으므로 자동 복원 분기를 타지 않는다
        verify(jiraOAuthClient, never()).listAccessibleResources(anyString());
    }

    @Test
    @DisplayName("선택 단계를 선언한 provider가 확정 external_ref를 돌려주면 배선 오류로 막는다")
    void connectOAuthRejectsExternalRefFromProviderWithSelectionStep() {
        IntegrationService service = service();
        OAuthConnectFlow flow = connectFlow(IntegrationProvider.JIRA);
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn(Optional.empty());
        when(flow.exchangeCode("auth-code"))
                .thenReturn(new OAuthConnection("jira-credential", Map.of("cloud_id", "cloud-1")));

        // 저장했다면 확정 시 통째로 덮어써져 조용히 사라진다 — 두 SPI의 선언이 어긋난 것을 바로 알린다
        assertThatThrownBy(() -> service.connectOAuth(OWNER_ID, PROJECT_ID, flow, "auth-code"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pendingSelection");
        verify(integrationRepository, never()).saveAndFlush(any(Integration.class));
    }

    @Test
    @DisplayName("선택 단계를 선언하지 않은 provider가 pendingSelection(빈 external_ref)을 돌려주면 배선 오류로 막는다")
    void connectOAuthRejectsPendingSelectionFromProviderWithoutSelectionStep() {
        IntegrationService service = service();
        OAuthConnectFlow flow = connectFlow(IntegrationProvider.SLACK);
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.SLACK))
                .thenReturn(Optional.empty());
        when(flow.exchangeCode("auth-code")).thenReturn(OAuthConnection.pendingSelection("slack-credential"));

        // 선택 단계를 신고하지 않은 provider가 external_ref 없이 pendingSelection을 돌려주면 확정할
        // 방법이 없는 pending 행이 영구히 남는다 — 두 SPI의 선언이 어긋난 배선 오류를 여기서도 막아야 한다.
        assertThatThrownBy(() -> service.connectOAuth(OWNER_ID, PROJECT_ID, flow, "auth-code"))
                .isInstanceOf(IllegalStateException.class);
        verify(integrationRepository, never()).saveAndFlush(any(Integration.class));
    }

    @Test
    @DisplayName("재동의 시 pending 행에 cloud_id·project_key가 남아 있고 새 토큰으로 여전히 접근 가능하면 자동 복원 후 확정한다")
    void connectOAuthAutoRestoresWhenExistingPendingSelectionIsStillAccessible() {
        IntegrationService service = service();
        Project project = project();
        OAuthConnectFlow flow = connectFlow(IntegrationProvider.JIRA);
        Integration revertedPending = Integration.pendingSelection(project, IntegrationProvider.JIRA, new byte[] {1, 2, 3});
        revertedPending.applyExternalRef(java.util.Map.of("cloud_id", "cloud-1", "site_name", "acme", "project_key", "PROJ", "project_name", "Project"));
        // 갱신 실패로 pending 되돌아온 행을 재현 — cloud_id·project_key는 남아 있다
        revertedPending.markPendingSelection();
        byte[] newEncryptedCredential = new byte[] {7, 8, 9};
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project);
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn(Optional.of(revertedPending));
        when(flow.exchangeCode("auth-code")).thenReturn(OAuthConnection.pendingSelection("jira-credential"));
        when(credentialCryptoService.encrypt("jira-credential")).thenReturn(newEncryptedCredential);
        when(integrationRepository.saveAndFlush(any(Integration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(jiraTokenService.getAccessToken(PROJECT_ID)).thenReturn("new-access-token");
        when(jiraOAuthClient.listAccessibleResources("new-access-token"))
                .thenReturn(List.of(new JiraOAuthClient.JiraSite("cloud-1", "acme", "https://acme.atlassian.net")));
        // 첫 단계만이 아니라 필수 단계를 전부 확인한다 — 사이트는 살아 있는데 프로젝트가 지워진 경우도 걸러진다
        when(jiraClient.listProjects("cloud-1", "new-access-token"))
                .thenReturn(List.of(new JiraClient.JiraProject("PROJ", "Project")));

        IntegrationService.ConnectResult result = service.connectOAuth(OWNER_ID, PROJECT_ID, flow, "auth-code");

        assertThat(result.integration()).isSameAs(revertedPending);
        assertThat(result.integration().isPendingSelection()).isFalse();
        assertThat(result.integration().externalRefValue("project_key")).isEqualTo("PROJ");
        assertThat(result.integration().externalRefValue("project_name")).isEqualTo("Project");
        // 프론트가 "선택하세요" 대신 "복원 완료" 배너를 띄우는 유일한 경로다
        assertThat(result.selectionRestored()).isTrue();
        verify(jiraTokenService).ensureAccessToken(PROJECT_ID);
        verify(pipelineWorkerClient).triggerCollection(IntegrationProvider.JIRA, PROJECT_ID);
    }

    @Test
    @DisplayName("갱신 실패로 pending 복귀한 기존 연동을 재동의로 복구하는 경로는 provider 연동 한도를 검사하지 않는다 "
            + "— \"해제 후 재연동\"과 \"pending으로 저하된 연동 복구\"는 다르므로, 이미 이력이 있다는 이유로 복구까지 막으면 안 된다")
    void connectOAuthDoesNotCheckProviderLimitWhenRepairingExistingPendingIntegration() {
        IntegrationService service = service();
        Project project = project();
        OAuthConnectFlow flow = connectFlow(IntegrationProvider.JIRA);
        Integration revertedPending = Integration.pendingSelection(project, IntegrationProvider.JIRA, new byte[] {1, 2, 3});
        revertedPending.applyExternalRef(java.util.Map.of("cloud_id", "cloud-1", "site_name", "acme", "project_key", "PROJ", "project_name", "Project"));
        // 갱신 실패로 pending 되돌아온 행을 재현 — cloud_id·project_key는 남아 있다
        revertedPending.markPendingSelection();
        byte[] newEncryptedCredential = new byte[] {7, 8, 9};
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project);
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn(Optional.of(revertedPending));
        when(flow.exchangeCode("auth-code")).thenReturn(OAuthConnection.pendingSelection("jira-credential"));
        when(credentialCryptoService.encrypt("jira-credential")).thenReturn(newEncryptedCredential);
        when(integrationRepository.saveAndFlush(any(Integration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(jiraTokenService.getAccessToken(PROJECT_ID)).thenReturn("new-access-token");
        when(jiraOAuthClient.listAccessibleResources("new-access-token"))
                .thenReturn(List.of(new JiraOAuthClient.JiraSite("cloud-1", "acme", "https://acme.atlassian.net")));
        when(jiraClient.listProjects("cloud-1", "new-access-token"))
                .thenReturn(List.of(new JiraClient.JiraProject("PROJ", "Project")));

        service.connectOAuth(OWNER_ID, PROJECT_ID, flow, "auth-code");

        // FREE 사용자가 최초 연동 때 이미 이력을 남긴 뒤, 갱신 영구 실패로 pending에 빠져 재동의로
        // 복구하는 경로다 — 여기서도 이력 검사를 걸면 유료 전환 외에는 복구할 방법이 없어진다
        verify(planService, never()).ensureProviderConnectable(any(), any());
    }

    @Test
    @DisplayName("재동의로 얻은 새 토큰의 접근 목록에 기존 cloudId가 없으면(다른 Atlassian 계정) pending을 유지한다")
    void connectOAuthKeepsPendingWhenRestoredSelectionIsNoLongerAccessible() {
        IntegrationService service = service();
        Project project = project();
        OAuthConnectFlow flow = connectFlow(IntegrationProvider.JIRA);
        Integration revertedPending = Integration.pendingSelection(project, IntegrationProvider.JIRA, new byte[] {1, 2, 3});
        revertedPending.applyExternalRef(java.util.Map.of("cloud_id", "cloud-1", "site_name", "acme", "project_key", "PROJ", "project_name", "Project"));
        revertedPending.markPendingSelection();
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project);
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn(Optional.of(revertedPending));
        when(flow.exchangeCode("auth-code")).thenReturn(OAuthConnection.pendingSelection("jira-credential"));
        when(credentialCryptoService.encrypt("jira-credential")).thenReturn(new byte[] {7, 8, 9});
        when(integrationRepository.saveAndFlush(any(Integration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(jiraTokenService.getAccessToken(PROJECT_ID)).thenReturn("new-access-token");
        when(jiraOAuthClient.listAccessibleResources("new-access-token"))
                .thenReturn(List.of(new JiraOAuthClient.JiraSite("cloud-2", "other-site", "https://other.atlassian.net")));

        IntegrationService.ConnectResult result = service.connectOAuth(OWNER_ID, PROJECT_ID, flow, "auth-code");

        assertThat(result.integration()).isSameAs(revertedPending);
        assertThat(result.integration().isPendingSelection()).isTrue();
        assertThat(result.selectionRestored()).isFalse();
        verify(pipelineWorkerClient, never()).triggerCollection(any(), any());
        verify(jiraTokenService, never()).ensureAccessToken(any());
    }

    @Test
    @DisplayName("자동 복원 중 후보 조회가 예외를 던져도 연결 자체는 성공 처리하고 pending을 반환한다")
    void connectOAuthKeepsPendingWhenSelectionOptionLookupFails() {
        // 토큰 저장 트랜잭션은 이미 커밋된 뒤이므로(동의 자체는 성공) 조회 실패를 "연결 실패"로
        // 알리면 실제 상태와 어긋난다 — 복원만 포기하고 pending으로 남겨 사용자가 그 자리에서
        // 사이트·프로젝트를 고를 수 있게 한다.
        IntegrationService service = service();
        Project project = project();
        OAuthConnectFlow flow = connectFlow(IntegrationProvider.JIRA);
        Integration revertedPending = Integration.pendingSelection(project, IntegrationProvider.JIRA, new byte[] {1, 2, 3});
        revertedPending.applyExternalRef(java.util.Map.of("cloud_id", "cloud-1", "site_name", "acme", "project_key", "PROJ", "project_name", "Project"));
        revertedPending.markPendingSelection();
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project);
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn(Optional.of(revertedPending));
        when(flow.exchangeCode("auth-code")).thenReturn(OAuthConnection.pendingSelection("jira-credential"));
        when(credentialCryptoService.encrypt("jira-credential")).thenReturn(new byte[] {7, 8, 9});
        when(integrationRepository.saveAndFlush(any(Integration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(jiraTokenService.getAccessToken(PROJECT_ID)).thenReturn("new-access-token");
        when(jiraOAuthClient.listAccessibleResources("new-access-token"))
                .thenThrow(new BadGatewayException("Jira accessible resources request failed."));

        IntegrationService.ConnectResult result = service.connectOAuth(OWNER_ID, PROJECT_ID, flow, "auth-code");

        assertThat(result.integration()).isSameAs(revertedPending);
        assertThat(result.integration().isPendingSelection()).isTrue();
        assertThat(result.selectionRestored()).isFalse();
        verify(pipelineWorkerClient, never()).triggerCollection(any(), any());
        verify(jiraTokenService, never()).ensureAccessToken(any());
    }

    @Test
    @DisplayName("pending 행 재동의 시 기존 행의 자격증명을 덮어쓴다")
    void connectOAuthOverwritesCredentialOfExistingPendingIntegration() {
        IntegrationService service = service();
        Project project = project();
        OAuthConnectFlow flow = connectFlow(IntegrationProvider.JIRA);
        Integration pending = Integration.pendingSelection(project, IntegrationProvider.JIRA, new byte[] {1, 2, 3});
        byte[] newEncryptedCredential = new byte[] {7, 8, 9};
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project);
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn(Optional.of(pending));
        when(flow.exchangeCode("auth-code")).thenReturn(OAuthConnection.pendingSelection("jira-credential"));
        when(credentialCryptoService.encrypt("jira-credential")).thenReturn(newEncryptedCredential);
        when(integrationRepository.saveAndFlush(any(Integration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        IntegrationService.ConnectResult result = service.connectOAuth(OWNER_ID, PROJECT_ID, flow, "auth-code");

        assertThat(result.integration()).isSameAs(pending);
        assertThat(result.integration().isPendingSelection()).isTrue();
        assertThat(result.integration().getEncryptedCredential()).containsExactly(newEncryptedCredential);
    }

    @Test
    @DisplayName("JiraTokenService가 보장한 access token으로 접근 가능한 Jira 사이트 목록 조회")
    void listJiraSitesReturnsAccessibleSites() {
        IntegrationService service = service();
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(jiraTokenService.getAccessToken(PROJECT_ID)).thenReturn("atl-access-token");
        when(jiraOAuthClient.listAccessibleResources("atl-access-token"))
                .thenReturn(List.of(new JiraOAuthClient.JiraSite("cloud-1", "acme", "https://acme.atlassian.net")));

        List<SelectionOption> result = service.selectionOptions(
                OWNER_ID, PROJECT_ID, IntegrationProvider.JIRA, "cloud_id", Map.of());

        assertThat(result).containsExactly(new SelectionOption("cloud-1", "acme"));
    }

    @Test
    @DisplayName("선택한 사이트(cloudId)의 Jira 프로젝트 목록 조회 — JiraTokenService가 보장한 access token 사용")
    void listJiraProjectsReturnsProjectsForSelectedSite() {
        IntegrationService service = service();
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(jiraTokenService.getAccessToken(PROJECT_ID)).thenReturn("atl-access-token");
        when(jiraClient.listProjects("cloud-1", "atl-access-token"))
                .thenReturn(List.of(new JiraClient.JiraProject("PROJ", "Project")));

        // 뒤 단계의 후보는 앞 단계에서 고른 값으로 조회된다 (부모 id 없이는 자식 목록을 못 만든다)
        List<SelectionOption> result = service.selectionOptions(
                OWNER_ID, PROJECT_ID, IntegrationProvider.JIRA, "project_key", Map.of("cloud_id", "cloud-1"));

        assertThat(result).containsExactly(new SelectionOption("PROJ", "Project"));
    }

    @Test
    @DisplayName("pending 행 확정 저장 후 커밋 뒤(트랜잭션 밖) 토큰 확보·초기 수집 트리거 순서로 진행")
    void completeJiraProjectSavesAndTriggersCollectionOutsideTransaction() {
        IntegrationService service = service();
        Integration pending = Integration.pendingSelection(project(), IntegrationProvider.JIRA, new byte[] {1, 2, 3});
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn(Optional.of(pending));
        when(integrationRepository.saveAndFlush(pending))
                .thenAnswer(invocation -> {
                    assertThat(transactionManager.transactionActive).isTrue();
                    return invocation.getArgument(0);
                });
        doAnswer(invocation -> {
            assertThat(transactionManager.transactionActive).isFalse();
            return null;
        }).when(jiraTokenService).ensureAccessToken(PROJECT_ID);
        doAnswer(invocation -> {
            assertThat(transactionManager.transactionActive).isFalse();
            return null;
        }).when(pipelineWorkerClient).triggerCollection(IntegrationProvider.JIRA, PROJECT_ID);

        Integration result = service.completeSelection(OWNER_ID, PROJECT_ID, IntegrationProvider.JIRA, Map.of(
                "cloud_id", "cloud-1", "site_name", "acme", "project_key", "PROJ", "project_name", "Project"));

        assertThat(result).isSameAs(pending);
        assertThat(result.isPendingSelection()).isFalse();
        assertThat(result.externalRefValue("project_key")).isEqualTo("PROJ");
        assertThat(result.externalRefValue("project_name")).isEqualTo("Project");
        // GitHub이 트리거 직전에 토큰을 갱신하는 것과 같은 자리 — 토큰 확보가 먼저, 수집 트리거가 그다음이다
        InOrder inOrder = inOrder(jiraTokenService, pipelineWorkerClient);
        inOrder.verify(jiraTokenService).ensureAccessToken(PROJECT_ID);
        inOrder.verify(pipelineWorkerClient).triggerCollection(IntegrationProvider.JIRA, PROJECT_ID);
    }

    @Test
    @DisplayName("GoogleChatTokenService가 보장한 access token으로 연동 가능한 Google Chat 스페이스 목록 조회")
    void listGoogleChatSpacesReturnsAccessibleSpaces() {
        IntegrationService service = service();
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(googleChatTokenService.getAccessToken(PROJECT_ID)).thenReturn("gc-access-token");
        when(googleChatClient.listSpaces("gc-access-token")).thenReturn(List.of(
                new GoogleChatSpaceListResponse.GoogleChatSpace("spaces/AAAA", "engineering")));

        List<SelectionOption> result = service.selectionOptions(
                OWNER_ID, PROJECT_ID, IntegrationProvider.GOOGLE_CHAT, GoogleChatSelectionFlow.SPACE_ID, Map.of());

        assertThat(result).containsExactly(new SelectionOption("spaces/AAAA", "engineering"));
    }

    @Test
    @DisplayName("displayName이 비어 있는 스페이스는 리소스 이름(spaces/{id})으로 폴백해 표시")
    void listGoogleChatSpacesFallsBackToResourceNameWhenDisplayNameBlank() {
        IntegrationService service = service();
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(googleChatTokenService.getAccessToken(PROJECT_ID)).thenReturn("gc-access-token");
        when(googleChatClient.listSpaces("gc-access-token")).thenReturn(List.of(
                new GoogleChatSpaceListResponse.GoogleChatSpace("spaces/AAAA", null)));

        List<SelectionOption> result = service.selectionOptions(
                OWNER_ID, PROJECT_ID, IntegrationProvider.GOOGLE_CHAT, GoogleChatSelectionFlow.SPACE_ID, Map.of());

        assertThat(result).containsExactly(new SelectionOption("spaces/AAAA", "spaces/AAAA"));
    }

    @Test
    @DisplayName("pending 행 확정 저장 후 커밋 뒤(트랜잭션 밖) 토큰 확보·초기 수집 트리거 순서로 진행 — Google Chat")
    void completeGoogleChatSpaceSavesAndTriggersCollectionOutsideTransaction() {
        IntegrationService service = service();
        Integration pending = Integration.pendingSelection(project(), IntegrationProvider.GOOGLE_CHAT, new byte[] {1, 2, 3});
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.GOOGLE_CHAT))
                .thenReturn(Optional.of(pending));
        when(integrationRepository.saveAndFlush(pending))
                .thenAnswer(invocation -> {
                    assertThat(transactionManager.transactionActive).isTrue();
                    return invocation.getArgument(0);
                });
        doAnswer(invocation -> {
            assertThat(transactionManager.transactionActive).isFalse();
            return null;
        }).when(googleChatTokenService).ensureAccessToken(PROJECT_ID);
        doAnswer(invocation -> {
            assertThat(transactionManager.transactionActive).isFalse();
            return null;
        }).when(pipelineWorkerClient).triggerCollection(IntegrationProvider.GOOGLE_CHAT, PROJECT_ID);

        Integration result = service.completeSelection(OWNER_ID, PROJECT_ID, IntegrationProvider.GOOGLE_CHAT, Map.of(
                GoogleChatSelectionFlow.SPACE_ID, "spaces/AAAA", GoogleChatSelectionFlow.SPACE_NAME, "engineering"));

        assertThat(result).isSameAs(pending);
        assertThat(result.isPendingSelection()).isFalse();
        assertThat(result.externalRefValue(GoogleChatSelectionFlow.SPACE_ID)).isEqualTo("spaces/AAAA");
        assertThat(result.externalRefValue(GoogleChatSelectionFlow.SPACE_NAME)).isEqualTo("engineering");
        // GitHub이 트리거 직전에 토큰을 갱신하는 것과 같은 자리 — 토큰 확보가 먼저, 수집 트리거가 그다음이다.
        // GoogleChatAccessTokenRefresher가 실제 등록돼 있어(service()) 이 경로가 배선까지 검증한다.
        InOrder inOrder = inOrder(googleChatTokenService, pipelineWorkerClient);
        inOrder.verify(googleChatTokenService).ensureAccessToken(PROJECT_ID);
        inOrder.verify(pipelineWorkerClient).triggerCollection(IntegrationProvider.GOOGLE_CHAT, PROJECT_ID);
    }

    @Test
    @DisplayName("이미 확정된 행에 다시 확정 시도 → 409, 토큰 확보·트리거 호출 안 함")
    void completeJiraProjectRejectsWhenAlreadyConfirmed() {
        IntegrationService service = service();
        Integration confirmed = Integration.pendingSelection(project(), IntegrationProvider.JIRA, new byte[] {1, 2, 3});
        confirmed.applyExternalRef(java.util.Map.of("cloud_id", "cloud-1", "site_name", "acme", "project_key", "PROJ", "project_name", "Project"));
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn(Optional.of(confirmed));

        assertThatThrownBy(() -> service.completeSelection(OWNER_ID, PROJECT_ID, IntegrationProvider.JIRA, Map.of(
                "cloud_id", "cloud-2", "site_name", "other-site", "project_key", "OTHER", "project_name", "Other")))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Jira integration already exists.");
        verify(jiraTokenService, never()).ensureAccessToken(any());
        verify(pipelineWorkerClient, never()).triggerCollection(IntegrationProvider.JIRA, PROJECT_ID);
    }

    @Test
    @DisplayName("연동 해제 — 그래프를 트랜잭션 밖에서 먼저 지우고 연동·checkpoint를 함께 삭제")
    void disconnectDeletesSourceGraphThenIntegrationAndCheckpoints() {
        IntegrationService service = service();
        Integration integration = Integration.github(project(), installation(), 12345L, "acme/widget", "main");
        ReflectionTestUtils.setField(integration, "id", INTEGRATION_ID);
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.GITHUB))
                .thenReturn(Optional.of(integration));
        doAnswer(invocation -> {
            // 그래프 삭제는 외부 HTTP다 — 트랜잭션이 열린 채로 호출하면 커넥션을 점유한다
            assertThat(transactionManager.transactionActive).isFalse();
            return null;
        }).when(aiEngineGraphClient).deleteProjectSourceGraph(PROJECT_ID, IntegrationProvider.GITHUB);
        doAnswer(invocation -> {
            assertThat(transactionManager.transactionActive).isTrue();
            return 1;
        }).when(checkpointRepository).deleteByProject_IdAndId_Provider(PROJECT_ID, IntegrationProvider.GITHUB);
        doAnswer(invocation -> {
            assertThat(transactionManager.transactionActive).isTrue();
            return null;
        }).when(integrationRepository).deleteById(INTEGRATION_ID);

        service.disconnect(OWNER_ID, PROJECT_ID, IntegrationProvider.GITHUB);

        // 그래프 먼저, RDB 나중 — 재시도로 수렴하는 순서(ProjectService.deleteProject와 동일)
        InOrder inOrder = inOrder(aiEngineGraphClient, checkpointRepository, integrationRepository);
        inOrder.verify(aiEngineGraphClient).deleteProjectSourceGraph(PROJECT_ID, IntegrationProvider.GITHUB);
        inOrder.verify(checkpointRepository).deleteByProject_IdAndId_Provider(PROJECT_ID, IntegrationProvider.GITHUB);
        inOrder.verify(integrationRepository).deleteById(INTEGRATION_ID);
        assertThat(transactionManager.rollbackCount).isZero();
    }

    @Test
    @DisplayName("Slack 해제는 우리 토큰 삭제 전에 provider 권한을 폐기한다")
    void disconnectRevokesSlackTokenBeforeDeletingIt() {
        IntegrationService service = service();
        Integration integration = Integration.oauth(
                project(),
                IntegrationProvider.SLACK,
                Map.of("workspace_id", "T1", "workspace_name", "acme"),
                new byte[] {1, 2, 3});
        ReflectionTestUtils.setField(integration, "id", INTEGRATION_ID);
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.SLACK))
                .thenReturn(Optional.of(integration));
        when(credentialCryptoService.decrypt(new byte[] {1, 2, 3})).thenReturn("xoxp-token");

        service.disconnect(OWNER_ID, PROJECT_ID, IntegrationProvider.SLACK);

        // 폐기가 삭제보다 앞서야 한다 — 행을 먼저 지우면 폐기에 쓸 토큰이 사라진다
        InOrder inOrder = inOrder(slackClient, integrationRepository);
        inOrder.verify(slackClient).revoke("xoxp-token");
        inOrder.verify(integrationRepository).deleteById(INTEGRATION_ID);
    }

    @Test
    @DisplayName("Jira 해제는 refresh token을 폐기한다 (파생 access token도 함께 무효화)")
    void disconnectRevokesJiraRefreshToken() {
        IntegrationService service = service();
        Integration integration = Integration.pendingSelection(project(), IntegrationProvider.JIRA, new byte[] {9});
        ReflectionTestUtils.setField(integration, "id", INTEGRATION_ID);
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn(Optional.of(integration));
        when(jiraCredentialCodec.decrypt(new byte[] {9}))
                .thenReturn(new JiraCredential("access", "refresh", Instant.parse("2026-08-01T00:00:00Z")));

        service.disconnect(OWNER_ID, PROJECT_ID, IntegrationProvider.JIRA);

        verify(jiraOAuthClient).revoke("refresh");
    }

    @Test
    @DisplayName("Discord 해제는 OAuth grant를 폐기하고 봇이 길드를 나간다 (A8: externalRef 전달 검증)")
    void disconnectRevokesDiscordTokenAndLeavesGuild() {
        IntegrationService service = service();
        Integration integration = Integration.oauth(
                project(),
                IntegrationProvider.DISCORD,
                Map.of(DiscordOAuthConnectFlow.GUILD_ID, "G1", DiscordOAuthConnectFlow.GUILD_NAME, "Acme"),
                new byte[] {7, 8, 9});
        ReflectionTestUtils.setField(integration, "id", INTEGRATION_ID);
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.DISCORD))
                .thenReturn(Optional.of(integration));
        when(credentialCryptoService.decrypt(new byte[] {7, 8, 9})).thenReturn("discord-refresh-token");

        service.disconnect(OWNER_ID, PROJECT_ID, IntegrationProvider.DISCORD);

        // 폐기가 삭제보다 앞서야 한다 — 행을 먼저 지우면 폐기에 쓸 토큰이 사라진다
        InOrder inOrder = inOrder(discordClient, integrationRepository);
        inOrder.verify(discordClient).revokeToken("discord-refresh-token");
        inOrder.verify(discordClient).leaveGuild("G1");
        inOrder.verify(integrationRepository).deleteById(INTEGRATION_ID);
    }

    @Test
    @DisplayName("Google Chat 해제는 refresh token(grant)을 폐기한다 (파생 access token도 함께 무효화)")
    void disconnectRevokesGoogleChatRefreshToken() {
        IntegrationService service = service();
        Integration integration = Integration.oauth(
                project(),
                IntegrationProvider.GOOGLE_CHAT,
                Map.of(GoogleChatSelectionFlow.SPACE_ID, "spaces/AAAA", GoogleChatSelectionFlow.SPACE_NAME, "engineering"),
                new byte[] {4, 5, 6});
        ReflectionTestUtils.setField(integration, "id", INTEGRATION_ID);
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.GOOGLE_CHAT))
                .thenReturn(Optional.of(integration));
        when(googleChatCredentialCodec.decrypt(new byte[] {4, 5, 6}))
                .thenReturn(new GoogleChatCredential("access", "refresh", Instant.parse("2026-08-01T00:00:00Z")));

        service.disconnect(OWNER_ID, PROJECT_ID, IntegrationProvider.GOOGLE_CHAT);

        // 폐기가 삭제보다 앞서야 한다 — 행을 먼저 지우면 폐기에 쓸 토큰이 사라진다
        InOrder inOrder = inOrder(googleChatClient, integrationRepository);
        inOrder.verify(googleChatClient).revoke("refresh");
        inOrder.verify(integrationRepository).deleteById(INTEGRATION_ID);
    }

    @Test
    @DisplayName("GitHub 해제는 폐기 호출이 없다 — App 설치는 계정 단위라 유지")
    void disconnectDoesNotRevokeForGitHub() {
        IntegrationService service = service();
        Integration integration = Integration.github(project(), installation(), 12345L, "acme/widget", "main");
        ReflectionTestUtils.setField(integration, "id", INTEGRATION_ID);
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.GITHUB))
                .thenReturn(Optional.of(integration));

        service.disconnect(OWNER_ID, PROJECT_ID, IntegrationProvider.GITHUB);

        verify(slackClient, never()).revoke(anyString());
        verify(jiraOAuthClient, never()).revoke(anyString());
        verify(integrationRepository).deleteById(INTEGRATION_ID);
    }

    @Test
    @DisplayName("연동 해제 — provider 권한 폐기를 IntegrationRevocationService에 위임한다 "
            + "(registry.find(Optional) 조회·provider별 client 호출은 IntegrationRevocationServiceTest가 검증한다)")
    void disconnectDelegatesRevocationToIntegrationRevocationService() {
        IntegrationService service = serviceWithRevocationService(revocationService);
        Integration integration = Integration.pendingSelection(project(), IntegrationProvider.JIRA, new byte[] {9});
        ReflectionTestUtils.setField(integration, "id", INTEGRATION_ID);
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn(Optional.of(integration));
        // disconnect가 반환값을 무시하고 진행하는지 확인하는 게 목적이라 false를 반환해도 흐름은 그대로다
        when(revocationService.revoke(integration)).thenReturn(false);

        service.disconnect(OWNER_ID, PROJECT_ID, IntegrationProvider.JIRA);

        // 폐기가 삭제보다 앞서야 한다 — 행을 먼저 지우면 폐기에 쓸 자격증명이 사라진다. 방금 조회로
        // 찾은 그 integration 인스턴스가 그대로 위임된다.
        InOrder inOrder = inOrder(revocationService, integrationRepository);
        inOrder.verify(revocationService).revoke(integration);
        inOrder.verify(integrationRepository).deleteById(INTEGRATION_ID);
    }

    @Test
    @DisplayName("연동이 없으면 404 — 그래프 삭제도 호출하지 않음")
    void disconnectRejectsMissingIntegration() {
        IntegrationService service = service();
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.SLACK))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.disconnect(OWNER_ID, PROJECT_ID, IntegrationProvider.SLACK))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Slack integration not found.");

        verify(aiEngineGraphClient, never()).deleteProjectSourceGraph(any(), any());
        assertThat(transactionManager.beginCount).isZero();
    }

    @Test
    @DisplayName("그래프 삭제가 실패하면 연동·checkpoint를 지우지 않는다")
    void disconnectKeepsIntegrationWhenGraphDeleteFails() {
        IntegrationService service = service();
        Integration integration = Integration.github(project(), installation(), 12345L, "acme/widget", "main");
        ReflectionTestUtils.setField(integration, "id", INTEGRATION_ID);
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.GITHUB))
                .thenReturn(Optional.of(integration));
        doThrow(new BadGatewayException("Failed to delete integration graph."))
                .when(aiEngineGraphClient).deleteProjectSourceGraph(PROJECT_ID, IntegrationProvider.GITHUB);

        assertThatThrownBy(() -> service.disconnect(OWNER_ID, PROJECT_ID, IntegrationProvider.GITHUB))
                .isInstanceOf(BadGatewayException.class);

        // 연동이 남아 있어야 사용자가 재시도할 수 있다 — 여기서 지우면 고아 그래프가 영구히 남는다
        verify(integrationRepository, never()).deleteById(any());
        verify(checkpointRepository, never()).deleteByProject_IdAndId_Provider(any(), any());
        assertThat(transactionManager.beginCount).isZero();
    }

    // --- 구 status 값(pending_project) 호환 ---
    // 중립 값 이전 배포가 저장한 행이 남아 있어 데이터 마이그레이션 없이 넘어가는 것이 전제다.
    // 호환을 걷어낼 때 이 블록을 통째로 지우면 된다.

    @Test
    @DisplayName("구 status 값 pending 행에 재동의 → 409가 아니라 자격증명을 덮어쓰고 pending을 유지한다")
    void connectOAuthOverwritesLegacyPendingIntegration() {
        IntegrationService service = service();
        Project project = project();
        OAuthConnectFlow flow = connectFlow(IntegrationProvider.JIRA);
        Integration legacyPending = legacyPendingJira(project, Map.of(Integration.STATUS, LEGACY_PENDING_PROJECT));
        byte[] newEncryptedCredential = new byte[] {7, 8, 9};
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project);
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn(Optional.of(legacyPending));
        when(flow.exchangeCode("auth-code")).thenReturn(OAuthConnection.pendingSelection("jira-credential"));
        when(credentialCryptoService.encrypt("jira-credential")).thenReturn(newEncryptedCredential);
        when(integrationRepository.saveAndFlush(any(Integration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        IntegrationService.ConnectResult result = service.connectOAuth(OWNER_ID, PROJECT_ID, flow, "auth-code");

        // 옛 값을 pending으로 못 읽으면 "확정된 연동"으로 오인해 409가 나가고, 사용자는 재연결도 해제도 못 한다
        assertThat(result.integration()).isSameAs(legacyPending);
        assertThat(result.integration().isPendingSelection()).isTrue();
        assertThat(result.integration().getEncryptedCredential()).containsExactly(newEncryptedCredential);
    }

    @Test
    @DisplayName("구 status 값 pending 행도 재동의 시 자동 복원된다")
    void connectOAuthAutoRestoresFromLegacyPendingRow() {
        IntegrationService service = service();
        Project project = project();
        OAuthConnectFlow flow = connectFlow(IntegrationProvider.JIRA);
        Integration legacyPending = legacyPendingJira(project, Map.of(
                Integration.STATUS, LEGACY_PENDING_PROJECT,
                "cloud_id", "cloud-1",
                "site_name", "acme",
                "project_key", "PROJ",
                "project_name", "Project"));
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project);
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn(Optional.of(legacyPending));
        when(flow.exchangeCode("auth-code")).thenReturn(OAuthConnection.pendingSelection("jira-credential"));
        when(credentialCryptoService.encrypt("jira-credential")).thenReturn(new byte[] {7, 8, 9});
        when(integrationRepository.saveAndFlush(any(Integration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(jiraTokenService.getAccessToken(PROJECT_ID)).thenReturn("new-access-token");
        when(jiraOAuthClient.listAccessibleResources("new-access-token"))
                .thenReturn(List.of(new JiraOAuthClient.JiraSite("cloud-1", "acme", "https://acme.atlassian.net")));
        when(jiraClient.listProjects("cloud-1", "new-access-token"))
                .thenReturn(List.of(new JiraClient.JiraProject("PROJ", "Project")));

        IntegrationService.ConnectResult result = service.connectOAuth(OWNER_ID, PROJECT_ID, flow, "auth-code");

        assertThat(result.integration().isPendingSelection()).isFalse();
        assertThat(result.selectionRestored()).isTrue();
        // 확정하면서 external_ref가 통째로 교체돼 옛 status 값 자체가 사라진다
        assertThat(result.integration().getExternalRef()).doesNotContainKey(Integration.STATUS);
        verify(pipelineWorkerClient).triggerCollection(IntegrationProvider.JIRA, PROJECT_ID);
    }

    @Test
    @DisplayName("구 status 값 pending 행도 선택 확정이 가능하다 — 409로 막히지 않는다")
    void completeSelectionAcceptsLegacyPendingRow() {
        IntegrationService service = service();
        Integration legacyPending = legacyPendingJira(project(), Map.of(Integration.STATUS, LEGACY_PENDING_PROJECT));
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn(Optional.of(legacyPending));
        when(integrationRepository.saveAndFlush(legacyPending))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Integration result = service.completeSelection(OWNER_ID, PROJECT_ID, IntegrationProvider.JIRA, Map.of(
                "cloud_id", "cloud-1", "site_name", "acme", "project_key", "PROJ", "project_name", "Project"));

        assertThat(result.isPendingSelection()).isFalse();
        assertThat(result.externalRefValue("project_key")).isEqualTo("PROJ");
        verify(pipelineWorkerClient).triggerCollection(IntegrationProvider.JIRA, PROJECT_ID);
    }

    @Test
    @DisplayName("구 status 값 pending 행의 표시 이름은 사이트까지만 — 확정되지 않은 프로젝트를 연결된 것처럼 보여주지 않는다")
    void listIntegrationsShowsSiteOnlyForLegacyPendingRow() {
        IntegrationService service = service();
        Project project = project();
        // 갱신 영구 실패로 pending 복귀한 행 — 고른 값이 남아 있어, 옛 status를 pending으로 못 읽으면
        // 화면에 "acme / Project"가 떠 아직 못 쓰는 연동이 정상 연결된 것처럼 보인다
        Integration legacyPending = legacyPendingJira(project, Map.of(
                Integration.STATUS, LEGACY_PENDING_PROJECT,
                "cloud_id", "cloud-1",
                "site_name", "acme",
                "project_key", "PROJ",
                "project_name", "Project"));
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project);
        when(integrationRepository.findAllByProject_IdOrderByCreatedAtDesc(PROJECT_ID))
                .thenReturn(List.of(legacyPending));
        when(checkpointRepository.findAllByProject_Id(PROJECT_ID)).thenReturn(List.of());

        List<IntegrationResponse> result = service.listIntegrations(OWNER_ID, PROJECT_ID);

        assertThat(result.get(0).displayName()).isEqualTo("acme");
    }

    // ─── disconnectSlackWorkspace ───────────────────────────────────────────────

    @Test
    @DisplayName("disconnectSlackWorkspace — 매칭 workspace_id 행의 폐기·그래프·RDB 삭제가 순서대로 일어난다")
    void disconnectSlackWorkspaceDisconnectsMatchingRow() {
        IntegrationService service = serviceWithRevocationService(revocationService);
        Integration matching = slackWorkspaceIntegration("T_MATCH", INTEGRATION_ID);
        when(integrationRepository.findAllByProvider(IntegrationProvider.SLACK))
                .thenReturn(List.of(matching));

        service.disconnectSlackWorkspace("T_MATCH");

        InOrder inOrder = inOrder(revocationService, aiEngineGraphClient, checkpointRepository, integrationRepository);
        inOrder.verify(revocationService).revoke(matching);
        inOrder.verify(aiEngineGraphClient).deleteProjectSourceGraph(PROJECT_ID, IntegrationProvider.SLACK);
        inOrder.verify(checkpointRepository).deleteByProject_IdAndId_Provider(PROJECT_ID, IntegrationProvider.SLACK);
        inOrder.verify(integrationRepository).deleteById(INTEGRATION_ID);
    }

    @Test
    @DisplayName("disconnectSlackWorkspace — 매칭 행 없으면 no-op, 폐기·그래프·삭제 미호출")
    void disconnectSlackWorkspaceIsNoOpWhenNoRowMatches() {
        IntegrationService service = serviceWithRevocationService(revocationService);
        Integration other = slackWorkspaceIntegration("T_OTHER", INTEGRATION_ID);
        when(integrationRepository.findAllByProvider(IntegrationProvider.SLACK))
                .thenReturn(List.of(other));

        service.disconnectSlackWorkspace("T_NOT_EXIST");

        verify(revocationService, never()).revoke(any());
        verify(aiEngineGraphClient, never()).deleteProjectSourceGraph(any(), any());
        verify(integrationRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("disconnectSlackWorkspace — 다른 workspace_id 행은 건드리지 않는다")
    void disconnectSlackWorkspaceDoesNotTouchOtherWorkspaceRow() {
        IntegrationService service = serviceWithRevocationService(revocationService);
        Integration matching = slackWorkspaceIntegration("T_MATCH", INTEGRATION_ID);
        Integration other = slackWorkspaceIntegration("T_OTHER", INTEGRATION_ID_2);
        when(integrationRepository.findAllByProvider(IntegrationProvider.SLACK))
                .thenReturn(List.of(matching, other));

        service.disconnectSlackWorkspace("T_MATCH");

        // 매칭 행만 처리된다
        verify(revocationService).revoke(matching);
        verify(revocationService, never()).revoke(other);
        verify(integrationRepository).deleteById(INTEGRATION_ID);
        verify(integrationRepository, never()).deleteById(INTEGRATION_ID_2);
    }

    @Test
    @DisplayName("disconnectSlackWorkspace — workspaceId가 blank이면 no-op")
    void disconnectSlackWorkspaceIsNoOpWhenWorkspaceIdIsBlank() {
        IntegrationService service = serviceWithRevocationService(revocationService);

        service.disconnectSlackWorkspace("  ");

        verify(revocationService, never()).revoke(any());
        verify(aiEngineGraphClient, never()).deleteProjectSourceGraph(any(), any());
        verify(integrationRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("disconnectSlackWorkspace — workspaceId가 null이면 no-op")
    void disconnectSlackWorkspaceIsNoOpWhenWorkspaceIdIsNull() {
        IntegrationService service = serviceWithRevocationService(revocationService);

        service.disconnectSlackWorkspace(null);

        verify(revocationService, never()).revoke(any());
        verify(aiEngineGraphClient, never()).deleteProjectSourceGraph(any(), any());
        verify(integrationRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("disconnectSlackWorkspace — 그래프 삭제 예외 시 그 행의 RDB는 지우지 않는다 (기존 disconnectKeepsIntegrationWhenGraphDeleteFails와 같은 불변식)")
    void disconnectSlackWorkspaceKeepsRowWhenGraphDeleteFails() {
        IntegrationService service = serviceWithRevocationService(revocationService);
        Integration matching = slackWorkspaceIntegration("T_MATCH", INTEGRATION_ID);
        when(integrationRepository.findAllByProvider(IntegrationProvider.SLACK))
                .thenReturn(List.of(matching));
        doThrow(new BadGatewayException("Failed to delete slack graph."))
                .when(aiEngineGraphClient).deleteProjectSourceGraph(PROJECT_ID, IntegrationProvider.SLACK);

        service.disconnectSlackWorkspace("T_MATCH");

        verify(revocationService).revoke(matching);
        verify(integrationRepository, never()).deleteById(any());
        verify(checkpointRepository, never()).deleteByProject_IdAndId_Provider(any(), any());
    }

    @Test
    @DisplayName("disconnectSlackWorkspace — 한 행의 그래프 삭제가 실패해도 같은 workspace의 다른 행은 해제한다")
    void disconnectSlackWorkspaceContinuesWhenOneRowFails() {
        IntegrationService service = serviceWithRevocationService(revocationService);
        Integration failing = slackWorkspaceIntegration("T_MATCH", INTEGRATION_ID, project());
        Integration surviving = slackWorkspaceIntegration("T_MATCH", INTEGRATION_ID_2, project(PROJECT_ID_2));
        when(integrationRepository.findAllByProvider(IntegrationProvider.SLACK))
                .thenReturn(List.of(failing, surviving));
        doThrow(new BadGatewayException("Failed to delete slack graph."))
                .when(aiEngineGraphClient).deleteProjectSourceGraph(PROJECT_ID, IntegrationProvider.SLACK);

        service.disconnectSlackWorkspace("T_MATCH");

        verify(revocationService).revoke(failing);
        verify(revocationService).revoke(surviving);
        verify(integrationRepository, never()).deleteById(INTEGRATION_ID);
        verify(integrationRepository).deleteById(INTEGRATION_ID_2);
        verify(checkpointRepository, never())
                .deleteByProject_IdAndId_Provider(PROJECT_ID, IntegrationProvider.SLACK);
        verify(checkpointRepository)
                .deleteByProject_IdAndId_Provider(PROJECT_ID_2, IntegrationProvider.SLACK);
    }

    @Test
    @DisplayName("disconnectSlackWorkspace — 같은 workspace의 BYO 행은 건너뛰고 OAuth 행만 해제한다")
    void disconnectSlackWorkspaceSkipsByoRowAndDisconnectsOauthRow() {
        IntegrationService service = serviceWithRevocationService(revocationService);
        Integration oauthRow = slackUserIntegration("T_MATCH", "U111", INTEGRATION_ID);
        Integration byoRow = slackByoWorkspaceIntegration("T_MATCH", "U111", INTEGRATION_ID_2);
        when(integrationRepository.findAllByProvider(IntegrationProvider.SLACK))
                .thenReturn(List.of(oauthRow, byoRow));

        service.disconnectSlackWorkspace("T_MATCH");

        verify(revocationService).revoke(oauthRow);
        verify(revocationService, never()).revoke(byoRow);
        verify(integrationRepository).deleteById(INTEGRATION_ID);
        verify(integrationRepository, never()).deleteById(INTEGRATION_ID_2);
    }

    // ─── disconnectSlackUsers ───────────────────────────────────────────────────

    @Test
    @DisplayName("disconnectSlackUsers — workspace_id AND connected_user_id 모두 매칭되는 행만 삭제")
    void disconnectSlackUsersDisconnectsMatchingUsers() {
        IntegrationService service = serviceWithRevocationService(revocationService);
        Integration u111 = slackUserIntegration("T123", "U111", INTEGRATION_ID);
        Integration u222 = slackUserIntegration("T123", "U222", INTEGRATION_ID_2);
        when(integrationRepository.findAllByProvider(IntegrationProvider.SLACK))
                .thenReturn(List.of(u111, u222));

        service.disconnectSlackUsers("T123", List.of("U111", "U222"));

        verify(revocationService).revoke(u111);
        verify(revocationService).revoke(u222);
        verify(integrationRepository).deleteById(INTEGRATION_ID);
        verify(integrationRepository).deleteById(INTEGRATION_ID_2);
    }

    @Test
    @DisplayName("disconnectSlackUsers — connected_user_id 키가 없는 레거시 행은 건너뛴다")
    void disconnectSlackUsersSkipsRowsWithoutConnectedUserId() {
        IntegrationService service = serviceWithRevocationService(revocationService);
        // connected_user_id 없는 레거시 행(workspace_id만 있음)
        Integration legacyRow = slackWorkspaceIntegration("T123", INTEGRATION_ID);
        when(integrationRepository.findAllByProvider(IntegrationProvider.SLACK))
                .thenReturn(List.of(legacyRow));

        service.disconnectSlackUsers("T123", List.of("U111"));

        verify(revocationService, never()).revoke(any());
        verify(integrationRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("disconnectSlackUsers — 같은 workspace의 다른 사용자는 유지된다")
    void disconnectSlackUsersDoesNotTouchOtherUsersInSameWorkspace() {
        IntegrationService service = serviceWithRevocationService(revocationService);
        Integration u111 = slackUserIntegration("T123", "U111", INTEGRATION_ID);
        Integration u333 = slackUserIntegration("T123", "U333", INTEGRATION_ID_2);
        when(integrationRepository.findAllByProvider(IntegrationProvider.SLACK))
                .thenReturn(List.of(u111, u333));

        service.disconnectSlackUsers("T123", List.of("U111"));

        verify(revocationService).revoke(u111);
        verify(revocationService, never()).revoke(u333);
        verify(integrationRepository).deleteById(INTEGRATION_ID);
        verify(integrationRepository, never()).deleteById(INTEGRATION_ID_2);
    }

    @Test
    @DisplayName("disconnectSlackUsers — 매칭 행 없으면 no-op")
    void disconnectSlackUsersIsNoOpWhenNoRowMatches() {
        IntegrationService service = serviceWithRevocationService(revocationService);
        Integration other = slackUserIntegration("T123", "U999", INTEGRATION_ID);
        when(integrationRepository.findAllByProvider(IntegrationProvider.SLACK))
                .thenReturn(List.of(other));

        service.disconnectSlackUsers("T123", List.of("U111", "U222"));

        verify(revocationService, never()).revoke(any());
        verify(integrationRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("disconnectSlackUsers — 빈 사용자 목록이면 no-op")
    void disconnectSlackUsersIsNoOpWhenUserIdsEmpty() {
        IntegrationService service = serviceWithRevocationService(revocationService);

        service.disconnectSlackUsers("T123", List.of());

        verify(revocationService, never()).revoke(any());
        verify(aiEngineGraphClient, never()).deleteProjectSourceGraph(any(), any());
    }

    @Test
    @DisplayName("disconnectSlackUsers — 한 행의 그래프 삭제가 실패해도 매칭된 다른 사용자는 해제한다")
    void disconnectSlackUsersContinuesWhenOneRowFails() {
        IntegrationService service = serviceWithRevocationService(revocationService);
        Integration failing = slackUserIntegration("T123", "U111", INTEGRATION_ID, project());
        Integration surviving = slackUserIntegration("T123", "U222", INTEGRATION_ID_2, project(PROJECT_ID_2));
        when(integrationRepository.findAllByProvider(IntegrationProvider.SLACK))
                .thenReturn(List.of(failing, surviving));
        doThrow(new BadGatewayException("Failed to delete slack graph."))
                .when(aiEngineGraphClient).deleteProjectSourceGraph(PROJECT_ID, IntegrationProvider.SLACK);

        service.disconnectSlackUsers("T123", List.of("U111", "U222"));

        verify(revocationService).revoke(failing);
        verify(revocationService).revoke(surviving);
        verify(integrationRepository, never()).deleteById(INTEGRATION_ID);
        verify(integrationRepository).deleteById(INTEGRATION_ID_2);
    }

    @Test
    @DisplayName("disconnectSlackUsers — connect_method=byo 행은 같은 workspace·user여도 건너뛴다")
    void disconnectSlackUsersSkipsByoRows() {
        IntegrationService service = serviceWithRevocationService(revocationService);
        Integration oauthRow = slackUserIntegration("T123", "U111", INTEGRATION_ID);
        Integration byoRow = slackByoWorkspaceIntegration("T123", "U111", INTEGRATION_ID_2);
        when(integrationRepository.findAllByProvider(IntegrationProvider.SLACK))
                .thenReturn(List.of(oauthRow, byoRow));

        service.disconnectSlackUsers("T123", List.of("U111"));

        verify(revocationService).revoke(oauthRow);
        verify(revocationService, never()).revoke(byoRow);
        verify(integrationRepository).deleteById(INTEGRATION_ID);
        verify(integrationRepository, never()).deleteById(INTEGRATION_ID_2);
    }

    // ─── Slack slash command 대상 조회·백필 ─────────────────────────────────────

    @Test
    @DisplayName("listSlackCommandTargets — workspace_id 일치 행만 DTO로 매핑하고 다른 워크스페이스는 제외")
    void listSlackCommandTargetsMapsMatchingRowsToDtoAndExcludesOtherWorkspace() {
        IntegrationService service = service();
        Integration matching = slackUserIntegration("T123", "U111", INTEGRATION_ID, namedProject(PROJECT_ID, "Alpha"));
        Integration otherWorkspace = slackUserIntegration(
                "T_OTHER", "U111", INTEGRATION_ID_2, namedProject(PROJECT_ID_2, "Beta"));
        when(integrationRepository.findAllByProvider(IntegrationProvider.SLACK))
                .thenReturn(List.of(matching, otherWorkspace));

        List<IntegrationService.SlackCommandTarget> targets = service.listSlackCommandTargets("T123");

        assertThat(targets).hasSize(1);
        IntegrationService.SlackCommandTarget target = targets.get(0);
        assertThat(target.integrationId()).isEqualTo(INTEGRATION_ID);
        assertThat(target.projectId()).isEqualTo(PROJECT_ID);
        assertThat(target.projectName()).isEqualTo("Alpha");
        assertThat(target.ownerUserId()).isEqualTo(OWNER_ID);
        assertThat(target.connectedUserId()).isEqualTo("U111");
        assertThat(target.encryptedCredential()).isEqualTo(new byte[] {1, 2, 3});
        verify(integrationRepository).findAllByProvider(IntegrationProvider.SLACK);
    }

    @Test
    @DisplayName("listSlackCommandTargets — pending 행은 커맨드 대상이 아니다")
    void listSlackCommandTargetsExcludesPendingSelectionRows() {
        IntegrationService service = service();
        Integration pending = slackUserIntegration("T123", "U111", INTEGRATION_ID);
        pending.markPendingSelection();
        Integration confirmed = slackUserIntegration(
                "T123", "U222", INTEGRATION_ID_2, namedProject(PROJECT_ID_2, "Beta"));
        when(integrationRepository.findAllByProvider(IntegrationProvider.SLACK))
                .thenReturn(List.of(pending, confirmed));

        List<IntegrationService.SlackCommandTarget> targets = service.listSlackCommandTargets("T123");

        assertThat(targets).extracting(IntegrationService.SlackCommandTarget::integrationId)
                .containsExactly(INTEGRATION_ID_2);
        assertThat(targets.get(0).projectName()).isEqualTo("Beta");
    }

    @Test
    @DisplayName("listSlackCommandTargets — connected_user_id 없는 레거시 행도 대상에 포함한다 (auth.test 백필은 커맨드 서비스 몫)")
    void listSlackCommandTargetsIncludesLegacyRowsWithoutConnectedUserId() {
        IntegrationService service = service();
        Integration legacy = slackWorkspaceIntegration("T123", INTEGRATION_ID);
        when(integrationRepository.findAllByProvider(IntegrationProvider.SLACK))
                .thenReturn(List.of(legacy));

        List<IntegrationService.SlackCommandTarget> targets = service.listSlackCommandTargets("T123");

        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).connectedUserId()).isNull();
        assertThat(targets.get(0).integrationId()).isEqualTo(INTEGRATION_ID);
        assertThat(targets.get(0).projectName()).isEqualTo("History Tracker");
        assertThat(targets.get(0).ownerUserId()).isEqualTo(OWNER_ID);
    }

    @Test
    @DisplayName("listSlackCommandTargets — connect_method=byo 행은 제외하고, 키 없는 레거시는 계속 포함한다")
    void listSlackCommandTargetsExcludesByoRowsAndKeepsLegacyRows() {
        IntegrationService service = service();
        Integration byo = slackByoWorkspaceIntegration("T123", "U111", INTEGRATION_ID);
        Integration legacy = slackWorkspaceIntegration("T123", INTEGRATION_ID_2);
        when(integrationRepository.findAllByProvider(IntegrationProvider.SLACK))
                .thenReturn(List.of(byo, legacy));

        List<IntegrationService.SlackCommandTarget> targets = service.listSlackCommandTargets("T123");

        assertThat(targets).extracting(IntegrationService.SlackCommandTarget::integrationId)
                .containsExactly(INTEGRATION_ID_2);
        assertThat(targets.get(0).connectedUserId()).isNull();
    }

    @Test
    @DisplayName("backfillSlackConnectedUserId — 기존 external_ref 키를 유지한 채 connected_user_id만 추가")
    void backfillSlackConnectedUserIdAddsConnectedUserIdAndKeepsOtherKeys() {
        IntegrationService service = service();
        Integration row = Integration.oauth(
                project(),
                IntegrationProvider.SLACK,
                Map.of(
                        SlackOAuthConnectFlow.WORKSPACE_ID, "T123",
                        SlackOAuthConnectFlow.WORKSPACE_NAME, "Acme"),
                new byte[] {1, 2, 3});
        ReflectionTestUtils.setField(row, "id", INTEGRATION_ID);
        when(integrationRepository.findById(INTEGRATION_ID)).thenReturn(Optional.of(row));

        service.backfillSlackConnectedUserId(INTEGRATION_ID, "U111");

        assertThat(row.externalRefValue(SlackOAuthConnectFlow.WORKSPACE_ID)).isEqualTo("T123");
        assertThat(row.externalRefValue(SlackOAuthConnectFlow.WORKSPACE_NAME)).isEqualTo("Acme");
        assertThat(row.externalRefValue(SlackOAuthConnectFlow.CONNECTED_USER_ID)).isEqualTo("U111");
    }

    @Test
    @DisplayName("backfillSlackConnectedUserId — 없는 integration이면 NotFoundException")
    void backfillSlackConnectedUserIdRejectsMissingIntegration() {
        IntegrationService service = service();
        when(integrationRepository.findById(INTEGRATION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.backfillSlackConnectedUserId(INTEGRATION_ID, "U111"))
                .isInstanceOf(NotFoundException.class);
    }

    // 중립 값 이전 배포가 저장한 행 재현. 엔티티 상수가 아니라 문자열 리터럴을 쓰는 이유는 DB에 실제로
    // 들어 있는 값이 계약이기 때문이다 — 상수를 참조하면 상수가 바뀔 때 테스트가 따라가 호환이 깨진 걸 놓친다.
    private Integration legacyPendingJira(Project project, Map<String, Object> externalRef) {
        Integration integration = Integration.pendingSelection(
                project, IntegrationProvider.JIRA, new byte[] {1, 2, 3});
        integration.applyExternalRef(externalRef);
        return integration;
    }

    // workspace_id만 있는 Slack 행 (connected_user_id 없는 레거시 형태 포함)
    private Integration slackWorkspaceIntegration(String workspaceId, UUID integrationId) {
        return slackWorkspaceIntegration(workspaceId, integrationId, project());
    }

    private Integration slackWorkspaceIntegration(String workspaceId, UUID integrationId, Project project) {
        Integration i = Integration.oauth(project, IntegrationProvider.SLACK,
                Map.of(SlackOAuthConnectFlow.WORKSPACE_ID, workspaceId), new byte[]{1, 2, 3});
        ReflectionTestUtils.setField(i, "id", integrationId);
        return i;
    }

    // connect_method=byo 인 Slack 행. connectedUserId 가 있으면 tokens_revoked 매칭 키도 넣는다.
    private Integration slackByoWorkspaceIntegration(String workspaceId, String connectedUserId, UUID integrationId) {
        return slackByoWorkspaceIntegration(workspaceId, connectedUserId, integrationId, project());
    }

    private Integration slackByoWorkspaceIntegration(
            String workspaceId, String connectedUserId, UUID integrationId, Project project
    ) {
        Map<String, Object> externalRef = new LinkedHashMap<>();
        externalRef.put(SlackOAuthConnectFlow.WORKSPACE_ID, workspaceId);
        if (connectedUserId != null) {
            externalRef.put(SlackOAuthConnectFlow.CONNECTED_USER_ID, connectedUserId);
        }
        externalRef.put(SlackOAuthConnectFlow.CONNECT_METHOD, SlackOAuthConnectFlow.CONNECT_METHOD_BYO);
        Integration i = Integration.oauth(project, IntegrationProvider.SLACK, externalRef, new byte[]{1, 2, 3});
        ReflectionTestUtils.setField(i, "id", integrationId);
        return i;
    }

    // workspace_id + connected_user_id가 있는 Slack 행
    private Integration slackUserIntegration(String workspaceId, String connectedUserId, UUID integrationId) {
        return slackUserIntegration(workspaceId, connectedUserId, integrationId, project());
    }

    private Integration slackUserIntegration(
            String workspaceId, String connectedUserId, UUID integrationId, Project project
    ) {
        Integration i = Integration.oauth(project, IntegrationProvider.SLACK,
                Map.of(SlackOAuthConnectFlow.WORKSPACE_ID, workspaceId,
                        "connected_user_id", connectedUserId),
                new byte[]{1, 2, 3});
        ReflectionTestUtils.setField(i, "id", integrationId);
        return i;
    }

    private User user() {
        User user = new User("github", "12345", "owner@example.com", "Owner", null);
        ReflectionTestUtils.setField(user, "id", OWNER_ID);
        return user;
    }

    private Project project() {
        return project(PROJECT_ID);
    }

    private Project project(UUID projectId) {
        return namedProject(projectId, "History Tracker");
    }

    private Project namedProject(UUID projectId, String name) {
        Project project = new Project(user(), name, null);
        ReflectionTestUtils.setField(project, "id", projectId);
        return project;
    }

    private GitHubInstallation installation() {
        GitHubInstallation installation = new GitHubInstallation(98765L, "Organization", "acme", user());
        ReflectionTestUtils.setField(installation, "id", INSTALLATION_ID);
        return installation;
    }

    private RepositoryResponse visibleRepo() {
        return visibleRepo(12345L, "acme/widget");
    }

    private RepositoryResponse visibleRepo(Long repositoryId, String fullName) {
        return new RepositoryResponse(
                repositoryId,
                "widget",
                fullName,
                "acme",
                true,
                "private",
                "main"
        );
    }

    private Checkpoint checkpoint(Project project, String cursorKey, Instant updatedAt) {
        Checkpoint checkpoint = new Checkpoint(project, IntegrationProvider.GITHUB, cursorKey, updatedAt);
        // updatedAt은 @PrePersist에서만 채워지므로 단위 테스트에서는 직접 주입
        ReflectionTestUtils.setField(checkpoint, "updatedAt", updatedAt);
        return checkpoint;
    }

    @Test
    @DisplayName("선택적 중간 단계는 건너뛸 수 있다 — ClickUp의 folder처럼 없어도 하위 단계로 간다")
    void buildSelectionExternalRefSkipsOptionalStep() {
        IntegrationService service = serviceWithSelectionFlow(stubFlow(List.of(
                SelectionStep.required("workspace_id", "workspace_name", "워크스페이스"),
                SelectionStep.optional("folder_id", "folder_name", "폴더"),
                SelectionStep.required("list_id", "list_name", "리스트"))));

        Map<String, Object> externalRef = service.buildSelectionExternalRef(
                IntegrationProvider.JIRA,
                Map.of(
                        "workspace_id", new IntegrationService.SelectionInput("W1", "Acme"),
                        "list_id", new IntegrationService.SelectionInput("L1", "Backlog")));

        assertThat(externalRef).containsOnly(
                Map.entry("workspace_id", "W1"),
                Map.entry("workspace_name", "Acme"),
                Map.entry("list_id", "L1"),
                Map.entry("list_name", "Backlog"));
    }

    @Test
    @DisplayName("필수 단계가 비면 400 — 확정 후 수집이 대상을 못 찾는 상태를 만들지 않는다")
    void buildSelectionExternalRefRejectsMissingRequiredStep() {
        IntegrationService service = serviceWithSelectionFlow(stubFlow(List.of(
                SelectionStep.required("workspace_id", "workspace_name", "워크스페이스"),
                SelectionStep.required("list_id", "list_name", "리스트"))));

        assertThatThrownBy(() -> service.buildSelectionExternalRef(
                IntegrationProvider.JIRA,
                Map.of("workspace_id", new IntegrationService.SelectionInput("W1", "Acme"))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("list_id");
    }

    @Test
    @DisplayName("선택 단계가 없는 provider의 단계 조회는 빈 목록 (동의 직후 곧바로 확정)")
    void selectionStepsIsEmptyForProviderWithoutFlow() {
        IntegrationService service = service();
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());

        assertThat(service.selectionSteps(OWNER_ID, PROJECT_ID, IntegrationProvider.SLACK)).isEmpty();
    }

    // 저장 정책만 검증하므로 provider 프로토콜은 스텁으로 둔다 (교환 결과는 각 flow 테스트가 검증한다)
    private OAuthConnectFlow connectFlow(IntegrationProvider provider) {
        OAuthConnectFlow flow = mock(OAuthConnectFlow.class);
        when(flow.provider()).thenReturn(provider);
        return flow;
    }

    private IntegrationSelectionFlow stubFlow(List<SelectionStep> steps) {
        return new IntegrationSelectionFlow() {
            @Override
            public IntegrationProvider provider() {
                return IntegrationProvider.JIRA;
            }

            @Override
            public List<SelectionStep> steps() {
                return steps;
            }

            @Override
            public List<SelectionOption> options(UUID projectId, String stepKey, Map<String, String> selected) {
                return List.of();
            }
        };
    }

    private IntegrationService serviceWithSelectionFlow(IntegrationSelectionFlow flow) {
        return new IntegrationService(
                integrationRepository,
                checkpointRepository,
                projectService,
                gitHubInstallationService,
                installationTokenService,
                credentialCryptoService,
                pipelineWorkerClient,
                aiEngineGraphClient,
                new ProviderCredentialLifecycleRegistry(List.of()),
                // 이 factory를 쓰는 테스트는 선택 단계 로직만 검증하고 disconnect를 호출하지 않으므로
                // stubbing 없는 mock으로 충분하다
                revocationService,
                new AccessTokenRefresherRegistry(List.of()),
                new IntegrationSelectionFlowRegistry(List.of(flow)),
                new TransactionTemplate(transactionManager),
                planService,
                slackClient,
                new SlackCredentialCodec(credentialCryptoService)
        );
    }

    // disconnect가 폐기를 IntegrationRevocationService로 위임하는지만 별도로 고정하는 자리 —
    // 위임된 뒤 registry.find(Optional)·provider client 호출은 IntegrationRevocationServiceTest가 검증한다
    private IntegrationService serviceWithRevocationService(IntegrationRevocationService revocationService) {
        return new IntegrationService(
                integrationRepository,
                checkpointRepository,
                projectService,
                gitHubInstallationService,
                installationTokenService,
                credentialCryptoService,
                pipelineWorkerClient,
                aiEngineGraphClient,
                new ProviderCredentialLifecycleRegistry(List.of()),
                revocationService,
                new AccessTokenRefresherRegistry(List.of()),
                new IntegrationSelectionFlowRegistry(List.of()),
                new TransactionTemplate(transactionManager),
                planService,
                slackClient,
                new SlackCredentialCodec(credentialCryptoService)
        );
    }

    private IntegrationService service() {
        // 실제 lifecycle 구현을 물린 IntegrationRevocationService — disconnect가 이를 통해
        // provider 클라이언트까지 닿는지 기존과 동일하게 검증한다(위임 자체는
        // disconnectDelegatesRevocationToIntegrationRevocationService가 mock으로 별도 고정한다).
        SlackCredentialCodec slackCredentialCodec = new SlackCredentialCodec(credentialCryptoService);
        ProviderCredentialLifecycleRegistry credentialLifecycleRegistry = new ProviderCredentialLifecycleRegistry(List.of(
                new SlackCredentialLifecycle(slackClient, slackCredentialCodec),
                new JiraCredentialLifecycle(jiraOAuthClient, jiraCredentialCodec),
                new DiscordCredentialLifecycle(discordClient, credentialCryptoService),
                new GoogleChatCredentialLifecycle(googleChatClient, googleChatCredentialCodec)
        ));
        return new IntegrationService(
                integrationRepository,
                checkpointRepository,
                projectService,
                gitHubInstallationService,
                installationTokenService,
                credentialCryptoService,
                pipelineWorkerClient,
                aiEngineGraphClient,
                credentialLifecycleRegistry,
                new IntegrationRevocationService(integrationRepository, credentialLifecycleRegistry),
                // 갱신은 별도 SPI다 — Slack·Discord는 폐기만 있고 갱신 등록이 없다
                new AccessTokenRefresherRegistry(List.of(
                        new JiraAccessTokenRefresher(jiraTokenService),
                        new GoogleChatAccessTokenRefresher(googleChatTokenService))),
                new IntegrationSelectionFlowRegistry(List.of(
                        new JiraSelectionFlow(jiraOAuthClient, jiraClient, jiraTokenService),
                        new GoogleChatSelectionFlow(googleChatClient, googleChatTokenService))),
                new TransactionTemplate(transactionManager),
                planService,
                slackClient,
                slackCredentialCodec
        );
    }

    private static class NoopTransactionManager extends AbstractPlatformTransactionManager {

        private int rollbackCount;
        private int beginCount;
        private boolean transactionActive;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            beginCount++;
            transactionActive = true;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            transactionActive = false;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            transactionActive = false;
            rollbackCount++;
        }
    }
}
