package com.history.backend.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
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
import com.history.backend.conversation.dto.AiEnginePriorEvidence;
import com.history.backend.conversation.dto.Cursor;
import com.history.backend.conversation.repository.ConversationRepository;
import com.history.backend.conversation.repository.MessageRepository;
import com.history.backend.graph.dto.EvidenceRef;
import com.history.backend.project.domain.Project;
import com.history.backend.project.service.ProjectService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
@DisplayName("MessageService: 메시지 추가·히스토리·요약 관리")
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
    @DisplayName("메시지 추가 시 사용자·보조자 메시지 저장")
    void addMessageSavesUserAndAssistantMessages() {
        MessageService service = service();
        Conversation conversation = conversation();
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findByIdAndProject_Id(CONVERSATION_ID, PROJECT_ID))
                .thenReturn(Optional.of(conversation))
                .thenReturn(Optional.of(conversation));
        Message previousUser = Message.user(conversation, "What changed?");
        Message previousAssistant = Message.assistant(conversation, "PR #18 changed auth.", Map.of(
                "structured", Map.of(
                        "summary", "PR #18 changed auth.",
                        "evidence", List.of(Map.of(
                                "type", "pull_request",
                                "id", "#18",
                                "quote", "OAuth callback update",
                                "occurredAt", "2026-06-01T00:00:00Z",
                                "event_meaning", "pr_merged",
                                "author", "kim"
                        )),
                        "unknown_aspects", List.of()
                )
        ));
        when(messageRepository.findAllByConversation_IdOrderByCreatedAtAsc(CONVERSATION_ID))
                .thenReturn(List.of(previousUser, previousAssistant));
        when(aiEngineQueryClient.ask("Why did auth change?", PROJECT_ID, List.of(
                new AiEngineHistoryMessage("user", "What changed?"),
                new AiEngineHistoryMessage("assistant", "PR #18 changed auth.")
        ), List.of(new AiEnginePriorEvidence("pull_request", "#18", "OAuth callback update")), null, List.of()))
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
                "  Why did auth change?  ",
                List.of()
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
        ), List.of(new AiEnginePriorEvidence("pull_request", "#18", "OAuth callback update")), null, List.of());
    }

    @Test
    @DisplayName("ai-engine 실패 시 fallback 보조자 메시지 저장")
    void addMessageStoresFallbackAssistantMessageWhenAiEngineFails() {
        MessageService service = service();
        Conversation conversation = conversation();
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findByIdAndProject_Id(CONVERSATION_ID, PROJECT_ID))
                .thenReturn(Optional.of(conversation))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findAllByConversation_IdOrderByCreatedAtAsc(CONVERSATION_ID))
                .thenReturn(List.of());
        when(aiEngineQueryClient.ask("Why did auth change?", PROJECT_ID, List.of(), List.of(), null, List.of()))
                .thenReturn(AiEngineQueryResult.fallback("질문을 처리하는 중 오류가 발생했습니다."));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MessageExchange result = service.addMessage(
                USER_ID,
                PROJECT_ID,
                CONVERSATION_ID,
                "Why did auth change?",
                List.of()
        );

        assertThat(result.userMessage().getContent()).isEqualTo("Why did auth change?");
        assertThat(result.assistantMessage().getContent()).isEqualTo("질문을 처리하는 중 오류가 발생했습니다.");
        assertThat(result.assistantMessage().getMetadata())
                .containsEntry("fallback", true)
                .containsEntry("error_type", "AI_ENGINE_ERROR");
    }

    @Test
    @DisplayName("focus evidence를 ai-engine 질의로 전달")
    void addMessageForwardsFocusEvidenceToAiEngine() {
        MessageService service = service();
        Conversation conversation = conversation();
        List<EvidenceRef> focusEvidence = List.of(new EvidenceRef("commit", "abc1234def"));
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findByIdAndProject_Id(CONVERSATION_ID, PROJECT_ID))
                .thenReturn(Optional.of(conversation))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findAllByConversation_IdOrderByCreatedAtAsc(CONVERSATION_ID))
                .thenReturn(List.of());
        when(aiEngineQueryClient.ask("Why this commit?", PROJECT_ID, List.of(), List.of(), null, focusEvidence))
                .thenReturn(AiEngineQueryResult.success("Because of the commit.", null));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.addMessage(USER_ID, PROJECT_ID, CONVERSATION_ID, "Why this commit?", focusEvidence);

        verify(aiEngineQueryClient).ask("Why this commit?", PROJECT_ID, List.of(), List.of(), null, focusEvidence);
    }

    @Test
    @DisplayName("focus evidence 중 무효 항목(알 수 없는 type·빈/과길이 id)은 걸러내고 전달")
    void addMessageFiltersInvalidFocusEvidence() {
        MessageService service = service();
        Conversation conversation = conversation();
        EvidenceRef valid = new EvidenceRef("commit", "abc1234def");
        List<EvidenceRef> focusEvidence = List.of(
                valid,
                new EvidenceRef("weird", "x"),          // 알 수 없는 type
                new EvidenceRef("issue", " "),           // 빈 id
                new EvidenceRef("commit", "a".repeat(201)), // 과길이 id
                new EvidenceRef(null, "x"),              // type null (Set.of.contains(null) → NPE 방어)
                new EvidenceRef("commit", null)          // id null
        );
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findByIdAndProject_Id(CONVERSATION_ID, PROJECT_ID))
                .thenReturn(Optional.of(conversation))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findAllByConversation_IdOrderByCreatedAtAsc(CONVERSATION_ID))
                .thenReturn(List.of());
        when(aiEngineQueryClient.ask("Q", PROJECT_ID, List.of(), List.of(), null, List.of(valid)))
                .thenReturn(AiEngineQueryResult.success("A", null));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.addMessage(USER_ID, PROJECT_ID, CONVERSATION_ID, "Q", focusEvidence);

        verify(aiEngineQueryClient).ask("Q", PROJECT_ID, List.of(), List.of(), null, List.of(valid));
    }

    @Test
    @DisplayName("사용자 메시지 저장 시 대화 updated_at 갱신")
    void appendUserMessageTouchesConversation() {
        MessageService service = service();
        Conversation conversation = conversation();
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Message result = service.appendUserMessageInCurrentTransaction(conversation, "Question");

        assertThat(result.getContent()).isEqualTo("Question");
        assertThat(conversation.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("system·fallback·빈 메시지는 히스토리에서 제외")
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
        ), List.of(), null, List.of())).thenReturn(AiEngineQueryResult.success("More details", null));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.addMessage(USER_ID, PROJECT_ID, CONVERSATION_ID, "Tell me more", List.of());

        verify(aiEngineQueryClient).ask("Tell me more", PROJECT_ID, List.of(
                new AiEngineHistoryMessage("user", "What changed?"),
                new AiEngineHistoryMessage("assistant", "PR #18 changed auth.")
        ), List.of(), null, List.of());
    }

    @Test
    @DisplayName("최근 5개 완성 턴만 히스토리로 전송")
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
        when(aiEngineQueryClient.ask("Current question", PROJECT_ID, expectedHistory, List.of(), null, List.of()))
                .thenReturn(AiEngineQueryResult.success("Current answer", null));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.addMessage(USER_ID, PROJECT_ID, CONVERSATION_ID, "Current question", List.of());

        verify(aiEngineQueryClient).ask("Current question", PROJECT_ID, expectedHistory, List.of(), null, List.of());
    }

    @Test
    @DisplayName("최근 5개 이전 턴은 running summary로 압축")
    void addMessageSummarizesTurnsOlderThanRecentFive() {
        MessageService service = service();
        Conversation conversation = conversation();
        Map<String, Object> existingSummary = summary("existing");
        Map<String, Object> generatedSummary = summary("merged");
        setSummaryState(conversation, existingSummary, null, 2L);
        List<Message> messages = completedTurns(conversation, 6);
        List<AiEngineHistoryMessage> oldHistory = List.of(
                new AiEngineHistoryMessage("user", "Question 1"),
                new AiEngineHistoryMessage("assistant", "Answer 1")
        );
        List<AiEngineHistoryMessage> recentHistory = historyTurns(2, 6);
        UUID throughMessageId = messages.get(1).getId();
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findByIdAndProject_Id(CONVERSATION_ID, PROJECT_ID))
                .thenReturn(Optional.of(conversation))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findAllByConversation_IdOrderByCreatedAtAsc(CONVERSATION_ID))
                .thenReturn(messages);
        when(aiEngineQueryClient.summarize(existingSummary, oldHistory)).thenReturn(generatedSummary);
        when(conversationRepository.updateRunningSummary(
                eq(CONVERSATION_ID),
                eq(2L),
                eq(generatedSummary),
                eq(throughMessageId),
                any(Instant.class)
        ))
                .thenReturn(1);
        when(aiEngineQueryClient.ask("Current question", PROJECT_ID, recentHistory, List.of(), generatedSummary, List.of()))
                .thenReturn(AiEngineQueryResult.success("Current answer", null));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.addMessage(USER_ID, PROJECT_ID, CONVERSATION_ID, "Current question", List.of());

        verify(aiEngineQueryClient).summarize(existingSummary, oldHistory);
        verify(conversationRepository).updateRunningSummary(
                eq(CONVERSATION_ID),
                eq(2L),
                eq(generatedSummary),
                eq(throughMessageId),
                any(Instant.class)
        );
        verify(aiEngineQueryClient).ask("Current question", PROJECT_ID, recentHistory, List.of(), generatedSummary, List.of());
    }

    @Test
    @DisplayName("요약 갱신 충돌 시 최신 저장 요약 사용")
    void addMessageUsesLatestSummaryWhenConditionalUpdateConflicts() {
        MessageService service = service();
        Conversation initialConversation = conversation();
        Conversation latestConversation = conversation();
        Map<String, Object> existingSummary = summary("existing");
        Map<String, Object> generatedSummary = summary("generated by current request");
        Map<String, Object> latestSummary = summary("stored by concurrent request");
        setSummaryState(initialConversation, existingSummary, null, 3L);
        setSummaryState(latestConversation, latestSummary, null, 4L);
        List<Message> messages = completedTurns(initialConversation, 6);
        List<AiEngineHistoryMessage> oldHistory = List.of(
                new AiEngineHistoryMessage("user", "Question 1"),
                new AiEngineHistoryMessage("assistant", "Answer 1")
        );
        List<AiEngineHistoryMessage> recentHistory = historyTurns(2, 6);
        UUID throughMessageId = messages.get(1).getId();
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findByIdAndProject_Id(CONVERSATION_ID, PROJECT_ID))
                .thenReturn(Optional.of(initialConversation))
                .thenReturn(Optional.of(latestConversation))
                .thenReturn(Optional.of(latestConversation));
        when(messageRepository.findAllByConversation_IdOrderByCreatedAtAsc(CONVERSATION_ID))
                .thenReturn(messages);
        when(aiEngineQueryClient.summarize(existingSummary, oldHistory)).thenReturn(generatedSummary);
        when(conversationRepository.updateRunningSummary(
                eq(CONVERSATION_ID),
                eq(3L),
                eq(generatedSummary),
                eq(throughMessageId),
                any(Instant.class)
        ))
                .thenReturn(0);
        when(aiEngineQueryClient.ask("Current question", PROJECT_ID, recentHistory, List.of(), latestSummary, List.of()))
                .thenReturn(AiEngineQueryResult.success("Current answer", null));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.addMessage(USER_ID, PROJECT_ID, CONVERSATION_ID, "Current question", List.of());

        verify(aiEngineQueryClient).ask("Current question", PROJECT_ID, recentHistory, List.of(), latestSummary, List.of());
    }

    @Test
    @DisplayName("저장된 커서 이후 턴만 추가 요약")
    void addMessageSummarizesOnlyTurnsAfterStoredCursor() {
        MessageService service = service();
        Conversation conversation = conversation();
        Map<String, Object> existingSummary = summary("through turn 1");
        Map<String, Object> generatedSummary = summary("through turn 2");
        List<Message> messages = completedTurns(conversation, 7);
        UUID storedCursor = messages.get(1).getId();
        UUID nextCursor = messages.get(3).getId();
        setSummaryState(conversation, existingSummary, storedCursor, 1L);
        List<AiEngineHistoryMessage> newlyOldHistory = List.of(
                new AiEngineHistoryMessage("user", "Question 2"),
                new AiEngineHistoryMessage("assistant", "Answer 2")
        );
        List<AiEngineHistoryMessage> recentHistory = historyTurns(3, 7);
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findByIdAndProject_Id(CONVERSATION_ID, PROJECT_ID))
                .thenReturn(Optional.of(conversation))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findAllFromCursor(CONVERSATION_ID, storedCursor))
                .thenReturn(messages);
        when(aiEngineQueryClient.summarize(existingSummary, newlyOldHistory)).thenReturn(generatedSummary);
        when(conversationRepository.updateRunningSummary(
                eq(CONVERSATION_ID),
                eq(1L),
                eq(generatedSummary),
                eq(nextCursor),
                any(Instant.class)
        )).thenReturn(1);
        when(aiEngineQueryClient.ask("Current question", PROJECT_ID, recentHistory, List.of(), generatedSummary, List.of()))
                .thenReturn(AiEngineQueryResult.success("Current answer", null));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.addMessage(USER_ID, PROJECT_ID, CONVERSATION_ID, "Current question", List.of());

        verify(aiEngineQueryClient).summarize(existingSummary, newlyOldHistory);
        verify(aiEngineQueryClient).ask("Current question", PROJECT_ID, recentHistory, List.of(), generatedSummary, List.of());
    }

    @Test
    @DisplayName("요약 커서 누락 시 전체 히스토리 폴백")
    void addMessageFallsBackToFullHistoryWhenSummaryCursorIsMissing() {
        MessageService service = service();
        Conversation conversation = conversation();
        UUID missingCursor = UUID.randomUUID();
        setSummaryState(conversation, summary("existing"), missingCursor, 1L);
        List<Message> messages = completedTurns(conversation, 2);
        List<AiEngineHistoryMessage> expectedHistory = historyTurns(1, 2);
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findByIdAndProject_Id(CONVERSATION_ID, PROJECT_ID))
                .thenReturn(Optional.of(conversation))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findAllFromCursor(CONVERSATION_ID, missingCursor)).thenReturn(List.of());
        when(messageRepository.findAllByConversation_IdOrderByCreatedAtAsc(CONVERSATION_ID))
                .thenReturn(messages);
        when(aiEngineQueryClient.ask(
                "Current question",
                PROJECT_ID,
                expectedHistory,
                List.of(),
                summary("existing"),
                List.of()
        )).thenReturn(AiEngineQueryResult.success("Current answer", null));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.addMessage(USER_ID, PROJECT_ID, CONVERSATION_ID, "Current question", List.of());

        verify(messageRepository).findAllFromCursor(CONVERSATION_ID, missingCursor);
        verify(messageRepository).findAllByConversation_IdOrderByCreatedAtAsc(CONVERSATION_ID);
        verify(aiEngineQueryClient).ask(
                "Current question",
                PROJECT_ID,
                expectedHistory,
                List.of(),
                summary("existing"),
                List.of()
        );
    }

    @Test
    @DisplayName("요약 생성 실패 시 기존 요약 유지하고 계속 진행")
    void addMessageContinuesWithExistingSummaryWhenSummarizationFails() {
        MessageService service = service();
        Conversation conversation = conversation();
        Map<String, Object> existingSummary = summary("existing");
        setSummaryState(conversation, existingSummary, null, 1L);
        List<Message> messages = completedTurns(conversation, 6);
        List<AiEngineHistoryMessage> oldHistory = List.of(
                new AiEngineHistoryMessage("user", "Question 1"),
                new AiEngineHistoryMessage("assistant", "Answer 1")
        );
        List<AiEngineHistoryMessage> recentHistory = historyTurns(2, 6);
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findByIdAndProject_Id(CONVERSATION_ID, PROJECT_ID))
                .thenReturn(Optional.of(conversation))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findAllByConversation_IdOrderByCreatedAtAsc(CONVERSATION_ID))
                .thenReturn(messages);
        when(aiEngineQueryClient.summarize(existingSummary, oldHistory)).thenReturn(null);
        when(aiEngineQueryClient.ask("Current question", PROJECT_ID, recentHistory, List.of(), existingSummary, List.of()))
                .thenReturn(AiEngineQueryResult.success("Current answer", null));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.addMessage(USER_ID, PROJECT_ID, CONVERSATION_ID, "Current question", List.of());

        verify(aiEngineQueryClient).ask("Current question", PROJECT_ID, recentHistory, List.of(), existingSummary, List.of());
    }

    @Test
    @DisplayName("요약 갱신 예외 시 기존 요약 유지하고 계속 진행")
    void addMessageContinuesWithExistingSummaryWhenSummaryUpdateThrows() {
        MessageService service = service();
        Conversation conversation = conversation();
        Map<String, Object> existingSummary = summary("existing");
        Map<String, Object> generatedSummary = summary("generated");
        setSummaryState(conversation, existingSummary, null, 1L);
        List<Message> messages = completedTurns(conversation, 6);
        List<AiEngineHistoryMessage> oldHistory = List.of(
                new AiEngineHistoryMessage("user", "Question 1"),
                new AiEngineHistoryMessage("assistant", "Answer 1")
        );
        List<AiEngineHistoryMessage> recentHistory = historyTurns(2, 6);
        UUID throughMessageId = messages.get(1).getId();
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findByIdAndProject_Id(CONVERSATION_ID, PROJECT_ID))
                .thenReturn(Optional.of(conversation))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findAllByConversation_IdOrderByCreatedAtAsc(CONVERSATION_ID))
                .thenReturn(messages);
        when(aiEngineQueryClient.summarize(existingSummary, oldHistory)).thenReturn(generatedSummary);
        when(conversationRepository.updateRunningSummary(
                eq(CONVERSATION_ID),
                eq(1L),
                eq(generatedSummary),
                eq(throughMessageId),
                any(Instant.class)
        )).thenThrow(new RuntimeException("database unavailable"));
        when(aiEngineQueryClient.ask("Current question", PROJECT_ID, recentHistory, List.of(), existingSummary, List.of()))
                .thenReturn(AiEngineQueryResult.success("Current answer", null));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MessageExchange result = service.addMessage(
                USER_ID,
                PROJECT_ID,
                CONVERSATION_ID,
                "Current question",
                List.of()
        );

        assertThat(result.assistantMessage().getContent()).isEqualTo("Current answer");
        verify(aiEngineQueryClient).ask("Current question", PROJECT_ID, recentHistory, List.of(), existingSummary, List.of());
    }

    @Test
    @DisplayName("prior evidence는 최신 완성 턴의 보조자 메시지에서만 추출")
    void addMessageSendsPriorEvidenceOnlyFromLatestCompletedTurn() {
        MessageService service = service();
        Conversation conversation = conversation();
        Message olderAssistant = Message.assistant(conversation, "Older PR #12", Map.of(
                "structured", Map.of("evidence", List.of(Map.of(
                        "type", "pull_request",
                        "id", "#12",
                        "quote", "Older change"
                )))
        ));
        Message latestAssistant = Message.assistant(conversation, "Latest issue HT-37", Map.of(
                "structured", Map.of("evidence", List.of(Map.of(
                        "type", "issue",
                        "id", "HT-37",
                        "quote", "Latest issue"
                )))
        ));
        List<Message> messages = List.of(
                Message.user(conversation, "Older question"),
                olderAssistant,
                Message.user(conversation, "Latest question"),
                latestAssistant
        );
        List<AiEngineHistoryMessage> expectedHistory = List.of(
                new AiEngineHistoryMessage("user", "Older question"),
                new AiEngineHistoryMessage("assistant", "Older PR #12"),
                new AiEngineHistoryMessage("user", "Latest question"),
                new AiEngineHistoryMessage("assistant", "Latest issue HT-37")
        );
        List<AiEnginePriorEvidence> expectedPriorEvidence = List.of(
                new AiEnginePriorEvidence("issue", "HT-37", "Latest issue")
        );
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findByIdAndProject_Id(CONVERSATION_ID, PROJECT_ID))
                .thenReturn(Optional.of(conversation))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findAllByConversation_IdOrderByCreatedAtAsc(CONVERSATION_ID))
                .thenReturn(messages);
        when(aiEngineQueryClient.ask("Current question", PROJECT_ID, expectedHistory, expectedPriorEvidence, null, List.of()))
                .thenReturn(AiEngineQueryResult.success("Current answer", null));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.addMessage(USER_ID, PROJECT_ID, CONVERSATION_ID, "Current question", List.of());

        verify(aiEngineQueryClient).ask("Current question", PROJECT_ID, expectedHistory, expectedPriorEvidence, null, List.of());
    }

    @Test
    @DisplayName("fallback 보조자 메시지로 완성된 턴은 히스토리에서 제외")
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
        when(aiEngineQueryClient.ask("Current question", PROJECT_ID, List.of(), List.of(), null, List.of()))
                .thenReturn(AiEngineQueryResult.success("Current answer", null));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.addMessage(USER_ID, PROJECT_ID, CONVERSATION_ID, "Current question", List.of());

        verify(aiEngineQueryClient).ask("Current question", PROJECT_ID, List.of(), List.of(), null, List.of());
    }

    @Test
    @DisplayName("빈 메시지 내용으로 추가 거부")
    void addMessageRejectsBlankContent() {
        MessageService service = service();

        assertThatThrownBy(() -> service.addMessage(USER_ID, PROJECT_ID, CONVERSATION_ID, " ", List.of()))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Message content is required.");

        verifyNoInteractions(projectService, conversationRepository, messageRepository, aiEngineQueryClient);
    }

    @Test
    @DisplayName("존재하지 않는 대화에 메시지 추가 거부")
    void addMessageRejectsMissingConversation() {
        MessageService service = service();
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findByIdAndProject_Id(CONVERSATION_ID, PROJECT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addMessage(USER_ID, PROJECT_ID, CONVERSATION_ID, "Question", List.of()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Conversation not found.");
    }

    @Test
    @DisplayName("메시지 페이지 조회 시 접근 권한 검증 후 최신순을 오름차순으로 반환")
    void findMessagesPageValidatesAccessAndReturnsAscending() {
        MessageService service = service();
        Conversation conversation = conversation();
        Message older = Message.user(conversation, "older");
        Message newer = Message.assistant(conversation, "newer", null);
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findByIdAndProject_Id(CONVERSATION_ID, PROJECT_ID))
                .thenReturn(Optional.of(conversation));
        // 리포지토리는 최신순(newer, older) 반환 → 표시용은 오름차순(older, newer)
        when(messageRepository.findLatest(eq(CONVERSATION_ID), any(Pageable.class)))
                .thenReturn(List.of(newer, older));

        MessagePage result = service.findMessagesPage(USER_ID, PROJECT_ID, CONVERSATION_ID, null);

        assertThat(result.items()).containsExactly(older, newer);
        assertThat(result.hasMore()).isFalse();
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    @DisplayName("페이지 크기를 초과하면 절단 후 가장 오래된 메시지 커서를 nextCursor로 설정")
    void findMessagesPageTruncatesAndSetsNextCursorWhenOverPageSize() {
        MessageService service = service();
        Conversation conversation = conversation();
        // 리포지토리는 최신순으로 MESSAGE_PAGE_SIZE(30) + 1개 반환
        List<Message> newestFirst = messages(conversation, 31);
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findByIdAndProject_Id(CONVERSATION_ID, PROJECT_ID))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findLatest(eq(CONVERSATION_ID), any(Pageable.class)))
                .thenReturn(newestFirst);

        MessagePage result = service.findMessagesPage(USER_ID, PROJECT_ID, CONVERSATION_ID, null);

        assertThat(result.items()).hasSize(30);
        assertThat(result.hasMore()).isTrue();
        assertThat(result.nextCursor()).isNotNull();
        // 최신순 앞 30개를 뒤집으므로 가장 오래된(30번째 최신) 메시지가 첫 항목
        assertThat(result.items().get(0)).isSameAs(newestFirst.get(29));
    }

    @Test
    @DisplayName("before 커서가 주어지면 커서 이전(older) 메시지 페이지 조회")
    void findMessagesPageUsesBeforeCursorWhenProvided() {
        MessageService service = service();
        Conversation conversation = conversation();
        Message message = Message.user(conversation, "older");
        String before = new Cursor(Instant.parse("2026-05-23T01:00:00Z"), CONVERSATION_ID).encode();
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findByIdAndProject_Id(CONVERSATION_ID, PROJECT_ID))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findOlderBefore(
                eq(CONVERSATION_ID), any(Instant.class), any(UUID.class), any(Pageable.class)))
                .thenReturn(List.of(message));

        MessagePage result = service.findMessagesPage(USER_ID, PROJECT_ID, CONVERSATION_ID, before);

        assertThat(result.items()).containsExactly(message);
        verify(messageRepository).findOlderBefore(
                eq(CONVERSATION_ID), any(Instant.class), any(UUID.class), any(Pageable.class));
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

    private List<Message> messages(Conversation conversation, int count) {
        List<Message> messages = new java.util.ArrayList<>();
        for (int index = 0; index < count; index++) {
            Message message = Message.user(conversation, "m" + index);
            ReflectionTestUtils.setField(message, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(
                    message, "createdAt", Instant.parse("2026-05-23T01:00:00Z").plusSeconds(index));
            messages.add(message);
        }
        return messages;
    }

    private List<Message> completedTurns(Conversation conversation, int count) {
        List<Message> messages = new java.util.ArrayList<>();
        for (int turn = 1; turn <= count; turn++) {
            Message userMessage = Message.user(conversation, "Question " + turn);
            Message assistantMessage = Message.assistant(conversation, "Answer " + turn, null);
            ReflectionTestUtils.setField(userMessage, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(assistantMessage, "id", UUID.randomUUID());
            messages.add(userMessage);
            messages.add(assistantMessage);
        }
        return messages;
    }

    private List<AiEngineHistoryMessage> historyTurns(int from, int to) {
        List<AiEngineHistoryMessage> history = new java.util.ArrayList<>();
        for (int turn = from; turn <= to; turn++) {
            history.add(new AiEngineHistoryMessage("user", "Question " + turn));
            history.add(new AiEngineHistoryMessage("assistant", "Answer " + turn));
        }
        return history;
    }

    private Map<String, Object> summary(String value) {
        return Map.of(
                "summary", value,
                "entities", List.of(),
                "unresolved_aspects", List.of()
        );
    }

    private void setSummaryState(
            Conversation conversation,
            Map<String, Object> runningSummary,
            UUID throughMessageId,
            long version
    ) {
        ReflectionTestUtils.setField(conversation, "runningSummary", runningSummary);
        ReflectionTestUtils.setField(conversation, "summaryThroughMessageId", throughMessageId);
        ReflectionTestUtils.setField(conversation, "summaryVersion", version);
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
