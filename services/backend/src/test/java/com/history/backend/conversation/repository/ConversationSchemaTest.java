package com.history.backend.conversation.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

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
class ConversationSchemaTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ConversationSchemaTest::postgresJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static String postgresJdbcUrl() {
        return postgres.getJdbcUrl() + "&stringtype=unspecified";
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void conversationCanBeCreatedForProjectAndUser() {
        UUID ownerId = insertUser("owner@example.com");
        UUID projectId = insertProject(ownerId);

        UUID conversationId = insertConversation(projectId, ownerId, "Auth changes");

        assertThat(conversationId).isNotNull();
    }

    @Test
    void deletingProjectCascadesConversationsAndMessages() {
        UUID ownerId = insertUser("owner2@example.com");
        UUID projectId = insertProject(ownerId);
        UUID conversationId = insertConversation(projectId, ownerId, "Auth changes");
        insertMessage(conversationId, "USER", "What changed?");

        jdbcTemplate.update("DELETE FROM projects WHERE id = ?", projectId);

        Integer conversationCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM conversations WHERE project_id = ?",
                Integer.class,
                projectId
        );
        Integer messageCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM messages WHERE conversation_id = ?",
                Integer.class,
                conversationId
        );
        assertThat(conversationCount).isZero();
        assertThat(messageCount).isZero();
    }

    @Test
    void deletingUserSetsConversationUserToNull() {
        UUID ownerId = insertUser("owner3@example.com");
        UUID viewerId = insertUser("viewer3@example.com");
        UUID projectId = insertProject(ownerId);
        UUID conversationId = insertConversation(projectId, viewerId, "Auth changes");

        jdbcTemplate.update("DELETE FROM users WHERE id = ?", viewerId);

        UUID userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM conversations WHERE id = ?",
                UUID.class,
                conversationId
        );
        assertThat(userId).isNull();
    }

    @Test
    void deletingConversationCascadesMessages() {
        UUID ownerId = insertUser("owner4@example.com");
        UUID projectId = insertProject(ownerId);
        UUID conversationId = insertConversation(projectId, ownerId, "Auth changes");
        insertMessage(conversationId, "USER", "What changed?");

        jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", conversationId);

        Integer messageCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM messages WHERE conversation_id = ?",
                Integer.class,
                conversationId
        );
        assertThat(messageCount).isZero();
    }

    @Test
    void messageRoleRejectsUnexpectedValue() {
        UUID ownerId = insertUser("owner5@example.com");
        UUID projectId = insertProject(ownerId);
        UUID conversationId = insertConversation(projectId, ownerId, "Auth changes");

        assertThatThrownBy(() -> insertMessage(conversationId, "BOT", "Unsupported role"))
                .isInstanceOf(DataIntegrityViolationException.class);
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

    private UUID insertConversation(UUID projectId, UUID userId, String title) {
        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO conversations (project_id, user_id, title)
                        VALUES (?, ?, ?)
                        RETURNING id
                        """,
                UUID.class,
                projectId,
                userId,
                title
        );
    }

    private UUID insertMessage(UUID conversationId, String role, String content) {
        return jdbcTemplate.queryForObject(
                """
                        INSERT INTO messages (conversation_id, role, content)
                        VALUES (?, ?, ?)
                        RETURNING id
                        """,
                UUID.class,
                conversationId,
                role,
                content
        );
    }
}
