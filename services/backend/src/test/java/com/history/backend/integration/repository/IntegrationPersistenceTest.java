package com.history.backend.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;

import com.history.backend.auth.domain.User;
import com.history.backend.auth.repository.UserRepository;
import com.history.backend.github.domain.GitHubInstallation;
import com.history.backend.github.repository.GitHubInstallationRepository;
import com.history.backend.integration.domain.Integration;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.project.domain.Project;
import com.history.backend.project.repository.ProjectRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration")
@DisplayName("IntegrationRepository: 연동 JPA 퍼시스턴스")
class IntegrationPersistenceTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", IntegrationPersistenceTest::postgresJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static String postgresJdbcUrl() {
        return postgres.getJdbcUrl() + "&stringtype=unspecified";
    }

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private GitHubInstallationRepository gitHubInstallationRepository;

    @Autowired
    private IntegrationRepository integrationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("GitHub 연동 저장 후 조회 성공")
    void saveAndFindGitHubIntegration() {
        ProjectFixture fixture = createProjectFixture();
        Integration integration = integrationRepository.saveAndFlush(Integration.github(
                fixture.project(),
                fixture.installation(),
                12345L,
                "acme/widget",
                "main"
        ));

        Optional<Integration> result = integrationRepository.findByProject_IdAndProvider(
                fixture.project().getId(),
                IntegrationProvider.GITHUB
        );

        assertThat(result).contains(integration);
        assertThat(result.orElseThrow().getProvider()).isEqualTo(IntegrationProvider.GITHUB);
        assertThat(result.orElseThrow().getGitHubRepositoryId()).isEqualTo(12345L);
        assertThat(result.orElseThrow().getGitHubRepositoryFullName()).isEqualTo("acme/widget");
        assertThat(result.orElseThrow().getInstallation()).isEqualTo(fixture.installation());
        assertThat(integrationRepository.findAllByProject_IdOrderByCreatedAtDesc(fixture.project().getId()))
                .containsExactly(integration);
        assertThat(integrationRepository.findByIdAndProject_Id(integration.getId(), fixture.project().getId()))
                .contains(integration);
        assertThat(integrationRepository.existsByProject_IdAndProvider(
                fixture.project().getId(),
                IntegrationProvider.GITHUB
        )).isTrue();
    }

    @Test
    @DisplayName("Slack 연동 저장 후 조회 성공")
    void saveAndFindSlackIntegration() {
        ProjectFixture fixture = createProjectFixture();
        byte[] encryptedCredential = new byte[] {10, 20, 30};
        Integration integration = integrationRepository.saveAndFlush(Integration.slack(
                fixture.project(),
                "T123",
                "Acme",
                encryptedCredential
        ));

        Optional<Integration> result = integrationRepository.findByProject_IdAndProvider(
                fixture.project().getId(),
                IntegrationProvider.SLACK
        );

        assertThat(result).contains(integration);
        assertThat(result.orElseThrow().getProvider()).isEqualTo(IntegrationProvider.SLACK);
        assertThat(result.orElseThrow().getSlackWorkspaceId()).isEqualTo("T123");
        assertThat(result.orElseThrow().getSlackWorkspaceName()).isEqualTo("Acme");
        assertThat(result.orElseThrow().getInstallation()).isNull();
        assertThat(result.orElseThrow().getEncryptedCredential()).containsExactly(10, 20, 30);
    }

    @Test
    @DisplayName("Jira 연동 저장 후 조회 성공")
    void saveAndFindJiraIntegration() {
        ProjectFixture fixture = createProjectFixture();
        byte[] encryptedCredential = new byte[] {40, 50, 60};
        Integration integration = integrationRepository.saveAndFlush(Integration.jira(
                fixture.project(),
                "PLAT",
                "Platform",
                "https://acme.atlassian.net",
                encryptedCredential
        ));

        Optional<Integration> result = integrationRepository.findByProject_IdAndProvider(
                fixture.project().getId(),
                IntegrationProvider.JIRA
        );

        assertThat(result).contains(integration);
        assertThat(result.orElseThrow().getProvider()).isEqualTo(IntegrationProvider.JIRA);
        assertThat(result.orElseThrow().getJiraProjectKey()).isEqualTo("PLAT");
        assertThat(result.orElseThrow().getJiraProjectName()).isEqualTo("Platform");
        assertThat(result.orElseThrow().getJiraBaseUrl()).isEqualTo("https://acme.atlassian.net");
        assertThat(result.orElseThrow().getInstallation()).isNull();
        assertThat(result.orElseThrow().getEncryptedCredential()).containsExactly(40, 50, 60);
    }

    @Test
    @DisplayName("provider는 소문자로 DB에 저장")
    void providerIsStoredAsLowercaseDatabaseValue() {
        ProjectFixture fixture = createProjectFixture();
        Integration integration = integrationRepository.saveAndFlush(Integration.github(
                fixture.project(),
                fixture.installation(),
                12345L,
                "acme/widget",
                "main"
        ));

        String provider = jdbcTemplate.queryForObject(
                "SELECT provider FROM integrations WHERE id = ?",
                String.class,
                integration.getId()
        );

        assertThat(provider).isEqualTo("github");
    }

    @Test
    @DisplayName("GitHub external_ref는 JSONB로 저장")
    void externalRefIsStoredAsJsonb() {
        ProjectFixture fixture = createProjectFixture();
        Integration integration = integrationRepository.saveAndFlush(Integration.github(
                fixture.project(),
                fixture.installation(),
                12345L,
                "acme/widget",
                "main"
        ));

        String repositoryFullName = jdbcTemplate.queryForObject(
                "SELECT external_ref->>'repository_full_name' FROM integrations WHERE id = ?",
                String.class,
                integration.getId()
        );

        assertThat(repositoryFullName).isEqualTo("acme/widget");
    }

    @Test
    @DisplayName("Slack external_ref는 JSONB로 저장")
    void slackExternalRefIsStoredAsJsonb() {
        ProjectFixture fixture = createProjectFixture();
        Integration integration = integrationRepository.saveAndFlush(Integration.slack(
                fixture.project(),
                "T123",
                "Acme",
                new byte[] {1, 2, 3}
        ));

        String workspaceId = jdbcTemplate.queryForObject(
                "SELECT external_ref->>'workspace_id' FROM integrations WHERE id = ?",
                String.class,
                integration.getId()
        );

        assertThat(workspaceId).isEqualTo("T123");
    }

    @Test
    @DisplayName("Jira external_ref는 JSONB로 저장")
    void jiraExternalRefIsStoredAsJsonb() {
        ProjectFixture fixture = createProjectFixture();
        Integration integration = integrationRepository.saveAndFlush(Integration.jira(
                fixture.project(),
                "PLAT",
                "Platform",
                "https://acme.atlassian.net",
                new byte[] {1, 2, 3}
        ));

        String projectKey = jdbcTemplate.queryForObject(
                "SELECT external_ref->>'project_key' FROM integrations WHERE id = ?",
                String.class,
                integration.getId()
        );
        String projectName = jdbcTemplate.queryForObject(
                "SELECT external_ref->>'project_name' FROM integrations WHERE id = ?",
                String.class,
                integration.getId()
        );

        assertThat(projectKey).isEqualTo("PLAT");
        assertThat(projectName).isEqualTo("Platform");
    }

    @Test
    @DisplayName("GitHub repository_id 누락 시 IllegalStateException")
    void githubRepositoryIdFailsWhenExternalRefIsMissingRepositoryId() {
        ProjectFixture fixture = createProjectFixture();
        Integration integration = Integration.github(fixture.project(), fixture.installation(), 12345L, "acme/widget", "main");
        ReflectionTestUtils.setField(integration, "externalRef", Map.of(
                Integration.GITHUB_REPOSITORY_FULL_NAME, "acme/widget"
        ));

        assertThatThrownBy(integration::getGitHubRepositoryId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing GitHub repository_id.");
    }

    @Test
    @DisplayName("GitHub repository_id 타입 불일치 시 IllegalStateException")
    void githubRepositoryIdFailsWhenExternalRefUsesUnexpectedType() {
        ProjectFixture fixture = createProjectFixture();
        Integration integration = Integration.github(fixture.project(), fixture.installation(), 12345L, "acme/widget", "main");
        ReflectionTestUtils.setField(integration, "externalRef", Map.of(
                Integration.GITHUB_REPOSITORY_ID, "12345",
                Integration.GITHUB_REPOSITORY_FULL_NAME, "acme/widget"
        ));

        assertThatThrownBy(integration::getGitHubRepositoryId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Unexpected GitHub repository_id type:");
    }

    @Test
    @DisplayName("Slack workspace_id 누락 시 IllegalStateException")
    void slackWorkspaceIdFailsWhenExternalRefIsMissingWorkspaceId() {
        ProjectFixture fixture = createProjectFixture();
        Integration integration = Integration.slack(fixture.project(), "T123", "Acme", new byte[] {1, 2, 3});
        ReflectionTestUtils.setField(integration, "externalRef", Map.of(
                Integration.SLACK_WORKSPACE_NAME, "Acme"
        ));

        assertThatThrownBy(integration::getSlackWorkspaceId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing Slack workspace_id.");
    }

    @Test
    @DisplayName("Jira base_url 타입 불일치 시 IllegalStateException")
    void jiraBaseUrlFailsWhenExternalRefUsesUnexpectedType() {
        ProjectFixture fixture = createProjectFixture();
        Integration integration = Integration.jira(
                fixture.project(),
                "PLAT",
                "Platform",
                "https://acme.atlassian.net",
                new byte[] {1, 2, 3}
        );
        ReflectionTestUtils.setField(integration, "externalRef", Map.of(
                Integration.JIRA_PROJECT_KEY, "PLAT",
                Integration.JIRA_BASE_URL, 123
        ));

        assertThatThrownBy(integration::getJiraBaseUrl)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Unexpected Jira base_url type:");
    }

    private ProjectFixture createProjectFixture() {
        User owner = userRepository.save(new User(
                "github",
                "user-" + System.nanoTime(),
                "owner@example.com",
                "Owner",
                null
        ));
        Project project = projectRepository.save(new Project(owner, "History Tracker", null));
        GitHubInstallation installation = gitHubInstallationRepository.save(new GitHubInstallation(
                System.nanoTime(),
                "Organization",
                "acme",
                owner
        ));
        return new ProjectFixture(project, installation);
    }

    private record ProjectFixture(Project project, GitHubInstallation installation) {
    }
}
