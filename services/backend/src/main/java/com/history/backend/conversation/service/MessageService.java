package com.history.backend.conversation.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.history.backend.common.error.BadRequestException;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.conversation.domain.Conversation;
import com.history.backend.conversation.domain.Message;
import com.history.backend.conversation.domain.MessageRole;
import com.history.backend.conversation.dto.AiEngineHistoryMessage;
import com.history.backend.conversation.dto.AiEnginePriorEvidence;
import com.history.backend.conversation.repository.ConversationRepository;
import com.history.backend.conversation.repository.MessageRepository;
import com.history.backend.project.service.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Slf4j
@RequiredArgsConstructor
public class MessageService {

    private static final int MAX_HISTORY_TURNS = 5;
    private static final String ERROR_TYPE_KEY = "error_type";
    private static final String AI_ENGINE_ERROR = "AI_ENGINE_ERROR";
    private static final String STRUCTURED_KEY = "structured";
    private static final Map<String, Object> AI_ENGINE_ERROR_METADATA = Map.of(
            "fallback", true,
            ERROR_TYPE_KEY, AI_ENGINE_ERROR
    );

    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final ProjectService projectService;
    private final AiEngineQueryClient aiEngineQueryClient;
    private final TransactionTemplate transactionTemplate;

    // 사용자 메시지 저장 → AI 질의 → 응답 저장 (트랜잭션 2단계 분리)
    public MessageExchange addMessage(UUID userId, UUID projectId, UUID conversationId, String content) {
        String normalizedContent = normalizeContent(content);
        projectService.getProject(userId, projectId);
        // 느린 AI 질의 중 커넥션 점유를 피하고, 질의 실패와 무관하게 사용자 메시지를 보존
        PendingQuery pendingQuery = transactionTemplate.execute(status -> {
            Conversation conversation = findConversation(projectId, conversationId);
            // history와 question의 현재 질문 중복 방지를 위한 USER 저장 전 이력 캡처
            QueryContext queryContext = loadQueryContext(conversation);
            Message userMessage = appendUserMessageInCurrentTransaction(conversation, normalizedContent);
            return new PendingQuery(userMessage, queryContext);
        });
        Map<String, Object> runningSummary = refreshRunningSummary(
                projectId,
                conversationId,
                pendingQuery.queryContext()
        );
        Message assistantMessage = appendAssistantMessageAfterQuery(
                projectId,
                conversationId,
                normalizedContent,
                pendingQuery.queryContext().history(),
                pendingQuery.queryContext().priorEvidence(),
                runningSummary
        );
        return new MessageExchange(pendingQuery.userMessage(), assistantMessage);
    }

    // 호출자 트랜잭션 안에서만 실행 (대화 저장과 메시지 저장의 원자성 보장)
    @Transactional(propagation = Propagation.MANDATORY)
    public Message appendUserMessageInCurrentTransaction(Conversation conversation, String normalizedContent) {
        Message message = messageRepository.save(Message.user(conversation, normalizedContent));
        conversation.touch();
        return message;
    }

    // AI 질의(트랜잭션 밖) 후 assistant 응답 메시지 저장
    Message appendAssistantMessageAfterQuery(
            UUID projectId,
            UUID conversationId,
            String normalizedContent,
            List<AiEngineHistoryMessage> history,
            List<AiEnginePriorEvidence> priorEvidence,
            Map<String, Object> runningSummary
    ) {
        AiEngineQueryResult queryResult = aiEngineQueryClient.ask(
                normalizedContent,
                projectId,
                history,
                priorEvidence,
                runningSummary
        );
        return transactionTemplate.execute(status -> {
            // 트랜잭션이 분리되어 있어 질의 중 삭제됐을 수 있으므로 conversation 재조회
            Conversation conversation = findConversation(projectId, conversationId);
            Message assistantMessage = messageRepository.save(Message.assistant(
                    conversation,
                    queryResult.answer(),
                    metadataFor(queryResult)
            ));
            conversation.touch();
            return assistantMessage;
        });
    }

    @Transactional(readOnly = true)
    public List<Message> findMessages(UUID userId, UUID projectId, UUID conversationId) {
        projectService.getProject(userId, projectId);
        findConversation(projectId, conversationId);
        return findMessagesInCurrentTransaction(conversationId);
    }

    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public List<Message> findMessagesInCurrentTransaction(UUID conversationId) {
        return messageRepository.findAllByConversation_IdOrderByCreatedAtAsc(conversationId);
    }

    private Conversation findConversation(UUID projectId, UUID conversationId) {
        return conversationRepository.findByIdAndProject_Id(conversationId, projectId)
                .orElseThrow(() -> new NotFoundException("Conversation not found."));
    }

    private String normalizeContent(String content) {
        if (content == null || content.isBlank()) {
            throw new BadRequestException("Message content is required.");
        }
        return content.trim();
    }

    // AI 질의 컨텍스트와 새로 요약할 오래된 완성 턴 구성
    private QueryContext loadQueryContext(Conversation conversation) {
        List<HistoryTurn> completedTurns = completedHistoryTurns(loadContextMessages(conversation));
        int fromIndex = Math.max(0, completedTurns.size() - MAX_HISTORY_TURNS);
        List<AiEngineHistoryMessage> history = completedTurns.subList(fromIndex, completedTurns.size()).stream()
                .flatMap(turn -> turn.messages().stream())
                .toList();
        List<AiEnginePriorEvidence> priorEvidence = completedTurns.isEmpty()
                ? List.of()
                : extractPriorEvidence(completedTurns.get(completedTurns.size() - 1).assistant());
        SummaryTask summaryTask = summaryTask(conversation, completedTurns.subList(0, fromIndex));
        return new QueryContext(history, priorEvidence, conversation.getRunningSummary(), summaryTask);
    }

    // 누적 요약 커서 이후의 AI 문맥 대상 메시지 조회
    private List<Message> loadContextMessages(Conversation conversation) {
        UUID cursorMessageId = conversation.getSummaryThroughMessageId();
        if (cursorMessageId == null) {
            return messageRepository.findAllByConversation_IdOrderByCreatedAtAsc(conversation.getId());
        }
        List<Message> messages = messageRepository.findAllFromCursor(conversation.getId(), cursorMessageId);
        for (int index = 0; index < messages.size(); index++) {
            if (cursorMessageId.equals(messages.get(index).getId())) {
                // 동일 생성 시각의 커서 이전 메시지가 다시 요약되는 것을 방지
                return messages.subList(index, messages.size());
            }
        }
        // 잘못된 커서로 이후 문맥이 누락되는 것보다 전체 이력 재처리를 우선
        log.warn("running summary 커서 메시지 조회 실패 - 전체 이력으로 진행: {}", cursorMessageId);
        return messageRepository.findAllByConversation_IdOrderByCreatedAtAsc(conversation.getId());
    }

    // 커서 이후 조회 결과에서 최근 이력 밖으로 밀려난 완성 턴의 요약 작업 구성
    private SummaryTask summaryTask(Conversation conversation, List<HistoryTurn> oldTurns) {
        if (oldTurns.isEmpty()) {
            return null;
        }
        UUID throughMessageId = oldTurns.get(oldTurns.size() - 1).assistant().getId();
        if (throughMessageId == null) {
            return null;
        }
        List<AiEngineHistoryMessage> history = oldTurns.stream()
                .flatMap(turn -> turn.messages().stream())
                .toList();
        return new SummaryTask(history, throughMessageId, conversation.getSummaryVersion());
    }

    // 요약 실패 또는 동시 갱신 충돌이 현재 질문 실패로 이어지지 않는 보조 문맥 갱신
    private Map<String, Object> refreshRunningSummary(
            UUID projectId,
            UUID conversationId,
            QueryContext queryContext
    ) {
        if (queryContext.summaryTask() == null) {
            return queryContext.runningSummary();
        }
        Map<String, Object> generatedSummary = aiEngineQueryClient.summarize(
                queryContext.runningSummary(),
                queryContext.summaryTask().history()
        );
        if (generatedSummary == null) {
            return queryContext.runningSummary();
        }
        try {
            Integer updated = transactionTemplate.execute(status -> conversationRepository.updateRunningSummary(
                    conversationId,
                    queryContext.summaryTask().expectedVersion(),
                    generatedSummary,
                    queryContext.summaryTask().throughMessageId(),
                    Instant.now()
            ));
            if (updated != null && updated == 1) {
                return generatedSummary;
            }
            return transactionTemplate.execute(
                    status -> findConversation(projectId, conversationId).getRunningSummary()
            );
        } catch (RuntimeException exception) {
            log.warn("running summary 갱신 실패 - 기존 요약으로 진행: {}", exception.getMessage());
            return queryContext.runningSummary();
        }
    }

    // 완성 턴 = 유효한 USER + 바로 뒤 유효한 ASSISTANT 쌍. fallback/blank 답변으로 끝난 턴은 제외.
    private List<HistoryTurn> completedHistoryTurns(List<Message> messages) {
        List<HistoryTurn> completedTurns = new ArrayList<>();
        Message pendingUser = null;

        for (Message message : messages) {
            if (message.getRole() == MessageRole.SYSTEM) {
                continue;
            }
            if (message.getRole() == MessageRole.USER) {
                pendingUser = isHistoryMessage(message) ? message : null;
                continue;
            }
            if (message.getRole() == MessageRole.ASSISTANT && pendingUser != null && isHistoryMessage(message)) {
                completedTurns.add(new HistoryTurn(pendingUser, message));
            }
            pendingUser = null;
        }
        return completedTurns;
    }

    private boolean isHistoryMessage(Message message) {
        if (message.getRole() == MessageRole.SYSTEM || message.getContent() == null || message.getContent().isBlank()) {
            return false;
        }
        return !isAiEngineError(message);
    }

    private boolean isAiEngineError(Message message) {
        Map<String, Object> metadata = message.getMetadata();
        return metadata != null && AI_ENGINE_ERROR.equals(metadata.get(ERROR_TYPE_KEY));
    }

    // AI 응답 유형별 메시지 metadata 구성
    private Map<String, Object> metadataFor(AiEngineQueryResult queryResult) {
        if (queryResult.fallback()) {
            return AI_ENGINE_ERROR_METADATA;
        }
        return queryResult.structured() == null
                ? null
                : Map.of(STRUCTURED_KEY, queryResult.structured());
    }

    // 직전 정상 응답의 구조화 근거 중 후속 질문의 대상 식별에 필요한 필드 추출
    private List<AiEnginePriorEvidence> extractPriorEvidence(Message assistantMessage) {
        Map<String, Object> metadata = assistantMessage.getMetadata();
        if (metadata == null || !(metadata.get(STRUCTURED_KEY) instanceof Map<?, ?> structured)) {
            return List.of();
        }
        if (!(structured.get("evidence") instanceof List<?> evidence)) {
            return List.of();
        }
        return evidence.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(this::toPriorEvidence)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private AiEnginePriorEvidence toPriorEvidence(Map<?, ?> evidence) {
        String type = stringValue(evidence.get("type"));
        String id = stringValue(evidence.get("id"));
        String quote = stringValue(evidence.get("quote"));
        if (type == null || id == null || quote == null) {
            return null;
        }
        return new AiEnginePriorEvidence(type, id, quote);
    }

    private String stringValue(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private record PendingQuery(
            Message userMessage,
            QueryContext queryContext
    ) {
    }

    private record QueryContext(
            List<AiEngineHistoryMessage> history,
            List<AiEnginePriorEvidence> priorEvidence,
            Map<String, Object> runningSummary,
            SummaryTask summaryTask
    ) {
    }

    private record SummaryTask(
            List<AiEngineHistoryMessage> history,
            UUID throughMessageId,
            long expectedVersion
    ) {
    }

    private record HistoryTurn(
            Message user,
            Message assistant
    ) {
        private List<AiEngineHistoryMessage> messages() {
            return List.of(toHistoryMessage(user), toHistoryMessage(assistant));
        }

        private static AiEngineHistoryMessage toHistoryMessage(Message message) {
            String role = message.getRole() == MessageRole.USER ? "user" : "assistant";
            return new AiEngineHistoryMessage(role, message.getContent());
        }
    }
}
