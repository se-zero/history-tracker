package com.history.backend.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
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
import com.history.backend.conversation.ChatMemoryProperties;
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
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
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

    @Mock
    private SummaryBackoffTracker summaryBackoffTracker;

    private final TaskExecutor summaryTaskExecutor = new SyncTaskExecutor();

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
    @DisplayName("예산(100자) 밖 백로그도 캡(8000자) 이내면 히스토리에 동승")
    void addMessageSendsOnlyRecentCompletedTurnsWithinBudget() {
        // 턴당 18자("Question N"+"Answer N") × 5턴 = 90자(예산 이내), 6턴째부터 108자로 예산 초과 → window는 turn3-7
        // 창 밖 백로그(turn1-2, 36자)는 캡(summaryTriggerChars=8000)보다 훨씬 작아 전부 동승 → 최종 history는 turn1-7 전체
        MessageService service = service(new ChatMemoryProperties(100, 8000, 3, 3));
        Conversation conversation = conversation();
        List<Message> messages = new java.util.ArrayList<>();
        for (int turn = 1; turn <= 7; turn++) {
            messages.add(Message.user(conversation, "Question " + turn));
            messages.add(Message.assistant(conversation, "Answer " + turn, null));
        }
        messages.add(Message.user(conversation, "Incomplete question"));
        List<AiEngineHistoryMessage> expectedHistory = List.of(
                new AiEngineHistoryMessage("user", "Question 1"),
                new AiEngineHistoryMessage("assistant", "Answer 1"),
                new AiEngineHistoryMessage("user", "Question 2"),
                new AiEngineHistoryMessage("assistant", "Answer 2"),
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
    @DisplayName("최신 턴이 단독으로 예산을 초과해도 히스토리에 포함")
    void addMessageIncludesLatestTurnAloneWhenItAloneExceedsBudget() {
        // 이전 턴 18자는 예산(50자) 이내지만, 최신 턴 단독 80자는 예산을 초과 → 그래도 반드시 포함(window=최신 턴만)
        // 이전 턴(18자)은 캡(summaryTriggerChars=8000) 이내라 백로그로 동승 → 최종 history엔 이전 턴도 포함
        MessageService service = service(new ChatMemoryProperties(50, 8000, 3, 3));
        Conversation conversation = conversation();
        Message olderUser = Message.user(conversation, "Question 1");
        Message olderAssistant = Message.assistant(conversation, "Answer 1", null);
        String bigUserContent = "X".repeat(40);
        String bigAssistantContent = "Y".repeat(40);
        Message latestUser = Message.user(conversation, bigUserContent);
        Message latestAssistant = Message.assistant(conversation, bigAssistantContent, null);
        List<AiEngineHistoryMessage> expectedHistory = List.of(
                new AiEngineHistoryMessage("user", "Question 1"),
                new AiEngineHistoryMessage("assistant", "Answer 1"),
                new AiEngineHistoryMessage("user", bigUserContent),
                new AiEngineHistoryMessage("assistant", bigAssistantContent)
        );
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findByIdAndProject_Id(CONVERSATION_ID, PROJECT_ID))
                .thenReturn(Optional.of(conversation))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findAllByConversation_IdOrderByCreatedAtAsc(CONVERSATION_ID))
                .thenReturn(List.of(olderUser, olderAssistant, latestUser, latestAssistant));
        when(aiEngineQueryClient.ask("Current question", PROJECT_ID, expectedHistory, List.of(), null, List.of()))
                .thenReturn(AiEngineQueryResult.success("Current answer", null));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.addMessage(USER_ID, PROJECT_ID, CONVERSATION_ID, "Current question", List.of());

        verify(aiEngineQueryClient).ask("Current question", PROJECT_ID, expectedHistory, List.of(), null, List.of());
    }

    @Test
    @DisplayName("예산에 일부만 걸치는 턴은 창(window)에서 통째로 제외되지만, 캡 이내라 백로그로 동승")
    void addMessageExcludesWholeTurnWhenOnlyPartiallyFitsBudget() {
        // 최신 턴(12자)만 예산(25자) 이내. 경계 턴(20자)은 합산 시 32자로 초과하므로
        // user(10자)만 따로 보면 22자로 예산 이내지만, 턴 전체를 통째로 제외해야 한다(window=최신 턴만).
        // 단, oldest+boundary(24자)는 캡(summaryTriggerChars=8000) 이내라 백로그로 동승 → 최종 history는 전체 포함
        MessageService service = service(new ChatMemoryProperties(25, 8000, 3, 3));
        Conversation conversation = conversation();
        Message oldestUser = Message.user(conversation, "Q1");
        Message oldestAssistant = Message.assistant(conversation, "A1", null);
        Message boundaryUser = Message.user(conversation, "U2U2U2U2U2");
        Message boundaryAssistant = Message.assistant(conversation, "A2A2A2A2A2", null);
        String latestUserContent = "U3U3U3";
        String latestAssistantContent = "A3A3A3";
        Message latestUser = Message.user(conversation, latestUserContent);
        Message latestAssistant = Message.assistant(conversation, latestAssistantContent, null);
        List<AiEngineHistoryMessage> expectedHistory = List.of(
                new AiEngineHistoryMessage("user", "Q1"),
                new AiEngineHistoryMessage("assistant", "A1"),
                new AiEngineHistoryMessage("user", "U2U2U2U2U2"),
                new AiEngineHistoryMessage("assistant", "A2A2A2A2A2"),
                new AiEngineHistoryMessage("user", latestUserContent),
                new AiEngineHistoryMessage("assistant", latestAssistantContent)
        );
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findByIdAndProject_Id(CONVERSATION_ID, PROJECT_ID))
                .thenReturn(Optional.of(conversation))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findAllByConversation_IdOrderByCreatedAtAsc(CONVERSATION_ID))
                .thenReturn(List.of(
                        oldestUser, oldestAssistant, boundaryUser, boundaryAssistant, latestUser, latestAssistant
                ));
        when(aiEngineQueryClient.ask("Current question", PROJECT_ID, expectedHistory, List.of(), null, List.of()))
                .thenReturn(AiEngineQueryResult.success("Current answer", null));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.addMessage(USER_ID, PROJECT_ID, CONVERSATION_ID, "Current question", List.of());

        verify(aiEngineQueryClient).ask("Current question", PROJECT_ID, expectedHistory, List.of(), null, List.of());
    }

    @Test
    @DisplayName("백로그가 캡 이내면 원문 그대로 history에 동승하고 summaryTask는 창 밖 전체를 유지")
    void addMessageRidesAlongBacklogWithinCapWhileSummaryTaskCoversFullBacklog() {
        // 턴당 18자 × 5턴. 예산(40자)엔 turn4,5만 들어가고(window), 백로그(turn1-3, 54자)는
        // 캡(summaryTriggerChars=54)과 정확히 같아 전부 동승. 캡=trigger를 재사용하므로 백로그(54자)가
        // 트리거(54)도 충족해 summarize가 호출된다 — 그 호출 인자(oldHistory)로 summaryTask 범위가
        // 동승과 무관하게 창 밖 전체(turn1-3)인지 함께 확인한다.
        MessageService service = service(new ChatMemoryProperties(40, 54, 3, 3));
        Conversation conversation = conversation();
        List<Message> messages = completedTurns(conversation, 5);
        List<AiEngineHistoryMessage> expectedHistory = historyTurns(1, 5);
        List<AiEngineHistoryMessage> oldHistory = historyTurns(1, 3);
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findByIdAndProject_Id(CONVERSATION_ID, PROJECT_ID))
                .thenReturn(Optional.of(conversation))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findAllByConversation_IdOrderByCreatedAtAsc(CONVERSATION_ID))
                .thenReturn(messages);
        when(summaryBackoffTracker.shouldSkip(CONVERSATION_ID)).thenReturn(false);
        when(aiEngineQueryClient.ask("Current question", PROJECT_ID, expectedHistory, List.of(), null, List.of()))
                .thenReturn(AiEngineQueryResult.success("Current answer", null));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.addMessage(USER_ID, PROJECT_ID, CONVERSATION_ID, "Current question", List.of());

        verify(aiEngineQueryClient).ask("Current question", PROJECT_ID, expectedHistory, List.of(), null, List.of());
        // summaryTask는 동승 여부와 무관하게 창 밖 전체(turn1-3)를 그대로 요약 대상으로 삼는다
        verify(aiEngineQueryClient).summarize(null, oldHistory);
    }

    @Test
    @DisplayName("백로그가 캡을 초과하면 가장 오래된 턴부터 제외하되 summaryTask는 백로그 전체를 유지")
    void addMessageExcludesOldestBacklogTurnsBeyondCapButKeepsFullSummaryTaskRange() {
        // 턴당 18자 × 6턴. 예산(18자)엔 turn6만 들어가고(window), 백로그(turn1-5, 90자)는
        // 캡(summaryTriggerChars=72)을 초과해 가장 오래된 turn1(18자)만 제외되고 turn2-5(72자)만 동승한다.
        // 캡=trigger를 재사용하므로 백로그 전체(90자)가 트리거(72)를 충족해 summarize가 호출되는데,
        // 그 인자(oldHistory)가 캡으로 줄어들지 않고 백로그 전체(turn1-5)인지로 요약 커버리지 축소를 방지한다.
        MessageService service = service(new ChatMemoryProperties(18, 72, 3, 3));
        Conversation conversation = conversation();
        List<Message> messages = completedTurns(conversation, 6);
        List<AiEngineHistoryMessage> expectedHistory = historyTurns(2, 6);
        List<AiEngineHistoryMessage> oldHistory = historyTurns(1, 5);
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findByIdAndProject_Id(CONVERSATION_ID, PROJECT_ID))
                .thenReturn(Optional.of(conversation))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findAllByConversation_IdOrderByCreatedAtAsc(CONVERSATION_ID))
                .thenReturn(messages);
        when(summaryBackoffTracker.shouldSkip(CONVERSATION_ID)).thenReturn(false);
        when(aiEngineQueryClient.ask("Current question", PROJECT_ID, expectedHistory, List.of(), null, List.of()))
                .thenReturn(AiEngineQueryResult.success("Current answer", null));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.addMessage(USER_ID, PROJECT_ID, CONVERSATION_ID, "Current question", List.of());

        // 가장 오래된 백로그 턴(turn1)은 history에서 제외되고, 캡 이내의 turn2-5만 동승한다
        verify(aiEngineQueryClient).ask("Current question", PROJECT_ID, expectedHistory, List.of(), null, List.of());
        // summaryTask는 캡과 무관하게 백로그 전체(turn1-5)를 그대로 요약 대상으로 유지한다 — 요약 커버리지가 줄면 안 된다
        verify(aiEngineQueryClient).summarize(null, oldHistory);
    }

    @Test
    @DisplayName("캡에 일부만 걸치는 백로그 턴은 통째로 제외(부분 포함 없음)")
    void addMessageExcludesWholeBacklogTurnWhenOnlyPartiallyFitsCap() {
        // 백로그는 오래된 순으로 oldest(4자)+boundary(20자)+near(12자). 캡(25자)은 창에 가장 가까운
        // near(12자)까지는 담지만, 그다음(더 오래된) boundary(20자)를 더하면 32자로 캡을 넘으므로
        // boundary는 통째로 제외되고(부분 포함 없음), 누적이 중단되어 그 이전 oldest도 함께 제외된다.
        // 예산(2자)엔 latest 턴만 들어간다(window).
        MessageService service = service(new ChatMemoryProperties(2, 25, 3, 3));
        Conversation conversation = conversation();
        Message oldestUser = Message.user(conversation, "Q1");
        Message oldestAssistant = Message.assistant(conversation, "A1", null);
        Message boundaryUser = Message.user(conversation, "U2U2U2U2U2");
        Message boundaryAssistant = Message.assistant(conversation, "A2A2A2A2A2", null);
        Message nearUser = Message.user(conversation, "U3U3U3");
        Message nearAssistant = Message.assistant(conversation, "A3A3A3", null);
        Message latestUser = Message.user(conversation, "L");
        Message latestAssistant = Message.assistant(conversation, "L", null);
        List<AiEngineHistoryMessage> expectedHistory = List.of(
                new AiEngineHistoryMessage("user", "U3U3U3"),
                new AiEngineHistoryMessage("assistant", "A3A3A3"),
                new AiEngineHistoryMessage("user", "L"),
                new AiEngineHistoryMessage("assistant", "L")
        );
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findByIdAndProject_Id(CONVERSATION_ID, PROJECT_ID))
                .thenReturn(Optional.of(conversation))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findAllByConversation_IdOrderByCreatedAtAsc(CONVERSATION_ID))
                .thenReturn(List.of(
                        oldestUser, oldestAssistant, boundaryUser, boundaryAssistant,
                        nearUser, nearAssistant, latestUser, latestAssistant
                ));
        when(aiEngineQueryClient.ask("Current question", PROJECT_ID, expectedHistory, List.of(), null, List.of()))
                .thenReturn(AiEngineQueryResult.success("Current answer", null));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.addMessage(USER_ID, PROJECT_ID, CONVERSATION_ID, "Current question", List.of());

        verify(aiEngineQueryClient).ask("Current question", PROJECT_ID, expectedHistory, List.of(), null, List.of());
    }

    @Test
    @DisplayName("트리거 미달 시 요약 백그라운드 제출 없이 저장된 요약으로 질의")
    void addMessageSkipsSummarizationWhenBacklogBelowTrigger() {
        // 턴당 18자 × 5턴 = 90자(예산 이내), 6턴째부터 108자로 예산 초과 → window는 turn2-6, 최고령 턴(turn1,18자)만 요약 대상
        // trigger(8000)가 백로그(18자)보다 훨씬 커 요약 자체가 제출되지 않아야 한다.
        // 단, 캡도 같은 값(8000)을 재사용하므로 turn1은 캡 이내로 백로그 동승 → 최종 history는 turn1-6 전체
        MessageService service = service(new ChatMemoryProperties(100, 8000, 3, 3));
        Conversation conversation = conversation();
        Map<String, Object> existingSummary = summary("existing");
        setSummaryState(conversation, existingSummary, null, 2L);
        List<Message> messages = completedTurns(conversation, 6);
        List<AiEngineHistoryMessage> recentHistory = historyTurns(1, 6);
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findByIdAndProject_Id(CONVERSATION_ID, PROJECT_ID))
                .thenReturn(Optional.of(conversation))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findAllByConversation_IdOrderByCreatedAtAsc(CONVERSATION_ID))
                .thenReturn(messages);
        when(aiEngineQueryClient.ask("Current question", PROJECT_ID, recentHistory, List.of(), existingSummary, List.of()))
                .thenReturn(AiEngineQueryResult.success("Current answer", null));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.addMessage(USER_ID, PROJECT_ID, CONVERSATION_ID, "Current question", List.of());

        verify(summaryBackoffTracker, never()).shouldSkip(any());
        verify(aiEngineQueryClient, never()).summarize(any(), any());
        verify(conversationRepository, never()).updateRunningSummary(any(), anyLong(), any(), any(), any());
        verify(aiEngineQueryClient).ask("Current question", PROJECT_ID, recentHistory, List.of(), existingSummary, List.of());
    }

    @Test
    @DisplayName("트리거 충족 시 요약을 백그라운드로 제출하되 이번 턴 질의는 저장된 기존 요약 사용")
    void addMessageTriggersBackgroundSummarizationButAsksWithStoredSummary() {
        // 턴당 18자 × 5턴 = 90자(예산 이내), 6턴째부터 108자로 예산 초과 → 최고령 턴(18자)만 요약 대상
        // trigger를 1로 낮춰 백로그(18자)가 항상 트리거를 충족하게 한다
        MessageService service = service(new ChatMemoryProperties(100, 1, 3, 3));
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
        when(summaryBackoffTracker.shouldSkip(CONVERSATION_ID)).thenReturn(false);
        when(aiEngineQueryClient.summarize(existingSummary, oldHistory)).thenReturn(generatedSummary);
        when(conversationRepository.updateRunningSummary(
                eq(CONVERSATION_ID),
                eq(2L),
                eq(generatedSummary),
                eq(throughMessageId),
                any(Instant.class)
        ))
                .thenReturn(1);
        when(aiEngineQueryClient.ask("Current question", PROJECT_ID, recentHistory, List.of(), existingSummary, List.of()))
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
        verify(summaryBackoffTracker).recordSuccess(CONVERSATION_ID);
        verify(summaryBackoffTracker, never()).recordFailure(any());
        // ask에는 새로 생성된 요약이 아니라 이번 턴 시작 시점에 저장돼 있던 기존 요약이 전달돼야 한다
        verify(aiEngineQueryClient).ask("Current question", PROJECT_ID, recentHistory, List.of(), existingSummary, List.of());
    }

    @Test
    @DisplayName("백오프 트래커가 스킵을 지시하면 요약을 제출하지 않고 저장된 요약으로 질의")
    void addMessageSkipsSummarizationWhenBackoffTrackerSignalsSkip() {
        MessageService service = service(new ChatMemoryProperties(100, 1, 3, 3));
        Conversation conversation = conversation();
        Map<String, Object> existingSummary = summary("existing");
        setSummaryState(conversation, existingSummary, null, 2L);
        List<Message> messages = completedTurns(conversation, 6);
        List<AiEngineHistoryMessage> recentHistory = historyTurns(2, 6);
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findByIdAndProject_Id(CONVERSATION_ID, PROJECT_ID))
                .thenReturn(Optional.of(conversation))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findAllByConversation_IdOrderByCreatedAtAsc(CONVERSATION_ID))
                .thenReturn(messages);
        when(summaryBackoffTracker.shouldSkip(CONVERSATION_ID)).thenReturn(true);
        when(aiEngineQueryClient.ask("Current question", PROJECT_ID, recentHistory, List.of(), existingSummary, List.of()))
                .thenReturn(AiEngineQueryResult.success("Current answer", null));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.addMessage(USER_ID, PROJECT_ID, CONVERSATION_ID, "Current question", List.of());

        verify(summaryBackoffTracker).shouldSkip(CONVERSATION_ID);
        verify(aiEngineQueryClient, never()).summarize(any(), any());
        verify(conversationRepository, never()).updateRunningSummary(any(), anyLong(), any(), any(), any());
        verify(summaryBackoffTracker, never()).recordSuccess(any());
        verify(summaryBackoffTracker, never()).recordFailure(any());
        verify(aiEngineQueryClient).ask("Current question", PROJECT_ID, recentHistory, List.of(), existingSummary, List.of());
    }

    @Test
    @DisplayName("CAS 갱신 충돌(0건)은 요약 실패로 기록하지 않음")
    void addMessageDoesNotRecordFailureWhenCasUpdateConflicts() {
        MessageService service = service(new ChatMemoryProperties(100, 1, 3, 3));
        Conversation conversation = conversation();
        Map<String, Object> existingSummary = summary("existing");
        Map<String, Object> generatedSummary = summary("generated by current request");
        setSummaryState(conversation, existingSummary, null, 3L);
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
        when(summaryBackoffTracker.shouldSkip(CONVERSATION_ID)).thenReturn(false);
        when(aiEngineQueryClient.summarize(existingSummary, oldHistory)).thenReturn(generatedSummary);
        when(conversationRepository.updateRunningSummary(
                eq(CONVERSATION_ID),
                eq(3L),
                eq(generatedSummary),
                eq(throughMessageId),
                any(Instant.class)
        ))
                .thenReturn(0);
        when(aiEngineQueryClient.ask("Current question", PROJECT_ID, recentHistory, List.of(), existingSummary, List.of()))
                .thenReturn(AiEngineQueryResult.success("Current answer", null));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.addMessage(USER_ID, PROJECT_ID, CONVERSATION_ID, "Current question", List.of());

        // LLM 요약 자체는 성공했으므로(CAS 충돌은 동시 전진일 뿐) 실패로 기록하지 않는다
        verify(summaryBackoffTracker).recordSuccess(CONVERSATION_ID);
        verify(summaryBackoffTracker, never()).recordFailure(any());
        verify(aiEngineQueryClient).ask("Current question", PROJECT_ID, recentHistory, List.of(), existingSummary, List.of());
    }

    @Test
    @DisplayName("CAS 갱신 예외는 DB 사정이므로 요약 실패로 기록하지 않고 addMessage는 정상 완료")
    void addMessageCompletesWithoutRecordingFailureWhenCasUpdateThrows() {
        MessageService service = service(new ChatMemoryProperties(100, 1, 3, 3));
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
        when(summaryBackoffTracker.shouldSkip(CONVERSATION_ID)).thenReturn(false);
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

        MessageExchange result = service.addMessage(USER_ID, PROJECT_ID, CONVERSATION_ID, "Current question", List.of());

        assertThat(result.assistantMessage().getContent()).isEqualTo("Current answer");
        verify(summaryBackoffTracker, never()).recordFailure(any());
    }

    @Test
    @DisplayName("요약 executor가 작업을 거부해도 addMessage는 정상 완료")
    void addMessageCompletesWhenSummaryExecutorRejectsTask() {
        MessageService service = service(new ChatMemoryProperties(100, 1, 3, 3), new RejectingTaskExecutor());
        Conversation conversation = conversation();
        Map<String, Object> existingSummary = summary("existing");
        setSummaryState(conversation, existingSummary, null, 2L);
        List<Message> messages = completedTurns(conversation, 6);
        List<AiEngineHistoryMessage> recentHistory = historyTurns(2, 6);
        when(projectService.getProject(USER_ID, PROJECT_ID)).thenReturn(project());
        when(conversationRepository.findByIdAndProject_Id(CONVERSATION_ID, PROJECT_ID))
                .thenReturn(Optional.of(conversation))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.findAllByConversation_IdOrderByCreatedAtAsc(CONVERSATION_ID))
                .thenReturn(messages);
        when(summaryBackoffTracker.shouldSkip(CONVERSATION_ID)).thenReturn(false);
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
        verify(aiEngineQueryClient, never()).summarize(any(), any());
        verify(summaryBackoffTracker, never()).recordSuccess(any());
        verify(summaryBackoffTracker, never()).recordFailure(any());
    }

    @Test
    @DisplayName("저장된 커서 이후 턴만 추가 요약하되 질의는 저장된 기존 요약 사용")
    void addMessageSummarizesOnlyTurnsAfterStoredCursor() {
        // trigger를 1로 낮춰 커서 이후 백로그(턴2, 18자)가 항상 트리거를 충족하게 한다
        MessageService service = service(new ChatMemoryProperties(100, 1, 3, 3));
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
        when(summaryBackoffTracker.shouldSkip(CONVERSATION_ID)).thenReturn(false);
        when(aiEngineQueryClient.summarize(existingSummary, newlyOldHistory)).thenReturn(generatedSummary);
        when(conversationRepository.updateRunningSummary(
                eq(CONVERSATION_ID),
                eq(1L),
                eq(generatedSummary),
                eq(nextCursor),
                any(Instant.class)
        )).thenReturn(1);
        when(aiEngineQueryClient.ask("Current question", PROJECT_ID, recentHistory, List.of(), existingSummary, List.of()))
                .thenReturn(AiEngineQueryResult.success("Current answer", null));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.addMessage(USER_ID, PROJECT_ID, CONVERSATION_ID, "Current question", List.of());

        verify(aiEngineQueryClient).summarize(existingSummary, newlyOldHistory);
        verify(summaryBackoffTracker).recordSuccess(CONVERSATION_ID);
        verify(aiEngineQueryClient).ask("Current question", PROJECT_ID, recentHistory, List.of(), existingSummary, List.of());
    }

    @Test
    @DisplayName("요약 커서 누락 시 전체 히스토리 폴백")
    void addMessageFallsBackToFullHistoryWhenSummaryCursorIsMissing() {
        MessageService service = service(new ChatMemoryProperties(100, 8000, 3, 3));
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
    @DisplayName("요약 생성 null 반환 시 실패로 기록하고 addMessage는 정상 완료")
    void addMessageRecordsFailureWhenSummarizeReturnsNull() {
        MessageService service = service(new ChatMemoryProperties(100, 1, 3, 3));
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
        when(summaryBackoffTracker.shouldSkip(CONVERSATION_ID)).thenReturn(false);
        when(aiEngineQueryClient.summarize(existingSummary, oldHistory)).thenReturn(null);
        when(aiEngineQueryClient.ask("Current question", PROJECT_ID, recentHistory, List.of(), existingSummary, List.of()))
                .thenReturn(AiEngineQueryResult.success("Current answer", null));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MessageExchange result = service.addMessage(USER_ID, PROJECT_ID, CONVERSATION_ID, "Current question", List.of());

        assertThat(result.assistantMessage().getContent()).isEqualTo("Current answer");
        verify(summaryBackoffTracker).recordFailure(CONVERSATION_ID);
        verify(conversationRepository, never()).updateRunningSummary(any(), anyLong(), any(), any(), any());
        verify(aiEngineQueryClient).ask("Current question", PROJECT_ID, recentHistory, List.of(), existingSummary, List.of());
    }

    @Test
    @DisplayName("요약 생성 예외 시 실패로 기록하고 addMessage는 정상 완료")
    void addMessageRecordsFailureWhenSummarizeThrows() {
        MessageService service = service(new ChatMemoryProperties(100, 1, 3, 3));
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
        when(summaryBackoffTracker.shouldSkip(CONVERSATION_ID)).thenReturn(false);
        when(aiEngineQueryClient.summarize(existingSummary, oldHistory))
                .thenThrow(new RuntimeException("ai-engine unavailable"));
        when(aiEngineQueryClient.ask("Current question", PROJECT_ID, recentHistory, List.of(), existingSummary, List.of()))
                .thenReturn(AiEngineQueryResult.success("Current answer", null));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MessageExchange result = service.addMessage(USER_ID, PROJECT_ID, CONVERSATION_ID, "Current question", List.of());

        assertThat(result.assistantMessage().getContent()).isEqualTo("Current answer");
        verify(summaryBackoffTracker).recordFailure(CONVERSATION_ID);
        verify(conversationRepository, never()).updateRunningSummary(any(), anyLong(), any(), any(), any());
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
        return service(new ChatMemoryProperties(32000, 8000, 3, 3));
    }

    private MessageService service(ChatMemoryProperties chatMemoryProperties) {
        return service(chatMemoryProperties, summaryTaskExecutor);
    }

    private MessageService service(ChatMemoryProperties chatMemoryProperties, TaskExecutor taskExecutor) {
        return new MessageService(
                messageRepository,
                conversationRepository,
                projectService,
                aiEngineQueryClient,
                chatMemoryProperties,
                summaryBackoffTracker,
                taskExecutor,
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

    // 요약 executor 포화 시나리오 검증용 — SyncTaskExecutor 기반으로 execute() 호출 자체가 즉시 거부되게 한다
    private static class RejectingTaskExecutor extends SyncTaskExecutor {
        @Override
        public void execute(Runnable task) {
            throw new TaskRejectedException("summary executor full");
        }
    }
}
