package com.history.backend.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.auth.service.UserService;
import com.history.backend.common.error.BadRequestException;
import com.history.backend.conversation.domain.Conversation;
import com.history.backend.conversation.domain.Message;
import com.history.backend.conversation.repository.ConversationRepository;
import com.history.backend.project.domain.Project;
import com.history.backend.project.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
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
        when(messageService.appendAssistantMessageAfterQuery(PROJECT_ID, CONVERSATION_ID, "Why did auth change?"))
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
    void createConversationRejectsBlankFirstMessage() {
        ConversationService service = service();

        assertThatThrownBy(() -> service.createConversation(USER_ID, PROJECT_ID, " "))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Message content is required.");

        verifyNoInteractions(userService, projectService, conversationRepository, messageService);
    }

    @Test
    void findConversationsValidatesProjectAccess() {
        ConversationService service = service();
        Conversation conversation = conversation(project(), user(), "Title");
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findAllByProject_IdOrderByUpdatedAtDesc(PROJECT_ID))
                .thenReturn(List.of(conversation));

        List<Conversation> result = service.findConversations(USER_ID, PROJECT_ID);

        assertThat(result).containsExactly(conversation);
    }

    @Test
    void getConversationDetailReturnsConversationAndMessages() {
        ConversationService service = service();
        Conversation conversation = conversation(project(), user(), "Title");
        Message message = Message.user(conversation, "Question");
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findByIdAndProject_Id(CONVERSATION_ID, PROJECT_ID))
                .thenReturn(Optional.of(conversation));
        when(messageService.findMessagesInCurrentTransaction(CONVERSATION_ID))
                .thenReturn(List.of(message));

        ConversationDetail result = service.getConversationDetail(USER_ID, PROJECT_ID, CONVERSATION_ID);

        assertThat(result.conversation()).isSameAs(conversation);
        assertThat(result.messages()).containsExactly(message);
    }

    @Test
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
