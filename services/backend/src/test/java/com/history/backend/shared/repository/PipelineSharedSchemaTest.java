package com.history.backend.shared.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
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
@DisplayName("webhook_deliveries/checkpoints 테이블: DB 스키마 제약 조건")
class PipelineSharedSchemaTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PipelineSharedSchemaTest::postgresJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static String postgresJdbcUrl() {
        return postgres.getJdbcUrl() + "&stringtype=unspecified";
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("프로젝트 없이 webhook delivery 생성 가능")
    void webhookDeliveryCanBeClaimedWithoutProject() {
        UUID deliveryId = insertWebhookDelivery("delivery-1", null, "IN_PROGRESS");

        assertThat(deliveryId).isNotNull();
    }

    @Test
    @DisplayName("delivery_id 유니크 제약")
    void webhookDeliveryIdIsUnique() {
        UUID ownerId = insertUser("owner@example.com");
        UUID projectId = insertProject(ownerId);
        insertWebhookDelivery("delivery-2", projectId, "IN_PROGRESS");

        assertThatThrownBy(() -> insertWebhookDelivery("delivery-2", projectId, "IN_PROGRESS"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("webhook delivery status 허용되지 않는 값 거부")
    void webhookDeliveryStatusRejectsUnexpectedValue() {
        UUID ownerId = insertUser("owner2@example.com");
        UUID projectId = insertProject(ownerId);

        assertThatThrownBy(() -> insertWebhookDelivery("delivery-3", projectId, "RETRYING"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("프로젝트 삭제 시 webhook delivery cascade 삭제")
    void deletingProjectCascadesWebhookDeliveries() {
        UUID ownerId = insertUser("owner3@example.com");
        UUID projectId = insertProject(ownerId);
        UUID deliveryId = insertWebhookDelivery("delivery-4", projectId, "PROCESSED");

        jdbcTemplate.update("DELETE FROM projects WHERE id = ?", projectId);

        Integer deliveryCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM webhook_deliveries WHERE id = ?",
                Integer.class,
                deliveryId
        );
        assertThat(deliveryCount).isZero();
    }

    @Test
    @DisplayName("프로젝트·provider·cursor_key로 체크포인트 저장 가능")
    void checkpointCanBeSavedPerProjectProviderAndCursorKey() {
        UUID ownerId = insertUser("owner4@example.com");
        UUID projectId = insertProject(ownerId);

        int inserted = insertCheckpoint(
                projectId,
                "github",
                "github_commits",
                Instant.parse("2024-01-03T00:00:00Z")
        );

        assertThat(inserted).isOne();
    }

    @Test
    @DisplayName("복합 PK 중복 삽입 거부")
    void checkpointCompositePrimaryKeyRejectsDuplicateCursor() {
        UUID ownerId = insertUser("owner5@example.com");
        UUID projectId = insertProject(ownerId);
        insertCheckpoint(projectId, "github", "github_commits", Instant.parse("2024-01-03T00:00:00Z"));

        assertThatThrownBy(() -> insertCheckpoint(
                projectId,
                "github",
                "github_commits",
                Instant.parse("2024-01-04T00:00:00Z")
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("다른 프로젝트에서 동일 cursor_key 사용 가능")
    void sameCursorKeyCanBeUsedForDifferentProjects() {
        UUID firstOwnerId = insertUser("owner6@example.com");
        UUID secondOwnerId = insertUser("owner7@example.com");
        UUID firstProjectId = insertProject(firstOwnerId);
        UUID secondProjectId = insertProject(secondOwnerId);
        insertCheckpoint(firstProjectId, "github", "github_commits", Instant.parse("2024-01-03T00:00:00Z"));

        int inserted = insertCheckpoint(
                secondProjectId,
                "github",
                "github_commits",
                Instant.parse("2024-01-03T00:00:00Z")
        );

        assertThat(inserted).isOne();
    }

    @Test
    @DisplayName("checkpoints provider 'linear' 허용")
    void checkpointProviderAcceptsLinearValue() {
        UUID ownerId = insertUser("owner8@example.com");
        UUID projectId = insertProject(ownerId);

        int inserted = insertCheckpoint(
                projectId,
                "linear",
                "linear_issues",
                Instant.parse("2024-01-03T00:00:00Z")
        );

        assertThat(inserted).isOne();
    }

    @Test
    @DisplayName("프로젝트 삭제 시 체크포인트 cascade 삭제")
    void deletingProjectCascadesCheckpoints() {
        UUID ownerId = insertUser("owner9@example.com");
        UUID projectId = insertProject(ownerId);
        insertCheckpoint(projectId, "github", "github_commits", Instant.parse("2024-01-03T00:00:00Z"));
        insertCheckpoint(projectId, "jira", "jira_updated", Instant.parse("2024-01-04T00:00:00Z"));

        jdbcTemplate.update("DELETE FROM projects WHERE id = ?", projectId);

        Integer checkpointCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM checkpoints WHERE project_id = ?",
                Integer.class,
                projectId
        );
        assertThat(checkpointCount).isZero();
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

    private UUID insertWebhookDelivery(String deliveryId, UUID projectId, String status) {
        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO webhook_deliveries (delivery_id, project_id, status)
                        VALUES (?, ?, ?)
                        RETURNING id
                        """,
                UUID.class,
                deliveryId,
                projectId,
                status
        );
    }

    private int insertCheckpoint(UUID projectId, String provider, String cursorKey, Instant cursorValue) {
        return jdbcTemplate.update(
                """
                        INSERT INTO checkpoints (project_id, provider, cursor_key, cursor_value)
                        VALUES (?, ?, ?, ?)
                        """,
                projectId,
                provider,
                cursorKey,
                Timestamp.from(cursorValue)
        );
    }
}
