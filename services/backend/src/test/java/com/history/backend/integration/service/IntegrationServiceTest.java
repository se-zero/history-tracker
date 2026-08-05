package com.history.backend.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.common.crypto.CredentialCryptoService;
import com.history.backend.auth.domain.User;
import com.history.backend.common.error.BadGatewayException;
import com.history.backend.common.error.ConflictException;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.github.domain.GitHubInstallation;
import com.history.backend.github.service.GitHubInstallationService;
import com.history.backend.github.service.InstallationTokenService;
import com.history.backend.graph.service.AiEngineGraphClient;
import com.history.backend.integration.domain.Integration;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.domain.SelectionStep;
import com.history.backend.common.error.BadRequestException;
import com.history.backend.integration.dto.IntegrationResponse;
import com.history.backend.integration.repository.IntegrationRepository;
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
    private static final UUID INSTALLATION_ID = UUID.fromString("45b30a75-46d0-4402-b842-9e9c7d07e9ab");
    private static final UUID INTEGRATION_ID = UUID.fromString("2f0f1c2e-9a4e-4f0e-9d1a-6b0f8c3d7a55");

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
    private PipelineWorkerClient pipelineWorkerClient;

    @Mock
    private AiEngineGraphClient aiEngineGraphClient;

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
        when(gitHubInstallationService.getInstallationForInstaller(OWNER_ID, INSTALLATION_ID))
                .thenReturn(installation);
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
    }

    @Test
    @DisplayName("설치 토큰 발급 실패 시 저장 트랜잭션 시작하지 않음")
    void connectGitHubRepositoryDoesNotStartSaveTransactionWhenInstallationTokenCannotBeIssued() {
        IntegrationService service = service();
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(gitHubInstallationService.getInstallationForInstaller(OWNER_ID, INSTALLATION_ID))
                .thenReturn(installation());
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
        when(gitHubInstallationService.getInstallationForInstaller(OWNER_ID, INSTALLATION_ID))
                .thenReturn(installation);
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
    }

    @Test
    @DisplayName("연동 저장이 실패하면 프로젝트 생성까지 롤백되고 수집 트리거도 하지 않음")
    void createProjectWithGitHubRepositoryRollsBackProjectWhenIntegrationSaveFails() {
        IntegrationService service = service();
        when(gitHubInstallationService.getInstallationForInstaller(OWNER_ID, INSTALLATION_ID))
                .thenReturn(installation());
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
        when(gitHubInstallationService.getInstallationForInstaller(OWNER_ID, INSTALLATION_ID))
                .thenReturn(installation());
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
        when(gitHubInstallationService.getInstallationForInstaller(OWNER_ID, INSTALLATION_ID))
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
    }

    @Test
    @DisplayName("존재하지 않는 설치 정보를 NotFoundException으로 전파")
    void connectGitHubRepositoryPropagatesMissingInstallationAsNotFound() {
        IntegrationService service = service();
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(gitHubInstallationService.getInstallationForInstaller(OWNER_ID, INSTALLATION_ID))
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
        when(gitHubInstallationService.getInstallationForInstaller(OWNER_ID, INSTALLATION_ID))
                .thenReturn(installation());
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
    @DisplayName("Slack code 교환 후 소유 프로젝트에 연동 저장")
    void connectSlackWorkspaceExchangesCodeAndSavesIntegrationForOwnedProject() {
        IntegrationService service = service();
        Project project = project();
        byte[] encryptedCredential = new byte[] {1, 2, 3};
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project);
        when(integrationRepository.existsByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.SLACK))
                .thenReturn(false);
        when(slackClient.exchangeCode("auth-code"))
                .thenReturn(new SlackClient.SlackWorkspace("T123", "Acme", "xoxp-token"));
        when(credentialCryptoService.encrypt("xoxp-token")).thenReturn(encryptedCredential);
        when(integrationRepository.saveAndFlush(any(Integration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doAnswer(invocation -> {
            assertThat(transactionManager.transactionActive).isFalse();
            return null;
        }).when(pipelineWorkerClient).triggerCollection(IntegrationProvider.SLACK, PROJECT_ID);

        Integration result = service.connectSlackWorkspace(
                OWNER_ID,
                PROJECT_ID,
                "auth-code"
        );

        assertThat(result.getProject()).isSameAs(project);
        assertThat(result.getInstallation()).isNull();
        assertThat(result.getProvider()).isEqualTo(IntegrationProvider.SLACK);
        assertThat(result.getSlackWorkspaceId()).isEqualTo("T123");
        assertThat(result.getSlackWorkspaceName()).isEqualTo("Acme");
        assertThat(result.getEncryptedCredential()).containsExactly(encryptedCredential);
        verify(pipelineWorkerClient).triggerCollection(IntegrationProvider.SLACK, PROJECT_ID);
    }

    @Test
    @DisplayName("중복 Slack 연동 거부 (code 교환 호출 안 함)")
    void connectSlackWorkspaceRejectsDuplicateSlackProviderWithoutExchangingCode() {
        IntegrationService service = service();
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(integrationRepository.existsByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.SLACK))
                .thenReturn(true);

        assertThatThrownBy(() -> service.connectSlackWorkspace(
                OWNER_ID,
                PROJECT_ID,
                "auth-code"
        ))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Slack integration already exists.");
        // 이미 연동된 프로젝트라면 Slack API 호출로 코드를 낭비하지 않는다
        verify(slackClient, never()).exchangeCode(anyString());
    }

    @Test
    @DisplayName("Slack 연동 시 유니크 제약 위반을 ConflictException으로 변환")
    void connectSlackWorkspaceConvertsUniqueConstraintViolationToConflict() {
        IntegrationService service = service();
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(integrationRepository.existsByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.SLACK))
                .thenReturn(false);
        when(slackClient.exchangeCode("auth-code"))
                .thenReturn(new SlackClient.SlackWorkspace("T123", "Acme", "xoxp-token"));
        when(credentialCryptoService.encrypt("xoxp-token")).thenReturn(new byte[] {1, 2, 3});
        when(integrationRepository.saveAndFlush(any(Integration.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate integration"));

        assertThatThrownBy(() -> service.connectSlackWorkspace(
                OWNER_ID,
                PROJECT_ID,
                "auth-code"
        ))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Slack integration already exists.");
    }

    @Test
    @DisplayName("code 교환 후 새 pending Jira 연동 생성")
    void connectJiraSiteCreatesNewPendingIntegration() {
        IntegrationService service = service();
        Project project = project();
        byte[] encryptedCredential = new byte[] {7, 8, 9};
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project);
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn(Optional.empty());
        when(jiraOAuthClient.exchangeCode("auth-code"))
                .thenReturn(new JiraOAuthClient.JiraTokens("atl-access-token", "atl-refresh-token", 3600L));
        ArgumentCaptor<JiraCredential> credentialCaptor = ArgumentCaptor.forClass(JiraCredential.class);
        when(jiraCredentialCodec.encrypt(credentialCaptor.capture())).thenReturn(encryptedCredential);
        when(integrationRepository.saveAndFlush(any(Integration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Integration result = service.connectJiraSite(OWNER_ID, PROJECT_ID, "auth-code");

        assertThat(result.getProject()).isSameAs(project);
        assertThat(result.getProvider()).isEqualTo(IntegrationProvider.JIRA);
        assertThat(result.isPendingSelection()).isTrue();
        assertThat(result.getEncryptedCredential()).containsExactly(encryptedCredential);
        assertThat(credentialCaptor.getValue().accessToken()).isEqualTo("atl-access-token");
        assertThat(credentialCaptor.getValue().refreshToken()).isEqualTo("atl-refresh-token");
        assertThat(credentialCaptor.getValue().expiresAt()).isAfter(Instant.now());
        // 확정 전이므로 초기 수집 트리거는 completeJiraProject의 책임이다
        verify(pipelineWorkerClient, never()).triggerCollection(any(), any());
        // 최초 연결의 pending 행에는 cloud_id가 없으므로 자동 복원 분기를 타지 않는다
        verify(jiraOAuthClient, never()).listAccessibleResources(anyString());
    }

    @Test
    @DisplayName("재동의 시 pending 행에 cloud_id·project_key가 남아 있고 새 토큰으로 여전히 접근 가능하면 자동 복원 후 확정한다")
    void connectJiraSiteAutoRestoresWhenExistingPendingProjectIsStillAccessible() {
        IntegrationService service = service();
        Project project = project();
        Integration revertedPending = Integration.pendingSelection(project, IntegrationProvider.JIRA, new byte[] {1, 2, 3});
        revertedPending.applySelections(java.util.Map.of("cloud_id", "cloud-1", "site_name", "acme", "project_key", "PROJ", "project_name", "Project"));
        // 갱신 실패로 pending 되돌아온 행을 재현 — cloud_id·project_key는 남아 있다
        revertedPending.markPendingSelection();
        byte[] newEncryptedCredential = new byte[] {7, 8, 9};
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project);
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn(Optional.of(revertedPending));
        when(jiraOAuthClient.exchangeCode("auth-code"))
                .thenReturn(new JiraOAuthClient.JiraTokens("new-access-token", "new-refresh-token", 3600L));
        when(jiraCredentialCodec.encrypt(any(JiraCredential.class))).thenReturn(newEncryptedCredential);
        when(integrationRepository.saveAndFlush(any(Integration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(jiraTokenService.getAccessToken(PROJECT_ID)).thenReturn("new-access-token");
        when(jiraOAuthClient.listAccessibleResources("new-access-token"))
                .thenReturn(List.of(new JiraOAuthClient.JiraSite("cloud-1", "acme", "https://acme.atlassian.net")));
        // 첫 단계만이 아니라 필수 단계를 전부 확인한다 — 사이트는 살아 있는데 프로젝트가 지워진 경우도 걸러진다
        when(jiraClient.listProjects("cloud-1", "new-access-token"))
                .thenReturn(List.of(new JiraClient.JiraProject("PROJ", "Project")));

        Integration result = service.connectJiraSite(OWNER_ID, PROJECT_ID, "auth-code");

        assertThat(result).isSameAs(revertedPending);
        assertThat(result.isPendingSelection()).isFalse();
        assertThat(result.selectionValue("project_key")).isEqualTo("PROJ");
        assertThat(result.selectionValue("project_name")).isEqualTo("Project");
        verify(jiraTokenService).ensureAccessToken(PROJECT_ID);
        verify(pipelineWorkerClient).triggerCollection(IntegrationProvider.JIRA, PROJECT_ID);
    }

    @Test
    @DisplayName("재동의로 얻은 새 토큰의 접근 목록에 기존 cloudId가 없으면(다른 Atlassian 계정) pending을 유지한다")
    void connectJiraSiteKeepsPendingWhenRestoredCloudIdIsNoLongerAccessible() {
        IntegrationService service = service();
        Project project = project();
        Integration revertedPending = Integration.pendingSelection(project, IntegrationProvider.JIRA, new byte[] {1, 2, 3});
        revertedPending.applySelections(java.util.Map.of("cloud_id", "cloud-1", "site_name", "acme", "project_key", "PROJ", "project_name", "Project"));
        revertedPending.markPendingSelection();
        byte[] newEncryptedCredential = new byte[] {7, 8, 9};
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project);
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn(Optional.of(revertedPending));
        when(jiraOAuthClient.exchangeCode("auth-code"))
                .thenReturn(new JiraOAuthClient.JiraTokens("new-access-token", "new-refresh-token", 3600L));
        when(jiraCredentialCodec.encrypt(any(JiraCredential.class))).thenReturn(newEncryptedCredential);
        when(integrationRepository.saveAndFlush(any(Integration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(jiraTokenService.getAccessToken(PROJECT_ID)).thenReturn("new-access-token");
        when(jiraOAuthClient.listAccessibleResources("new-access-token"))
                .thenReturn(List.of(new JiraOAuthClient.JiraSite("cloud-2", "other-site", "https://other.atlassian.net")));

        Integration result = service.connectJiraSite(OWNER_ID, PROJECT_ID, "auth-code");

        assertThat(result).isSameAs(revertedPending);
        assertThat(result.isPendingSelection()).isTrue();
        verify(pipelineWorkerClient, never()).triggerCollection(any(), any());
        verify(jiraTokenService, never()).ensureAccessToken(any());
    }

    @Test
    @DisplayName("자동 복원 중 사이트 목록 조회가 예외를 던져도 연결 자체는 성공 처리하고 pending을 반환한다")
    void connectJiraSiteKeepsPendingWhenAccessibleResourcesLookupFails() {
        // 토큰 저장 트랜잭션은 이미 커밋된 뒤이므로(동의 자체는 성공) 조회 실패를 "연결 실패"로
        // 알리면 실제 상태와 어긋난다 — 복원만 포기하고 pending으로 남겨 사용자가 그 자리에서
        // 사이트·프로젝트를 고를 수 있게 한다.
        IntegrationService service = service();
        Project project = project();
        Integration revertedPending = Integration.pendingSelection(project, IntegrationProvider.JIRA, new byte[] {1, 2, 3});
        revertedPending.applySelections(java.util.Map.of("cloud_id", "cloud-1", "site_name", "acme", "project_key", "PROJ", "project_name", "Project"));
        revertedPending.markPendingSelection();
        byte[] newEncryptedCredential = new byte[] {7, 8, 9};
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project);
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn(Optional.of(revertedPending));
        when(jiraOAuthClient.exchangeCode("auth-code"))
                .thenReturn(new JiraOAuthClient.JiraTokens("new-access-token", "new-refresh-token", 3600L));
        when(jiraCredentialCodec.encrypt(any(JiraCredential.class))).thenReturn(newEncryptedCredential);
        when(integrationRepository.saveAndFlush(any(Integration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(jiraTokenService.getAccessToken(PROJECT_ID)).thenReturn("new-access-token");
        when(jiraOAuthClient.listAccessibleResources("new-access-token"))
                .thenThrow(new BadGatewayException("Jira accessible resources request failed."));

        Integration result = service.connectJiraSite(OWNER_ID, PROJECT_ID, "auth-code");

        assertThat(result).isSameAs(revertedPending);
        assertThat(result.isPendingSelection()).isTrue();
        verify(pipelineWorkerClient, never()).triggerCollection(any(), any());
        verify(jiraTokenService, never()).ensureAccessToken(any());
    }

    @Test
    @DisplayName("pending 행 재시도 시 기존 행의 자격증명을 덮어쓴다")
    void connectJiraSiteOverwritesExistingPendingIntegration() {
        IntegrationService service = service();
        Project project = project();
        Integration pending = Integration.pendingSelection(project, IntegrationProvider.JIRA, new byte[] {1, 2, 3});
        byte[] newEncryptedCredential = new byte[] {7, 8, 9};
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project);
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn(Optional.of(pending));
        when(jiraOAuthClient.exchangeCode("auth-code"))
                .thenReturn(new JiraOAuthClient.JiraTokens("new-access-token", "new-refresh-token", 3600L));
        when(jiraCredentialCodec.encrypt(any(JiraCredential.class))).thenReturn(newEncryptedCredential);
        when(integrationRepository.saveAndFlush(any(Integration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Integration result = service.connectJiraSite(OWNER_ID, PROJECT_ID, "auth-code");

        assertThat(result).isSameAs(pending);
        assertThat(result.isPendingSelection()).isTrue();
        assertThat(result.getEncryptedCredential()).containsExactly(newEncryptedCredential);
    }

    @Test
    @DisplayName("이미 확정된 Jira 연동에 재연결 시도 → 409, code 교환 안 함")
    void connectJiraSiteRejectsWhenAlreadyConfirmedWithoutExchangingCode() {
        IntegrationService service = service();
        Integration confirmed = Integration.pendingSelection(project(), IntegrationProvider.JIRA, new byte[] {1, 2, 3});
        confirmed.applySelections(java.util.Map.of("cloud_id", "cloud-1", "site_name", "acme", "project_key", "PROJ", "project_name", "Project"));
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(integrationRepository.findByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn(Optional.of(confirmed));

        assertThatThrownBy(() -> service.connectJiraSite(OWNER_ID, PROJECT_ID, "auth-code"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Jira integration already exists.");
        // 1회용 code를 낭비하지 않도록 확정 여부를 code 교환 전에 확인한다
        verify(jiraOAuthClient, never()).exchangeCode(anyString());
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
        assertThat(result.selectionValue("project_key")).isEqualTo("PROJ");
        assertThat(result.selectionValue("project_name")).isEqualTo("Project");
        // GitHub이 트리거 직전에 토큰을 갱신하는 것과 같은 자리 — 토큰 확보가 먼저, 수집 트리거가 그다음이다
        InOrder inOrder = inOrder(jiraTokenService, pipelineWorkerClient);
        inOrder.verify(jiraTokenService).ensureAccessToken(PROJECT_ID);
        inOrder.verify(pipelineWorkerClient).triggerCollection(IntegrationProvider.JIRA, PROJECT_ID);
    }

    @Test
    @DisplayName("이미 확정된 행에 다시 확정 시도 → 409, 토큰 확보·트리거 호출 안 함")
    void completeJiraProjectRejectsWhenAlreadyConfirmed() {
        IntegrationService service = service();
        Integration confirmed = Integration.pendingSelection(project(), IntegrationProvider.JIRA, new byte[] {1, 2, 3});
        confirmed.applySelections(java.util.Map.of("cloud_id", "cloud-1", "site_name", "acme", "project_key", "PROJ", "project_name", "Project"));
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
        Integration integration = Integration.slack(project(), "T1", "acme", new byte[] {1, 2, 3});
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

    private User user() {
        User user = new User("github", "12345", "owner@example.com", "Owner", null);
        ReflectionTestUtils.setField(user, "id", OWNER_ID);
        return user;
    }

    private Project project() {
        Project project = new Project(user(), "History Tracker", null);
        ReflectionTestUtils.setField(project, "id", PROJECT_ID);
        return project;
    }

    private GitHubInstallation installation() {
        GitHubInstallation installation = new GitHubInstallation(98765L, "Organization", "acme", user());
        ReflectionTestUtils.setField(installation, "id", INSTALLATION_ID);
        return installation;
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
                slackClient,
                jiraOAuthClient,
                jiraCredentialCodec,
                pipelineWorkerClient,
                aiEngineGraphClient,
                new ProviderCredentialLifecycleRegistry(List.of()),
                new IntegrationSelectionFlowRegistry(List.of(flow)),
                new TransactionTemplate(transactionManager)
        );
    }

    private IntegrationService service() {
        return new IntegrationService(
                integrationRepository,
                checkpointRepository,
                projectService,
                gitHubInstallationService,
                installationTokenService,
                credentialCryptoService,
                slackClient,
                jiraOAuthClient,
                jiraCredentialCodec,
                pipelineWorkerClient,
                aiEngineGraphClient,
                // 실제 lifecycle 구현을 물려 폐기 경로가 provider 클라이언트까지 닿는지 그대로 검증한다
                new ProviderCredentialLifecycleRegistry(List.of(
                        new SlackCredentialLifecycle(slackClient, credentialCryptoService),
                        new JiraCredentialLifecycle(jiraOAuthClient, jiraCredentialCodec, jiraTokenService)
                )),
                new IntegrationSelectionFlowRegistry(List.of(
                        new JiraSelectionFlow(jiraOAuthClient, jiraClient, jiraTokenService))),
                new TransactionTemplate(transactionManager)
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
