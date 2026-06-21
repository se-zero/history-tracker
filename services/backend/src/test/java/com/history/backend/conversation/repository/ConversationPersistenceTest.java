package com.history.backend.conversation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import com.history.backend.auth.domain.User;
import com.history.backend.auth.repository.UserRepository;
import com.history.backend.conversation.domain.Conversation;
import com.history.backend.conversation.domain.Message;
import com.history.backend.conversation.domain.MessageRole;
import com.history.backend.project.domain.Project;
import com.history.backend.project.repository.ProjectRepository;
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
@DisplayName("ConversationRepository/MessageRepository: 대화·메시지 JPA 퍼시스턴스")
class ConversationPersistenceTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ConversationPersistenceTest::postgresJdbcUrl);
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
    private ConversationRepository conversationRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("대화 저장 후 조회 성공")
    void saveAndFindConversation() {
        ProjectFixture fixture = createProjectFixture();
        Conversation conversation = conversationRepository.saveAndFlush(new Conversation(
                fixture.project(),
                fixture.owner(),
                "Why did auth change?"
        ));

        assertThat(conversationRepository.findByIdAndProject_Id(conversation.getId(), fixture.project().getId()))
                .contains(conversation);
        assertThat(conversationRepository.findAllByProject_IdOrderByUpdatedAtDesc(fixture.project().getId()))
                .containsExactly(conversation);
        assertThat(conversation.belongsToProject(fixture.project().getId())).isTrue();
    }

    @Test
    @DisplayName("메시지 저장 후 생성 순서로 조회")
    void saveAndFindMessagesInCreatedOrder() throws InterruptedException {
        ProjectFixture fixture = createProjectFixture();
        Conversation conversation = conversationRepository.saveAndFlush(new Conversation(
                fixture.project(),
                fixture.owner(),
                "Auth context"
        ));
        Message userMessage = messageRepository.saveAndFlush(Message.user(conversation, "What changed?"));
        Thread.sleep(5);
        Message assistantMessage = messageRepository.saveAndFlush(Message.assistant(
                conversation,
                "The OAuth flow changed.",
                Map.of(
                        "model", "test-model",
                        "latency_ms", 120
                )
        ));

        assertThat(messageRepository.findAllByConversation_IdOrderByCreatedAtAsc(conversation.getId()))
                .containsExactly(userMessage, assistantMessage);
        assertThat(assistantMessage.getRole()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(assistantMessage.getMetadata())
                .containsEntry("model", "test-model")
                .containsEntry("latency_ms", 120);
    }

    @Test
    @DisplayName("요약 커서 이후 메시지만 조회")
    void findMessagesFromSummaryCursorTime() throws InterruptedException {
        ProjectFixture fixture = createProjectFixture();
        Conversation conversation = conversationRepository.saveAndFlush(new Conversation(
                fixture.project(),
                fixture.owner(),
                "Cursor context"
        ));
        Message beforeCursor = messageRepository.saveAndFlush(Message.user(conversation, "Old question"));
        Thread.sleep(5);
        Message cursor = messageRepository.saveAndFlush(Message.assistant(conversation, "Old answer", null));
        Thread.sleep(5);
        Message afterCursor = messageRepository.saveAndFlush(Message.user(conversation, "Recent question"));

        assertThat(messageRepository.findAllFromCursor(conversation.getId(), cursor.getId()))
                .containsExactly(cursor, afterCursor)
                .doesNotContain(beforeCursor);
    }

    @Test
    @DisplayName("메시지 metadata는 JSONB로 저장")
    void metadataIsStoredAsJsonb() {
        ProjectFixture fixture = createProjectFixture();
        Conversation conversation = conversationRepository.saveAndFlush(new Conversation(
                fixture.project(),
                fixture.owner(),
                "Sources"
        ));
        Message message = messageRepository.saveAndFlush(Message.assistant(
                conversation,
                "See PR 42.",
                Map.of("source_nodes", java.util.List.of("pr:acme/widget#42"))
        ));

        String firstSource = jdbcTemplate.queryForObject(
                "SELECT metadata->'source_nodes'->>0 FROM messages WHERE id = ?",
                String.class,
                message.getId()
        );

        assertThat(firstSource).isEqualTo("pr:acme/widget#42");
    }

    @Test
    @DisplayName("running summary 갱신 시 version CAS(Compare-And-Set) 적용")
    void updateRunningSummaryUsesSummaryVersionCompareAndSet() {
        ProjectFixture fixture = createProjectFixture();
        Conversation conversation = conversationRepository.saveAndFlush(new Conversation(
                fixture.project(),
                fixture.owner(),
                "Long conversation"
        ));
        Message assistantMessage = messageRepository.saveAndFlush(Message.assistant(
                conversation,
                "Old answer",
                null
        ));
        Map<String, Object> summary = Map.of(
                "summary", "Accumulated context",
                "entities", java.util.List.of(),
                "unresolved_aspects", java.util.List.of()
        );

        int firstUpdate = conversationRepository.updateRunningSummary(
                conversation.getId(),
                0L,
                summary,
                assistantMessage.getId(),
                Instant.now()
        );
        int staleUpdate = conversationRepository.updateRunningSummary(
                conversation.getId(),
                0L,
                Map.of("summary", "Stale summary"),
                assistantMessage.getId(),
                Instant.now()
        );
        Conversation updated = conversationRepository.findById(conversation.getId()).orElseThrow();

        assertThat(firstUpdate).isEqualTo(1);
        assertThat(staleUpdate).isZero();
        assertThat(updated.getRunningSummary()).containsEntry("summary", "Accumulated context");
        assertThat(updated.getSummaryThroughMessageId()).isEqualTo(assistantMessage.getId());
        assertThat(updated.getSummaryUpdatedAt()).isNotNull();
        assertThat(updated.getSummaryVersion()).isEqualTo(1L);
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
        return new ProjectFixture(owner, project);
    }

    private record ProjectFixture(User owner, Project project) {
    }
}
