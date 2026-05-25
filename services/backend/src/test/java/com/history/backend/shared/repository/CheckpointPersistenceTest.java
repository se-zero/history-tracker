package com.history.backend.shared.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;

import com.history.backend.auth.domain.User;
import com.history.backend.auth.repository.UserRepository;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.project.domain.Project;
import com.history.backend.project.repository.ProjectRepository;
import com.history.backend.shared.domain.Checkpoint;
import com.history.backend.shared.domain.CheckpointId;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
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
class CheckpointPersistenceTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CheckpointPersistenceTest::postgresJdbcUrl);
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
    private CheckpointRepository checkpointRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    void saveAndFindCheckpoint() {
        Project project = createProject();
        Checkpoint checkpoint = checkpointRepository.saveAndFlush(new Checkpoint(
                project,
                IntegrationProvider.GITHUB,
                "github_commits",
                "2024-01-03T00:00:00Z"
        ));

        Optional<Checkpoint> result = checkpointRepository.findById(new CheckpointId(
                project.getId(),
                IntegrationProvider.GITHUB,
                "github_commits"
        ));

        assertThat(result).contains(checkpoint);
        assertThat(result.orElseThrow().getProject()).isEqualTo(project);
        assertThat(result.orElseThrow().getProvider()).isEqualTo(IntegrationProvider.GITHUB);
        assertThat(result.orElseThrow().getCursorKey()).isEqualTo("github_commits");
        assertThat(result.orElseThrow().getCursorValue()).isEqualTo("2024-01-03T00:00:00Z");
        assertThat(result.orElseThrow().getUpdatedAt()).isNotNull();
    }

    @Test
    void findByCheckpointId() {
        Project project = createProject();
        Checkpoint checkpoint = checkpointRepository.saveAndFlush(new Checkpoint(
                project,
                IntegrationProvider.JIRA,
                "jira_updated",
                "2024-01-04T00:00:00Z"
        ));

        Optional<Checkpoint> result = checkpointRepository.findById(new CheckpointId(
                project.getId(),
                IntegrationProvider.JIRA,
                "jira_updated"
        ));

        assertThat(result).contains(checkpoint);
    }

    @Test
    void findAllByProjectAndProvider() {
        Project project = createProject();
        checkpointRepository.save(new Checkpoint(
                project,
                IntegrationProvider.GITHUB,
                "github_commits",
                "2024-01-03T00:00:00Z"
        ));
        checkpointRepository.save(new Checkpoint(
                project,
                IntegrationProvider.GITHUB,
                "github_issues",
                "2024-01-04T00:00:00Z"
        ));
        checkpointRepository.save(new Checkpoint(
                project,
                IntegrationProvider.SLACK,
                "slack_messages",
                "1714000100.000000"
        ));
        checkpointRepository.flush();

        assertThat(checkpointRepository.findAllByProject_Id(project.getId())).hasSize(3);
        assertThat(checkpointRepository.findAllByProject_IdAndId_Provider(
                project.getId(),
                IntegrationProvider.GITHUB
        )).extracting(Checkpoint::getCursorKey)
                .containsExactlyInAnyOrder("github_commits", "github_issues");
    }

    @Test
    void providerIsStoredAsLowercaseDatabaseValue() {
        Project project = createProject();
        checkpointRepository.saveAndFlush(new Checkpoint(
                project,
                IntegrationProvider.SLACK,
                "slack_messages",
                "1714000100.000000"
        ));

        String provider = jdbcTemplate.queryForObject(
                """
                        SELECT provider
                        FROM checkpoints
                        WHERE project_id = ? AND cursor_key = ?
                        """,
                String.class,
                project.getId(),
                "slack_messages"
        );

        assertThat(provider).isEqualTo("slack");
    }

    @Test
    void updateCursorValueRefreshesUpdatedAt() {
        Project project = createProject();
        Checkpoint checkpoint = checkpointRepository.saveAndFlush(new Checkpoint(
                project,
                IntegrationProvider.GITHUB,
                "github_pull_requests",
                "2024-01-03T00:00:00Z"
        ));
        Instant before = checkpoint.getUpdatedAt();

        checkpoint.updateCursorValue("2024-01-04T00:00:00Z");
        checkpointRepository.flush();

        assertThat(checkpoint.getCursorValue()).isEqualTo("2024-01-04T00:00:00Z");
        assertThat(checkpoint.getUpdatedAt()).isAfterOrEqualTo(before);
    }

    @Test
    void upsertCursorInsertsNewCheckpoint() {
        Project project = createProject();

        checkpointRepository.upsertCursor(
                project.getId(),
                IntegrationProvider.GITHUB,
                "github_commits",
                "2024-01-03T00:00:00Z"
        );

        assertThat(checkpointRepository.findById(new CheckpointId(
                project.getId(),
                IntegrationProvider.GITHUB,
                "github_commits"
        ))).hasValueSatisfying(checkpoint ->
                assertThat(checkpoint.getCursorValue()).isEqualTo("2024-01-03T00:00:00Z")
        );
    }

    @Test
    void upsertCursorUpdatesExistingCheckpoint() {
        Project project = createProject();
        checkpointRepository.upsertCursor(
                project.getId(),
                IntegrationProvider.GITHUB,
                "github_commits",
                "2024-01-03T00:00:00Z"
        );

        checkpointRepository.upsertCursor(
                project.getId(),
                IntegrationProvider.GITHUB,
                "github_commits",
                "2024-01-04T00:00:00Z"
        );

        assertThat(checkpointRepository.findById(new CheckpointId(
                project.getId(),
                IntegrationProvider.GITHUB,
                "github_commits"
        ))).hasValueSatisfying(checkpoint ->
                assertThat(checkpoint.getCursorValue()).isEqualTo("2024-01-04T00:00:00Z")
        );
    }

    @Test
    void deletingProjectCascadesCheckpoints() {
        Project project = createProject();
        checkpointRepository.saveAndFlush(new Checkpoint(
                project,
                IntegrationProvider.GITHUB,
                "github_commits",
                "2024-01-03T00:00:00Z"
        ));

        jdbcTemplate.update("DELETE FROM projects WHERE id = ?", project.getId());
        entityManager.clear();

        assertThat(checkpointRepository.findAllByProject_Id(project.getId())).isEmpty();
    }

    private Project createProject() {
        User owner = userRepository.save(new User(
                "github",
                "user-" + System.nanoTime(),
                "owner@example.com",
                "Owner",
                null
        ));
        return projectRepository.saveAndFlush(new Project(owner, "History Tracker " + System.nanoTime(), null));
    }
}
