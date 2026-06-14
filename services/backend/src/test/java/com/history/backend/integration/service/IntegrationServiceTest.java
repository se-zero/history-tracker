package com.history.backend.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.common.crypto.CredentialCryptoService;
import com.history.backend.auth.domain.User;
import com.history.backend.common.error.BadRequestException;
import com.history.backend.common.error.ConflictException;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.github.domain.GitHubInstallation;
import com.history.backend.github.service.GitHubInstallationService;
import com.history.backend.integration.domain.Integration;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.repository.IntegrationRepository;
import com.history.backend.jira.service.JiraClient;
import com.history.backend.project.domain.Project;
import com.history.backend.project.service.ProjectService;
import com.history.backend.slack.service.SlackClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

@ExtendWith(MockitoExtension.class)
class IntegrationServiceTest {

    private static final UUID OWNER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");
    private static final UUID INSTALLATION_ID = UUID.fromString("45b30a75-46d0-4402-b842-9e9c7d07e9ab");
    private static final UUID INTEGRATION_ID = UUID.fromString("72b9c869-77f6-4b4d-b8c5-db85023ef3b8");

    @Mock
    private IntegrationRepository integrationRepository;

    @Mock
    private ProjectService projectService;

    @Mock
    private GitHubInstallationService gitHubInstallationService;

    @Mock
    private CredentialCryptoService credentialCryptoService;

    @Mock
    private SlackClient slackClient;

    @Mock
    private JiraClient jiraClient;

    private final PlatformTransactionManager transactionManager = new NoopTransactionManager();

    @Test
    void listIntegrationsReturnsIntegrationsForOwnedProject() {
        IntegrationService service = new IntegrationService(
                integrationRepository,
                projectService,
                gitHubInstallationService,
                credentialCryptoService,
                slackClient,
                jiraClient,
                transactionManager
        );
        Project project = project();
        Integration githubIntegration = Integration.github(project, installation(), 12345L, "acme/widget");
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project);
        when(integrationRepository.findAllByProject_IdOrderByCreatedAtDesc(PROJECT_ID))
                .thenReturn(List.of(githubIntegration));

        List<Integration> result = service.listIntegrations(OWNER_ID, PROJECT_ID);

        assertThat(result).containsExactly(githubIntegration);
    }

    @Test
    void disconnectIntegrationDeletesIntegrationForOwnedProject() {
        IntegrationService service = new IntegrationService(
                integrationRepository,
                projectService,
                gitHubInstallationService,
                credentialCryptoService,
                slackClient,
                jiraClient,
                transactionManager
        );
        Integration integration = Integration.github(project(), installation(), 12345L, "acme/widget");
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(integrationRepository.findByIdAndProject_Id(INTEGRATION_ID, PROJECT_ID))
                .thenReturn(Optional.of(integration));

        service.disconnectIntegration(OWNER_ID, PROJECT_ID, INTEGRATION_ID);

        verify(integrationRepository).delete(integration);
    }

    @Test
    void disconnectIntegrationThrowsNotFoundWhenIntegrationMissing() {
        IntegrationService service = new IntegrationService(
                integrationRepository,
                projectService,
                gitHubInstallationService,
                credentialCryptoService,
                slackClient,
                jiraClient,
                transactionManager
        );
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(integrationRepository.findByIdAndProject_Id(INTEGRATION_ID, PROJECT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.disconnectIntegration(OWNER_ID, PROJECT_ID, INTEGRATION_ID))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Integration not found.");
    }

    @Test
    void connectGitHubRepositorySavesIntegrationForOwnedProjectAndInstallation() {
        IntegrationService service = new IntegrationService(
                integrationRepository,
                projectService,
                gitHubInstallationService,
                credentialCryptoService,
                slackClient,
                jiraClient,
                transactionManager
        );
        Project project = project();
        GitHubInstallation installation = installation();
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project);
        when(gitHubInstallationService.getInstallationForInstaller(OWNER_ID, INSTALLATION_ID))
                .thenReturn(installation);
        when(integrationRepository.existsByProject_IdAndProvider(PROJECT_ID, IntegrationProvider.GITHUB))
                .thenReturn(false);
        when(integrationRepository.saveAndFlush(any(Integration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Integration result = service.connectGitHubRepository(
                OWNER_ID,
                PROJECT_ID,
                INSTALLATION_ID,
                12345L,
                "  acme/widget  "
        );

        assertThat(result.getProject()).isSameAs(project);
        assertThat(result.getInstallation()).isSameAs(installation);
        assertThat(result.getProvider()).isEqualTo(IntegrationProvider.GITHUB);
        assertThat(result.getGitHubRepositoryId()).isEqualTo(12345L);
        assertThat(result.getGitHubRepositoryFullName()).isEqualTo("acme/widget");
    }

    @Test
    void connectGitHubRepositoryRejectsDuplicateGitHubProvider() {
        IntegrationService service = new IntegrationService(
                integrationRepository,
                projectService,
                gitHubInstallationService,
                credentialCryptoService,
                slackClient,
                jiraClient,
                transactionManager
        );
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
                "acme/widget"
        ))
                .isInstanceOf(ConflictException.class)
                .hasMessage("GitHub integration already exists.");
    }

    @Test
    void connectGitHubRepositoryPropagatesMissingInstallationAsNotFound() {
        IntegrationService service = new IntegrationService(
                integrationRepository,
                projectService,
                gitHubInstallationService,
                credentialCryptoService,
                slackClient,
                jiraClient,
                transactionManager
        );
        when(projectService.getProject(OWNER_ID, PROJECT_ID)).thenReturn(project());
        when(gitHubInstallationService.getInstallationForInstaller(OWNER_ID, INSTALLATION_ID))
                .thenThrow(new NotFoundException("GitHub installation not found."));

        assertThatThrownBy(() -> service.connectGitHubRepository(
                OWNER_ID,
                PROJECT_ID,
                INSTALLATION_ID,
                12345L,
                "acme/widget"
        ))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("GitHub installation not found.");
    }

    @Test
    void connectGitHubRepositoryConvertsUniqueConstraintViolationToConflict() {
        IntegrationService service = new IntegrationService(
                integrationRepository,
                projectService,
                gitHubInstallationService,
                credentialCryptoService,
                slackClient,
                jiraClient,
                transactionManager
        );
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
                "acme/widget"
        ))
                .isInstanceOf(ConflictException.class)
                .hasMessage("GitHub integration already exists.");
    }

    @Test
    void connectSlackWorkspaceEncryptsTokenAndSavesIntegrationForOwnedProject() {
        IntegrationService service = new IntegrationService(
                integrationRepository,
                projectService,
                gitHubInstallationService,
                credentialCryptoService,
                slackClient,
                jiraClient,
                transactionManager
        );
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
    }

    @Test
    void connectSlackWorkspaceRejectsDuplicateSlackProvider() {
        IntegrationService service = new IntegrationService(
                integrationRepository,
                projectService,
                gitHubInstallationService,
                credentialCryptoService,
                slackClient,
                jiraClient,
                transactionManager
        );
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
    void connectSlackWorkspaceConvertsUniqueConstraintViolationToConflict() {
        IntegrationService service = new IntegrationService(
                integrationRepository,
                projectService,
                gitHubInstallationService,
                credentialCryptoService,
                slackClient,
                jiraClient,
                transactionManager
        );
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
    void connectJiraProjectEncryptsCredentialAndSavesIntegrationForOwnedProject() {
        IntegrationService service = new IntegrationService(
                integrationRepository,
                projectService,
                gitHubInstallationService,
                credentialCryptoService,
                slackClient,
                jiraClient,
                transactionManager
        );
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
    }

    @Test
    void connectJiraProjectRejectsLoopbackBaseUrl() {
        IntegrationService service = new IntegrationService(
                integrationRepository,
                projectService,
                gitHubInstallationService,
                credentialCryptoService,
                slackClient,
                jiraClient,
                transactionManager
        );

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
    void connectJiraProjectRejectsHttpBaseUrl() {
        IntegrationService service = new IntegrationService(
                integrationRepository,
                projectService,
                gitHubInstallationService,
                credentialCryptoService,
                slackClient,
                jiraClient,
                transactionManager
        );

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
    void connectJiraProjectRejectsDuplicateJiraProvider() {
        IntegrationService service = new IntegrationService(
                integrationRepository,
                projectService,
                gitHubInstallationService,
                credentialCryptoService,
                slackClient,
                jiraClient,
                transactionManager
        );
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
    void connectJiraProjectConvertsUniqueConstraintViolationToConflict() {
        IntegrationService service = new IntegrationService(
                integrationRepository,
                projectService,
                gitHubInstallationService,
                credentialCryptoService,
                slackClient,
                jiraClient,
                transactionManager
        );
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

    private static class NoopTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
