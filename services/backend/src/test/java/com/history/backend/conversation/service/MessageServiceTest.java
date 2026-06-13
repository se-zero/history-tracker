package com.history.backend.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.common.error.BadRequestException;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.conversation.domain.Conversation;
import com.history.backend.conversation.domain.Message;
import com.history.backend.conversation.domain.MessageRole;
import com.history.backend.conversation.dto.AiEngineHistoryMessage;
import com.history.backend.conversation.repository.ConversationRepository;
import com.history.backend.conversation.repository.MessageRepository;
import com.history.backend.project.domain.Project;
import com.history.backend.project.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    private static final UUID USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");
    private static final UUID CONVERSATION_ID = UUID.fromString("7dbd88a3-807d-4b6d-8fef-462de48f6c6c");

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private ProjectService projectService;

    @Mock
    private AiEngineQueryClient aiEngineQueryClient;

    private final TransactionTemplate transactionTemplate = new TransactionTemplate(new NoopTransactionManager());

    @Test
    void addMessageSavesUserAndAssistantMessages() {
        MessageService service = service();
        Conversation conversation = conversation();
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findByIdAndProject_Id(CONVERSATION_ID, PROJECT_ID))
                .thenReturn(Optional.of(conversation))
                .thenReturn(Optional.of(conversation));
        Message previousUser = Message.user(conversation, "What changed?");
        Message previousAssistant = Message.assistant(conversation, "PR #18 changed auth.", null);
        when(messageRepository.findAllByConversation_IdOrderByCreatedAtAsc(CONVERSATION_ID))
                .thenReturn(List.of(previousUser, previousAssistant));
        when(aiEngineQueryClient.ask("Why did auth change?", PROJECT_ID, List.of(
                new AiEngineHistoryMessage("user", "What changed?"),
                new AiEngineHistoryMessage("assistant", "PR #18 changed auth.")
        )))
                .thenReturn(AiEngineQueryResult.success("OAuth callback changed.", Map.of(
                        "summary", "OAuth callback changed.",
                        "evidence", List.of(),
                        "unknown_aspects", List.of()
                )));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MessageExchange result = service.addMessage(
                USER_ID,
                PROJECT_ID,
                CONVERSATION_ID,
                "  Why did auth change?  "
        );

        assertThat(result.userMessage().getConversation()).isSameAs(conversation);
        assertThat(result.userMessage().getRole()).isEqualTo(MessageRole.USER);
        assertThat(result.userMessage().getContent()).isEqualTo("Why did auth change?");
        assertThat(result.assistantMessage().getConversation()).isSameAs(conversation);
        assertThat(result.assistantMessage().getRole()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(result.assistantMessage().getContent()).isEqualTo("OAuth callback changed.");
        assertThat(result.assistantMessage().getMetadata()).containsEntry("structured", Map.of(
                "summary", "OAuth callback changed.",
                "evidence", List.of(),
                "unknown_aspects", List.of()
        ));
        assertThat(conversation.getUpdatedAt()).isNotNull();

        InOrder order = inOrder(messageRepository, aiEngineQueryClient);
        order.verify(messageRepository).findAllByConversation_IdOrderByCreatedAtAsc(CONVERSATION_ID);
        order.verify(messageRepository).save(any(Message.class));
        order.verify(aiEngineQueryClient).ask("Why did auth change?", PROJECT_ID, List.of(
                new AiEngineHistoryMessage("user", "What changed?"),
                new AiEngineHistoryMessage("assistant", "PR #18 changed auth.")
        ));
    }

    @Test
    void addMessageStoresFallbackAssistantMessageWhenAiEngineFails() {
        MessageService service = service();
        Conversation conversation = conversation();
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findByIdAndProject_Id(CONVERSATION_ID, PROJECT_ID))
                .thenReturn(Optional.of(conversation))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findAllByConversation_IdOrderByCreatedAtAsc(CONVERSATION_ID))
                .thenReturn(List.of());
        when(aiEngineQueryClient.ask("Why did auth change?", PROJECT_ID, List.of()))
                .thenReturn(AiEngineQueryResult.fallback("질문을 처리하는 중 오류가 발생했습니다."));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MessageExchange result = service.addMessage(
                USER_ID,
                PROJECT_ID,
                CONVERSATION_ID,
                "Why did auth change?"
        );

        assertThat(result.userMessage().getContent()).isEqualTo("Why did auth change?");
        assertThat(result.assistantMessage().getContent()).isEqualTo("질문을 처리하는 중 오류가 발생했습니다.");
        assertThat(result.assistantMessage().getMetadata())
                .containsEntry("fallback", true)
                .containsEntry("error_type", "AI_ENGINE_ERROR");
    }

    @Test
    void appendUserMessageTouchesConversation() {
        MessageService service = service();
        Conversation conversation = conversation();
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Message result = service.appendUserMessageInCurrentTransaction(conversation, "Question");

        assertThat(result.getContent()).isEqualTo("Question");
        assertThat(conversation.getUpdatedAt()).isNotNull();
    }

    @Test
    void addMessageExcludesSystemFallbackAndBlankMessagesFromHistory() {
        MessageService service = service();
        Conversation conversation = conversation();
        Message validUser = Message.user(conversation, "What changed?");
        Message system = Message.system(conversation, "Internal context", null);
        Message fallback = Message.assistant(conversation, "Query failed", Map.of(
                "fallback", true,
                "error_type", "AI_ENGINE_ERROR"
        ));
        Message blank = Message.assistant(conversation, "   ", null);
        Message validAssistant = Message.assistant(conversation, "PR #18 changed auth.", null);
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findByIdAndProject_Id(CONVERSATION_ID, PROJECT_ID))
                .thenReturn(Optional.of(conversation))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findAllByConversation_IdOrderByCreatedAtAsc(CONVERSATION_ID))
                .thenReturn(List.of(system, fallback, blank, validUser, validAssistant));
        when(aiEngineQueryClient.ask("Tell me more", PROJECT_ID, List.of(
                new AiEngineHistoryMessage("user", "What changed?"),
                new AiEngineHistoryMessage("assistant", "PR #18 changed auth.")
        ))).thenReturn(AiEngineQueryResult.success("More details", null));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.addMessage(USER_ID, PROJECT_ID, CONVERSATION_ID, "Tell me more");

        verify(aiEngineQueryClient).ask("Tell me more", PROJECT_ID, List.of(
                new AiEngineHistoryMessage("user", "What changed?"),
                new AiEngineHistoryMessage("assistant", "PR #18 changed auth.")
        ));
    }

    @Test
    void addMessageSendsOnlyFiveMostRecentCompletedTurns() {
        MessageService service = service();
        Conversation conversation = conversation();
        List<Message> messages = new java.util.ArrayList<>();
        for (int turn = 1; turn <= 7; turn++) {
            messages.add(Message.user(conversation, "Question " + turn));
            messages.add(Message.assistant(conversation, "Answer " + turn, null));
        }
        messages.add(Message.user(conversation, "Incomplete question"));
        List<AiEngineHistoryMessage> expectedHistory = List.of(
                new AiEngineHistoryMessage("user", "Question 3"),
                new AiEngineHistoryMessage("assistant", "Answer 3"),
                new AiEngineHistoryMessage("user", "Question 4"),
                new AiEngineHistoryMessage("assistant", "Answer 4"),
                new AiEngineHistoryMessage("user", "Question 5"),
                new AiEngineHistoryMessage("assistant", "Answer 5"),
                new AiEngineHistoryMessage("user", "Question 6"),
                new AiEngineHistoryMessage("assistant", "Answer 6"),
                new AiEngineHistoryMessage("user", "Question 7"),
                new AiEngineHistoryMessage("assistant", "Answer 7")
        );
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findByIdAndProject_Id(CONVERSATION_ID, PROJECT_ID))
                .thenReturn(Optional.of(conversation))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findAllByConversation_IdOrderByCreatedAtAsc(CONVERSATION_ID))
                .thenReturn(messages);
        when(aiEngineQueryClient.ask("Current question", PROJECT_ID, expectedHistory))
                .thenReturn(AiEngineQueryResult.success("Current answer", null));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.addMessage(USER_ID, PROJECT_ID, CONVERSATION_ID, "Current question");

        verify(aiEngineQueryClient).ask("Current question", PROJECT_ID, expectedHistory);
    }

    @Test
    void addMessageExcludesTurnCompletedByFallbackAnswer() {
        MessageService service = service();
        Conversation conversation = conversation();
        Message failedQuestion = Message.user(conversation, "Failed question");
        Message fallback = Message.assistant(conversation, "Query failed", Map.of(
                "fallback", true,
                "error_type", "AI_ENGINE_ERROR"
        ));
        Message orphanAssistant = Message.assistant(conversation, "Orphan answer", null);
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findByIdAndProject_Id(CONVERSATION_ID, PROJECT_ID))
                .thenReturn(Optional.of(conversation))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findAllByConversation_IdOrderByCreatedAtAsc(CONVERSATION_ID))
                .thenReturn(List.of(failedQuestion, fallback, orphanAssistant));
        when(aiEngineQueryClient.ask("Current question", PROJECT_ID, List.of()))
                .thenReturn(AiEngineQueryResult.success("Current answer", null));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.addMessage(USER_ID, PROJECT_ID, CONVERSATION_ID, "Current question");

        verify(aiEngineQueryClient).ask("Current question", PROJECT_ID, List.of());
    }

    @Test
    void addMessageRejectsBlankContent() {
        MessageService service = service();

        assertThatThrownBy(() -> service.addMessage(USER_ID, PROJECT_ID, CONVERSATION_ID, " "))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Message content is required.");

        verifyNoInteractions(projectService, conversationRepository, messageRepository, aiEngineQueryClient);
    }

    @Test
    void addMessageRejectsMissingConversation() {
        MessageService service = service();
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findByIdAndProject_Id(CONVERSATION_ID, PROJECT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addMessage(USER_ID, PROJECT_ID, CONVERSATION_ID, "Question"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Conversation not found.");
    }

    @Test
    void findMessagesValidatesProjectAndConversation() {
        MessageService service = service();
        Conversation conversation = conversation();
        Message message = Message.user(conversation, "Question");
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findByIdAndProject_Id(CONVERSATION_ID, PROJECT_ID))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findAllByConversation_IdOrderByCreatedAtAsc(CONVERSATION_ID))
                .thenReturn(List.of(message));

        List<Message> result = service.findMessages(USER_ID, PROJECT_ID, CONVERSATION_ID);

        assertThat(result).containsExactly(message);
    }

    private MessageService service() {
        return new MessageService(
                messageRepository,
                conversationRepository,
                projectService,
                aiEngineQueryClient,
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

    private Conversation conversation() {
        Conversation conversation = new Conversation(project(), user(), "Title");
        ReflectionTestUtils.setField(conversation, "id", CONVERSATION_ID);
        return conversation;
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
