package com.history.backend.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration")
@DisplayName("integrations 테이블: DB 스키마 제약 조건")
class IntegrationSchemaTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", IntegrationSchemaTest::postgresJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static String postgresJdbcUrl() {
        return postgres.getJdbcUrl() + "&stringtype=unspecified";
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("GitHub 연동은 installation 필수, encrypted_credential 불필요")
    void githubIntegrationRequiresInstallationWithoutCredential() {
        UUID ownerId = insertUser("owner@example.com");
        UUID projectId = insertProject(ownerId);
        UUID installationId = insertGitHubInstallation(ownerId, 1001L);

        UUID integrationId = insertGitHubIntegration(projectId, installationId, 12345L, "acme/widget");

        assertThat(integrationId).isNotNull();
    }

    @Test
    @DisplayName("GitHub 연동은 installation 없으면 거부")
    void githubIntegrationRejectsMissingInstallation() {
        UUID ownerId = insertUser("owner2@example.com");
        UUID projectId = insertProject(ownerId);

        assertThatThrownBy(() -> jdbcTemplate.queryForObject(
                """
                        INSERT INTO integrations (project_id, provider, external_ref)
                        VALUES (?, 'github', ?)
                        RETURNING id
                        """,
                UUID.class,
                projectId,
                "{\"repository_id\":12345,\"repository_full_name\":\"acme/widget\"}"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Slack 연동은 encrypted_credential 필수, installation 불필요")
    void slackIntegrationRequiresEncryptedCredentialWithoutInstallation() {
        UUID ownerId = insertUser("owner3@example.com");
        UUID projectId = insertProject(ownerId);

        UUID integrationId = jdbcTemplate.queryForObject(
                """
                        INSERT INTO integrations (
                            project_id, provider, external_ref, encrypted_credential
                        )
                        VALUES (?, 'slack', ?, ?)
                        RETURNING id
                        """,
                UUID.class,
                projectId,
                "{\"workspace_id\":\"T123\"}",
                new byte[] {1, 2, 3}
        );

        assertThat(integrationId).isNotNull();
    }

    @Test
    @DisplayName("Slack 연동에 installation 참조 시 거부")
    void slackIntegrationRejectsInstallationReference() {
        UUID ownerId = insertUser("owner4@example.com");
        UUID projectId = insertProject(ownerId);
        UUID installationId = insertGitHubInstallation(ownerId, 1004L);

        assertThatThrownBy(() -> jdbcTemplate.queryForObject(
                """
                        INSERT INTO integrations (
                            project_id, provider, external_ref, installation_id, encrypted_credential
                        )
                        VALUES (?, 'slack', ?, ?, ?)
                        RETURNING id
                        """,
                UUID.class,
                projectId,
                "{\"workspace_id\":\"T123\"}",
                installationId,
                new byte[] {1, 2, 3}
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Jira 연동은 encrypted_credential 필수, installation 불필요")
    void jiraIntegrationRequiresEncryptedCredentialWithoutInstallation() {
        UUID ownerId = insertUser("owner8@example.com");
        UUID projectId = insertProject(ownerId);

        UUID integrationId = jdbcTemplate.queryForObject(
                """
                        INSERT INTO integrations (
                            project_id, provider, external_ref, encrypted_credential
                        )
                        VALUES (?, 'jira', ?, ?)
                        RETURNING id
                        """,
                UUID.class,
                projectId,
                "{\"project_key\":\"PLAT\",\"base_url\":\"https://acme.atlassian.net\"}",
                new byte[] {1, 2, 3}
        );

        assertThat(integrationId).isNotNull();
    }

    @Test
    @DisplayName("Jira 연동은 encrypted_credential 없으면 거부")
    void jiraIntegrationRejectsMissingEncryptedCredential() {
        UUID ownerId = insertUser("owner9@example.com");
        UUID projectId = insertProject(ownerId);

        assertThatThrownBy(() -> jdbcTemplate.queryForObject(
                """
                        INSERT INTO integrations (project_id, provider, external_ref)
                        VALUES (?, 'jira', ?)
                        RETURNING id
                        """,
                UUID.class,
                projectId,
                "{\"project_key\":\"PLAT\",\"base_url\":\"https://acme.atlassian.net\"}"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("프로젝트별 provider 유니크 제약")
    void providerIsUniquePerProject() {
        UUID ownerId = insertUser("owner5@example.com");
        UUID projectId = insertProject(ownerId);
        UUID installationId = insertGitHubInstallation(ownerId, 1005L);
        insertGitHubIntegration(projectId, installationId, 12345L, "acme/widget");

        assertThatThrownBy(() -> insertGitHubIntegration(projectId, installationId, 67890L, "acme/api"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("프로젝트 삭제 시 연동 cascade 삭제")
    void deletingProjectCascadesIntegrations() {
        UUID ownerId = insertUser("owner6@example.com");
        UUID projectId = insertProject(ownerId);
        UUID installationId = insertGitHubInstallation(ownerId, 1006L);
        insertGitHubIntegration(projectId, installationId, 12345L, "acme/widget");

        jdbcTemplate.update("DELETE FROM projects WHERE id = ?", projectId);

        Integer integrationCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM integrations WHERE project_id = ?",
                Integer.class,
                projectId
        );
        assertThat(integrationCount).isZero();
    }

    @Test
    @DisplayName("GitHub installation 삭제 시 GitHub 연동 cascade 삭제")
    void deletingReferencedInstallationCascadesGitHubIntegrations() {
        UUID ownerId = insertUser("owner7@example.com");
        UUID projectId = insertProject(ownerId);
        UUID installationId = insertGitHubInstallation(ownerId, 1007L);
        insertGitHubIntegration(projectId, installationId, 12345L, "acme/widget");

        jdbcTemplate.update("DELETE FROM github_installations WHERE id = ?", installationId);

        Integer integrationCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM integrations WHERE installation_id = ?",
                Integer.class,
                installationId
        );
        assertThat(integrationCount).isZero();
    }

    @Test
    @DisplayName("사용자 삭제 시 프로젝트·연동·installation cascade 삭제")
    void deletingUserCascadesOwnedProjectsIntegrationsAndInstallations() {
        UUID ownerId = insertUser("owner10@example.com");
        UUID projectId = insertProject(ownerId);
        UUID installationId = insertGitHubInstallation(ownerId, 1010L);
        insertGitHubIntegration(projectId, installationId, 12345L, "acme/widget");

        jdbcTemplate.update("DELETE FROM users WHERE id = ?", ownerId);

        assertThat(countRows("projects", "id", projectId)).isZero();
        assertThat(countRows("integrations", "project_id", projectId)).isZero();
        assertThat(countRows("github_installations", "id", installationId)).isZero();
    }

    private UUID insertUser(String email) {
        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO users (provider, provider_user_id, email)
                        VALUES ('github', ?, ?)
                        RETURNING id
                        """,
                UUID.class,
                UUID.randomUUID().toString(),
                email
        );
    }

    private UUID insertProject(UUID ownerId) {
        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO projects (owner_id, name)
                        VALUES (?, ?)
                        RETURNING id
                        """,
                UUID.class,
                ownerId,
                "Project " + UUID.randomUUID()
        );
    }

    private UUID insertGitHubInstallation(UUID installerUserId, long installationId) {
        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO github_installations (
                            installation_id, account_type, account_login, installer_user_id
                        )
                        VALUES (?, 'Organization', ?, ?)
                        RETURNING id
                        """,
                UUID.class,
                installationId,
                "acme-" + installationId,
                installerUserId
        );
    }

    private UUID insertGitHubIntegration(
            UUID projectId,
            UUID installationId,
            long repositoryId,
            String repositoryFullName
    ) {
        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO integrations (
                            project_id, provider, external_ref, installation_id
                        )
                        VALUES (?, 'github', ?, ?)
                        RETURNING id
                        """,
                UUID.class,
                projectId,
                """
                        {"repository_id":%d,"repository_full_name":"%s"}
                        """.formatted(repositoryId, repositoryFullName),
                installationId
        );
    }

    private int countRows(String table, String column, UUID value) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class,
                value
        );
    }
}
