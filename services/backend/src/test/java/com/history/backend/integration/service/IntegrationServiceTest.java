package com.history.backend.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.history.backend.common.crypto.CredentialCryptoService;
import com.history.backend.auth.domain.User;
import com.history.backend.common.error.BadRequestException;
import com.history.backend.common.error.ConflictException;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.github.domain.GitHubInstallation;
import com.history.backend.github.service.GitHubInstallationService;
import com.history.backend.github.service.InstallationTokenService;
import com.history.backend.integration.domain.Integration;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.dto.IntegrationResponse;
import com.history.backend.integration.repository.IntegrationRepository;
import com.history.backend.jira.service.JiraClient;
import com.history.backend.project.domain.Project;
import com.history.backend.project.service.ProjectService;
import com.history.backend.shared.domain.Checkpoint;
import com.history.backend.shared.repository.CheckpointRepository;
import com.history.backend.slack.service.SlackClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    private JiraClient jiraClient;

    @Mock
    private PipelineWorkerClient pipelineWorkerClient;

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
    @DisplayName("Slack 토큰 암호화 후 소유 프로젝트에 연동 저장")
    void connectSlackWorkspaceEncryptsTokenAndSavesIntegrationForOwnedProject() {
        IntegrationService service = service();
        Project project = project();
        byte[] encryptedCredential = new byte[] {1, 2, 3};
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project);
        when(integrationRepository.existsByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.SLACK))
                .thenReturn(false);
        when(slackClient.verifyToken("xoxb-token"))
                .thenReturn(new SlackClient.SlackWorkspace("T123", "Acme"));
        when(credentialCryptoService.encrypt("xoxb-token")).thenReturn(encryptedCredential);
        when(integrationRepository.saveAndFlush(any(Integration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doAnswer(invocation -> {
            assertThat(transactionManager.transactionActive).isFalse();
            return null;
        }).when(pipelineWorkerClient).triggerCollection(IntegrationProvider.SLACK, PROJECT_ID);

        Integration result = service.connectSlackWorkspace(
                OWNER_ID,
                PROJECT_ID,
                "  xoxb-token  "
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
    @DisplayName("중복 Slack 연동 거부")
    void connectSlackWorkspaceRejectsDuplicateSlackProvider() {
        IntegrationService service = service();
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(integrationRepository.existsByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.SLACK))
                .thenReturn(true);

        assertThatThrownBy(() -> service.connectSlackWorkspace(
                OWNER_ID,
                PROJECT_ID,
                "xoxb-token"
        ))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Slack integration already exists.");
    }

    @Test
    @DisplayName("Slack 연동 시 유니크 제약 위반을 ConflictException으로 변환")
    void connectSlackWorkspaceConvertsUniqueConstraintViolationToConflict() {
        IntegrationService service = service();
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(integrationRepository.existsByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.SLACK))
                .thenReturn(false);
        when(slackClient.verifyToken("xoxb-token"))
                .thenReturn(new SlackClient.SlackWorkspace("T123", "Acme"));
        when(credentialCryptoService.encrypt("xoxb-token")).thenReturn(new byte[] {1, 2, 3});
        when(integrationRepository.saveAndFlush(any(Integration.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate integration"));

        assertThatThrownBy(() -> service.connectSlackWorkspace(
                OWNER_ID,
                PROJECT_ID,
                "xoxb-token"
        ))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Slack integration already exists.");
    }

    @Test
    @DisplayName("Jira 자격 증명 암호화 후 소유 프로젝트에 연동 저장")
    void connectJiraProjectEncryptsCredentialAndSavesIntegrationForOwnedProject() {
        IntegrationService service = service();
        Project project = project();
        byte[] encryptedCredential = new byte[] {4, 5, 6};
        when(jiraClient.verifyProject(
                "https://93.184.216.34",
                "PROJ",
                "owner@example.com",
                "jira-token"
        )).thenReturn(new JiraClient.JiraProject("PROJ", "Project"));
        when(credentialCryptoService.encrypt("owner@example.com:jira-token"))
                .thenReturn(encryptedCredential);
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project);
        when(integrationRepository.existsByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn(false);
        when(integrationRepository.saveAndFlush(any(Integration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doAnswer(invocation -> {
            assertThat(transactionManager.transactionActive).isFalse();
            return null;
        }).when(pipelineWorkerClient).triggerCollection(IntegrationProvider.JIRA, PROJECT_ID);

        Integration result = service.connectJiraProject(
                OWNER_ID,
                PROJECT_ID,
                "  https://93.184.216.34/  ",
                "PROJ",
                "  owner@example.com  ",
                "  jira-token  "
        );

        assertThat(result.getProject()).isSameAs(project);
        assertThat(result.getInstallation()).isNull();
        assertThat(result.getProvider()).isEqualTo(IntegrationProvider.JIRA);
        assertThat(result.getJiraProjectKey()).isEqualTo("PROJ");
        assertThat(result.getJiraProjectName()).isEqualTo("Project");
        assertThat(result.getJiraBaseUrl()).isEqualTo("https://93.184.216.34");
        assertThat(result.getEncryptedCredential()).containsExactly(encryptedCredential);
        verify(pipelineWorkerClient).triggerCollection(IntegrationProvider.JIRA, PROJECT_ID);
    }

    @Test
    @DisplayName("루프백 base URL로 Jira 연동 거부")
    void connectJiraProjectRejectsLoopbackBaseUrl() {
        IntegrationService service = service();

        assertThatThrownBy(() -> service.connectJiraProject(
                OWNER_ID,
                PROJECT_ID,
                "https://127.0.0.1",
                "PROJ",
                "owner@example.com",
                "jira-token"
        ))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Jira base URL host must be public.");
    }

    @Test
    @DisplayName("HTTP base URL로 Jira 연동 거부")
    void connectJiraProjectRejectsHttpBaseUrl() {
        IntegrationService service = service();

        assertThatThrownBy(() -> service.connectJiraProject(
                OWNER_ID,
                PROJECT_ID,
                "http://example.atlassian.net",
                "PROJ",
                "owner@example.com",
                "jira-token"
        ))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Jira base URL must start with https://.");
    }

    @Test
    @DisplayName("중복 Jira 연동 거부")
    void connectJiraProjectRejectsDuplicateJiraProvider() {
        IntegrationService service = service();
        when(jiraClient.verifyProject(
                "https://93.184.216.34",
                "PROJ",
                "owner@example.com",
                "jira-token"
        )).thenReturn(new JiraClient.JiraProject("PROJ", "Project"));
        when(credentialCryptoService.encrypt("owner@example.com:jira-token"))
                .thenReturn(new byte[] {4, 5, 6});
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(integrationRepository.existsByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn(true);

        assertThatThrownBy(() -> service.connectJiraProject(
                OWNER_ID,
                PROJECT_ID,
                "https://93.184.216.34",
                "PROJ",
                "owner@example.com",
                "jira-token"
        ))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Jira integration already exists.");
    }

    @Test
    @DisplayName("Jira 연동 시 유니크 제약 위반을 ConflictException으로 변환")
    void connectJiraProjectConvertsUniqueConstraintViolationToConflict() {
        IntegrationService service = service();
        when(jiraClient.verifyProject(
                "https://93.184.216.34",
                "PROJ",
                "owner@example.com",
                "jira-token"
        )).thenReturn(new JiraClient.JiraProject("PROJ", "Project"));
        when(credentialCryptoService.encrypt("owner@example.com:jira-token"))
                .thenReturn(new byte[] {4, 5, 6});
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(integrationRepository.existsByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.JIRA))
                .thenReturn(false);
        when(integrationRepository.saveAndFlush(any(Integration.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate integration"));

        assertThatThrownBy(() -> service.connectJiraProject(
                OWNER_ID,
                PROJECT_ID,
                "https://93.184.216.34",
                "PROJ",
                "owner@example.com",
                "jira-token"
        ))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Jira integration already exists.");
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

    private IntegrationService service() {
        return new IntegrationService(
                integrationRepository,
                checkpointRepository,
                projectService,
                gitHubInstallationService,
                installationTokenService,
                credentialCryptoService,
                slackClient,
                jiraClient,
                pipelineWorkerClient,
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
