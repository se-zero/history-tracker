package com.history.backend.project.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
@DisplayName("projects 테이블: DB 스키마 제약 조건")
class ProjectSchemaTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ProjectSchemaTest::postgresJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static String postgresJdbcUrl() {
        return postgres.getJdbcUrl() + "&stringtype=unspecified";
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("동일 소유자 프로젝트명 대소문자 무시 유니크 제약")
    void projectNameIsUniquePerActiveOwnerIgnoringCase() {
        UUID ownerId = insertUser("owner@example.com");
        insertProject(ownerId, "History Tracker", null);

        assertThatThrownBy(() -> insertProject(ownerId, "history tracker", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("하드 삭제 후 동일 프로젝트명 재사용 가능")
    void projectNameCanBeReusedAfterHardDelete() {
        UUID ownerId = insertUser("owner2@example.com");
        UUID projectId = insertProject(ownerId, "History Tracker", null);
        jdbcTemplate.update("DELETE FROM projects WHERE id = ?", projectId);

        UUID newProjectId = insertProject(ownerId, "history tracker", null);

        assertThat(newProjectId).isNotEqualTo(projectId);
    }

    @Test
    @DisplayName("다른 소유자는 동일 프로젝트명 사용 가능")
    void differentOwnersCanUseSameProjectName() {
        UUID firstOwnerId = insertUser("first@example.com");
        UUID secondOwnerId = insertUser("second@example.com");
        insertProject(firstOwnerId, "History Tracker", null);

        assertThatCode(() -> insertProject(secondOwnerId, "History Tracker", null))
                .doesNotThrowAnyException();
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

    private UUID insertProject(UUID ownerId, String name, String description) {
        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO projects (owner_id, name, description)
                        VALUES (?, ?, ?)
                        RETURNING id
                        """,
                UUID.class,
                ownerId,
                name,
                description
        );
    }
}
