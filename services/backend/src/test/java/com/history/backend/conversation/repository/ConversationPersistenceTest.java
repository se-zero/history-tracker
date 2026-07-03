package com.history.backend.conversation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
import org.springframework.data.domain.PageRequest;
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
        assertThat(conversationRepository.findFirstPageByProject(fixture.project().getId(), PageRequest.of(0, 20)))
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
    @DisplayName("최신 메시지 페이지는 createdAt·id 역순으로 개수 제한 조회")
    void findLatestReturnsMostRecentMessagesLimited() throws InterruptedException {
        ProjectFixture fixture = createProjectFixture();
        Conversation conversation = conversationRepository.saveAndFlush(new Conversation(
                fixture.project(),
                fixture.owner(),
                "Latest page"
        ));
        messageRepository.saveAndFlush(Message.user(conversation, "first"));
        Thread.sleep(5);
        Message second = messageRepository.saveAndFlush(Message.assistant(conversation, "second", null));
        Thread.sleep(5);
        Message third = messageRepository.saveAndFlush(Message.user(conversation, "third"));

        assertThat(messageRepository.findLatest(conversation.getId(), PageRequest.of(0, 2)))
                .containsExactly(third, second);
    }

    @Test
    @DisplayName("커서 이전(older) 메시지만 createdAt·id 역순으로 조회")
    void findOlderBeforeReturnsMessagesBeforeCursor() throws InterruptedException {
        ProjectFixture fixture = createProjectFixture();
        Conversation conversation = conversationRepository.saveAndFlush(new Conversation(
                fixture.project(),
                fixture.owner(),
                "Older page"
        ));
        Message first = messageRepository.saveAndFlush(Message.user(conversation, "first"));
        Thread.sleep(5);
        Message second = messageRepository.saveAndFlush(Message.assistant(conversation, "second", null));
        Thread.sleep(5);
        Message third = messageRepository.saveAndFlush(Message.user(conversation, "third"));

        assertThat(messageRepository.findOlderBefore(
                conversation.getId(), third.getCreatedAt(), third.getId(), PageRequest.of(0, 10)))
                .containsExactly(second, first)
                .doesNotContain(third);
    }

    @Test
    @DisplayName("동일 createdAt에서는 id로 tiebreak해 커서 경계 중복·누락 방지")
    void findOlderBeforeTiebreaksOnIdAtSameTimestamp() {
        ProjectFixture fixture = createProjectFixture();
        Conversation conversation = conversationRepository.saveAndFlush(new Conversation(
                fixture.project(),
                fixture.owner(),
                "Ties"
        ));
        messageRepository.saveAndFlush(Message.user(conversation, "a"));
        messageRepository.saveAndFlush(Message.user(conversation, "b"));
        // 두 메시지의 created_at을 동일하게 만들어 id tiebreak를 강제 검증
        Instant sameTime = Instant.parse("2026-05-23T01:00:00Z");
        jdbcTemplate.update(
                "UPDATE messages SET created_at = ? WHERE conversation_id = ?",
                sameTime.atOffset(ZoneOffset.UTC),
                conversation.getId()
        );

        // Postgres uuid 정렬은 Java UUID 정렬과 다를 수 있어, DB 정렬 결과로 큰/작은 id를 정한다
        List<Message> newestFirst = messageRepository.findLatest(conversation.getId(), PageRequest.of(0, 10));
        Message larger = newestFirst.get(0);
        Message smaller = newestFirst.get(1);

        assertThat(messageRepository.findOlderBefore(
                conversation.getId(), sameTime, larger.getId(), PageRequest.of(0, 10)))
                .containsExactly(smaller);
    }

    @Test
    @DisplayName("대화 첫 페이지는 updatedAt·id 역순으로 개수 제한 조회")
    void findFirstPageByProjectReturnsMostRecentlyUpdatedLimited() throws InterruptedException {
        ProjectFixture fixture = createProjectFixture();
        conversationRepository.saveAndFlush(new Conversation(fixture.project(), fixture.owner(), "first"));
        Thread.sleep(5);
        Conversation second = conversationRepository.saveAndFlush(
                new Conversation(fixture.project(), fixture.owner(), "second"));
        Thread.sleep(5);
        Conversation third = conversationRepository.saveAndFlush(
                new Conversation(fixture.project(), fixture.owner(), "third"));

        assertThat(conversationRepository.findFirstPageByProject(fixture.project().getId(), PageRequest.of(0, 2)))
                .containsExactly(third, second);
    }

    @Test
    @DisplayName("커서 이전(older) 대화만 updatedAt·id 역순으로 조회")
    void findPageByProjectBeforeReturnsConversationsBeforeCursor() throws InterruptedException {
        ProjectFixture fixture = createProjectFixture();
        Conversation first = conversationRepository.saveAndFlush(
                new Conversation(fixture.project(), fixture.owner(), "first"));
        Thread.sleep(5);
        Conversation second = conversationRepository.saveAndFlush(
                new Conversation(fixture.project(), fixture.owner(), "second"));
        Thread.sleep(5);
        Conversation third = conversationRepository.saveAndFlush(
                new Conversation(fixture.project(), fixture.owner(), "third"));

        assertThat(conversationRepository.findPageByProjectBefore(
                fixture.project().getId(), third.getUpdatedAt(), third.getId(), PageRequest.of(0, 10)))
                .containsExactly(second, first)
                .doesNotContain(third);
    }

    @Test
    @DisplayName("통합 검색 — 제목·메시지 본문 매치 대화를 updatedAt 역순 조회, 타 프로젝트 제외")
    void searchPageByProjectMatchesTitleOrMessageContent() throws InterruptedException {
        ProjectFixture fixture = createProjectFixture();
        Conversation titleMatch = conversationRepository.saveAndFlush(
                new Conversation(fixture.project(), fixture.owner(), "인증 토큰 정리"));
        Thread.sleep(5);
        Conversation contentMatch = conversationRepository.saveAndFlush(
                new Conversation(fixture.project(), fixture.owner(), "Other topic"));
        messageRepository.saveAndFlush(Message.user(contentMatch, "JWT 인증 흐름이 왜 바뀌었지?"));
        conversationRepository.saveAndFlush(
                new Conversation(fixture.project(), fixture.owner(), "Deployment"));
        // 같은 검색어라도 다른 프로젝트의 대화는 절대 나오면 안 된다
        ProjectFixture otherFixture = createProjectFixture();
        conversationRepository.saveAndFlush(
                new Conversation(otherFixture.project(), otherFixture.owner(), "인증 다른 프로젝트"));

        assertThat(conversationRepository.searchPageByProject(
                fixture.project().getId(), "%인증%", PageRequest.of(0, 10)))
                .containsExactly(contentMatch, titleMatch);
    }

    @Test
    @DisplayName("통합 검색 — LIKE 와일드카드는 escape('!')로 리터럴 매치")
    void searchPageByProjectEscapesLikeWildcards() {
        ProjectFixture fixture = createProjectFixture();
        Conversation literalPercent = conversationRepository.saveAndFlush(
                new Conversation(fixture.project(), fixture.owner(), "coverage 100% goal"));
        conversationRepository.saveAndFlush(
                new Conversation(fixture.project(), fixture.owner(), "coverage 1000 goal"));

        // '!%'는 리터럴 %만 매치해야 한다 ("1000"이 와일드카드 %에 걸리면 안 됨)
        assertThat(conversationRepository.searchPageByProject(
                fixture.project().getId(), "%100!%%", PageRequest.of(0, 10)))
                .containsExactly(literalPercent);
    }

    @Test
    @DisplayName("통합 검색 스니펫 — 대화별 가장 최근 '매치' 메시지 1건만 반환")
    void findLatestMatchPerConversationReturnsSingleLatestMatch() throws InterruptedException {
        ProjectFixture fixture = createProjectFixture();
        Conversation conversation = conversationRepository.saveAndFlush(
                new Conversation(fixture.project(), fixture.owner(), "Auth"));
        messageRepository.saveAndFlush(Message.user(conversation, "인증 첫 질문"));
        Thread.sleep(5);
        messageRepository.saveAndFlush(Message.assistant(conversation, "인증 최근 답변", null));
        Thread.sleep(5);
        // 더 최신이지만 매치되지 않는 메시지 — 필터가 랭킹보다 먼저 적용돼야 한다
        messageRepository.saveAndFlush(Message.user(conversation, "무관한 주제"));

        List<MessageRepository.MessageMatchRow> rows = messageRepository.findLatestMatchPerConversation(
                List.of(conversation.getId()), "%인증%");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getConversationId()).isEqualTo(conversation.getId());
        assertThat(rows.get(0).getContent()).isEqualTo("인증 최근 답변");
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
