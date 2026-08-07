package com.history.backend.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;

import jakarta.persistence.EntityManager;

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
    private EntityManager entityManager;

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
        Integration integration = integrationRepository.saveAndFlush(Integration.oauth(
                fixture.project(),
                IntegrationProvider.SLACK,
                Map.of("workspace_id", "T123", "workspace_name", "Acme"),
                encryptedCredential
        ));

        Optional<Integration> result = integrationRepository.findByProject_IdAndProvider(
                fixture.project().getId(),
                IntegrationProvider.SLACK
        );

        assertThat(result).contains(integration);
        assertThat(result.orElseThrow().getProvider()).isEqualTo(IntegrationProvider.SLACK);
        assertThat(result.orElseThrow().externalRefValue("workspace_id")).isEqualTo("T123");
        assertThat(result.orElseThrow().externalRefValue("workspace_name")).isEqualTo("Acme");
        assertThat(result.orElseThrow().getInstallation()).isNull();
        assertThat(result.orElseThrow().getEncryptedCredential()).containsExactly(10, 20, 30);
    }

    @Test
    @DisplayName("Jira 연동 저장 후 조회 성공")
    void saveAndFindJiraIntegration() {
        ProjectFixture fixture = createProjectFixture();
        byte[] encryptedCredential = new byte[] {40, 50, 60};
        Integration pending = Integration.pendingSelection(fixture.project(), IntegrationProvider.JIRA, encryptedCredential);
        pending.applyExternalRef(java.util.Map.of("cloud_id", "cloud-1", "site_name", "acme", "project_key", "PLAT", "project_name", "Platform"));
        Integration integration = integrationRepository.saveAndFlush(pending);

        Optional<Integration> result = integrationRepository.findByProject_IdAndProvider(
                fixture.project().getId(),
                IntegrationProvider.JIRA
        );

        assertThat(result).contains(integration);
        assertThat(result.orElseThrow().getProvider()).isEqualTo(IntegrationProvider.JIRA);
        assertThat(result.orElseThrow().externalRefValue("project_key")).isEqualTo("PLAT");
        assertThat(result.orElseThrow().externalRefValue("project_name")).isEqualTo("Platform");
        assertThat(result.orElseThrow().isPendingSelection()).isFalse();
        assertThat(result.orElseThrow().getInstallation()).isNull();
        assertThat(result.orElseThrow().getEncryptedCredential()).containsExactly(40, 50, 60);
    }

    @Test
    @DisplayName("구 status 값(pending_project)으로 저장된 행도 JSONB 왕복 후 pending으로 읽힌다")
    void legacyPendingProjectRowIsReadAsPendingAfterRoundTrip() {
        // 중립 값 이전 배포가 저장한 행을 그대로 재현한다 — 데이터 마이그레이션 없이 넘어가는 것이 전제라
        // Hibernate/JSONB를 실제로 통과시켜 확인한다(문자열 리터럴이 곧 DB에 들어 있는 계약이다).
        ProjectFixture fixture = createProjectFixture();
        Integration pending = Integration.pendingSelection(
                fixture.project(), IntegrationProvider.JIRA, new byte[] {40, 50, 60});
        pending.applyExternalRef(Map.of("status", "pending_project", "cloud_id", "cloud-1", "site_name", "acme"));
        integrationRepository.saveAndFlush(pending);
        entityManager.clear();

        Integration result = integrationRepository.findByProject_IdAndProvider(
                fixture.project().getId(),
                IntegrationProvider.JIRA
        ).orElseThrow();

        assertThat(result.isPendingSelection()).isTrue();
        assertThat(result.externalRefValue("cloud_id")).isEqualTo("cloud-1");
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
        Integration integration = integrationRepository.saveAndFlush(Integration.oauth(
                fixture.project(),
                IntegrationProvider.SLACK,
                Map.of("workspace_id", "T123", "workspace_name", "Acme"),
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
        Integration pending = Integration.pendingSelection(fixture.project(), IntegrationProvider.JIRA, new byte[] {1, 2, 3});
        pending.applyExternalRef(java.util.Map.of("cloud_id", "cloud-1", "site_name", "acme", "project_key", "PLAT", "project_name", "Platform"));
        Integration integration = integrationRepository.saveAndFlush(pending);

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
        String cloudId = jdbcTemplate.queryForObject(
                "SELECT external_ref->>'cloud_id' FROM integrations WHERE id = ?",
                String.class,
                integration.getId()
        );

        assertThat(projectKey).isEqualTo("PLAT");
        assertThat(projectName).isEqualTo("Platform");
        assertThat(cloudId).isEqualTo("cloud-1");
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
    @DisplayName("없는 키를 읽으면 null — 표시 이름이 없는 연동도 목록 조회를 깨뜨리지 않는다")
    void externalRefValueReturnsNullWhenKeyIsMissing() {
        ProjectFixture fixture = createProjectFixture();
        Integration integration = Integration.oauth(
                fixture.project(),
                IntegrationProvider.SLACK,
                Map.of("workspace_id", "T123"),
                new byte[] {1, 2, 3});

        assertThat(integration.externalRefValue("workspace_name")).isNull();
    }

    @Test
    @DisplayName("선택 값 타입이 문자열이 아니면 null — 손상된 행 하나가 목록 조회 전체를 500으로 만들지 않는다")
    void selectionValueReturnsNullWhenExternalRefUsesUnexpectedType() {
        ProjectFixture fixture = createProjectFixture();
        Integration integration = Integration.pendingSelection(fixture.project(), IntegrationProvider.JIRA, new byte[] {1, 2, 3});
        ReflectionTestUtils.setField(integration, "externalRef", Map.of(
                "project_key", 123
        ));

        assertThat(integration.externalRefValue("project_key")).isNull();
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
