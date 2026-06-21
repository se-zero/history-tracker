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
import org.junit.jupiter.api.DisplayName;
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
@DisplayName("CheckpointRepository: 체크포인트 JPA 퍼시스턴스")
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
    @DisplayName("체크포인트 저장 후 조회 성공")
    void saveAndFindCheckpoint() {
        Project project = createProject();
        Checkpoint checkpoint = checkpointRepository.saveAndFlush(new Checkpoint(
                project,
                IntegrationProvider.GITHUB,
                "github_commits",
                Instant.parse("2024-01-03T00:00:00Z")
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
        assertThat(result.orElseThrow().getCursorValue()).isEqualTo(Instant.parse("2024-01-03T00:00:00Z"));
        assertThat(result.orElseThrow().getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("복합키로 체크포인트 조회 성공")
    void findByCheckpointId() {
        Project project = createProject();
        Checkpoint checkpoint = checkpointRepository.saveAndFlush(new Checkpoint(
                project,
                IntegrationProvider.JIRA,
                "jira_updated",
                Instant.parse("2024-01-04T00:00:00Z")
        ));

        Optional<Checkpoint> result = checkpointRepository.findById(new CheckpointId(
                project.getId(),
                IntegrationProvider.JIRA,
                "jira_updated"
        ));

        assertThat(result).contains(checkpoint);
    }

    @Test
    @DisplayName("프로젝트·provider별 체크포인트 전체 조회")
    void findAllByProjectAndProvider() {
        Project project = createProject();
        checkpointRepository.save(new Checkpoint(
                project,
                IntegrationProvider.GITHUB,
                "github_commits",
                Instant.parse("2024-01-03T00:00:00Z")
        ));
        checkpointRepository.save(new Checkpoint(
                project,
                IntegrationProvider.GITHUB,
                "github_issues",
                Instant.parse("2024-01-04T00:00:00Z")
        ));
        checkpointRepository.save(new Checkpoint(
                project,
                IntegrationProvider.SLACK,
                "slack_messages",
                Instant.parse("2024-04-25T00:01:40Z")
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
    @DisplayName("provider는 소문자로 DB에 저장")
    void providerIsStoredAsLowercaseDatabaseValue() {
        Project project = createProject();
        checkpointRepository.saveAndFlush(new Checkpoint(
                project,
                IntegrationProvider.SLACK,
                "slack_messages",
                Instant.parse("2024-04-25T00:01:40Z")
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
    @DisplayName("cursor_value 업데이트 성공")
    void updateCursorValueChangesCursorValue() {
        Project project = createProject();
        Checkpoint checkpoint = checkpointRepository.saveAndFlush(new Checkpoint(
                project,
                IntegrationProvider.GITHUB,
                "github_pull_requests",
                Instant.parse("2024-01-03T00:00:00Z")
        ));

        checkpoint.updateCursorValue(Instant.parse("2024-01-04T00:00:00Z"));
        checkpointRepository.flush();

        assertThat(checkpoint.getCursorValue()).isEqualTo(Instant.parse("2024-01-04T00:00:00Z"));
    }

    @Test
    @DisplayName("신규 체크포인트 upsert 삽입")
    void upsertCursorInsertsNewCheckpoint() {
        Project project = createProject();

        checkpointRepository.upsertCursor(
                project.getId(),
                IntegrationProvider.GITHUB,
                "github_commits",
                Instant.parse("2024-01-03T00:00:00Z")
        );
        entityManager.clear();

        assertThat(checkpointRepository.findById(new CheckpointId(
                project.getId(),
                IntegrationProvider.GITHUB,
                "github_commits"
        ))).hasValueSatisfying(checkpoint ->
                assertThat(checkpoint.getCursorValue()).isEqualTo(Instant.parse("2024-01-03T00:00:00Z"))
        );
    }

    @Test
    @DisplayName("기존 체크포인트 upsert 갱신")
    void upsertCursorUpdatesExistingCheckpoint() {
        Project project = createProject();
        checkpointRepository.upsertCursor(
                project.getId(),
                IntegrationProvider.GITHUB,
                "github_commits",
                Instant.parse("2024-01-03T00:00:00Z")
        );

        checkpointRepository.upsertCursor(
                project.getId(),
                IntegrationProvider.GITHUB,
                "github_commits",
                Instant.parse("2024-01-04T00:00:00Z")
        );
        entityManager.clear();

        assertThat(checkpointRepository.findById(new CheckpointId(
                project.getId(),
                IntegrationProvider.GITHUB,
                "github_commits"
        ))).hasValueSatisfying(checkpoint ->
                assertThat(checkpoint.getCursorValue()).isEqualTo(Instant.parse("2024-01-04T00:00:00Z"))
        );
    }

    @Test
    @DisplayName("과거 시각으로 upsert 시 기존 값 유지")
    void upsertCursorIgnoresOlderCheckpoint() {
        Project project = createProject();
        checkpointRepository.upsertCursor(
                project.getId(),
                IntegrationProvider.GITHUB,
                "github_commits",
                Instant.parse("2024-01-04T00:00:00Z")
        );
        Instant updatedAt = findUpdatedAt(project, IntegrationProvider.GITHUB, "github_commits");

        checkpointRepository.upsertCursor(
                project.getId(),
                IntegrationProvider.GITHUB,
                "github_commits",
                Instant.parse("2024-01-03T00:00:00Z")
        );
        entityManager.clear();

        assertThat(checkpointRepository.findById(new CheckpointId(
                project.getId(),
                IntegrationProvider.GITHUB,
                "github_commits"
        ))).hasValueSatisfying(checkpoint -> {
            assertThat(checkpoint.getCursorValue()).isEqualTo(Instant.parse("2024-01-04T00:00:00Z"));
            assertThat(checkpoint.getUpdatedAt()).isEqualTo(updatedAt);
        });
    }

    @Test
    @DisplayName("프로젝트 삭제 시 체크포인트 cascade 삭제")
    void deletingProjectCascadesCheckpoints() {
        Project project = createProject();
        checkpointRepository.saveAndFlush(new Checkpoint(
                project,
                IntegrationProvider.GITHUB,
                "github_commits",
                Instant.parse("2024-01-03T00:00:00Z")
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

    private Instant findUpdatedAt(Project project, IntegrationProvider provider, String cursorKey) {
        return checkpointRepository.findById(new CheckpointId(project.getId(), provider, cursorKey))
                .orElseThrow()
                .getUpdatedAt();
    }
}
