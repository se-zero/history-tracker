package com.history.backend.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.auth.service.UserService;
import com.history.backend.common.error.BadRequestException;
import com.history.backend.conversation.domain.Conversation;
import com.history.backend.conversation.domain.Message;
import com.history.backend.conversation.dto.Cursor;
import com.history.backend.conversation.repository.ConversationRepository;
import com.history.backend.project.domain.Project;
import com.history.backend.project.service.ProjectService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConversationService: 대화 생성·조회·수정·삭제")
class ConversationServiceTest {

    private static final UUID USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");
    private static final UUID CONVERSATION_ID = UUID.fromString("7dbd88a3-807d-4b6d-8fef-462de48f6c6c");

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private ProjectService projectService;

    @Mock
    private UserService userService;

    @Mock
    private MessageService messageService;

    private final ConversationTitleGenerator titleGenerator = new ConversationTitleGenerator();
    private final TransactionTemplate transactionTemplate = new TransactionTemplate(new NoopTransactionManager());

    @Test
    @DisplayName("대화 생성 시 대화와 첫 메시지 교환 저장")
    void createConversationSavesConversationAndFirstMessageExchange() {
        ConversationService service = service();
        User user = user();
        Project project = project();
        Message userMessage = Message.user(conversation(project, user, "Why did auth change?"), "Why did auth change?");
        Message assistantMessage = Message.assistant(conversation(project, user, "Why did auth change?"), "Because", null);
        when(userService.getActiveUser(USER_ID)).thenReturn(user);
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project);
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(invocation -> {
            Conversation conversation = invocation.getArgument(0);
            ReflectionTestUtils.setField(conversation, "id", CONVERSATION_ID);
            return conversation;
        });
        when(messageService.appendUserMessageInCurrentTransaction(any(Conversation.class), any()))
                .thenReturn(userMessage);
        when(messageService.appendAssistantMessageAfterQuery(
                PROJECT_ID,
                CONVERSATION_ID,
                "Why did auth change?",
                List.of(),
                List.of(),
                null,
                List.of()
        ))
                .thenReturn(assistantMessage);

        ConversationStart result = service.createConversation(
                USER_ID,
                PROJECT_ID,
                "  Why did auth change?  "
        );

        assertThat(result.conversation().getProject()).isSameAs(project);
        assertThat(result.conversation().getUser()).isSameAs(user);
        assertThat(result.conversation().getTitle()).isEqualTo("Why did auth change?");
        assertThat(result.userMessage()).isSameAs(userMessage);
        assertThat(result.assistantMessage()).isSameAs(assistantMessage);
    }

    @Test
    @DisplayName("빈 첫 메시지로 대화 생성 거부")
    void createConversationRejectsBlankFirstMessage() {
        ConversationService service = service();

        assertThatThrownBy(() -> service.createConversation(USER_ID, PROJECT_ID, " "))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Message content is required.");

        verifyNoInteractions(userService, projectService, conversationRepository, messageService);
    }

    @Test
    @DisplayName("대화 목록 첫 페이지 조회 시 프로젝트 접근 권한 검증")
    void findConversationsPageValidatesProjectAccess() {
        ConversationService service = service();
        List<Conversation> rows = conversations(3);
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findFirstPageByProject(eq(PROJECT_ID), any(Pageable.class)))
                .thenReturn(rows);

        ConversationPage result = service.findConversationsPage(USER_ID, PROJECT_ID, null);

        assertThat(result.items()).containsExactlyElementsOf(rows);
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    @DisplayName("첫 페이지가 페이지 크기를 초과하면 hasMore 절단 후 nextCursor 설정")
    void findConversationsPageTruncatesAndSetsNextCursorWhenOverPageSize() {
        ConversationService service = service();
        List<Conversation> rows = conversations(21); // CONVERSATION_PAGE_SIZE(20) + 1
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findFirstPageByProject(eq(PROJECT_ID), any(Pageable.class)))
                .thenReturn(rows);

        ConversationPage result = service.findConversationsPage(USER_ID, PROJECT_ID, null);

        assertThat(result.items()).containsExactlyElementsOf(rows.subList(0, 20));
        assertThat(result.nextCursor()).isNotNull();
    }

    @Test
    @DisplayName("커서가 주어지면 커서 이전(older) 대화 페이지 조회")
    void findConversationsPageUsesCursorWhenProvided() {
        ConversationService service = service();
        Conversation conversation = conversations(1).get(0);
        String cursor = new Cursor(Instant.parse("2026-05-23T01:00:00Z"), CONVERSATION_ID).encode();
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findPageByProjectBefore(
                eq(PROJECT_ID), any(Instant.class), any(UUID.class), any(Pageable.class)))
                .thenReturn(List.of(conversation));

        ConversationPage result = service.findConversationsPage(USER_ID, PROJECT_ID, cursor);

        assertThat(result.items()).containsExactly(conversation);
        verify(conversationRepository).findPageByProjectBefore(
                eq(PROJECT_ID), any(Instant.class), any(UUID.class), any(Pageable.class));
    }

    @Test
    @DisplayName("대화 상세 조회 시 대화와 최신 메시지 페이지 반환")
    void getConversationDetailReturnsConversationAndMessagePage() {
        ConversationService service = service();
        Conversation conversation = conversation(project(), user(), "Title");
        Message message = Message.user(conversation, "Question");
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findByIdAndProject_Id(CONVERSATION_ID, PROJECT_ID))
                .thenReturn(Optional.of(conversation));
        when(messageService.findMessagePageInCurrentTransaction(CONVERSATION_ID, null))
                .thenReturn(new MessagePage(List.of(message), false, null));

        ConversationDetail result = service.getConversationDetail(USER_ID, PROJECT_ID, CONVERSATION_ID);

        assertThat(result.conversation()).isSameAs(conversation);
        assertThat(result.messages().items()).containsExactly(message);
        assertThat(result.messages().hasMore()).isFalse();
    }

    @Test
    @DisplayName("대화 제목 수정 시 양 끝 공백 제거")
    void updateTitleTrimsTitle() {
        ConversationService service = service();
        Conversation conversation = conversation(project(), user(), "Old title");
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findByIdAndProject_Id(CONVERSATION_ID, PROJECT_ID))
                .thenReturn(Optional.of(conversation));

        Conversation result = service.updateTitle(USER_ID, PROJECT_ID, CONVERSATION_ID, "  New title  ");

        assertThat(result.getTitle()).isEqualTo("New title");
    }

    @Test
    @DisplayName("빈 값으로 대화 제목 초기화 허용")
    void updateTitleAllowsClearingTitle() {
        ConversationService service = service();
        Conversation conversation = conversation(project(), user(), "Old title");
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findByIdAndProject_Id(CONVERSATION_ID, PROJECT_ID))
                .thenReturn(Optional.of(conversation));

        Conversation result = service.updateTitle(USER_ID, PROJECT_ID, CONVERSATION_ID, " ");

        assertThat(result.getTitle()).isNull();
    }

    @Test
    @DisplayName("대화 검색 — 이스케이프된 패턴으로 조회 후 한 줄 스니펫과 함께 반환")
    void searchConversationsBuildsEscapedPatternAndSnippets() {
        ConversationService service = service();
        Conversation conversation = conversation(project(), user(), "Auth 정리");
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.searchPageByProject(eq(PROJECT_ID), eq("%100!%%"), any(Pageable.class)))
                .thenReturn(List.of(conversation));
        when(messageService.findLatestMatchedContents(List.of(CONVERSATION_ID), "%100!%%"))
                .thenReturn(Map.of(CONVERSATION_ID, "커버리지  100%\n달성 계획"));

        List<ConversationSearchResult> results = service.searchConversations(USER_ID, PROJECT_ID, " 100% ");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).conversation()).isSameAs(conversation);
        // 개행·연속 공백은 한 줄로 접힌다
        assertThat(results.get(0).snippet()).isEqualTo("커버리지 100% 달성 계획");
    }

    @Test
    @DisplayName("대화 검색 — 제목만 매치한 대화는 스니펫 없이 반환")
    void searchConversationsReturnsNullSnippetForTitleOnlyMatch() {
        ConversationService service = service();
        Conversation conversation = conversation(project(), user(), "인증 정리");
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.searchPageByProject(eq(PROJECT_ID), eq("%인증%"), any(Pageable.class)))
                .thenReturn(List.of(conversation));
        when(messageService.findLatestMatchedContents(List.of(CONVERSATION_ID), "%인증%"))
                .thenReturn(Map.of());

        List<ConversationSearchResult> results = service.searchConversations(USER_ID, PROJECT_ID, "인증");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).snippet()).isNull();
    }

    @Test
    @DisplayName("대화 검색 — 긴 본문은 매치 주변만 발췌하고 앞뒤 말줄임")
    void searchConversationsTruncatesSnippetAroundMatch() {
        ConversationService service = service();
        Conversation conversation = conversation(project(), user(), "Long");
        String longContent = "a".repeat(100) + "인증" + "b".repeat(300);
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.searchPageByProject(eq(PROJECT_ID), eq("%인증%"), any(Pageable.class)))
                .thenReturn(List.of(conversation));
        when(messageService.findLatestMatchedContents(List.of(CONVERSATION_ID), "%인증%"))
                .thenReturn(Map.of(CONVERSATION_ID, longContent));

        List<ConversationSearchResult> results = service.searchConversations(USER_ID, PROJECT_ID, "인증");

        String snippet = results.get(0).snippet();
        assertThat(snippet).startsWith("…").endsWith("…").contains("인증");
        // 말줄임 2자 + 발췌 최대 길이(160)
        assertThat(snippet).hasSize(162);
    }

    @Test
    @DisplayName("대화 검색 — 빈 검색어는 저장소 조회 없이 빈 결과")
    void searchConversationsReturnsEmptyForBlankQuery() {
        ConversationService service = service();
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());

        assertThat(service.searchConversations(USER_ID, PROJECT_ID, "  ")).isEmpty();

        verifyNoInteractions(conversationRepository, messageService);
    }

    @Test
    @DisplayName("프로젝트 내 대화 삭제")
    void deleteConversationDeletesConversationForProject() {
        ConversationService service = service();
        Conversation conversation = conversation(project(), user(), "Title");
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findByIdAndProject_Id(CONVERSATION_ID, PROJECT_ID))
                .thenReturn(Optional.of(conversation));

        service.deleteConversation(USER_ID, PROJECT_ID, CONVERSATION_ID);

        verify(conversationRepository).delete(conversation);
    }

    private ConversationService service() {
        return new ConversationService(
                conversationRepository,
                projectService,
                userService,
                messageService,
                titleGenerator,
                transactionTemplate
        );
    }

    private User user() {
        User user = new User("github", "12345", "owner@example.com", "Owner", null);
        ReflectionTestUtils.setField(user, "id", USER_ID);
        return user;
    }

    private Project project() {
        Project project = new Project(user(), "History Tracker", null);
        ReflectionTestUtils.setField(project, "id", PROJECT_ID);
        return project;
    }

    private Conversation conversation(Project project, User user, String title) {
        Conversation conversation = new Conversation(project, user, title);
        ReflectionTestUtils.setField(conversation, "id", CONVERSATION_ID);
        return conversation;
    }

    private List<Conversation> conversations(int count) {
        List<Conversation> conversations = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            Conversation conversation = new Conversation(project(), user(), "Title " + index);
            ReflectionTestUtils.setField(conversation, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(
                    conversation, "updatedAt", Instant.parse("2026-05-23T01:00:00Z").plusSeconds(index));
            conversations.add(conversation);
        }
        return conversations;
    }

    private static class NoopTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
